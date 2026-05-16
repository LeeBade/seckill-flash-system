package com.seckill.order;

import com.seckill.common.SnowflakeIdGenerator;
import com.seckill.engine.SeckillEngine;
import com.seckill.engine.SeckillResult;
import com.seckill.mq.MqConstants;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * 订单服务——执行严格的 Saga 物理序列。
 * <p>
 * <b>正确的 Phase 0~3 序列（不可颠倒）</b>：
 * <ol>
 *   <li><b>内存极速判决</b>——{@link SeckillEngine#execute} 执行 Lua 原子扣减。
 *       不成功（售罄/已购买）→ 直接拦截返回，不产生任何 MQ 请求。</li>
 *   <li><b>危机区间</b>——Lua 已成功，Redis 状态已变更。进入脆弱的"已扣减但未落库"状态。</li>
 *   <li><b>发送事务半消息到 TOPIC_ORDER_CREATE</b>——{@code sendMessageInTransaction}
 *       被 try-catch 包裹。COMMIT 后消息立即投递（事务消息与延时消息物理互斥，
 *       延时由 {@link com.seckill.order.OrderCreateConsumer} 中转实现）。</li>
 *   <li><b>物理补偿兜底</b>——catch block 调用 {@link SeckillEngine#rollback}，
 *       原子恢复库存 + 删除购买记录。用户可立即重试。</li>
 *   <li><b>本地落库 + 引流中转</b>——{@link OrderTransactionListener} 执行纯粹的
 *       MySQL INSERT（无 Redis 操作）。COMMIT 后消息立即投递到
 *       {@link com.seckill.order.OrderCreateConsumer} 中转 → 延时 10 分钟 →
 *       {@link com.seckill.order.OrderTimeoutConsumer} 执行超时核验。</li>
 * </ol>
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final SeckillEngine seckillEngine;
    private final SnowflakeIdGenerator idGenerator;
    private final RocketMQTemplate rocketMQTemplate;

    public OrderService(SeckillEngine seckillEngine,
                        SnowflakeIdGenerator idGenerator,
                        RocketMQTemplate rocketMQTemplate) {
        this.seckillEngine = seckillEngine;
        this.idGenerator = idGenerator;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 创建订单——Saga 物理序列（execute + MQ + rollback 绑定在同一 try-catch）。
     * <p>
     * <b>调用方（SeckillController）不得在此之前独立调用 engine.execute()，
     * 否则会破坏 Saga 原子性并导致双重扣减。</b>
     * </p>
     *
     * @param goodsId     商品 ID
     * @param userId      用户 ID
     * @param activityId  活动 ID
     * @param seckillPrice 秒杀成交价（单位:分）
     * @return 创建结果——成功携带 orderId，失败携带拒绝原因
     * @throws RuntimeException 若 Lua 扣减成功但 MQ 半消息发送失败（已执行 Saga 回滚）
     * @since 2026-05-12
     */
    public CreateOrderResult createOrder(long goodsId, long userId, long activityId, int seckillPrice) {

        // ════════════════════════════════════════════════════
        // Step 1: 内存极速判决——Lua 原子扣减
        // 不成功 → 直接返回，零 MQ 请求，零数据库操作
        // ════════════════════════════════════════════════════
        SeckillResult result = seckillEngine.execute(goodsId, userId);
        if (result != SeckillResult.SUCCESS) {
            log.info("Seckill rejected: goodsId={}, userId={}, result={}", goodsId, userId, result);
            return CreateOrderResult.rejected(result);
        }

        // ════════════════════════════════════════════════════
        // Step 2: 危机区间——Lua 已成功，Redis 库存已扣。
        // 此时若 JVM 宕机（1ms 黑暗时刻）→ 库存泄漏（少卖），
        // 但 Redis-first 是 Saga 的故意取舍——宁可少卖也不可超卖。
        // ════════════════════════════════════════════════════
        Long orderId = idGenerator.nextId();
        OrderTimeoutMessage body = new OrderTimeoutMessage(orderId, goodsId);
        OrderCreationContext ctx = new OrderCreationContext(
                orderId, userId, goodsId, activityId, seckillPrice);

        // Step 3: 发送事务半消息 → Broker 回调 executeLocalTransaction 执行纯 DB INSERT
        TransactionSendResult sendResult;
        try {
            sendResult = rocketMQTemplate.sendMessageInTransaction(
                    MqConstants.TOPIC_ORDER_CREATE,
                    MessageBuilder.withPayload(body).build(),
                    ctx
            );
        } catch (Exception e) {
            // ════════════════════════════════════════════════════
            // Step 4a: 半消息发送失败（Broker 不可达 / 网络中断）
            // 半消息未持久化 → Broker 不会回调 executeLocalTransaction
            // → DB 无订单 → 必须回滚 Redis
            // ════════════════════════════════════════════════════
            log.error("Half message send failed! Rolling back Redis: orderId={}, goodsId={}, userId={}",
                    orderId, goodsId, userId, e);
            seckillEngine.rollback(goodsId, userId);
            throw new RuntimeException(
                    "Seckill succeeded but order creation failed, stock has been restored. " +
                            "Please retry. goodsId=" + goodsId, e);
        }

        // Step 4b: 半消息发送成功——检查本地事务执行结果
        // sendMessageInTransaction 对 ROLLBACK 不抛异常，只反映在返回值中
        LocalTransactionState state = sendResult.getLocalTransactionState();

        if (state == LocalTransactionState.COMMIT_MESSAGE) {
            log.info("Order created: orderId={}, goodsId={}, userId={}", orderId, goodsId, userId);
            return CreateOrderResult.ok(orderId);
        }

        if (state == LocalTransactionState.ROLLBACK_MESSAGE) {
            // executeLocalTransaction 返回 ROLLBACK —— DB INSERT 确定失败
            // 必须回滚 Redis，否则库存永久泄漏 + 用户被限购锁死
            log.error("Local transaction ROLLBACK, rolling back Redis: orderId={}, goodsId={}, userId={}",
                    orderId, goodsId, userId);
            seckillEngine.rollback(goodsId, userId);
            return CreateOrderResult.rejected(SeckillResult.SOLD_OUT);
        }

        // UNKNOW: 不确定 DB INSERT 是否成功 —— 不碰 Redis（避免超卖）
        // Broker 会回调 checkLocalTransaction 回查 DB 状态补判
        log.error("Transaction state UNKNOW: orderId={}, goodsId={}, userId={}. " +
                "Not rolling back Redis to avoid potential oversell. " +
                "checkLocalTransaction will probe DB and determine final state.",
                orderId, goodsId, userId);
        throw new RuntimeException(
                "Order transaction state UNKNOW, please retry or check order status later. orderId=" + orderId);
    }

    /**
     * 订单创建结果——携带 orderId 或引擎拒绝原因。
     * <p>
     * 成功时可通过 {@link #isSuccess()} 判定并取 {@link #getOrderId()}；
     * 失败时通过 {@link #getRejection()} 取 {@link SeckillResult} 映射到对应的 {@link com.seckill.common.ResultCode}。
     * </p>
     */
    public static class CreateOrderResult {
        private final boolean success;
        private final Long orderId;
        private final SeckillResult rejection;

        private CreateOrderResult(boolean success, Long orderId, SeckillResult rejection) {
            this.success = success;
            this.orderId = orderId;
            this.rejection = rejection;
        }

        public static CreateOrderResult ok(Long orderId) {
            return new CreateOrderResult(true, orderId, null);
        }

        public static CreateOrderResult rejected(SeckillResult reason) {
            return new CreateOrderResult(false, null, reason);
        }

        public boolean isSuccess() { return success; }
        public Long getOrderId() { return orderId; }
        public SeckillResult getRejection() { return rejection; }
    }

    /**
     * 订单创建上下文——从 OrderService 传递到 OrderTransactionListener 的参数载体。
     */
    public static class OrderCreationContext {
        private final Long orderId;
        private final Long userId;
        private final Long goodsId;
        private final Long activityId;
        private final Integer seckillPrice;

        public OrderCreationContext(Long orderId, Long userId, Long goodsId,
                                    Long activityId, Integer seckillPrice) {
            this.orderId = orderId;
            this.userId = userId;
            this.goodsId = goodsId;
            this.activityId = activityId;
            this.seckillPrice = seckillPrice;
        }

        public Long getOrderId() {
            return orderId;
        }

        public Long getUserId() {
            return userId;
        }

        public Long getGoodsId() {
            return goodsId;
        }

        public Long getActivityId() {
            return activityId;
        }

        public Integer getSeckillPrice() {
            return seckillPrice;
        }
    }
}
