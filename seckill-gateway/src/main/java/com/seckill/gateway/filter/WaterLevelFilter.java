package com.seckill.gateway.filter;

import com.seckill.common.ResultCode;
import com.seckill.gateway.GatewayConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * 第 4 层：全局 QPS 水位降级（本地计数 + 异步批量上报，零请求级 Redis）。
 * <p>
 * <b>doFilter() 零 Redis 调用</b>——主线程只做 {@link LongAdder#increment()}
 * + 读 {@code volatile currentGlobalQps}（纳秒级）。
 * </p>
 * <p>
 * <b>时间桶（Time-Bucket）</b>——Key 降维到秒级（{@code rate:watermark:qps:1715000000}），
 * 并设置 2s 过期。避免计数器永久累积导致系统永久性拒绝服务。
 * </p>
 * <p>
 * 后台 {@code @Scheduled(100ms)} 承担<b>双向同步</b>：
 * <ol>
 *   <li>推送：{@code adder.sumThenReset()} → {@code INCRBY key:second delta}</li>
 *   <li>拉取：{@code GET key:second} → 写回 {@code volatile currentGlobalQps}</li>
 * </ol>
 * 10 万 QPS 下 10 万次 Redis GET 被压缩为每秒 10 次。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class WaterLevelFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WaterLevelFilter.class);

    private final LongAdder adder = new LongAdder();
    private volatile int currentGlobalQps;
    private final StringRedisTemplate redisTemplate;

    public WaterLevelFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        adder.increment();

        if (currentGlobalQps >= GatewayConstants.WATER_LEVEL_QPS) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 后台双向同步——推送本地增量 + 拉取全局总量。
     * <p>
     * <b>为什么 delta==0 也必须 GET Redis（核心设计决策）</b>：
     * <ol>
     *   <li><b>单机退场</b>——如果本机流量在 12:00:05 彻底归零而跳过 GET，
     *       {@code currentGlobalQps} 永远卡在前一秒的高位值（如 20000），
     *       后续正常用户被永久 CAPTCHA 拦截——状态毒化。</li>
     *   <li><b>集群统御</b>——100 台 Node 每台 10 万 QPS = 1000 万次/秒。
     *       每台每 100ms 一次 {@code GET key:second}，全集群 = 1000 次 Redis GET/秒。
     *       1000 万次请求的全局水位判断被压缩成 1000 次 Redis 交互——
     *       压缩比 10000:1，消灭读热点。</li>
     *   <li><b>归零机制</b>——Key 不存在 = 这一秒全集群零写入。
     *       此时必须将 {@code currentGlobalQps} 归零，解除熔断。</li>
     * </ol>
     * </p>
     */
    @Scheduled(fixedRateString = "#{T(com.seckill.gateway.GatewayConstants).WATER_LEVEL_FLUSH_MS}")
    void flushToRedis() {
        try {
            long delta = adder.sumThenReset();
            long second = System.currentTimeMillis() / 1000;
            String key = GatewayConstants.WATERMARK_KEY_PREFIX + second;

            if (delta > 0) {
                redisTemplate.opsForValue().increment(key, delta);
                redisTemplate.expire(key, GatewayConstants.WATERMARK_KEY_TTL_SEC, TimeUnit.SECONDS);
            }

            String val = redisTemplate.opsForValue().get(key);
            if (val != null) {
                currentGlobalQps = Integer.parseInt(val);
            } else {
                currentGlobalQps = 0;
            }
        } catch (Exception e) {
            log.warn("WaterLevel sync failed, using cached value={}", currentGlobalQps, e);
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":" + ResultCode.CAPTCHA_REQUIRED.getCode()
                + ",\"message\":\"" + ResultCode.CAPTCHA_REQUIRED.getMessage() + "\"}");
    }
}
