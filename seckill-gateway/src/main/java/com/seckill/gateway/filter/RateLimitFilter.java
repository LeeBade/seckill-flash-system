package com.seckill.gateway.filter;

import com.seckill.common.RedisKeyBuilder;
import com.seckill.common.ResultCode;
import com.seckill.gateway.GatewayConstants;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 第 5 层：用户级滑动窗口限流（Redis ZSet Lua，~0.2ms）。
 * <p>
 * <b>Gateway 自行加载 rate_limit.lua</b>——不依赖 seckill-engine 模块，
 * 保持边缘网关的绝对轻量。userId 从 request attribute 读取（由 DynamicUrlFilter
 * 验证 Token 后注入），零信任 headers/params。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();

    public RateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void loadScript() {
        script.setResultType(Long.class);
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/rate_limit.lua")));
        log.info("RateLimitFilter loaded rate_limit.lua from gateway resources");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Long userId = (Long) request.getAttribute(GatewayConstants.ATTR_USER_ID);

        if (userId == null) {
            response.setStatus(403);
            response.getWriter().write("{\"code\":9002,\"message\":\"missing user identity\"}");
            return;
        }

        long nowMs = System.currentTimeMillis();
        long windowMs = GatewayConstants.WINDOW_SECONDS * 1000L;
        int maxRequests = GatewayConstants.USER_QPS;
        int ttlSeconds = GatewayConstants.WINDOW_SECONDS * 2;
        String nonce = Long.toHexString(ThreadLocalRandom.current().nextLong());

        String key = RedisKeyBuilder.rateLimit(userId);
        Long result = redisTemplate.execute(script, List.of(key),
                String.valueOf(nowMs),
                String.valueOf(windowMs),
                String.valueOf(maxRequests),
                String.valueOf(ttlSeconds),
                nonce);

        if (result == null || result == 1L) {
            filterChain.doFilter(request, response);
        } else {
            if (result == -1L) {
                log.warn("Big Key anomaly for userId={}, rejecting", userId);
            }
            reject(response);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + ResultCode.RATE_LIMITED.getCode()
                + ",\"message\":\"" + ResultCode.RATE_LIMITED.getMessage() + "\"}");
    }
}
