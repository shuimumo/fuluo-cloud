-- P2 修复：批量收件箱推送，一次往返写全部活跃粉丝（替代逐粉丝一次 execute 的 N 次往返）
-- KEYS = 各活跃粉丝的 inbox ZSET key
-- ARGV[1] = postId, ARGV[2] = score, ARGV[3] = 每个收件箱保留上限
local postId = ARGV[1]
local score = ARGV[2]
local maxKeep = tonumber(ARGV[3])
for i = 1, #KEYS do
    redis.call('ZADD', KEYS[i], score, postId)
    local size = redis.call('ZCARD', KEYS[i])
    if size > maxKeep then
        redis.call('ZREMRANGEBYRANK', KEYS[i], 0, size - maxKeep - 1)
    end
end
return #KEYS
