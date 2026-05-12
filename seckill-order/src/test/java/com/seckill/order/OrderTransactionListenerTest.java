package com.seckill.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T2: 事务消息监听器单元测试——验证 checkLocalTransaction 回查与 executeLocalTransaction 逻辑。
 * <p>
 * 使用 Mockito Mock OrderMapper，不依赖真实 MySQL/Redis/MQ。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderTransactionListener 事务回查测试")
class OrderTransactionListenerTest {

    @Mock
    private OrderMapper orderMapper;

    private OrderTransactionListener listener;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        listener = new OrderTransactionListener(orderMapper, objectMapper);
    }

    // ═══════════════════════════════════════════════════════════
    // TEST-2.1: checkLocalTransaction 回查——订单存在 → COMMIT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST-2.1a: checkLocalTransaction——订单存在 → COMMIT")
    void testCheckLocalTransactionOrderExists() {
        Long orderId = 123456789L;
        OrderTimeoutMessage body = new OrderTimeoutMessage(orderId, 1L);
        Message<OrderTimeoutMessage> msg = MessageBuilder.withPayload(body).build();

        Order existingOrder = new Order();
        existingOrder.setId(orderId);
        existingOrder.setStatus(OrderStatus.PENDING_PAY.getCode());

        when(orderMapper.selectById(orderId)).thenReturn(existingOrder);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(msg);
        assertEquals(RocketMQLocalTransactionState.COMMIT, state,
                "When order exists in DB, check must return COMMIT");
    }

    // ═══════════════════════════════════════════════════════════
    // TEST-2.1b: checkLocalTransaction 回查——订单不存在 → ROLLBACK
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST-2.1b: checkLocalTransaction——订单不存在 → ROLLBACK")
    void testCheckLocalTransactionOrderNotFound() {
        Long orderId = 999999L;
        OrderTimeoutMessage body = new OrderTimeoutMessage(orderId, 1L);
        Message<OrderTimeoutMessage> msg = MessageBuilder.withPayload(body).build();

        when(orderMapper.selectById(orderId)).thenReturn(null);

        RocketMQLocalTransactionState state = listener.checkLocalTransaction(msg);
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state,
                "When order not in DB, check must return ROLLBACK");
    }

    // ═══════════════════════════════════════════════════════════
    // executeLocalTransaction——INSERT 成功 → COMMIT
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeLocalTransaction: INSERT 成功 → COMMIT")
    void testExecuteLocalTransactionInsertSuccess() {
        OrderService.OrderCreationContext ctx = new OrderService.OrderCreationContext(
                123456789L, 888L, 1L, 1L, 199900);
        OrderTimeoutMessage body = new OrderTimeoutMessage(123456789L, 1L);
        Message<OrderTimeoutMessage> msg = MessageBuilder.withPayload(body).build();

        when(orderMapper.insert(any(Order.class))).thenReturn(1);

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, ctx);
        assertEquals(RocketMQLocalTransactionState.COMMIT, state,
                "Successful INSERT must return COMMIT");
    }

    // ═══════════════════════════════════════════════════════════
    // executeLocalTransaction——INSERT 失败 → ROLLBACK
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("executeLocalTransaction: INSERT 抛异常 → ROLLBACK")
    void testExecuteLocalTransactionInsertFails() {
        OrderService.OrderCreationContext ctx = new OrderService.OrderCreationContext(
                123456789L, 888L, 1L, 1L, 199900);
        OrderTimeoutMessage body = new OrderTimeoutMessage(123456789L, 1L);
        Message<OrderTimeoutMessage> msg = MessageBuilder.withPayload(body).build();

        when(orderMapper.insert(any(Order.class)))
                .thenThrow(new RuntimeException("DB connection refused"));

        RocketMQLocalTransactionState state = listener.executeLocalTransaction(msg, ctx);
        assertEquals(RocketMQLocalTransactionState.ROLLBACK, state,
                "Failed INSERT must return ROLLBACK");
    }
}
