-- 配额释放（ADR-010 决策 2 修订）：归还一次**未消耗**的预扣，且必须幂等。
-- 语义：预扣量从账期用量中扣回，并删除预扣键与结算标记——使同一 requestKey 的下一次预扣
--       成为**全新扣减**（而不是命中残留键的「幂等免扣」）。
--
-- 为什么必须是「删键 + 回退」，而不是「settle(0)」：
--   settle(0) 只写结算标记、保留预扣键。之后同一逻辑调用若再次执行（超租约重抢 / 审批恢复 /
--   失败重试），reserve 会命中残留键走幂等分支——**不再扣减**；而随后的 settle(actual=reserved)
--   差额为 0，于是 INCRBY 不执行 → 这次真实执行**完全不计费**（usage 恒为 0，明细却有行）。
--   实测：release → re-reserve → settle 之后 usage 仍为 0。删键后 usage 为 100（正确）。
--
-- 为什么还要查结算标记：释放只允许发生在「**尚未结算**」的预扣上（调用点互斥：成功走 settle，
--   失败 / 驳回走 release）。若该键已结算过，说明这次预扣已被消费，release 必须是 no-op——
--   否则一次多余的 release 会把已计入的用量凭空抹掉（实测：结算后再 release 会把 usage 打成 0）。
--
-- KEYS[1] 用量 key  KEYS[2] 预扣 key（值=预扣量）  KEYS[3] 结算标记 key
-- 返回 {释放量}（0 = 无预扣可释放 / 已结算，安全 no-op）
if redis.call('EXISTS', KEYS[3]) == 1 then
  redis.call('DEL', KEYS[2])
  return {0}
end
local reserved = tonumber(redis.call('GET', KEYS[2]) or '0')
if reserved > 0 then
  redis.call('INCRBY', KEYS[1], -reserved)
end
redis.call('DEL', KEYS[2])
return {reserved}
