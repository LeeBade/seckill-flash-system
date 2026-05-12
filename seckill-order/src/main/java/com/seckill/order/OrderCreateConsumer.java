package com.seckill.order;

import com.seckill.mq.MqConstants;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 订单创建消费者——引流中转模式的枢纽。
 * <p>
 * RocketMQ 事务消息（Half Message）与延时消息（Scheduled Message）在物理上互斥。
 * 因此：OrderService 的事务消息 → TOPIC_ORDER_CREATE（立即投递）→ 此 Consumer
 * 收到后 → 发送一条普通延时消息到 TOPIC_ORDER_TIMEOUT（10 分钟后投递）。
 * </p>
 * <p>
 * <b>可靠性保证</b>：若发送延时消息失败，Consumer 不返回 CONSUME_SUCCESS——
 * RocketMQ 会自动重试，保证引流动作绝对不丢。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Component
@RocketMQMessageListener(
        topic = MqConstants.TOPIC_ORDER_CREATE,
        consumerGroup = MqConstants.CONSUMER_GROUP_ORDER_CREATE,
        consumeThreadNumber = 2
)
public class OrderCreateConsumer implements RocketMQListener<OrderTimeoutMessage> {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateConsumer.class);

    private final RocketMQTemplate rocketMQTemplate;

    public OrderCreateConsumer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 收到事务消息 COMMIT 事件 → 发送延时消息到超时 Topic。
     * <p>
     * 若发送失败（如 Broker 不可达），直接抛异常让 RocketMQ 重试——不 ACK。
     * </p>
     *
     * @param msg 包含 orderId 和 goodsId 的消息体
     * @since 2026-05-12
     */
    @Override
    public void onMessage(OrderTimeoutMessage msg) {
        log.info("Order create event received: orderId={}, goodsId={}, relaying to timeout topic",
                msg.getOrderId(), msg.getGoodsId());

        rocketMQTemplate.syncSend(
                MqConstants.TOPIC_ORDER_TIMEOUT,
                MessageBuilder.withPayload(msg).build(),
                500,  // timeout 500ms
                MqConstants.DELAY_LEVEL_10_MIN  // 10 分钟后投递
        );

        log.info("Delayed message sent: orderId={}, delayLevel={}",
                msg.getOrderId(), MqConstants.DELAY_LEVEL_10_MIN);
    }
}
