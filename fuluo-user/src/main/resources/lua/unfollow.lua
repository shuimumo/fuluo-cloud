-- 取关操作，原子执行
-- KEYS[1] = user:following:{followerId}
-- KEYS[2] = user:followers:{followedId}
-- KEYS[3] = user:stats:{followerId}
-- KEYS[4] = user:stats:{followedId}
-- ARGV[1] = followedId
-- ARGV[2] = followerId
-- 返回: 1=取关成功, 0=本来就没关注

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 0 then
    return 0
end

redis.call('SREM', KEYS[1], ARGV[1])
redis.call('SREM', KEYS[2], ARGV[2])
redis.call('HINCRBY', KEYS[3], 'following', -1)
redis.call('HINCRBY', KEYS[4], 'followers', -1)
return 1
