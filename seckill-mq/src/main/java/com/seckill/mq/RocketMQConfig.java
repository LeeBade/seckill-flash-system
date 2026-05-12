package com.seckill.mq;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RocketMQ 基础设施配置——显式声明 {@link RocketMQTemplate} Bean，
 * 防止自动装配因 NameServer 暂时不可达而失败。
 * <p>
 * {@code @ConditionalOnMissingBean} 保证如果 Starter 的自动装配已成功创建则复用，
 * 否则由此处兜底创建。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Configuration
public class RocketMQConfig {

    /**
     * 显式声明 RocketMQTemplate Bean——不再单纯依赖 Starter 自动装配。
     *
     * @param messageConverter Jackson JSON 消息转换器
     * @return RocketMQTemplate 实例
     * @since 2026-05-12
     */
    @Bean
    @ConditionalOnMissingBean(RocketMQTemplate.class)
    public RocketMQTemplate rocketMQTemplate(RocketMQMessageConverter messageConverter) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setMessageConverter(messageConverter.getMessageConverter());
        return template;
    }

    /**
     * RocketMQ 消息转换器——使用 Jackson JSON 序列化。
     * <p>
     * starter 2.2.3 不再暴露 {@code setObjectMapper}，默认
     * {@link org.springframework.messaging.converter.MappingJackson2MessageConverter}
     * 已满足当前 DTO（{@code OrderTimeoutMessage} 等）的序列化需求。后续如需定制
     * ObjectMapper 可在该 Converter 上独立配置。
     * </p>
     *
     * @return 默认的 RocketMQMessageConverter
     * @since 2026-05-12
     */
    @Bean
    public RocketMQMessageConverter rocketMQMessageConverter() {
        return new RocketMQMessageConverter();
    }
}
