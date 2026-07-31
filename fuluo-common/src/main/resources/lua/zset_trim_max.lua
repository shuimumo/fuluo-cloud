-- 保留 ZSET 分数最高的 max 个成员（reverse rank 0..max-1），删除更旧/更低分的尾部
-- KEYS[1] = zset key
-- ARGV[1] = max size
local key = KEYS[1]
local max = tonumber(ARGV[1])
if max == nil or max < 1 then
  return 0
end
local card = redis.call('ZCARD', key)
if card <= max then
  return 0
end
-- 删除 rank 0 到 (card-max-1)，即去掉分数最低的多余成员
return redis.call('ZREMRANGEBYRANK', key, 0, card - max - 1)
