-- 关注操作，原子执行
-- KEYS[1] = user:following:{followerId}
-- KEYS[2] = user:followers:{followedId}
-- KEYS[3] = user:stats:{followerId}
-- KEYS[4] = user:stats:{followedId}
-- ARGV[1] = followedId
-- ARGV[2] = followerId
-- 返回: 1=关注成功, 0=已关注

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 1 then
    return 0
end

redis.call('SADD', KEYS[1], ARGV[1])
redis.call('SADD', KEYS[2], ARGV[2])
redis.call('HINCRBY', KEYS[3], 'following', 1)
redis.call('HINCRBY', KEYS[4], 'followers', 1)
return 1
