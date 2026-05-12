package com.seckill;

import com.seckill.common.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 秒杀系统全局配置——暴露应用层 Bean。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Configuration
public class SeckillConfig {

    /**
     * Snowflake ID 生成器——workerId 从 application.yml 读取。
     *
     * @param workerId 机器 ID（Node A=1, Node B=2）
     * @return 单例的 ID 生成器
     * @since 2026-05-12
     */
    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${seckill.snowflake.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
