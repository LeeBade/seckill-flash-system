package com.seckill.mq;

/**
 * RocketMQ 配置标记接口——实际 Bean 创建已迁移至 {@code SeckillConfig}。
 * <p>
 * {@code TransactionMQProducer} 和 {@code RocketMQTemplate} 的显式声明在
 * {@code seckill-web} 模块的 {@link com.seckill.SeckillConfig} 中，
 * 确保在 Spring Boot 自动装配完成前就位。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public final class RocketMQConfig {
    private RocketMQConfig() {}
}
