package com.seckill.order;

/**
 * 订单状态枚举。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public enum OrderStatus {

    /** 待支付——抢购成功进入支付倒计时 */
    PENDING_PAY(0),
    /** 已支付——用户完成付款 */
    PAID(1),
    /** 已取消——用户主动取消 */
    CANCELLED(2),
    /** 超时取消——5 分钟未支付，MQ 消费者自动取消 */
    TIMEOUT_CANCELLED(3);

    private final int code;

    OrderStatus(int code) {
        this.code = code;
    }

    /**
     * 获取数据库存储的整型值。
     *
     * @return 状态码
     * @since 2026-05-12
     */
    public int getCode() {
        return code;
    }
}
