-- rate_limit.lua
-- 滑动窗口限流 + Big Key 防护
-- KEYS[1]: rate:limit:{userId}       ZSet
-- ARGV[1]: now                       当前毫秒时间戳 (score)
-- ARGV[2]: window_ms                 窗口大小(毫秒)
-- ARGV[3]: max_requests              窗口内最大请求数
-- ARGV[4]: ttl_seconds               Key TTL(秒) = 窗口秒数 × 2
-- ARGV[5]: nonce                     唯一随机串(Java生成) -- 防止毫秒黑洞
-- 返回: 1=允许  0=超频拒绝  -1=Big Key异常拒绝

local now = tonumber(ARGV[1])
local windowStart = now - tonumber(ARGV[2])
local maxRequests = tonumber(ARGV[3])
local doubleMax = maxRequests * 2

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, windowStart)
local count = redis.call('ZCARD', KEYS[1])

if count > doubleMax then
    return -1
end

if count >= maxRequests then
    return 0
end

redis.call('ZADD', KEYS[1], now, now .. '_' .. ARGV[5])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
return 1
