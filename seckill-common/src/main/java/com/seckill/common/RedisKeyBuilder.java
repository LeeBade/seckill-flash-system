package com.seckill.common;

/**
 * Redis Key 构建工具——统一管理 Key 命名空间与 Hash Tag 策略。
 * <p>
 * 所有 Key 均遵循 {@code {tag}:...} 格式：Hash Tag 确保 Lua 脚本中同组 Key 落在同一 Redis Slot，
 * 杜绝 Cluster 环境下的 CROSSSLOT 报错。当前单机 Redis 下无实际分片行为，但 Tag 是零成本的
 * 架构约束语法——未来迁移到 Cluster 时一行 Key 代码不改。
 * </p>
 * <p>
 * <b>实现注意</b>：热点路径（10 万+ QPS 的 seckill-engine 和 gateway）使用原始字符串拼接
 * 而非 {@code String.format}——避免 Format 正则解析和 Formatter 临时对象引发频繁 Minor GC。
 * Java 9+ 的 {@code invokedynamic} + {@code StringConcatFactory} 将拼接优化为零分配字节码。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public final class RedisKeyBuilder {

    private RedisKeyBuilder() {
    }

    /**
     * 商品库存 Key。
     *
     * @param goodsId 商品 ID
     * @return 格式为 {@code {goods:1}:stock}
     * @since 2026-05-12
     */
    public static String stockKey(long goodsId) {
        return "{goods:" + goodsId + "}:stock";
    }

    /**
     * 用户购买记录 Key——与库存 Key 使用同一 {@code {goodsId}} Hash Tag。
     *
     * @param goodsId 商品 ID
     * @param userId  用户 ID
     * @return 格式为 {@code {goods:1}:record:888}
     * @since 2026-05-12
     */
    public static String purchaseRecord(long goodsId, long userId) {
        return "{goods:" + goodsId + "}:record:" + userId;
    }

    /**
     * 全局购买占位 Key——跨商品限购两阶段的第一阶段抢占位。
     *
     * @param userId 用户 ID
     * @return 格式为 {@code {global}:record:888}
     * @since 2026-05-12
     */
    public static String globalRecord(long userId) {
        return "{global}:record:" + userId;
    }

    /**
     * 滑动窗口限流 ZSet Key。
     *
     * @param userId 用户 ID
     * @return 格式为 {@code rate:limit:888}
     * @since 2026-05-12
     */
    public static String rateLimit(long userId) {
        return "rate:limit:" + userId;
    }

    /**
     * 商品售罄标志 Key——列表页 O(1) 查询，Caffeine 本地缓存 2s 过期。
     *
     * @param goodsId 商品 ID
     * @return 格式为 {@code {goods:1}:sold_out}
     * @since 2026-05-12
     */
    public static String soldOutFlag(long goodsId) {
        return "{goods:" + goodsId + "}:sold_out";
    }
}
