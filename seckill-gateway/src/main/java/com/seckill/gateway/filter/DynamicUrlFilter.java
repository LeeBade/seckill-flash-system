package com.seckill.gateway.filter;

import com.seckill.common.ResultCode;
import com.seckill.gateway.GatewayConstants;
import com.seckill.gateway.HmacUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 第 3 层：HMAC Token 验签（纯 CPU，&lt;0.1ms，零 Redis）。
 * <p>
 * <b>绝对排在所有 Redis Filter 之前</b>——非法 Token 没有资格消耗宝贵的
 * 网络 I/O + Redis 连接池资源。
 * Token 从 HTTP Header {@code X-Token} 读取——不碰 JSON Body。
 * </p>
 * <p>
 * 验签通过后，将 goodsId 和 userId 写入 request attribute 供下游 Filter
 * 和 Controller 使用（信任链传递）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class DynamicUrlFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DynamicUrlFilter.class);

    @Value("${seckill.gateway.hmac-secret}")
    private String secret;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = request.getHeader(GatewayConstants.HEADER_TOKEN);
        if (token == null || token.isBlank()) {
            reject(response);
            return;
        }

        HmacUtils.TokenPayload payload = HmacUtils.verify(token, secret);
        if (payload == null) {
            log.debug("Token verification failed");
            reject(response);
            return;
        }

        request.setAttribute(GatewayConstants.ATTR_GOODS_ID, payload.goodsId());
        request.setAttribute(GatewayConstants.ATTR_USER_ID, payload.userId());

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + ResultCode.TOKEN_INVALID.getCode()
                + ",\"message\":\"" + ResultCode.TOKEN_INVALID.getMessage() + "\"}");
    }
}
