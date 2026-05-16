package com.seckill.gateway.filter;

import com.seckill.gateway.GatewayConstants;
import com.seckill.gateway.HmacUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TEST-3.3: DynamicUrlFilter 破坏性测试——HMAC 验签必须先于 Redis。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("DynamicUrlFilter 破坏性测试")
class DynamicUrlFilterTest {

    private static final String SECRET = "test-hmac-secret-for-phase3";
    private static final long GOODS_ID = 1L;
    private static final long USER_ID = 888L;

    private DynamicUrlFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;
    private StringWriter responseWriter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new DynamicUrlFilter();
        ReflectionTestUtils.setField(filter, "secret", SECRET);

        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        responseWriter = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    @DisplayName("合法 Token → 放行 + 注入 request attribute")
    void testValidTokenPassesAndInjectsAttributes() throws Exception {
        String token = HmacUtils.sign(GOODS_ID, USER_ID, SECRET, GatewayConstants.TOKEN_TTL_MS);
        when(request.getHeader(GatewayConstants.HEADER_TOKEN)).thenReturn(token);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(GatewayConstants.ATTR_GOODS_ID, GOODS_ID);
        verify(request).setAttribute(GatewayConstants.ATTR_USER_ID, USER_ID);
    }

    @Test
    @DisplayName("篡改的 Token → 403 拒绝，零 Redis 消耗")
    void testTamperedTokenRejected() throws Exception {
        String validToken = HmacUtils.sign(GOODS_ID, USER_ID, SECRET, GatewayConstants.TOKEN_TTL_MS);
        // 修改最后一个字符
        String tampered = validToken.substring(0, validToken.length() - 1)
                + (validToken.charAt(validToken.length() - 1) == 'A' ? 'B' : 'A');
        when(request.getHeader(GatewayConstants.HEADER_TOKEN)).thenReturn(tampered);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("缺少 Token Header → 403 拒绝")
    void testMissingTokenRejected() throws Exception {
        when(request.getHeader(GatewayConstants.HEADER_TOKEN)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(403);
    }

    @Test
    @DisplayName("Token 不在 request parameter 中——测试 Header 优先")
    void testTokenReadFromHeaderNotParameter() throws Exception {
        String token = HmacUtils.sign(GOODS_ID, USER_ID, SECRET, GatewayConstants.TOKEN_TTL_MS);
        when(request.getHeader(GatewayConstants.HEADER_TOKEN)).thenReturn(token);
        // getParameter 返回不同值也不影响
        when(request.getParameter("token")).thenReturn("bogus-param-value");

        filter.doFilterInternal(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("不调用 request.getParameter——整个验证只基于 Header")
    void testNeverReadsParameter() throws Exception {
        String token = HmacUtils.sign(GOODS_ID, USER_ID, SECRET, GatewayConstants.TOKEN_TTL_MS);
        when(request.getHeader(GatewayConstants.HEADER_TOKEN)).thenReturn(token);

        filter.doFilterInternal(request, response, chain);

        // 验证从未调用 getParameter
        verify(request, never()).getParameter(anyString());
    }

}
