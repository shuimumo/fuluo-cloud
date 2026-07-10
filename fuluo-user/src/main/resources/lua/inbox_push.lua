-- KEYS[1] = inbox ZSET key
-- ARGV[1] = postId, ARGV[2] = score (createTime epoch)
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
local count = redis.call('ZCARD', KEYS[1])
if count > 500 then
    redis.call('ZREMRANGE', KEYS[1], 0, -501)
end
return count
