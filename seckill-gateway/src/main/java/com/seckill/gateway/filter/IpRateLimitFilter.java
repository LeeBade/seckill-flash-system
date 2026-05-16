package com.seckill.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.util.concurrent.RateLimiter;
import com.seckill.common.ResultCode;
import com.seckill.gateway.GatewayConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 第 1 层：IP 令牌桶限流（Caffeine 堆内存，&lt;1μs）。
 * <p>
 * <b>IP 获取</b>：优先 {@code X-Forwarded-For}（取最左第一个），
 * 其次 {@code X-Real-IP}，最终回退 {@code getRemoteAddr()}。
 * 不这样做的话在 Nginx/ALB 后面拿到的永远是代理内网 IP。
 * </p>
 * <p>
 * <b>黑名单权衡</b>：直接封 IP 60s 在 NAT 网关（校园/写字楼共享出口）场景下
 * 会造成大面积连坐误杀。生产环境更优雅的做法是降级为强制 CAPTCHA 而非封杀。
 * 此处保留黑名单作为开发环境默认防御，生产需结合安全运营策略定制。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class IpRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimitFilter.class);

    private final Cache<String, RateLimiter> ipCache =
            Caffeine.newBuilder()
                    .maximumSize(GatewayConstants.IP_CACHE_MAX)
                    .build();

    private final Cache<String, Boolean> blacklist =
            Caffeine.newBuilder()
                    .maximumSize(GatewayConstants.BLACKLIST_CACHE_MAX)
                    .expireAfterWrite(GatewayConstants.BLACKLIST_TTL_SEC, TimeUnit.SECONDS)
                    .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = extractClientIp(request);

        if (blacklist.getIfPresent(ip) != null) {
            reject(response);
            return;
        }

        RateLimiter limiter = ipCache.getIfPresent(ip);
        if (limiter == null) {
            ipCache.put(ip, RateLimiter.create(GatewayConstants.IP_QPS));
            filterChain.doFilter(request, response);
            return;
        }

        if (limiter.tryAcquire()) {
            filterChain.doFilter(request, response);
        } else {
            blacklist.put(ip, true);
            log.warn("IP rate limited and blacklisted: {}", ip);
            reject(response);
        }
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(GatewayConstants.HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).strip() : forwarded.strip();
        }
        String realIp = request.getHeader(GatewayConstants.HEADER_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.strip();
        }
        return request.getRemoteAddr();
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + ResultCode.RATE_LIMITED.getCode()
                + ",\"message\":\"" + ResultCode.RATE_LIMITED.getMessage() + "\"}");
    }
}
