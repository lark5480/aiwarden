-- 配额结算（ADR-010 决策 2）：差额校正 actual - reserved；结算标记保证只应用一次。
-- release（归还预扣）= settle(actual=0)。
-- KEYS[1] 用量 key  KEYS[2] 预扣 key（值=预扣量）  KEYS[3] 结算标记 key
-- ARGV[1] 实际用量  ARGV[2] 结算标记 TTL（秒）
-- 返回 {应用标记 0=已结算(no-op)/1=本次应用, 本次差额 delta}
--
-- 【TTL 必须不短于预扣 key】结算标记写于「结算时刻」，预扣 key 写于「预扣时刻」；
--   若两者同 TTL，则「预扣后超过 TTL 才结算」（挂起审批 / 超租约重抢 / 崩溃后重放）时
--   会出现「预扣已过期、标记仍在」→ 重新预扣 + 结算被标记挡住 → 计量明细有、配额账没有。
--   调用方传 2× 预扣 TTL，保证标记活过预扣 key。
--
-- 【delta 下限保护】reserved 为 0（预扣已过期或从未预扣）时，actual 就是本次真实用量，
--   仍然是合法的「补记」；但 actual 为负会让 INCRBY 反向增加余额，故显式拒绝。
if redis.call('EXISTS', KEYS[3]) == 1 then
  return {0, 0}
end
if tonumber(ARGV[1]) < 0 then
  return redis.error_reply('实际用量不能为负')
end
local reserved = tonumber(redis.call('GET', KEYS[2]) or '0')
local actual = tonumber(ARGV[1])
local delta = actual - reserved
if delta ~= 0 then
  redis.call('INCRBY', KEYS[1], delta)
end
redis.call('SET', KEYS[3], 1, 'EX', tonumber(ARGV[2]))
return {1, delta}
