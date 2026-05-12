package com.seckill.common;

/**
 * 统一业务状态码枚举——覆盖秒杀、网关、订单、系统全部场景。
 * <p>
 * 分类规则：{@code 0=成功, 1xxx=秒杀业务, 2xxx=网关层, 3xxx=订单, 9xxx=系统级}。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public enum ResultCode {

    // ==================== 成功 ====================

    /** 请求成功 */
    SUCCESS(0, "success"),

    // ==================== 秒杀业务 (1xxx) ====================

    /** 库存不足，抢购失败 */
    SOLD_OUT(1001, "商品已售罄"),
    /** 全场限购一件——已购买过该商品 */
    ALREADY_BOUGHT(1002, "您已购买过该商品"),
    /** 服务端时间未到活动开始时间 */
    ACTIVITY_NOT_START(1003, "活动尚未开始"),
    /** 服务端时间超过活动结束时间 */
    ACTIVITY_ENDED(1004, "活动已结束"),
    /** 库存扣减成功，进入支付倒计时 */
    SECKILL_SUCCESS(1005, "抢购成功"),

    // ==================== 网关层 (2xxx) ====================

    /** IP/用户级限流触发 */
    RATE_LIMITED(2001, "请求过于频繁，请稍后重试"),
    /** HMAC Token 签名或有效期校验失败 */
    TOKEN_INVALID(2002, "Token 无效或已过期"),
    /** QPS 水位超阈值，需要人机验证 */
    CAPTCHA_REQUIRED(2003, "需要人机验证"),

    // ==================== 订单 (3xxx) ====================

    /** 订单不存在 */
    ORDER_NOT_FOUND(3001, "订单不存在"),
    /** 支付超时，库存已回池 */
    ORDER_TIMEOUT_CANCELLED(3002, "订单已超时取消"),
    /** 重复支付的幂等返回 */
    ORDER_ALREADY_PAID(3003, "订单已支付"),

    // ==================== 系统级 (9xxx) ====================

    /** Sentinel 熔断或 Redis 不可达 */
    SYSTEM_BUSY(9001, "系统繁忙，请稍后重试"),
    /** 请求参数校验失败 */
    PARAM_INVALID(9002, "参数校验失败"),
    /** 未捕获的服务端异常 */
    UNKNOWN_ERROR(9999, "未知错误");

    /** 业务状态码 */
    private final int code;
    /** 人类可读的描述 */
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取业务状态码。
     *
     * @return 状态码整数（如 {@code 1001}）
     * @since 2026-05-12
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取人类可读的描述信息。
     *
     * @return 描述字符串（如 {@code "商品已售罄"}）
     * @since 2026-05-12
     */
    public String getMessage() {
        return message;
    }
}
