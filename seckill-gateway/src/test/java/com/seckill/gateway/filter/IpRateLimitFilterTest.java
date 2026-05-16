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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TEST-3.1: IpRateLimitFilter 破坏性测试。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("IpRateLimitFilter 破坏性测试")
class IpRateLimitFilterTest {

    private IpRateLimitFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new IpRateLimitFilter();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));

        when(request.getRemoteAddr()).thenReturn("10.0.0.99");
        when(request.getHeader(GatewayConstants.HEADER_FORWARDED_FOR)).thenReturn(null);
        when(request.getHeader(GatewayConstants.HEADER_REAL_IP)).thenReturn(null);
    }

    // ═══ IP 提取 ═══

    @Test
    @DisplayName("提取 X-Forwarded-For 最左端 IP")
    void testExtractForwardedFor() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_FORWARDED_FOR))
                .thenReturn("1.2.3.4, 192.168.1.1, 10.0.0.1");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("X-Forwarded-For 不存在时回退到 X-Real-IP")
    void testFallbackToRealIp() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_FORWARDED_FOR)).thenReturn(null);
        when(request.getHeader(GatewayConstants.HEADER_REAL_IP)).thenReturn("5.6.7.8");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    // ═══ 黑名单 ═══

    @Test
    @DisplayName("黑名单 IP 直接拒绝 429")
    void testBlacklistRejects() throws Exception {
        String ip = "1.2.3.4";
        when(request.getHeader(GatewayConstants.HEADER_REAL_IP)).thenReturn(ip);

        // 同一 IP 连续 50 次请求——QPS 为 20，瞬间耗尽 Guava 令牌桶
        // 首次 tryAcquire 失败即入黑名单（60s TTL）
        for (int i = 0; i < 50; i++) {
            filter.doFilterInternal(request, response, chain);
        }

        // 重置后验证黑名单拦截
        reset(response, chain);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));

        filter.doFilterInternal(request, response, chain);
        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(429);
    }

    // ═══ 正常放行 ═══

    @Test
    @DisplayName("未知 IP 直接放行（只拦已知攻击 IP）")
    void testUnknownIpPassesThrough() throws Exception {
        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("已知 IP 在 QPS 内正常通过")
    void testKnownIpWithinLimit() throws Exception {
        String ip = "192.168.1.100";
        when(request.getHeader(GatewayConstants.HEADER_REAL_IP)).thenReturn(ip);

        // 第一次：放行 + 注册
        filter.doFilterInternal(request, response, chain);

        // 第二次：还在 QPS 内
        filter.doFilterInternal(request, response, chain);

        verify(chain, times(2)).doFilter(request, response);
    }
}
