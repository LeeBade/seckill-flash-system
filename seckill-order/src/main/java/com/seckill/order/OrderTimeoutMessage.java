package com.seckill.order;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 延时消息体——随 RocketMQ Half Message 发送，Consumer 据此定位订单并回池库存。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderTimeoutMessage {

    /** Snowflake 预生成的订单 ID */
    private Long orderId;

    /** 商品 ID——Consumer 回池时定位 Redis 库存 Key */
    private Long goodsId;
}
