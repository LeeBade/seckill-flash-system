package com.seckill.order;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 秒杀订单实体——映射 {@code seckill_order} 表。
 * <p>
 * id 由 Snowflake 预生成（{@link IdType#INPUT}），不使用数据库自增。
 * seckill_price 单位为分（INT 存储，避免 DECIMAL 浮点精度问题）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Getter
@Setter
@TableName("seckill_order")
public class Order {

    /** Snowflake 预生成的订单 ID */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 商品 ID */
    private Long goodsId;

    /** 活动 ID */
    private Long activityId;

    /** 秒杀成交价（单位:分） */
    private Integer seckillPrice;

    /** 订单状态 */
    private Integer status;

    /** 下单时间 = 支付倒计时起点 */
    private LocalDateTime createTime;

    /** 支付完成时间 */
    private LocalDateTime payTime;

    /** 取消时间 */
    private LocalDateTime cancelTime;
}
