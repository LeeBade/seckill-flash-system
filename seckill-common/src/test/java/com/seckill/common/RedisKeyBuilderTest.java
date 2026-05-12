package com.seckill.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis Key 格式正确性验证——确保 Hash Tag 跨 Key 一致性。
 * <p>
 * 同 {@code goodsId} 的 stock 和 record Key 必须共享同一 {@code {goods:{id}}} Tag，
 * 否则 Redis Cluster 下 Lua 脚本将抛出 CROSSSLOT 错误。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@DisplayName("RedisKeyBuilder 格式验证")
class RedisKeyBuilderTest {

    private static final long GOODS_ID = 1L;
    private static final long USER_ID = 888L;

    @Test
    @DisplayName("stockKey 和 purchaseRecord 共享同一 {goods:1} Hash Tag")
    void stockAndRecordShareSameHashTag() {
        String stock = RedisKeyBuilder.stockKey(GOODS_ID);
        String record = RedisKeyBuilder.purchaseRecord(GOODS_ID, USER_ID);

        assertEquals("{goods:1}:stock", stock);
        assertEquals("{goods:1}:record:888", record);

        // 提取 Hash Tag
        String stockTag = extractHashTag(stock);
        String recordTag = extractHashTag(record);

        assertEquals(stockTag, recordTag,
                "stock and record keys MUST share the same Hash Tag for CROSSSLOT prevention");
        assertEquals("goods:1", stockTag);
    }

    @Test
    @DisplayName("globalRecord 使用固定 {global} Hash Tag")
    void globalRecordUsesFixedHashTag() {
        String key = RedisKeyBuilder.globalRecord(USER_ID);
        assertEquals("{global}:record:888", key);
        assertEquals("global", extractHashTag(key));
    }

    @Test
    @DisplayName("rateLimit 不使用 Hash Tag——按 userId 分散")
    void rateLimitHasNoHashTag() {
        String key = RedisKeyBuilder.rateLimit(USER_ID);
        assertEquals("rate:limit:888", key);
        assertTrue(extractHashTag(key) == null,
                "rate:limit key must NOT have Hash Tag (natural scatter across slots)");
    }

    @Test
    @DisplayName("soldOutFlag 与 stock 使用同一 {goodsId} Hash Tag")
    void soldOutFlagSharesHashTagWithStock() {
        String soldOut = RedisKeyBuilder.soldOutFlag(GOODS_ID);
        String stock = RedisKeyBuilder.stockKey(GOODS_ID);

        assertEquals("{goods:1}:sold_out", soldOut);
        assertEquals(extractHashTag(stock), extractHashTag(soldOut),
                "sold_out flag must share Hash Tag with stock key");
    }

    @Test
    @DisplayName("不同 goodsId 的 Key 使用不同 Hash Tag——多商品隔离")
    void differentGoodsHaveDifferentHashTags() {
        long goods1 = 1L;
        long goods2 = 2L;

        String tag1 = extractHashTag(RedisKeyBuilder.stockKey(goods1));
        String tag2 = extractHashTag(RedisKeyBuilder.stockKey(goods2));

        assertTrue(!tag1.equals(tag2),
                "Different goods must have different Hash Tags for multi-goods isolation. " +
                        "tag1=" + tag1 + ", tag2=" + tag2);
        assertEquals("goods:1", tag1);
        assertEquals("goods:2", tag2);
    }

    /**
     * 提取 Redis Key 中的 Hash Tag（第一个 {@code {} 之间的文本）。
     *
     * @param key Redis Key
     * @return Hash Tag 文本，无 Tag 时返回 {@code null}
     */
    private static String extractHashTag(String key) {
        int open = key.indexOf('{');
        int close = key.indexOf('}');
        if (open >= 0 && close > open) {
            return key.substring(open + 1, close);
        }
        return null;
    }
}
