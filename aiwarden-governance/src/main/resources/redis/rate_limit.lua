-- 租户限流（ADR-010 决策 6，FR-COST-07）：ZSET 滑动窗口，与配额共用 Redis 管道。
-- KEYS[1] 窗口 zset（aiwarden:rate:{tenant}:{dimension}）
-- ARGV[1] now_ms  ARGV[2] window_ms  ARGV[3] limit  ARGV[4] 请求唯一成员
-- 返回 {状态 0=允许/1=超限, 窗口内计数}
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local count = tonumber(redis.call('ZCARD', KEYS[1]))
if count >= limit then
  return {1, count}
end
redis.call('ZADD', KEYS[1], now, ARGV[4])
redis.call('PEXPIRE', KEYS[1], window)
return {0, count + 1}
