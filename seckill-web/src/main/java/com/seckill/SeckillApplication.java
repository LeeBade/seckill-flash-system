package com.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 秒杀系统启动入口——单体多模块聚合。
 * <p>
 * {@code scanBasePackages = "com.seckill"} 扫描全部子模块的 Spring Bean；
 * {@code @MapperScan} 显式声明 MyBatis Mapper 路径，叠加 {@code @Mapper} 注解双重保障。
 * RocketMQ Consumer/Producer 由 Starter 自动装配（{@code rocketmq-spring-boot-starter}）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@SpringBootApplication(scanBasePackages = "com.seckill")
@MapperScan("com.seckill.order")
public class SeckillApplication {
    public static void main(String[] args) {
        SpringApplication.run(SeckillApplication.class, args);
    }
}
