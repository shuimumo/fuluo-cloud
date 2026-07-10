-- 取消赞，原子执行
-- KEYS[1] = post:likes:{postId}
-- KEYS[2] = user:stats:{authorId}
-- KEYS[3] = user:liked:{userId}
-- KEYS[4] = post:counts:{postId}
-- ARGV[1] = userId
-- ARGV[2] = postId
-- 返回: 1=取消赞成功, 0=本来就没赞

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 0 then
    return 0
end

redis.call('SREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[3], ARGV[2])
redis.call('HINCRBY', KEYS[2], 'likes', -1)
redis.call('HINCRBY', KEYS[4], 'likes', -1)
return 1
