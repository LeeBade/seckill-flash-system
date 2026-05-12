package com.seckill.engine;

import com.seckill.common.RedisKeyBuilder;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 秒杀核心引擎——所有抢购请求的唯一执行入口。
 * <p>
 * 启动时加载 4 个 Lua 脚本到 Redis（{@code SCRIPT LOAD}），后续调用全部走 {@code EVALSHA}。
 * 单实例 Redis 下不做库存分桶——直接操作 {@code {goods:{id}}:stock} 单 Key。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
@Component
public class SeckillEngine {

    private static final Logger log = LoggerFactory.getLogger(SeckillEngine.class);

    /** 纯字符串序列化的 RedisTemplate——Key/Value 均为 UTF-8 字符串 */
    private final StringRedisTemplate redisTemplate;

    /** 核心扣减脚本 SHA */
    private RedisScript<Long> deductScript;
    /** Saga 补偿释放脚本 SHA */
    private RedisScript<Long> globalReleaseScript;
    /** 滑动窗口限流脚本 SHA（Phase 3 使用） */
    private RedisScript<Long> rateLimitScript;
    /** Saga 逆向补偿脚本 SHA——MQ 半消息发送失败时回滚 Redis 状态 */
    private RedisScript<Long> rollbackScript;

    public SeckillEngine(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 启动时将 3 个 Lua 脚本加载到 Redis，缓存 SHA1 用于后续 {@code EVALSHA} 调用。
     *
     * @throws IOException 若 classpath 下的 Lua 文件读取失败
     * @since 2026-05-12
     */
    @PostConstruct
    public void loadScripts() throws IOException {
        deductScript = loadScript("lua/seckill_deduct.lua");
        globalReleaseScript = loadScript("lua/global_limit_release.lua");
        rateLimitScript = loadScript("lua/rate_limit.lua");
        rollbackScript = loadScript("lua/seckill_rollback.lua");
        log.info("SeckillEngine initialized: 4 Lua scripts loaded");
    }

    /**
     * 从 classpath 加载 Lua 脚本并封装为 Spring Data Redis 可执行的 {@link RedisScript}。
     *
     * @param path classpath 下的相对路径（如 {@code "lua/seckill_deduct.lua"}）
     * @return 返回类型为 {@code Long} 的 RedisScript
     * @throws IOException 若文件读取失败
     * @since 2026-05-12
     */
    private RedisScript<Long> loadScript(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        String scriptText = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 执行秒杀——原子完成"查已购 → 查库存 → 扣库存 → 写记录"四步。
     *
     * @param goodsId 商品 ID
     * @param userId  用户 ID
     * @return {@link SeckillResult#SUCCESS} 抢购成功；
     *         {@link SeckillResult#SOLD_OUT} 库存不足；
     *         {@link SeckillResult#ALREADY_BOUGHT} 已购买过
     * @since 2026-05-12
     */
    public SeckillResult execute(long goodsId, long userId) {
        String stockKey = RedisKeyBuilder.stockKey(goodsId);
        String recordKey = RedisKeyBuilder.purchaseRecord(goodsId, userId);

        List<String> keys = Arrays.asList(stockKey, recordKey);
        Long result = redisTemplate.execute(deductScript, keys);

        if (result == null) {
            log.error("Lua script returned null, goodsId={}, userId={}", goodsId, userId);
            return SeckillResult.SOLD_OUT;
        }
        return SeckillResult.fromLuaCode(result);
    }

    /**
     * Saga 补偿——释放跨商品限购的全局占位。
     * <p>
     * 仅当本次抢购的 goodsId 与占位值匹配时才执行 DEL，防止误删其他商品的成功购买记录。
     * </p>
     *
     * @param userId  用户 ID
     * @param goodsId 本次尝试购买的商品 ID（用于值匹配校验）
     * @return {@code true} 释放成功，{@code false} 值不匹配或已被释放
     * @since 2026-05-12
     */
    public boolean releaseGlobalLimit(long userId, long goodsId) {
        String globalKey = RedisKeyBuilder.globalRecord(userId);
        List<String> keys = List.of(globalKey);
        Long result = redisTemplate.execute(globalReleaseScript, keys, String.valueOf(goodsId));
        return result != null && result == 1L;
    }

    /**
     * 滑动窗口限流（Phase 3 网关层使用，Phase 1 预加载脚本）。
     *
     * @param userId      用户 ID
     * @param nowMs       当前毫秒时间戳（Java 传入，不依赖 Redis TIME）
     * @param windowMs    窗口大小（毫秒）
     * @param maxRequests 窗口内允许的最大请求数
     * @param ttlSeconds  Key 的 TTL 秒数（窗口秒数 × 2）
     * @return 1=允许 0=超频拒绝 -1=Big Key 异常拒绝
     * @since 2026-05-12
     */
    /**
     * Saga 逆向补偿——MQ 半消息发送失败时回滚 Redis 状态。
     * <p>
     * 原子执行 INCR stock + DEL record——将 Redis 恢复到扣减前的状态，
     * 让用户可以立即重试抢购。
     * </p>
     *
     * @param goodsId 商品 ID
     * @param userId  用户 ID
     * @return 1=回滚成功 0=库存 Key 异常 -1=购买记录异常
     * @since 2026-05-12
     */
    public Long rollback(long goodsId, long userId) {
        String stockKey = RedisKeyBuilder.stockKey(goodsId);
        String recordKey = RedisKeyBuilder.purchaseRecord(goodsId, userId);
        List<String> keys = Arrays.asList(stockKey, recordKey);
        Long result = redisTemplate.execute(rollbackScript, keys);
        log.warn("Saga rollback: goodsId={}, userId={}, result={}", goodsId, userId, result);
        return result;
    }

    public Long executeRateLimit(long userId, long nowMs, long windowMs, int maxRequests, int ttlSeconds) {
        String key = RedisKeyBuilder.rateLimit(userId);
        List<String> keys = List.of(key);
        String nonce = Long.toHexString(ThreadLocalRandom.current().nextLong());
        return redisTemplate.execute(rateLimitScript, keys,
                String.valueOf(nowMs),
                String.valueOf(windowMs),
                String.valueOf(maxRequests),
                String.valueOf(ttlSeconds),
                nonce);
    }
}
