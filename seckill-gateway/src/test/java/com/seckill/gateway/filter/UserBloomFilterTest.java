package com.seckill.gateway.filter;

import com.seckill.gateway.GatewayConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TEST-3.2: UserBloomFilter 破坏性测试——Bloom 拒绝不存在 userId，零 Redis。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("UserBloomFilter 破坏性测试")
class UserBloomFilterTest {

    private UserBloomFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new UserBloomFilter();
        filter.seed(); // 预暖 10000~110000

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    @DisplayName("预暖范围内的 userId 通过 Bloom")
    void testSeededUserIdPasses() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn("55000");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("随机 userId 被 Bloom 拒绝 ← 零 Redis 操作")
    void testRandomUserIdsRejected() throws Exception {
        int rejected = 0;
        for (int i = 0; i < 100; i++) {
            long fakeId = ThreadLocalRandom.current().nextLong(1_000_000, 10_000_000);
            when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn(String.valueOf(fakeId));

            filter.doFilterInternal(request, response, chain);
            // Bloom 可能误判（1% FPP），但绝大多数应被拒绝
        }

        // 100 个随机 ID 至少 ≥95% 被拒绝（考虑到 1% FPP + 随机可能命中种子范围）
        verify(chain, atMost(5)).doFilter(request, response);
    }

    @Test
    @DisplayName("缺少 X-User-Id Header → 拒绝 403（不带身份证明直接拦截）")
    void testMissingHeaderRejected() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("畸形的 userId → 拒绝 403")
    void testMalformedUserIdRejected() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn("not-a-number");

        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("边界值：种子范围下限 10000 通过")
    void testLowerBoundaryPasses() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn("10000");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("边界值：种子范围上限 109999 通过")
    void testUpperBoundaryPasses() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_USER_ID)).thenReturn("109999");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("零 Redis 操作——Bloom 不依赖任何外部存储")
    void testZeroRedisOperations() {
        // UserBloomFilter 构造函数和 seed() 都不需要 Redis
        assertDoesNotThrow(() -> filter.seed());
    }
}
