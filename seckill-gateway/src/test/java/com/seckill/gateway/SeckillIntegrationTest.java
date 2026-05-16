package com.seckill.gateway;

import com.seckill.common.ResultCode;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TEST-3.6: 全链路集成测试——五层 Filter → Controller（MockMvc，真实 Redis）。
 * <p>
 * 每个测试使用不同 X-Forwarded-For IP 避免 Caffeine 缓存跨 Test Case 污染。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@SpringBootTest(
        classes = SeckillIntegrationTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "seckill.gateway.hmac-secret=dev-test-key-please-change-in-prod"
})
@DisplayName("全链路集成测试")
class SeckillIntegrationTest {

    private static final long GOODS_ID = 1L;
    private static final long USER_ID = 55000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.seckill.gateway.filter.WaterLevelFilter waterLevelFilter;

    @BeforeEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys("rate:watermark:qps:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        keys = redisTemplate.keys("rate:limit:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        keys = redisTemplate.keys("order:lock:*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
    }

    private static String freshToken() {
        return HmacUtils.sign(GOODS_ID, USER_ID, "dev-test-key-please-change-in-prod", 5000);
    }

    // ═══ 高兴路径 ═══

    @Test
    @DisplayName("全链路正常——五层 Filter → Controller → 200")
    void testFullChainHappyPath() throws Exception {
        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, freshToken())
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.101")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("TokenController: 签发 Token（经过 IP + Bloom 过滤）")
    void testTokenControllerGeneratesToken() throws Exception {
        mockMvc.perform(get("/api/token")
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.102")
                        .param("goodsId", String.valueOf(GOODS_ID))
                        .param("userId", String.valueOf(USER_ID)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString());
    }

    // ═══ Layer 1: IpRateLimitFilter ═══

    @Test
    @DisplayName("Layer 1: IP 超频 → 黑名单 → 429 RATE_LIMITED")
    void testBlacklistedIpRejected() throws Exception {
        String attackerIp = "10.10.10.10";
        String token = freshToken();

        for (int i = 0; i < 50; i++) {
            mockMvc.perform(post("/api/seckill")
                    .header(GatewayConstants.HEADER_TOKEN, token)
                    .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                    .header(GatewayConstants.HEADER_FORWARDED_FOR, attackerIp)
                    .param("activityId", "1")
                    .param("price", "199900"));
        }

        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, token)
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, attackerIp)
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(ResultCode.RATE_LIMITED.getCode()));
    }

    // ═══ Layer 2: UserBloomFilter ═══

    @Test
    @DisplayName("Layer 2: 缺失 X-User-Id → Bloom 拒绝，403")
    void testMissingUserIdRejectedByBloom() throws Exception {
        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, freshToken())
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.103")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(403));
    }

    // ═══ Layer 3: DynamicUrlFilter ═══

    @Test
    @DisplayName("Layer 3: 缺失 X-Token → HMAC 拦截，403 TOKEN_INVALID")
    void testMissingTokenRejectedByDynamicUrl() throws Exception {
        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.104")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.code").value(ResultCode.TOKEN_INVALID.getCode()));
    }

    @Test
    @DisplayName("Layer 3: 篡改 Token → HMAC 拦截，403 TOKEN_INVALID")
    void testTamperedTokenRejectedByDynamicUrl() throws Exception {
        String token = freshToken();
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, tampered)
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.105")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.code").value(ResultCode.TOKEN_INVALID.getCode()));
    }

    // ═══ Layer 4: WaterLevelFilter ═══

    @Test
    @DisplayName("Layer 4: 水位超限 → 403 CAPTCHA_REQUIRED")
    void testWaterLevelExceededReturnsCaptcha() throws Exception {
        // 用反射直接注入超限水位值，绕过定时器时序问题
        java.lang.reflect.Field field = com.seckill.gateway.filter.WaterLevelFilter.class
                .getDeclaredField("currentGlobalQps");
        field.setAccessible(true);
        field.setInt(waterLevelFilter, 15000);

        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, freshToken())
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "192.168.1.106")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(403))
                .andExpect(jsonPath("$.code").value(ResultCode.CAPTCHA_REQUIRED.getCode()));
    }

    // ═══ Layer 5: RateLimitFilter ═══

    @Test
    @DisplayName("Layer 5: 滑动窗口超额 → 429 RATE_LIMITED")
    void testRateLimitExceededByZSet() throws Exception {
        String token = freshToken();
        int maxRequests = GatewayConstants.USER_QPS;

        for (int i = 0; i < maxRequests; i++) {
            mockMvc.perform(post("/api/seckill")
                    .header(GatewayConstants.HEADER_TOKEN, token)
                    .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                    .header(GatewayConstants.HEADER_FORWARDED_FOR, "10.0.0.1")
                    .param("activityId", "1")
                    .param("price", "199900"));
        }

        mockMvc.perform(post("/api/seckill")
                        .header(GatewayConstants.HEADER_TOKEN, token)
                        .header(GatewayConstants.HEADER_USER_ID, String.valueOf(USER_ID))
                        .header(GatewayConstants.HEADER_FORWARDED_FOR, "10.0.0.1")
                        .param("activityId", "1")
                        .param("price", "199900"))
                .andDo(print())
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.code").value(ResultCode.RATE_LIMITED.getCode()));
    }

    // ═══════════════════════════════════════════════════════════
    // 最小化测试上下文——只加载 Filter 链 + Redis + TestController
    // ═══════════════════════════════════════════════════════════

    @Configuration
    @EnableScheduling
    @ComponentScan(basePackageClasses = FilterConfig.class)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class TestConfig {
        @PostConstruct
        void init() {
            System.out.println("[TEST-3.6] Minimal context: Filter chain + Redis only");
        }
    }

    @RestController
    static class TestController {
        @PostMapping("/api/seckill")
        Map<String, Object> seckill(@RequestParam long activityId, @RequestParam int price) {
            return Map.of("code", 0, "message", "chain OK", "activityId", activityId);
        }
    }
}
