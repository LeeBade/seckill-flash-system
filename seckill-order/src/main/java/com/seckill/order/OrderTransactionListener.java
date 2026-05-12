package com.seckill.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * RocketMQ 事务消息监听器——实现 Two-Phase Commit。
 * <p>
 * <b>此监听器仅执行纯粹的 MySQL INSERT——不包含任何 Redis 操作。</b>
 * Redis 扣减已在 {@link com.seckill.order.OrderService#createOrder} 的 Step 1 完成，
 * Redis 回滚在 catch block 的 Step 4 完成。此处只关心 DB 是否写入成功。
 * </p>
 * <p>
 * Phase 2 ({@link #executeLocalTransaction}): INSERT order → COMMIT / ROLLBACK<br>
 * Phase 3 ({@link #checkLocalTransaction}): JVM 宕机后 Broker 回查 → SELECT order → 补判
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@RocketMQTransactionListener
public class OrderTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(OrderTransactionListener.class);

    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    public OrderTransactionListener(OrderMapper orderMapper, ObjectMapper objectMapper) {
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Phase 2: 执行本地事务——INSERT order。
     *
     * @param msg Half Message
     * @param arg OrderService 传入的 OrderCreationContext
     * @return COMMIT（消息变为延时消息，5 分钟后可消费）或 ROLLBACK（丢弃消息）
     * @since 2026-05-12
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        OrderService.OrderCreationContext ctx = (OrderService.OrderCreationContext) arg;
        Long orderId = ctx.getOrderId();

        try {
            Order order = new Order();
            order.setId(orderId);
            order.setUserId(ctx.getUserId());
            order.setGoodsId(ctx.getGoodsId());
            order.setActivityId(ctx.getActivityId());
            order.setSeckillPrice(ctx.getSeckillPrice());
            order.setStatus(OrderStatus.PENDING_PAY.getCode());
            order.setCreateTime(LocalDateTime.now());

            orderMapper.insert(order);
            log.info("Order created: orderId={}, userId={}, goodsId={}, price={}",
                    orderId, ctx.getUserId(), ctx.getGoodsId(), ctx.getSeckillPrice());

            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("Order insert failed: orderId={}", orderId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * Phase 3: Broker 回查——JVM 在 executeLocalTransaction 返回前宕机时触发。
     *
     * @param msg Half Message
     * @return COMMIT（DB 中已有订单）或 ROLLBACK（DB 中无订单）
     * @since 2026-05-12
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        Long orderId = parseOrderId(msg);
        if (orderId == null) {
            log.error("Failed to parse orderId from check message");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            Order order = orderMapper.selectById(orderId);
            if (order != null) {
                log.info("Check OK: orderId={} exists, committed", orderId);
                return RocketMQLocalTransactionState.COMMIT;
            }
            log.info("Check FAIL: orderId={} not found, rollback", orderId);
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            log.error("Check error: orderId={}", orderId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    private Long parseOrderId(Message msg) {
        if (msg.getPayload() instanceof OrderTimeoutMessage body) {
            return body.getOrderId();
        }
        if (msg.getPayload() instanceof byte[] bytes) {
            try {
                OrderTimeoutMessage body = objectMapper.readValue(
                        new String(bytes, StandardCharsets.UTF_8), OrderTimeoutMessage.class);
                return body.getOrderId();
            } catch (Exception e) {
                log.error("Failed to deserialize message body", e);
            }
        }
        return null;
    }
}
