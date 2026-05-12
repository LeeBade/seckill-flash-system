package com.seckill.engine;

/**
 * Lua 脚本返回码与业务结果的映射——{@link SeckillEngine#execute} 的返回值。
 * <p>
 * 枚举值与 Lua 脚本中 {@code return} 的整数值严格一一对应，
 * 修改 {@code seckill_deduct.lua} 的返回逻辑时必须同步更新此枚举。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public enum SeckillResult {

    /** 抢购成功——库存已扣减、购买记录已写入 */
    SUCCESS(1),
    /** 库存不足——当前商品已售罄 */
    SOLD_OUT(0),
    /** 已购买过——全场限购一件，该用户已有购买记录 */
    ALREADY_BOUGHT(-1);

    /** Lua 脚本中对应的整型返回码 */
    private final int luaReturnCode;

    SeckillResult(int luaReturnCode) {
        this.luaReturnCode = luaReturnCode;
    }

    /**
     * 获取对应的 Lua 返回码。
     *
     * @return Lua 整型返回码（1/0/-1）
     * @since 2026-05-12
     */
    public int getLuaReturnCode() {
        return luaReturnCode;
    }

    /**
     * 将 Lua 脚本的返回码转换为枚举。
     *
     * @param code Lua 返回的整型值
     * @return 对应的枚举常量
     * @throws IllegalArgumentException 若 code 不在已知范围内
     * @since 2026-05-12
     */
    public static SeckillResult fromLuaCode(long code) {
        for (SeckillResult r : values()) {
            if (r.luaReturnCode == code) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown Lua return code: " + code);
    }
}
