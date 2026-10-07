-- 配额预扣减（ADR-010 决策 2）：原子「检查 + 占用」，幂等通过 requestKey 复用调用幂等键实现。
-- KEYS[1] 用量 key（aiwarden:quota:usage:{tenant}:{period}）
-- KEYS[2] 预扣幂等 key（aiwarden:quota:reserve:{tenant}:{requestKey}，值=预扣量）
-- KEYS[3] 结算标记 key（aiwarden:quota:settled:{tenant}:{requestKey}）
-- ARGV[1] 预扣 tokens  ARGV[2] limit  ARGV[3] 预扣 key TTL（秒）  ARGV[4] 用量 key TTL（秒）
-- 返回 {状态 0=允许/1=超限, 扣后用量, 幂等命中 0/1}
--
-- 【键必须带租户】DB 唯一约束是 (tenant_id, idem_key)，两租户可持有同一个 idemKey；
--   键不带租户会让租户 B 命中租户 A 的预扣 → 幂等分支「允许且不扣」→ 免费调用（实测复现）。
--
-- 【幂等命中时必须清掉结算标记】release = settle(0) 会写结算标记且不删预扣 key；
--   若此处不清标记，同一逻辑调用「释放后重试」会被标记一票否决 —— 永远无法结算（预扣泄漏）。
--   本函数是唯一创建 / 复用预扣 key 的地方，因此也是重置该 mark 的正确位置。
--
-- 【入参方向校验】负值会让 INCRBY 反向增加余额（New API GO-2026-6242/6243 同形态）；
--   第二道防线放在脚本内，而不是只依赖调用方传对。
if tonumber(ARGV[1]) <= 0 then
  return redis.error_reply('tokens 必须为正数')
end
if tonumber(ARGV[2]) < 0 then
  return redis.error_reply('limit 不能为负')
end
redis.call('DEL', KEYS[3])
if redis.call('EXISTS', KEYS[2]) == 1 then
  local used = tonumber(redis.call('GET', KEYS[1]) or '0')
  return {0, used, 1}
end
local used = tonumber(redis.call('GET', KEYS[1]) or '0')
local tokens = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
if used + tokens > limit then
  return {1, used, 0}
end
redis.call('INCRBY', KEYS[1], tokens)
-- 用量 key 也必须有 TTL：它是「每租户每账期」一个的键，无 TTL 会无界增长（内存泄漏）
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
redis.call('SET', KEYS[2], tokens, 'EX', tonumber(ARGV[3]))
return {0, used + tokens, 0}
