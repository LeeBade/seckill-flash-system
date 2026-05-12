package com.seckill.product;

import com.seckill.common.RedisKeyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

/**
 * 库存预热服务——应用启动后将 MySQL 中的活动库存加载到 Redis。
 * <p>
 * 监听 {@link ApplicationReadyEvent}，在所有 Bean 初始化完成、应用可接收请求前执行。
 * 预热对象：status=1（进行中）且当前时间在 start_time 与 end_time 之间的活动商品。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Service
public class StockWarmupService {

    private static final Logger log = LoggerFactory.getLogger(StockWarmupService.class);

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public StockWarmupService(StringRedisTemplate redisTemplate, DataSource dataSource) {
        this.redisTemplate = redisTemplate;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 应用就绪后执行——将进行中活动的商品库存从 MySQL 写入 Redis 单 Key。
     *
     * @since 2026-05-12
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        log.info("Starting stock warmup");

        String sql = """
                SELECT p.id, p.total_stock
                FROM seckill_product p
                JOIN seckill_activity a ON p.activity_id = a.id
                WHERE a.status IN (0, 1) AND a.end_time > NOW()
                """;

        jdbcTemplate.query(sql, rs -> {
            long goodsId = rs.getLong("id");
            int totalStock = rs.getInt("total_stock");
            String key = RedisKeyBuilder.stockKey(goodsId);
            redisTemplate.opsForValue().set(key, String.valueOf(totalStock));
            log.info("Warmup goodsId={}, stock={}", goodsId, totalStock);
        });

        log.info("Stock warmup completed");
    }
}
