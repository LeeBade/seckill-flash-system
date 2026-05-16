-- ============================================================
-- seckill-flash-system DDL
-- MySQL 8.0+, InnoDB, utf8mb4
-- 执行方式: mysql -u root -p < seckill-web/src/main/resources/schema.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS seckill
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE seckill;

-- -------------------------------------------------
-- 秒杀活动表
-- -------------------------------------------------
DROP TABLE IF EXISTS seckill_activity;
CREATE TABLE seckill_activity (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_name   VARCHAR(128)    NOT NULL,
    start_time      DATETIME        NOT NULL,
    end_time        DATETIME        NOT NULL,
    status          TINYINT         NOT NULL DEFAULT 0  COMMENT '0-未开始 1-进行中 2-已结束',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_status_start (status, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀活动表';

-- -------------------------------------------------
-- 秒杀商品表
-- -------------------------------------------------
DROP TABLE IF EXISTS seckill_product;
CREATE TABLE seckill_product (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    activity_id     BIGINT UNSIGNED NOT NULL,
    goods_name      VARCHAR(256)    NOT NULL,
    goods_image     VARCHAR(512)    DEFAULT '',
    original_price  INT UNSIGNED    NOT NULL               COMMENT '原价(单位:分)',
    seckill_price   INT UNSIGNED    NOT NULL               COMMENT '秒杀价(单位:分)',
    total_stock     INT UNSIGNED    NOT NULL               COMMENT '权威库存，预热时写入Redis',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME        DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀商品表';

-- -------------------------------------------------
-- 秒杀订单表
--   - id 由 Snowflake 预生成，非 AUTO_INCREMENT
--   - 不设 UNIQUE(user_id, goods_id): 超时回池后允许复购（捡漏）
--   - 防重复完全由 Redis Lua EXISTS {goodsId}:record:{userId} 原子校验承担
-- -------------------------------------------------
DROP TABLE IF EXISTS seckill_order;
CREATE TABLE seckill_order (
    id              BIGINT UNSIGNED NOT NULL               COMMENT '订单ID(Snowflake预生成,非自增)',
    user_id         BIGINT UNSIGNED NOT NULL,
    goods_id        BIGINT UNSIGNED NOT NULL,
    activity_id     BIGINT UNSIGNED NOT NULL,
    seckill_price   INT UNSIGNED    NOT NULL               COMMENT '成交价快照(单位:分)',
    status          TINYINT         NOT NULL DEFAULT 0     COMMENT '0-待支付 1-已支付 2-已取消 3-超时取消',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pay_time        DATETIME        DEFAULT NULL,
    cancel_time     DATETIME        DEFAULT NULL,
    update_time     DATETIME        DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_user_goods (user_id, goods_id),
    INDEX idx_status_ctime (status, create_time),
    INDEX idx_activity (activity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='秒杀订单表';
