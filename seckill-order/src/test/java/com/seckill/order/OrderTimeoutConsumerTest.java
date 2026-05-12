package com.seckill.order;

import com.seckill.common.RedisKeyBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderTimeoutConsumer 集成测试——真实 Redis + Mock DB 边界。
 * <p>
 * <b>物理架构</b>：实例化真实的 {@link OrderTimeoutConsumer}，
 * 传入 {@link StringRedisTemplate} 和 {@link RedissonClient} 连接本地 Redis，
 * Mock {@link OrderMapper} 模拟数据库读写，调用 {@code onMessage()} 触发现场，
 * 验 Redis 库存变化 + Mockito verify DB 操作。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("OrderTimeoutConsumer 集成测试")
class OrderTimeoutConsumerTest {

    private static final long GOODS_ID = 8888L;
    private static final long ORDER_ID = 99999L;
    private static final long USER_ID = 77777L;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RedissonClient redissonClient;

    // ── Per-test: fresh mock + SUT ──
    private OrderMapper orderMapper;
    private OrderTimeoutConsumer consumer;

    @BeforeAll
    static void setUp() {
        RedisStandaloneConfiguration cfg =
                new RedisStandaloneConfiguration("127.0.0.1", 6379);
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        Config redissonCfg = new Config();
        redissonCfg.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setConnectionPoolSize(4)
                .setConnectionMinimumIdleSize(2);
        redissonClient = Redisson.create(redissonCfg);
    }

    @AfterAll
    static void tearDown() {
        if (redissonClient != null) redissonClient.shutdown();
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void init() {
        orderMapper = mock(OrderMapper.class);
        consumer = new OrderTimeoutConsumer(orderMapper, redisTemplate, redissonClient);
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys("{goods:" + GOODS_ID + "}:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        keys = redisTemplate.keys("order:lock:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    // ═══════════════════════════════════════════════════════════
    // 核心路径：PENDING_PAY → 库存回池 + 状态推进
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("PENDING_PAY 订单超时 → 库存回池 +1 + 状态更新为 TIMEOUT_CANCELLED")
    void testOnMessagePendingPayReturnsStock() {
        // Given
        String stockKey = RedisKeyBuilder.stockKey(GOODS_ID);
        redisTemplate.opsForValue().set(stockKey, "100");

        Order pendingOrder = new Order();
        pendingOrder.setId(ORDER_ID);
        pendingOrder.setGoodsId(GOODS_ID);
        pendingOrder.setUserId(USER_ID);
        pendingOrder.setStatus(OrderStatus.PENDING_PAY.getCode());

        when(orderMapper.selectById(ORDER_ID)).thenReturn(pendingOrder);
        when(orderMapper.updateById(any(Order.class))).thenReturn(1);

        // When
        consumer.onMessage(new OrderTimeoutMessage(ORDER_ID, GOODS_ID));

        // Then: Redis stock +1
        assertEquals("101", redisTemplate.opsForValue().get(stockKey),
                "Stock must increment by 1 after timeout return");

        // Then: order status updated to TIMEOUT_CANCELLED
        verify(orderMapper).updateById(argThat(order ->
                order.getStatus() == OrderStatus.TIMEOUT_CANCELLED.getCode()
                        && order.getCancelTime() != null));
    }

    // ═══════════════════════════════════════════════════════════
    // 幂等路径：PAID 订单永不回滚
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("PAID 订单 → 幂等 ACK，库存不变，不调用 updateById")
    void testOnMessagePaidOrderIsIdempotent() {
        // Given
        String stockKey = RedisKeyBuilder.stockKey(GOODS_ID);
        redisTemplate.opsForValue().set(stockKey, "100");

        Order paidOrder = new Order();
        paidOrder.setId(ORDER_ID);
        paidOrder.setStatus(OrderStatus.PAID.getCode());

        when(orderMapper.selectById(ORDER_ID)).thenReturn(paidOrder);

        // When
        consumer.onMessage(new OrderTimeoutMessage(ORDER_ID, GOODS_ID));

        // Then: stock unchanged
        assertEquals("100", redisTemplate.opsForValue().get(stockKey),
                "PAID order must NOT return stock");

        // Then: updateById NEVER called
        verify(orderMapper, never()).updateById(any());
    }

    // ═══════════════════════════════════════════════════════════
    // 边界：订单不存在 → ACK
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("订单不存在 → ACK，库存不变，不调用 updateById")
    void testOnMessageOrderNotFound() {
        // Given
        String stockKey = RedisKeyBuilder.stockKey(GOODS_ID);
        redisTemplate.opsForValue().set(stockKey, "100");

        when(orderMapper.selectById(ORDER_ID)).thenReturn(null);

        // When
        consumer.onMessage(new OrderTimeoutMessage(ORDER_ID, GOODS_ID));

        // Then: stock unchanged
        assertEquals("100", redisTemplate.opsForValue().get(stockKey));

        // Then: updateById never called
        verify(orderMapper, never()).updateById(any());
    }

    // ═══════════════════════════════════════════════════════════
    // 锁竞争：另一线程持有 → RuntimeException 重试
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("锁竞争 → tryLock 失败 → 抛出 RuntimeException 触发 MQ 重试")
    void testOnMessageLockContentionThrows() throws InterruptedException {
        // Given: another thread holds the lock
        var lock = redissonClient.getLock("order:lock:" + ORDER_ID);
        CountDownLatch held = new CountDownLatch(1);
        AtomicReference<Exception> thrown = new AtomicReference<>();

        Thread holder = new Thread(() -> {
            lock.lock();
            held.countDown();
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { lock.unlock(); }
        });
        holder.start();
        held.await();

        // When: main thread calls onMessage → tryLock should fail after 100ms
        try {
            consumer.onMessage(new OrderTimeoutMessage(ORDER_ID, GOODS_ID));
        } catch (Exception e) {
            thrown.set(e);
        }

        holder.join();

        // Then: exception must have been thrown
        assertNotNull(thrown.get(),
                "Consumer must throw when lock is held by another thread");
    }

    // ═══════════════════════════════════════════════════════════
    // 终态：CANCELLED → noop
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("CANCELLED 终态订单 → ACK，库存不变")
    void testOnMessageCancelledOrderNoop() {
        // Given
        String stockKey = RedisKeyBuilder.stockKey(GOODS_ID);
        redisTemplate.opsForValue().set(stockKey, "100");

        Order cancelledOrder = new Order();
        cancelledOrder.setId(ORDER_ID);
        cancelledOrder.setStatus(OrderStatus.CANCELLED.getCode());

        when(orderMapper.selectById(ORDER_ID)).thenReturn(cancelledOrder);

        // When
        consumer.onMessage(new OrderTimeoutMessage(ORDER_ID, GOODS_ID));

        // Then: stock unchanged
        assertEquals("100", redisTemplate.opsForValue().get(stockKey));
        verify(orderMapper, never()).updateById(any());
    }
}
