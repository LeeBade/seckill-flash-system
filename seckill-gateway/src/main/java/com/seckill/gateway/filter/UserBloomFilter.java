package com.seckill.gateway.filter;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.seckill.common.ResultCode;
import com.seckill.gateway.GatewayConstants;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 第 2 层：用户 Bloom 过滤器（Guava 堆内存，&lt;1μs，零 Redis）。
 * <p>
 * 拒绝 99.99% 的随机 userId（机器穷举），零 Redis 操作。
 * userId 从 HTTP Header {@code X-User-Id} 读取——不碰 JSON Body，
 * 避免 InputStream 只读一次的限制。
 * {@code @PostConstruct} 用 for-loop 代码预暖 10 万合法测试 userId。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class UserBloomFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UserBloomFilter.class);

    private final BloomFilter<Long> bloomFilter;

    public UserBloomFilter() {
        this.bloomFilter = BloomFilter.create(
                Funnels.longFunnel(),
                GatewayConstants.BLOOM_INSERTIONS,
                GatewayConstants.BLOOM_FPP);
    }

    @PostConstruct
    void seed() {
        long count = 0;
        for (long i = GatewayConstants.BLOOM_SEED_START;
             i < GatewayConstants.BLOOM_SEED_END; i++) {
            bloomFilter.put(i);
            count++;
        }
        log.info("BloomFilter seeded with {} legitimate userIds ({} ~ {})",
                count, GatewayConstants.BLOOM_SEED_START, GatewayConstants.BLOOM_SEED_END - 1);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String userIdParam = request.getHeader(GatewayConstants.HEADER_USER_ID);
        if (userIdParam == null || userIdParam.isBlank()) {
            reject(response, "missing");
            return;
        }

        long userId;
        try {
            userId = Long.parseLong(userIdParam);
        } catch (NumberFormatException e) {
            reject(response, userIdParam);
            return;
        }

        if (bloomFilter.mightContain(userId)) {
            filterChain.doFilter(request, response);
        } else {
            log.debug("Bloom rejected non-existent userId={}", userId);
            reject(response, String.valueOf(userId));
        }
    }

    private void reject(HttpServletResponse response, String userId) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + ResultCode.TOKEN_INVALID.getCode()
                + ",\"message\":\"invalid userId\"}");
    }
}
