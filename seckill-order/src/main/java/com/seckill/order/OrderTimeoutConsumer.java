package com.seckill.order;

import com.seckill.common.RedisKeyBuilder;
import com.seckill.mq.MqConstants;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 超时订单消费者——收到 5 分钟延时消息后，核验支付状态并回池库存。
 * <p>
 * 防御体系（四层）：
 * <ol>
 *   <li>Consumer 线程数压制到 4（不抢前台资源）</li>
 *   <li>Redisson RLock tryLock(100ms)——锁竞争时退避重试，Redis 宕机时降级到 DB 悲观锁</li>
 *   <li>状态机"已支付不可逆转"——PAID 订单永不回滚</li>
 *   <li>{@code SELECT ... FOR UPDATE} 作为 Redisson 完全不可用时的最终物理屏障</li>
 * </ol>
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Component
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ORDER_TIMEOUT,
        consumerGroup = MqConstants.CONSUMER_GROUP_ORDER_TIMEOUT,
        consumeThreadNumber = 4
)
public class OrderTimeoutConsumer implements RocketMQListener<OrderTimeoutMessage> {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutConsumer.class);

    private static final long LOCK_WAIT_MS = 100L;

    private final OrderMapper orderMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    public OrderTimeoutConsumer(OrderMapper orderMapper,
                                StringRedisTemplate redisTemplate,
                                RedissonClient redissonClient) {
        this.orderMapper = orderMapper;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 消费延时消息——核验订单状态并执行库存回池或幂等 ACK。
     *
     * @param msg 包含 orderId 和 goodsId 的消息体
     * @since 2026-05-12
     */
    @Override
    public void onMessage(OrderTimeoutMessage msg) {
        Long orderId = msg.getOrderId();
        Long goodsId = msg.getGoodsId();

        log.info("Timeout message received: orderId={}, goodsId={}", orderId, goodsId);

        RLock lock = redissonClient.getLock("order:lock:" + orderId);
        boolean redisFailed = false;

        try {
            if (lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                try {
                    processTimeout(orderId, goodsId);
                    return;
                } finally {
                    try {
                        lock.unlock();
                    } catch (Exception ignored) {
                    }
                }
            } else {
                log.warn("Lock contention for orderId={}, will retry", orderId);
                throw new RuntimeException("Lock contention, retry later");
            }
        } catch (RedisConnectionFailureException | org.redisson.client.RedisException e) {
            log.error("Redis unavailable for orderId={}, falling back to DB lock", orderId, e);
            redisFailed = true;
        } catch (Exception e) {
            log.error("Unexpected error processing orderId={}", orderId, e);
            throw new RuntimeException("Consumer error, retry", e);
        }

        if (redisFailed) {
            processTimeoutWithDbLock(orderId, goodsId);
        }
    }

    /**
     * 正常路径——Redisson 锁持有，核验状态并回池。
     *
     * @param orderId 订单 ID
     * @param goodsId 商品 ID
     * @since 2026-05-12
     */
    private void processTimeout(Long orderId, Long goodsId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("Order not found: orderId={}, ACK", orderId);
            return;
        }

        int status = order.getStatus();
        if (status == OrderStatus.PENDING_PAY.getCode()) {
            String stockKey = RedisKeyBuilder.stockKey(goodsId);
            redisTemplate.opsForValue().increment(stockKey, 1);

            order.setStatus(OrderStatus.TIMEOUT_CANCELLED.getCode());
            order.setCancelTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("Order timeout cancelled: orderId={}, goodsId={}, stock returned", orderId, goodsId);

        } else if (status == OrderStatus.PAID.getCode()) {
            log.info("Order already paid: orderId={}, ACK", orderId);

        } else {
            log.info("Order already in terminal state: orderId={}, status={}, ACK", orderId, status);
        }
    }

    /**
     * 降级路径——Redis 完全不可用，使用 MySQL 行级锁兜底。
     * <p>
     * <b>已知代价（库存黑洞）</b>：若 Redis 在此方法执行期间仍未恢复，
     * INCR 操作被吞掉，MySQL 订单已推进为超时取消——但 Redis 重启后（AOF 恢复快照）
     * 该库存不会回流。这件商品将<b>永久少卖 1 件</b>。
     * </p>
     * <p>
     * <b>为什么不补偿</b>：在 Redis 宕机 + MQ 延时消息到期的双重故障下，
     * 系统优先保证 MySQL 状态推进（防止业务阻塞），接受微量的"少卖"作为
     * 双故障降级的物理妥协。生产环境通过 Redis Sentinel + 对账脚本兜底。
     * </p>
     *
     * @param orderId 订单 ID
     * @param goodsId 商品 ID
     * @since 2026-05-12
     */
    private void processTimeoutWithDbLock(Long orderId, Long goodsId) {
        Order order = orderMapper.selectByIdForUpdate(orderId);
        if (order == null) {
            log.warn("Order not found (DB lock): orderId={}, ACK", orderId);
            return;
        }

        int status = order.getStatus();
        if (status == OrderStatus.PENDING_PAY.getCode()) {
            try {
                redisTemplate.opsForValue().increment(RedisKeyBuilder.stockKey(goodsId), 1);
            } catch (Exception e) {
                log.error("Stock return failed (Redis still down): orderId={}, goodsId={}", orderId, goodsId, e);
            }

            order.setStatus(OrderStatus.TIMEOUT_CANCELLED.getCode());
            order.setCancelTime(LocalDateTime.now());
            orderMapper.updateById(order);

            log.info("Order timeout cancelled (DB lock fallback): orderId={}", orderId);

        } else if (status == OrderStatus.PAID.getCode()) {
            log.info("Order already paid (DB lock): orderId={}, ACK", orderId);
        } else {
            log.info("Order terminal state (DB lock): orderId={}, status={}, ACK", orderId, status);
        }
    }
}
