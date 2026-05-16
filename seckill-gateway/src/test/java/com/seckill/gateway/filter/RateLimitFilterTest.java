package com.seckill.gateway.filter;

import com.seckill.common.RedisKeyBuilder;
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
 * TEST-3.5: RateLimitFilter 破坏性测试——ZSet Lua 滑动窗口限流。
 * <p>
 * 连接真实 Redis（127.0.0.1:6379），使用 gateway 自带的 rate_limit.lua。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("RateLimitFilter 破坏性测试")
class RateLimitFilterTest {

    private static final long USER_ID = 77777L;

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private RateLimitFilter filter;
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
        filter = new RateLimitFilter(redisTemplate);
        filter.loadScript();

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));

        when(request.getAttribute(GatewayConstants.ATTR_USER_ID)).thenReturn(USER_ID);
    }

    @AfterEach
    void cleanUp() {
        String key = RedisKeyBuilder.rateLimit(USER_ID);
        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("窗口内正常请求 → 放行")
    void testSingleRequestWithinWindowPasses() throws Exception {
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("窗口内超额请求 → 429 拒绝")
    void testExceedingWindowRejected() throws Exception {
        int max = GatewayConstants.USER_QPS;
        for (int i = 0; i < max; i++) {
            filter.doFilterInternal(request, response, chain);
            // 清空 response mock 状态
            reset(response);
            when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            reset(chain);
        }

        // 第 max+1 次 → 应该被拒绝
        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(429);
    }

    @Test
    @DisplayName("缺失 userId attribute → 403 拒绝")
    void testMissingUserIdAttributeRejected() throws Exception {
        when(request.getAttribute(GatewayConstants.ATTR_USER_ID)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("不同 userId 各自独立限流窗口")
    void testDifferentUsersIndependentWindows() throws Exception {
        // User A: 超额
        for (int i = 0; i < GatewayConstants.USER_QPS + 1; i++) {
            filter.doFilterInternal(request, response, chain);
        }
        verify(response, atLeastOnce()).setStatus(429);

        // User B: 全新窗口 → 应通过
        reset(response, chain);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        when(request.getAttribute(GatewayConstants.ATTR_USER_ID)).thenReturn(99999L);

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);

        // 清理
        redisTemplate.delete(RedisKeyBuilder.rateLimit(99999L));
    }

    @Test
    @DisplayName("Gateway 自载 Lua——不依赖 SeckillEngine")
    void testSelfContainedLuaLoading() {
        assertDoesNotThrow(() -> filter.loadScript(),
                "RateLimitFilter must load its own rate_limit.lua without seckill-engine");
    }

    @Test
    @DisplayName("Redis ZSet Key 被污染为 String → WRONGTYPE 错误自然传播，不静默吞异常")
    void testWronTypeErrorPropagatesNotSwallowed() throws Exception {
        // 故意用 String 覆盖 ZSet Key 模拟数据污染——这是严重故障，不应被吞
        String key = RedisKeyBuilder.rateLimit(USER_ID);
        redisTemplate.opsForValue().set(key, "corrupted");

        // WRONGTYPE 必须传播到上层，由全局异常处理器统一返回 9001 SYSTEM_BUSY
        assertThrows(org.springframework.data.redis.RedisSystemException.class,
                () -> filter.doFilterInternal(request, response, chain),
                "Redis WRONGTYPE must propagate — data corruption is not recoverable");
    }
}
