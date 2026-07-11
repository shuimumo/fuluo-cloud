-- 取消评论赞，原子执行
-- KEYS[1] = comment:likes:{commentId}
-- KEYS[2] = user:stats:{authorId}
-- KEYS[3] = post:comments:hot:{postId}
-- KEYS[4] = comment:counts:{commentId}
-- ARGV[1] = userId
-- ARGV[2] = commentId
-- 返回: 1=取消赞成功, 0=本来就没赞

local exists = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if exists == 0 then
    return 0
end

redis.call('SREM', KEYS[1], ARGV[1])
redis.call('HINCRBY', KEYS[2], 'likes', -1)
redis.call('ZINCRBY', KEYS[3], -1, ARGV[2])
redis.call('HINCRBY', KEYS[4], 'likes', -1)
return 1
