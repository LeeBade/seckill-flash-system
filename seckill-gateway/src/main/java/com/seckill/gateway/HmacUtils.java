package com.seckill.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * HMAC-SHA256 令牌工具——纯 CPU 零 Redis。
 * <p>
 * Token 格式：{@code Base64(goodsId:userId:expireTimestamp:signature)}<br>
 * 签名 = {@code HMAC-SHA256(goodsId + userId + expireTimestamp, secret)}
 * </p>
 * <p>
 * 验签使用常数时间比较（防时序攻击）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public final class HmacUtils {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String DELIMITER = ":";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private HmacUtils() {}

    /**
     * 签发 HMAC Token。
     *
     * @param goodsId 商品 ID
     * @param userId  用户 ID
     * @param secret  共享密钥
     * @param ttlMs   Token 有效期（毫秒），过期后拒签
     * @return Base64 编码的 Token
     */
    public static String sign(long goodsId, long userId, String secret, long ttlMs) {
        long expireTs = System.currentTimeMillis() + ttlMs;
        String payload = goodsId + DELIMITER + userId + DELIMITER + expireTs;
        byte[] signature = hmacSha256(payload, secret);
        String token = payload + DELIMITER + ENCODER.encodeToString(signature);
        return ENCODER.encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证 Token 并提取负载。
     *
     * @param token  Base64 编码的 Token
     * @param secret 共享密钥
     * @return {@link TokenPayload}(goodsId, userId) 或 {@code null}（过期/篡改/格式错）
     */
    public static TokenPayload verify(String token, String secret) {
        try {
            String decoded = new String(DECODER.decode(token), StandardCharsets.UTF_8);
            int i1 = decoded.indexOf(DELIMITER);
            int i2 = decoded.indexOf(DELIMITER, i1 + 1);
            int i3 = decoded.lastIndexOf(DELIMITER);
            if (i1 < 0 || i2 < 0 || i3 == i2) return null;

            long goodsId = Long.parseLong(decoded.substring(0, i1));
            long userId = Long.parseLong(decoded.substring(i1 + 1, i2));
            long expireTs = Long.parseLong(decoded.substring(i2 + 1, i3));
            String providedSig = decoded.substring(i3 + 1);

            if (System.currentTimeMillis() > expireTs) return null;

            String payload = goodsId + DELIMITER + userId + DELIMITER + expireTs;
            byte[] expectedSig = hmacSha256(payload, secret);
            String expectedSigStr = ENCODER.encodeToString(expectedSig);

            if (!constantTimeEquals(providedSig, expectedSigStr)) return null;

            return new TokenPayload(goodsId, userId);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * 常数时间字符串比较——防止时序侧信道攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Token 解析后的负载。
     */
    public record TokenPayload(long goodsId, long userId) {}
}
