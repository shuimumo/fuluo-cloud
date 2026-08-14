-- P2 修复：热度衰减 + 裁剪，单次往返完成（替代 reverseRange + N×(ZSCORE+ZADD)）
-- KEYS[1] = post:hot
-- ARGV[1] = 衰减因子 (0.75)
-- ARGV[2] = 最小分数阈值 (0.01)
-- ARGV[3] = 保留的最大成员数
local factor = tonumber(ARGV[1])
local minScore = tonumber(ARGV[2])
local maxKeep = tonumber(ARGV[3])

local members = redis.call('ZREVRANGE', KEYS[1], 0, -1)
for _, m in ipairs(members) do
    local s = redis.call('ZSCORE', KEYS[1], m)
    if s and tonumber(s) > minScore then
        redis.call('ZADD', KEYS[1], tonumber(s) * factor, m)
    end
end

-- 只保留分数最高的 maxKeep 个，防止热榜无界膨胀
local size = redis.call('ZCARD', KEYS[1])
if size > maxKeep then
    redis.call('ZREMRANGEBYRANK', KEYS[1], 0, size - maxKeep - 1)
end
return redis.call('ZCARD', KEYS[1])
