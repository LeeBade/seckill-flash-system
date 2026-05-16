package com.seckill.gateway.controller;

import com.seckill.gateway.GatewayConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CAPTCHA 人机验证端点——简单算术题（减法/加法），峰值削峰。
 * <p>
 * 流程：生成 challenge → Redis SETEX 60s → 用户提交答案 → GET/DEL 校验。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@RestController
public class CaptchaController {

    private static final String CAPTCHA_PREFIX = "captcha:";

    private final StringRedisTemplate redisTemplate;

    public CaptchaController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成算术 CAPTCHA 挑战。
     *
     * @return challengeId 和题目文本
     */
    @GetMapping("/api/captcha/generate")
    public Map<String, Object> generate() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int a = rnd.nextInt(10, 99);
        int b = rnd.nextInt(1, a); // 保证结果为正
        int answer = a - b;
        String question = a + " - " + b + " = ?";

        String challengeId = UUID.randomUUID().toString().substring(0, 8);
        String key = CAPTCHA_PREFIX + challengeId;
        redisTemplate.opsForValue().set(key, String.valueOf(answer),
                Duration.ofSeconds(GatewayConstants.CAPTCHA_TTL_SEC));

        return Map.of(
                "code", 0,
                "challengeId", challengeId,
                "question", question
        );
    }

    /**
     * 提交答案并验证。
     *
     * @param challengeId 挑战 ID
     * @param answer      用户输入的答案
     * @return 验证是否通过
     */
    @PostMapping("/api/captcha/verify")
    public Map<String, Object> verify(@RequestParam String challengeId,
                                      @RequestParam int answer) {
        String key = CAPTCHA_PREFIX + challengeId;
        String expected = redisTemplate.opsForValue().getAndDelete(key);

        if (expected == null) {
            return Map.of("code", 2003, "message", "captcha expired or not found");
        }

        if (Integer.parseInt(expected) == answer) {
            return Map.of("code", 0, "message", "captcha verified");
        }

        return Map.of("code", 2003, "message", "captcha answer incorrect");
    }
}
