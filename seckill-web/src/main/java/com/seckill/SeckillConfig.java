package com.seckill;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.seckill.common.SnowflakeIdGenerator;
import jakarta.annotation.PostConstruct;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀系统全局配置——Snowflake + Sentinel + RocketMQ + Scheduling。
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Configuration
@EnableScheduling
public class SeckillConfig {

    private static final Logger log = LoggerFactory.getLogger(SeckillConfig.class);

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @Value("${seckill.snowflake.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @PostConstruct
    void initSentinel() throws Exception {
        DegradeRule rule = new DegradeRule("seckill")
                .setGrade(CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType())
                .setCount(200)
                .setTimeWindow(10)
                .setMinRequestAmount(10)
                .setSlowRatioThreshold(0.2);
        DegradeRuleManager.loadRules(List.of(rule));
        log.info("Sentinel degrade rule loaded for resource 'seckill'");
    }

    // ═══════════════════════════════════════════════════════
    // RocketMQ 事务消息——完全手动创建
    // 绕过 rocketmq-spring-boot-starter 仅创建 DefaultMQProducer 的限制
    // ═══════════════════════════════════════════════════════

    @Bean(destroyMethod = "shutdown")
    public TransactionMQProducer transactionMQProducer(
            @Value("${rocketmq.name-server}") String nameServer,
            @Value("${rocketmq.producer.group}") String producerGroup,
            @Value("${rocketmq.producer.send-message-timeout:500}") int sendMsgTimeout) {

        TransactionMQProducer producer = new TransactionMQProducer(producerGroup);
        producer.setNamesrvAddr(nameServer);
        producer.setSendMsgTimeout(sendMsgTimeout);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryTimesWhenSendAsyncFailed(2);

        ExecutorService executor = new ThreadPoolExecutor(
                2, 5, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                r -> new Thread(r, "tx-mq-check-thread"));
        producer.setExecutorService(executor);

        log.info("TransactionMQProducer created: nameServer={}, group={}", nameServer, producerGroup);
        return producer;
    }

    @Bean
    public RocketMQTemplate rocketMQTemplate(TransactionMQProducer producer,
                                              RocketMQMessageConverter messageConverter) {
        RocketMQTemplate template = new RocketMQTemplate();
        template.setProducer(producer);
        template.setMessageConverter(messageConverter.getMessageConverter());
        log.info("RocketMQTemplate created with TransactionMQProducer");
        return template;
    }

    @Bean
    public RocketMQMessageConverter rocketMQMessageConverter() {
        return new RocketMQMessageConverter();
    }

}
