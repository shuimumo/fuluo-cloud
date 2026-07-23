-- 原子补齐 Hash 中缺失的 field（已存在的 field 不覆盖）
-- KEYS[1] = hash key
-- ARGV   = field1, value1, field2, value2, ...
-- return = 本次 HSETNX 新写入的 field 数量
--
-- 用于计数初始化：避免 putAll 覆盖并发 HINCRBY 产生的增量
local set = 0
for i = 1, #ARGV, 2 do
  if redis.call('HSETNX', KEYS[1], ARGV[i], ARGV[i + 1]) == 1 then
    set = set + 1
  end
end
return set
