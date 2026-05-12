-- global_limit_release.lua
-- Saga 补偿释放脚本——全场限购一件的两阶段回滚
-- KEYS[1]: {global}:record:{userId}  全局购买占位
-- ARGV[1]: goodsId                   当前尝试购买的商品ID
-- 返回: 1=释放成功  0=释放失败(值不匹配或已被释放)

local current = redis.call('GET', KEYS[1])
-- [执行动作]：去 Redis 查一下，这个用户当前在这个全局占位 Key 里面，存的商品 ID 是什么。
-- [物理含义]：确认“案发现场”。

if current == ARGV[1] then
-- [执行动作]：对比查出来的商品 ID (current) 和你想释放的商品 ID (ARGV[1]) 是否绝对相等。
-- [架构巧思 - 防误删]：为什么不直接 DEL？假设用户抢到了 A 商品，占位成功。

-- 如果代码有 Bug 或者重试风暴，发来了一个“释放 B 商品”的请求。如果不判断相等就直接 DEL，就会把用户买 A 的合法记录删掉，导致他还能再买一次 A（超买）

-- 。这个判断保证了“谁污染的，谁治理”。
    redis.call('DEL', KEYS[1])
    -- [执行动作]：匹配成功，物理删除这个占位 Key。
    return 1
    -- [执行动作]：告诉调用方（Java代码），补偿/释放成功。
end

return 0
-- [执行动作]：如果 Key 不存在，或者里面的值和你不匹配，直接返回 0（不采取任何行动）。