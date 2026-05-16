package com.seckill.gateway;

/**
 * 网关常量——所有阈值集中管理，避免各 Filter 散落魔数。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public final class GatewayConstants {

    private GatewayConstants() {}

    /** IP 速率缓存最大容量——超限时淘汰旧 Key，放行被淘汰 IP 请求 */
    public static final int IP_CACHE_MAX = 10_000;

    /** 黑名单独立缓存容量——60s TTL 自动过期 */
    public static final int BLACKLIST_CACHE_MAX = 1_000;

    /** 黑名单缓存 TTL（秒） */
    public static final int BLACKLIST_TTL_SEC = 60;

    /** IP 默认 QPS 限制 */
    public static final double IP_QPS = 20.0;

    /** 全局 QPS 水位阈值——超过此值触发 CAPTCHA */
    public static final int WATER_LEVEL_QPS = 10_000;

    /** 水位计数器向 Redis 刷新的间隔（毫秒） */
    public static final long WATER_LEVEL_FLUSH_MS = 100;

    /** Bloom Filter 预期插入量 */
    public static final int BLOOM_INSERTIONS = 100_000;

    /** Bloom Filter 误判率 */
    public static final double BLOOM_FPP = 0.01;

    /** Bloom 预暖——合法 userId 起始值 */
    public static final long BLOOM_SEED_START = 10_000L;

    /** Bloom 预暖——合法 userId 结束值（不含） */
    public static final long BLOOM_SEED_END = 110_000L;

    /** 滑动窗口限流——用户级 QPS */
    public static final int USER_QPS = 5;

    /** 滑动窗口限流——窗口大小（秒） */
    public static final int WINDOW_SECONDS = 1;

    /** HMAC Token 默认有效期（毫秒）——5 秒 */
    public static final long TOKEN_TTL_MS = 5_000;

    /** CAPTCHA 有效期（秒） */
    public static final int CAPTCHA_TTL_SEC = 60;

    // ── HTTP Header 名称（网关鉴权字段严禁放 JSON Body） ──

    /** 鉴权 Token——由 TokenController 签发，前端放入此 Header */
    public static final String HEADER_TOKEN = "X-Token";

    /** 用户 ID——前端透传，供 Bloom Filter 在 HMAC 校验前粗略筛选 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 真实客户端 IP——Nginx/ALB 注入 */
    public static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    /** 真实客户端 IP——代理层注入的备选 Header */
    public static final String HEADER_REAL_IP = "X-Real-IP";

    // ── Request Attribute Key（Filter 间传递已验证的 Trusted Identity） ──

    /** DynamicUrlFilter 验签后写入 goodsId */
    public static final String ATTR_GOODS_ID = "gateway.verified.goodsId";

    /** DynamicUrlFilter 验签后写入 userId */
    public static final String ATTR_USER_ID = "gateway.verified.userId";

    // ── Water Level Time-Bucket 配置 ──

    /** 水位 Redis Key 前缀——秒级粒度防止永久累积 */
    public static final String WATERMARK_KEY_PREFIX = "rate:watermark:qps:";

    /** 水位 Key 过期时间（秒）——2s，防止过期 Key 残留 */
    public static final int WATERMARK_KEY_TTL_SEC = 2;
}
