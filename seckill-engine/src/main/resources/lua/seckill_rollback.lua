-- seckill_rollback.lua
-- Saga 逆向补偿脚本——MQ 半消息发送失败时回滚 Redis 状态
-- KEYS[1]: {goods:{id}}:stock             库存 Key
-- KEYS[2]: {goods:{id}}:record:{userId}   用户购买记录 Key
-- 返回: 1=回滚成功  0=库存 Key 不存在(异常)  -1=购买记录不存在(异常)

local stock = redis.call('GET', KEYS[1])
if not stock then
    return 0
end

local record = redis.call('EXISTS', KEYS[2])
if record == 0 then
    return -1
end

redis.call('INCR', KEYS[1])
redis.call('DEL', KEYS[2])
return 1
