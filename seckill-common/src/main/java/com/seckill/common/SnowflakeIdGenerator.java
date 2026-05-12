package com.seckill.common;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Snowflake ID 生成器——41bit 时间戳 + 10bit workerId + 12bit 序列号。
 * <p>
 * 单机 QPS 约 400 万/秒。用于订单 ID 预生成——在发送 RocketMQ Half Message 前
 * 必须拿到 orderId（不能用 AUTO_INCREMENT）。
 * </p>
 *
 * @author TianJunQi
 * @since 2026-05-12
 */
public class SnowflakeIdGenerator {

    /** 起始时间戳：2026-01-01 00:00:00 UTC */
    private static final long EPOCH = 1767225600000L;

    /** workerId 位数 */
    private static final long WORKER_ID_BITS = 10L;

    /** 序列号位数 */
    private static final long SEQUENCE_BITS = 12L;

    /** 最大 workerId = 1023 */
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);

    /** 序列号掩码 = 4095 */
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    /** workerId 左移位数 = 12 */
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    /** 时间戳左移位数 = 22 */
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;

    private final long workerId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be 0.." + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    /**
     * 生成下一个唯一 ID。
     *
     * @return 64bit Snowflake ID
     * @since 2026-05-12
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "Clock moved backwards. Refusing to generate id for " +
                            (lastTimestamp - timestamp) + "ms");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = ThreadLocalRandom.current().nextLong(0, 32);
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
