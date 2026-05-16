package com.seckill.gateway.controller;

import com.seckill.gateway.GatewayConstants;
import com.seckill.gateway.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HMAC Token 签发端点——纯 CPU，零 Redis。
 * <p>
 * Token 格式为 Base64(goodsId:userId:expireTs:signature)，前台拿到后附加到
 * {@code /api/seckill?token=...} 请求中，由 {@code DynamicUrlFilter} 验签。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@RestController
public class TokenController {

    @Value("${seckill.gateway.hmac-secret}")
    private String secret;

    /**
     * 生成一次性 Token——有效期 5 秒（生产由前端自动刷新）。
     *
     * @param goodsId 商品 ID
     * @param userId  用户 ID
     * @return token 及其过期时间
     */
    @GetMapping("/api/token")
    public Map<String, Object> token(@RequestParam long goodsId, @RequestParam long userId) {
        long ttlMs = GatewayConstants.TOKEN_TTL_MS;
        String token = HmacUtils.sign(goodsId, userId, secret, ttlMs);
        return Map.of(
                "code", 0,
                "token", token,
                "expireInMs", ttlMs
        );
    }
}
