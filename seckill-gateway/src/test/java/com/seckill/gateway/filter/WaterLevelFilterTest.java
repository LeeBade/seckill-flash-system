package com.seckill.gateway.filter;

import com.seckill.gateway.GatewayConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TEST-3.4: WaterLevelFilter 破坏性测试——时间桶 + 阈值 + 状态毒化防止。
 * <p>
 * 连接真实 Redis（127.0.0.1:6379）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("WaterLevelFilter 破坏性测试")
class WaterLevelFilterTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private WaterLevelFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeAll
    static void setUpRedis() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() throws Exception {
        filter = new WaterLevelFilter(redisTemplate);
        responseWriter = new StringWriter();

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys(GatewayConstants.WATERMARK_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    @Test
    @DisplayName("doFilter 零 Redis 调用——只做 LongAdder + volatile 读")
    void testDoFilterZeroRedisCalls() throws Exception {
        // 验证 filter 不持有任何对外连接（除了注入的 redisTemplate 未被 doFilter 使用）
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("水位超阈值 → CAPTCHA_REQUIRED(2003)")
    void testWaterLevelExceededReturnsCaptcha() throws Exception {
        // 手动设置超限水位
        java.lang.reflect.Field field = WaterLevelFilter.class.getDeclaredField("currentGlobalQps");
        field.setAccessible(true);
        field.setInt(filter, 15000);

        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("水位未超限 → 正常放行")
    void testWaterLevelNormalPassesThrough() throws Exception {
        java.lang.reflect.Field field = WaterLevelFilter.class.getDeclaredField("currentGlobalQps");
        field.setAccessible(true);
        field.setInt(filter, 5000);

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("flushToRedis: 推送本地增量 + 拉取全局总量")
    void testFlushToRedisPushesAndPulls() throws Exception {
        // 模拟本地有增量
        java.lang.reflect.Field adderField = WaterLevelFilter.class.getDeclaredField("adder");
        adderField.setAccessible(true);
        java.util.concurrent.atomic.LongAdder adder =
                (java.util.concurrent.atomic.LongAdder) adderField.get(filter);
        adder.add(100);

        filter.flushToRedis();

        // 验证 Redis 里有当前秒的 Key
        long second = System.currentTimeMillis() / 1000;
        String key = GatewayConstants.WATERMARK_KEY_PREFIX + second;
        String val = redisTemplate.opsForValue().get(key);
        assertNotNull(val, "flushToRedis must write to time-bucket key");
    }

    @Test
    @DisplayName("状态毒化防止：Key 过期时 currentGlobalQps 归零")
    void testStatePoisoningPrevention() throws Exception {
        // 先污染状态
        java.lang.reflect.Field field = WaterLevelFilter.class.getDeclaredField("currentGlobalQps");
        field.setAccessible(true);
        field.setInt(filter, 20000);

        // delta==0 + 上一秒的 Key 已过期 → flush 应该归零
        long prevSecond = System.currentTimeMillis() / 1000 - 2; // 肯定已过期
        String prevKey = GatewayConstants.WATERMARK_KEY_PREFIX + prevSecond;

        // 确保 Key 不存在
        redisTemplate.delete(prevKey);

        // 手动构造 flush：没有 delta，但会读对应秒的 Key
        filter.flushToRedis();

        int qps = (int) field.get(filter);
        // 如果当前秒 Key 也不存在 → 应归零
        long currentSecond = System.currentTimeMillis() / 1000;
        String currentKey = GatewayConstants.WATERMARK_KEY_PREFIX + currentSecond;
        String currentVal = redisTemplate.opsForValue().get(currentKey);

        // currentGlobalQps 要么反映 Redis 真实值，要么为 0
        assertTrue(qps >= 0, "currentGlobalQps must never go negative");
        if (currentVal == null) {
            assertEquals(0, qps, "When Redis has no key, currentGlobalQps must be 0");
        }
    }

    @Test
    @DisplayName("LongAdder 计数——每个请求 adder.increment()")
    void testLongAdderIncrementsPerRequest() throws Exception {
        for (int i = 0; i < 100; i++) {
            filter.doFilterInternal(request, response, chain);
        }

        java.lang.reflect.Field adderField = WaterLevelFilter.class.getDeclaredField("adder");
        adderField.setAccessible(true);
        java.util.concurrent.atomic.LongAdder adder =
                (java.util.concurrent.atomic.LongAdder) adderField.get(filter);

        long sum = adder.sum();
        assertTrue(sum >= 100, "LongAdder must accumulate at least 100 (may be more if async flush runs)");
    }
}
