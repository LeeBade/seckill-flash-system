package com.seckill.gateway;

import com.seckill.gateway.filter.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Filter 注册配置——实例化并注册五层防线。
 * <p>
 * Filter 必须在此处声明为 {@code @Bean} 并同时用 {@link FilterRegistrationBean} 注册，
 * 避免 Spring Boot 自动将 {@link jakarta.servlet.Filter} Bean 加入 Servlet 容器链导致双重调用。
 * </p>
 * <p>
 * 顺序不可颠倒：IpRateLimit(内存) → UserBloom(内存) → DynamicUrl(CPU HMAC)
 * → WaterLevel(volatile) → RateLimit(Redis Lua)
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Configuration
public class FilterConfig {

    // ── Bean 声明（@Component 方式会导致 Spring Boot 自动注册到 Servlet 容器） ──

    @Bean
    public IpRateLimitFilter ipRateLimitFilter() {
        return new IpRateLimitFilter();
    }

    @Bean
    public UserBloomFilter userBloomFilter() {
        return new UserBloomFilter();
    }

    @Bean
    public DynamicUrlFilter dynamicUrlFilter() {
        return new DynamicUrlFilter();
    }

    @Bean
    public WaterLevelFilter waterLevelFilter(StringRedisTemplate redisTemplate) {
        return new WaterLevelFilter(redisTemplate);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(StringRedisTemplate redisTemplate) {
        return new RateLimitFilter(redisTemplate);
    }

    // ── FilterRegistrationBean 注册 ──

    @Bean
    public FilterRegistrationBean<IpRateLimitFilter> ipRateLimitReg(IpRateLimitFilter filter) {
        FilterRegistrationBean<IpRateLimitFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(filter);
        bean.addUrlPatterns("/api/seckill", "/api/token");
        bean.setOrder(1);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<UserBloomFilter> userBloomReg(UserBloomFilter filter) {
        FilterRegistrationBean<UserBloomFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(filter);
        bean.addUrlPatterns("/api/seckill", "/api/token");
        bean.setOrder(2);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<DynamicUrlFilter> dynamicUrlReg(DynamicUrlFilter filter) {
        FilterRegistrationBean<DynamicUrlFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(filter);
        bean.addUrlPatterns("/api/seckill");
        bean.setOrder(3);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<WaterLevelFilter> waterLevelReg(WaterLevelFilter filter) {
        FilterRegistrationBean<WaterLevelFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(filter);
        bean.addUrlPatterns("/api/seckill");
        bean.setOrder(4);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitReg(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(filter);
        bean.addUrlPatterns("/api/seckill");
        bean.setOrder(5);
        return bean;
    }
}
