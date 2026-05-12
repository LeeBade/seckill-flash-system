package com.seckill.mq;

/**
 * RocketMQ Topic 和 ConsumerGroup 常量——纯基础设施，不包含业务逻辑。
 *
 * <h3>引流中转模式</h3>
 * <p>
 * RocketMQ 的事务消息（Half Message）与延时消息（Scheduled Message）在物理上互斥——
 * 不能发送一条"带 5 分钟延时的半消息"。因此引入两个 Topic 的中转架构：
 * </p>
 * <ol>
 *   <li>{@link #TOPIC_ORDER_CREATE} —— 事务消息 Topic。OrderService 发送 Half Message 到此，
 *       OrderTransactionListener 执行 INSERT。COMMIT 后消息立刻投递（无延时）。</li>
 *   <li>{@link #TOPIC_ORDER_TIMEOUT} —— 延时消息 Topic。OrderCreateConsumer 收到创建事件后，
 *       发送一条 {@code delayLevel=14}（10 分钟）的普通延时消息到此。OrderTimeoutConsumer 在延时到期后
 *       执行超时核验与库存回池。</li>
 * </ol>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public final class MqConstants {

    /** 订单创建事务消息 Topic——OrderService 发送半消息的目标 */
    public static final String TOPIC_ORDER_CREATE = "seckill-order-create-topic";

    /** 订单创建消费者组——OrderCreateConsumer 监听 TOPIC_ORDER_CREATE */
    public static final String CONSUMER_GROUP_ORDER_CREATE = "seckill-order-create-consumer";

    /** 订单超时延时消息 Topic——OrderCreateConsumer 转发延时消息的目标 */
    public static final String TOPIC_ORDER_TIMEOUT = "seckill-order-timeout-topic";

    /** 超时订单消费者组——OrderTimeoutConsumer 监听 TOPIC_ORDER_TIMEOUT */
    public static final String CONSUMER_GROUP_ORDER_TIMEOUT = "seckill-order-timeout-consumer";

    /** 事务消息生产者组（OrderService 使用的 RocketMQTemplate） */
    public static final String PRODUCER_GROUP_ORDER = "seckill-order-producer";

    /**
     * 延时消息等级：10 分钟（RocketMQ 默认 MessageStoreConfig.messageDelayLevel）。
     * <p>
     * 默认 delayLevel 映射：1=1s, 2=5s, 3=10s, 4=30s, 5=1m,
     * 6=2m, 7=3m, 8=4m, 9=5m, 10=6m, 11=7m, 12=8m, 13=9m, 14=10m,
     * 15=20m, 16=30m, 17=1h, 18=2h。
     * </p>
     */
    public static final int DELAY_LEVEL_10_MIN = 14;

    private MqConstants() {
    }
}
