-- seckill_deduct.lua
-- 核心原子扣减脚本——秒杀系统的绝对心脏
-- KEYS[1]: {goods:{id}}:stock             库存（单 Key, 无分桶）
-- KEYS[2]: {goods:{id}}:record:{userId}   用户购买记录
-- 返回: 1=抢购成功  0=库存不足  -1=已购买过

local stock = redis.call('GET', KEYS[1])
-- [执行动作]：查询当前剩余库存。
if not stock or tonumber(stock) <= 0 then
-- [执行动作]：如果库存 Key 根本不存在，或者库存数量已经小于等于 0。
    return 0
    -- 拦截：卖光了（库存不足）。
end

local bought = redis.call('EXISTS', KEYS[2])
-- [执行动作]：查询这个用户的购买记录 Key 是否存在。
-- [物理含义]：检查用户是否已经下过单，防止黄牛开挂重复购买。
if bought == 1 then
    return -1
    -- 拦截：每人限购一件，你已经买过了。
end

-- [执行流走到这里，说明有库存，且用户没买过。由于是 Lua 脚本，这两句必然原子执行。]
redis.call('DECR', KEYS[1])
-- [执行动作]：绝对原子地将库存数量减 1。没有任何其他线程能在这一刻插队。
redis.call('SETEX', KEYS[2], 3600, '1')
-- [执行动作]：立刻为该用户生成购买记录 Key，并设置过期时间（比如抢购活动只持续 1 小时，那就设置 3600 秒）。
-- [架构巧思]：这两步（DECR 和 SETEX）在 Lua 内部被死死绑定在一起。不可能出现“减了库存但没记用户”或者“记了用户但没减库存”的撕裂状态。

return 1
-- 抢购成功，放行去创建订单。