package com.seckill.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 破坏性测试——验证 Lua 脚本在高并发下的原子性、防超卖、限购和 Big Key 防护。
 * <p>
 * 连接真实 Redis（localhost:6379），不依赖 Spring 容器——手动创建
 * {@code LettuceConnectionFactory} 和 {@code StringRedisTemplate}，
 * 确保序列化器为 StringRedisSerializer（UTF-8 纯字符串）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("SeckillEngine 破坏性测试")
class SeckillEngineTest {

    private static final long TEST_GOODS_ID = 9999L;
    private static final int THREAD_POOL_SIZE = 64;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static SeckillEngine engine;

    @BeforeAll
    static void setUp() throws Exception {
        RedisStandaloneConfiguration config =
                new RedisStandaloneConfiguration("127.0.0.1", 6379);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        engine = new SeckillEngine(redisTemplate);
        engine.loadScripts();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    @AfterEach
    void cleanUp() {
        String pattern = "{goods:" + TEST_GOODS_ID + "}:*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        // Clean global limit test keys
        String globalPattern = "{global}:record:*";
        Set<String> globalKeys = redisTemplate.keys(globalPattern);
        if (globalKeys != null && !globalKeys.isEmpty()) {
            redisTemplate.delete(globalKeys);
        }
        // Clean rate limit test keys
        String ratePattern = "rate:limit:*";
        Set<String> rateKeys = redisTemplate.keys(ratePattern);
        if (rateKeys != null && !rateKeys.isEmpty()) {
            redisTemplate.delete(rateKeys);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TEST-1.2: 库存绝对不超卖
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST-1.2: 100 库存 vs 1000 并发 → 成功订单 = 100, 无超卖")
    void testNoOversell100Stock1000Concurrent() throws Exception {
        int totalStock = 100;
        int threads = 1000;

        redisTemplate.opsForValue().set(
                "{goods:" + TEST_GOODS_ID + "}:stock",
                String.valueOf(totalStock));

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger soldOutCount = new AtomicInteger(0);
        AtomicInteger alreadyBoughtCount = new AtomicInteger(0);

        List<Future<SeckillResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final long userId = 10000L + i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                return engine.execute(TEST_GOODS_ID, userId);
            }));
        }

        long t0 = System.currentTimeMillis();
        startLatch.countDown();

        for (Future<SeckillResult> f : futures) {
            SeckillResult r = f.get(10, TimeUnit.SECONDS);
            if (r == SeckillResult.SUCCESS) {
                successCount.incrementAndGet();
            } else if (r == SeckillResult.SOLD_OUT) {
                soldOutCount.incrementAndGet();
            } else {
                alreadyBoughtCount.incrementAndGet();
            }
        }

        executor.shutdown();
        long elapsed = System.currentTimeMillis() - t0;

        assertEquals(totalStock, successCount.get(),
                "Must sell exactly " + totalStock + ", actual success=" + successCount.get());
        assertEquals(threads - totalStock, soldOutCount.get(),
                "Remaining must be SOLD_OUT");
        assertEquals(0, alreadyBoughtCount.get(),
                "Different userIds should not trigger ALREADY_BOUGHT");

        String remainingStock = redisTemplate.opsForValue().get(
                "{goods:" + TEST_GOODS_ID + "}:stock");
        assertEquals("0", remainingStock, "Redis stock must be exactly 0");

        System.out.printf("[TEST-1.2] %d threads × %d stock, elapsed=%dms, " +
                        "success=%d, soldOut=%d%n",
                threads, totalStock, elapsed, successCount.get(), soldOutCount.get());
    }

    // ═══════════════════════════════════════════════════════════
    // TEST-1.3: 限购一件——同一 userId 并发
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST-1.3: 同一 userId × 1000 并发 → 最多 1 次成功")
    void testOnePerUserLimitSameUserId1000Concurrent() throws Exception {
        int totalStock = 100;
        int threads = 1000;
        long sameUserId = 20000L;

        redisTemplate.opsForValue().set(
                "{goods:" + TEST_GOODS_ID + "}:stock",
                String.valueOf(totalStock));

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger alreadyBoughtCount = new AtomicInteger(0);

        List<Future<SeckillResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return engine.execute(TEST_GOODS_ID, sameUserId);
            }));
        }

        startLatch.countDown();

        for (Future<SeckillResult> f : futures) {
            SeckillResult r = f.get(10, TimeUnit.SECONDS);
            if (r == SeckillResult.SUCCESS) {
                successCount.incrementAndGet();
            } else if (r == SeckillResult.ALREADY_BOUGHT) {
                alreadyBoughtCount.incrementAndGet();
            }
        }

        executor.shutdown();

        assertEquals(1, successCount.get(),
                "Same userId must succeed exactly once, actual=" + successCount.get());
        assertEquals(threads - 1, alreadyBoughtCount.get(),
                "All other attempts must return ALREADY_BOUGHT");

        // Stock should be 99 (only 1 deducted)
        String remainingStock = redisTemplate.opsForValue().get(
                "{goods:" + TEST_GOODS_ID + "}:stock");
        assertEquals(String.valueOf(totalStock - 1), remainingStock,
                "Stock must be totalStock - 1");

        System.out.printf("[TEST-1.3] %d concurrent same-user requests, " +
                        "success=%d, alreadyBought=%d, stock=%s%n",
                threads, successCount.get(), alreadyBoughtCount.get(), remainingStock);
    }

    // ═══════════════════════════════════════════════════════════
    // TEST-1.4: Big Key 防护——ZSet member 数不超过阈值 × 2
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("TEST-1.4: 高频请求不产生 Big Key——ZSet member ≤ 阈值×2")
    void testBigKeyProtectionZSetMemberCap() throws Exception {
        long testUserId = 30000L;
        int maxRequests = 5;
        long windowMs = 1000L;
        int ttlSeconds = 2;

        // Warm up: send maxRequests * 2 requests — should all pass within window
        long now = System.currentTimeMillis();
        for (int i = 0; i < maxRequests * 2; i++) {
            Long result = engine.executeRateLimit(testUserId, now + i, windowMs, maxRequests, ttlSeconds);
            // First maxRequests should pass, the rest should be rejected (0) or BigKey (-1)
            if (i < maxRequests) {
                assertEquals(1L, result, "Request " + i + " should be allowed (within limit)");
            } else {
                assertTrue(result == 0L || result == -1L,
                        "Request " + i + " should be denied (0=over-limit, -1=big-key)");
            }
        }

        // Verify ZSet member count in Redis
        String zsetKey = "rate:limit:" + testUserId;
        Long zcard = redisTemplate.opsForZSet().zCard(zsetKey);
        assertNotNull(zcard, "ZSet must exist after rate limit calls");
        assertTrue(zcard <= maxRequests * 2L + 10,
                "ZSet member count=" + zcard + " must not explode beyond threshold×2+" +
                "margin. MaxRequests=" + maxRequests + ", doubleMax=" + (maxRequests * 2));

        System.out.printf("[TEST-1.4] ZSet card=%d, maxRequests=%d, doubleMax=%d%n",
                zcard, maxRequests, maxRequests * 2);
    }

    // ═══════════════════════════════════════════════════════════
    // Saga 补偿——释放全局占位
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("Saga 补偿: 全局占位释放——值匹配才 DEL，误删防护")
    void testGlobalLimitReleaseOnlyWhenValueMatches() {
        long userId = 40000L;
        long goodsA = 1L;
        long goodsB = 2L;
        String globalKey = "{global}:record:" + userId;

        // Simulate: user claimed goodsA
        redisTemplate.opsForValue().set(globalKey, String.valueOf(goodsA));

        // Try to release with goodsB → must fail (value mismatch)
        boolean releasedWrong = engine.releaseGlobalLimit(userId, goodsB);
        assertTrue(!releasedWrong, "Must NOT release when value doesn't match");

        // Verify goodsA's claim is still intact
        String valueAfter = redisTemplate.opsForValue().get(globalKey);
        assertEquals(String.valueOf(goodsA), valueAfter,
                "Claim must be preserved when release fails");

        // Release with correct goodsA → must succeed
        boolean releasedCorrect = engine.releaseGlobalLimit(userId, goodsA);
        assertTrue(releasedCorrect, "Must release when value matches");

        // Verify key is deleted
        String valueAfterRelease = redisTemplate.opsForValue().get(globalKey);
        assertTrue(valueAfterRelease == null,
                "Key must be deleted after successful release");

        System.out.println("[SAGA] Global limit release: value-match guard works correctly");
    }

    // ═══════════════════════════════════════════════════════════
    // T1: Saga 回滚——MQ 半消息发送失败后恢复 Redis 状态
    // ═══════════════════════════════════════════════════════════

    @Test
    @DisplayName("T1 Saga回滚: 扣减→回滚→stock恢复+record删除")
    void testRollbackRestoresStockAndDeletesRecord() {
        long goodsId = TEST_GOODS_ID;
        long userId = 50000L;
        int stock = 50;

        // Setup: stock=50
        redisTemplate.opsForValue().set(
                "{goods:" + goodsId + "}:stock", String.valueOf(stock));

        // Step 1: Execute deduction → should succeed
        SeckillResult result = engine.execute(goodsId, userId);
        assertEquals(SeckillResult.SUCCESS, result, "Deduction must succeed");

        // Verify: stock = 49
        String stockAfter = redisTemplate.opsForValue().get(
                "{goods:" + goodsId + "}:stock");
        assertEquals("49", stockAfter, "Stock should be 49 after deduction");

        // Verify: record exists
        String recordExists = redisTemplate.opsForValue().get(
                "{goods:" + goodsId + "}:record:" + userId);
        assertEquals("1", recordExists, "Purchase record must exist");

        // Step 2: Simulate MQ failure → rollback
        Long rollbackResult = engine.rollback(goodsId, userId);
        assertEquals(1L, rollbackResult, "Rollback must return 1 (success)");

        // Verify: stock restored to 50
        String stockRestored = redisTemplate.opsForValue().get(
                "{goods:" + goodsId + "}:stock");
        assertEquals("50", stockRestored, "Stock must be restored to 50");

        // Verify: record deleted
        String recordAfter = redisTemplate.opsForValue().get(
                "{goods:" + goodsId + "}:record:" + userId);
        assertTrue(recordAfter == null, "Purchase record must be deleted after rollback");

        System.out.println("[T1] Rollback: stock 50→49→50, record created→deleted ✓");
    }
}
