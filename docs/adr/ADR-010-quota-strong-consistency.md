<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-010 · 配额强一致机制（Redis Lua 预扣减、幂等、对账、限流）

**状态**：已采纳（2026-10-07）｜M2 切片③（P4a 配额强一致）开工前落盘

### 背景

P4a 承诺「租户级配额**预扣减 + 幂等 + 对账**，超限直接 429」。问题不是想象出来的：New API 官方漏洞库
GO-2026-6242/6243（整数溢出致**负数自增额度**、Redis 配额缓存覆盖绕过）与 issue #5290（quota **连预扣缓冲都没有**）
/#5441（部分模型 0 元购）就是「配额逻辑没写对」的现实样本。本 ADR 定死：预扣减的存储与原子性、幂等键、
结算/释放、挂点顺序（与幂等仲裁/限流的关系）、429 语义、对账口径。

### 决策

1. **存储与原子性**：配额账本在 **Redis（Lua 脚本原子执行）**；键：
   `aiwarden:quota:usage:{tenant}:{period}`（INCRBY 用量）、`aiwarden:quota:reserve:{requestKey}`（值=预扣量，TTL 72h）、
   `aiwarden:quota:settled:{requestKey}`（结算标记）；**不引 PG 行锁**（高频扣减不适合 PG；且与限流共用 Redis 管道——PRD FR-COST-07 原文）。
2. **三步语义**：`reserve`（幂等：**requestKey 复用调用幂等键**——同一逻辑调用的重放/重抢不重复扣）→
   `settle`（差额校正 `actual − reserved`，结算标记保证只应用一次）→ `release = settle(0)`（归还预扣）。
3. **挂点顺序**（以工具调用为例）：可见面 → **限流**（租户+工具双维度，Lua ZSET 滑动窗口）→ 幂等仲裁
   （**终态重放不消耗配额**）→ 新执行/重抢路径才 `reserve` → 执行 → 成功 `settle` / 业务失败 `release` /
   审批挂起 `release`（approve 恢复执行时再 `reserve`）。
4. **超限 429 且不留残留**：`reserve` 超限抛 `QuotaExceededException` → **事务回滚**（claim 的 PROCESSING 行同步消失，
   配额恢复后同键可重试）；指标 `aiwarden_budget_reject_total`；审计 `QUOTA_EXCEEDED`（REQUIRES_NEW 独立留痕，不被回滚）。
5. **对账口径**（FR-COST-05）：`GET /api/v1/admin/billing/reconcile` 比对 **Redis usage vs 明细表 SUM(tokens)**——
   前提：消耗配额的操作其明细 token == 扣减当量（工具调用写 `prompt_tokens=当量`）；差异清单 + 差异率落 `t_reconcile_report(type=BILLING)`。
   语义对齐 B2 边界：**不是绝对一致**——reserve 后宕机的泄漏窗口由对账发现。
6. **限流**（FR-COST-07）：与配额共用 Redis 管道；Lua ZSET 滑动窗口（租户+工具双维度）；超限 429 带 `Retry-After`；
   指标 `aiwarden_rate_limit_reject_total`；限流不写审计（它防的是频率，不是越权）。
7. **预算载体**：`t_budget`（tenant_id + period 唯一，period=YYYY-MM）；**未配置 = 不启用配额检查**（演示默认零门槛）。
8. **演示当量**：无真实 LLM 时，工具调用以配置当量计价（`aiwarden.quota.tool-token-cost`，默认 100）——
   口径为「操作当量」；真实模型接入时替换为真实 token 数（CallMeteringPayload 已预留模型维度）。

### 依据

- PRD 引文：P4a（§3）、FR-COST-01/02/04/05/07、§4.2 时序（幂等键 → 配额检查 → 超限 429）；New API 漏洞证据（GO-2026-6242/6243、#5290/#5441）。
- 与方法论对齐：预扣减的「先占后结」与 M1 对账体系同源（发现与修复分离；残余窗口如实披露）。

### 代价与放弃

- **放弃「强一致不丢」**：Redis 与明细双写存在窗口（reserve 后进程宕机 → 预扣未结算）——由对账发现，不假装不存在（B2）。
- **放弃 PG 行锁方案**：扣减频率×行锁冲突不可接受；Redis Lua 单线程原子足够本场景。
- **审批挂起时 release、approve 时再 reserve**：挂起期间不占用配额（避免长期挂起耗尽额度）；代价是 approve 可能失败（429）——语义正确。
- **限流参数按「租户+工具」维度落地**（无模型路由时的「模型维度」替身），真实模型接入后加模型维度。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 超限 429 + 指标 + 审计 + 零残留 | `QuotaEnforcementContainersTest`：小预算下第 N+1 次调用 429、`aiwarden_budget_reject_total` ≥1、审计 `QUOTA_EXCEEDED`、账本无该调用行；提额后同键可成功 |
| 预扣减幂等与差额结算 | `QuotaContainersTest`（Redis 容器）：同 requestKey 二次 reserve 不重复扣；settle(actual≠reserved) 正确校正；release 归还；16 线程并发 reserve 不超卖（limit 内恰好 N 个成功）|
| 对账零差异与缺口发现 | 正常扣减后 reconcile 差异 0；手工向 Redis 注入缺口 → 差异可见 + 报告落库 |
| 限流 429 + Retry-After | `RateLimitContainersTest`：限额压低后第 N+1 个请求 429 且带 `Retry-After` |
| 四维归因落库 | 工具调用明细含 session/step/tool（`t_llm_call_log`），GET /api/v1/admin/usage 可按多维过滤 |
| 审计检索接口 | GET /api/v1/admin/audit 可查越权与配额拒绝留痕 |

## 修订（2026-10，切片③实现回填）

四个测试类 14 测试全绿（`QuotaEnforcementContainersTest` 3 / `RateLimitContainersTest` 1 /
`ToolInvocationContainersTest` 5 / `ToolWhitelistContainersTest` 5，工具两类的 Redis 容器适配含在内）。实现期补充四条落地细节：

1. **决策 3 的挂起语义落地为「挂起保留预扣」**（原文写作「挂起 release、approve 再 reserve」）：实现时发现
   release 会写「已结算」标记，导致 approve 时的幂等 reserve 命中不扣、settle 又被标记挡住——该逻辑调用**永不结算**。
   修正：挂起时保留预扣（资源已预留），**驳回时释放**（release 无预扣时为安全 no-op）；
   approve 时 reserve 幂等命中（不重复扣；挂起后才配预算也能生效）。
2. **settle 仅在配额激活时调用**（reserve 返回值携带 active）：避免「未启用预算也写结算标记」的污染；
   而 release / 超限拒绝无需条件——前者无预扣时差额为 0，后者事务回滚不留残留。
3. **限流先于幂等仲裁**：被限流的请求不产生任何账本行（测试断言恰好 N 行）；限流维度落地为「租户 + 工具」。
4. **计量消费体抽为可直调方法**（`MeteringEventConsumer.consume(payloadJson)`）：测试以真实消费逻辑验证
   四维明细落库（无需 Kafka broker）；旧工具测试类补 Redis 容器（调用入口已触碰配额/限流管道）。

## 修订（2026-10，M2 复核后的配额修正）

对 P4a 做了一次独立复核（跨租户 / 幂等 / 泄漏面），发现三处**静默不生效**缺陷并修正。以下为现状描述，
上文决策 1 / 2 的键形态与 `release = settle(0)` 两处表述已被本节取代：

1. **预扣 / 结算键必须带租户**（决策 1 的 `{requestKey}` → `{tenant}:{requestKey}`）。DB 侧唯一约束是
   `(tenant_id, idem_key)`，因此两个租户**可以**持有完全相同的 `idemKey`；键不带租户时租户 B 会命中
   租户 A 留下的键 → 走幂等分支「允许且不扣减」→ **免费调用**，而明细仍写入 B 的账 → 对账负差异。
   实测证据（本机 redis:7.4-alpine）：修前同 requestKey 下租户 2 的 usage 恒为空、返回 `{allowed, 0, 幂等命中}`；
   修后为 `{allowed, 100, 非幂等}`、两租户各扣 100。回归门禁 `sameRequestKeyAcrossTenants_deductsIndependently`。
2. **`release` 不再是 `settle(0)`，改为「回退用量 + 删除预扣键与结算标记」**（决策 2 的 `release = settle(0)` 作废）。
   原因：`settle(0)` 只写标记、保留预扣键，导致同一逻辑调用释放后再次执行时 `reserve` 命中残留键**免扣**，
   而随后 `settle(actual == reserved)` 差额为 0 不执行 INCRBY → **这次真实执行完全不计费**（usage 恒 0，明细却有行）。
   同时 `release` 增加「已结算则 no-op」守卫——否则一次多余的 release 会把已计入的用量抹掉。
   为实现幂等，`release` 独立为 `redis/quota_release.lua`。回归门禁 `releaseThenReserveThenSettle_chargesExactlyOnce`。
3. **用量 key 补 TTL、结算标记 TTL 取 2× 预扣 TTL、预扣脚本拒绝非正数入参**：前者消除「每租户每账期一个
   常驻键」的无界增长；中者避免「预扣过期而标记仍在」造成的重复扣减；后者是 New API
   GO-2026-6242/6243（负值反向自增额度）同形态的第二道防线（当前调用方恒传正数，属防御性）。

**预算管理接口补租户边界**：`GET/PUT /api/v1/admin/budgets/{tenantId}` 原先只调 `requireTenantIdAsLong()`
**却丢弃返回值**，从不校验路径租户与调用方租户一致 → 租户 A 可读**并改写**任意租户预算（改大即解除限流、
改小即 DoS）。现统一校验，不一致返回 403。回归门禁 `budgetEndpoints_rejectCrossTenantAccess`。

**同批修正（P2 / P3 复核发现，与配额无关但同源「静默不生效」形态）**：
- `RetrievalService.search` 增加与 `TenantContext` 的交叉校验——可见集是值对象，ArchUnit 只能守护签名，
  守护不了来源；此前任何调用方 `new VisibilitySet(别的租户, …)` 即可越权读取（当前仓库唯一构造点是
  Controller，故不可达，属 latent）。同时补 `t_doc_acl` 跨租户脏行的判别性用例。
- `ToolInvocationService` 原先只 `catch (ToolExecutionException)`，工具抛 `IllegalArgumentException` /
  其它 `RuntimeException` 时事务回滚而 **Redis 预扣不回滚** → 残留预扣键变成「免扣券」。
  现按异常类型分区处理：参数 / 状态非法 → 归还预扣后原样抛出（无副作用，不生成补偿计划）；
  其它未包装异常 → 落失败账 + 归还预扣 + 生成补偿计划（不假定「无副作用」）。
- 计量明细的 `prompt_tokens` 由 `(int)` 强转改为 `Math.toIntExact`：溢出截断会静默破坏
  「明细 token == 扣减当量」这条对账前提。

> ⚠️ **验证表原列 `QuotaContainersTest` 未按该形态创建**——实际覆盖见上文列出的
> `QuotaEnforcementContainersTest` / `RateLimitContainersTest`；本修订段的测试计数已按实际类名书写。

---