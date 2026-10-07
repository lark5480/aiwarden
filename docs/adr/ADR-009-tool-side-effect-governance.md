<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-009 · 工具副作用治理机制（幂等键状态机、补偿逆序、工具可见面）

**状态**：已采纳（2026-10-07）｜M2 切片②（P3）开工前落盘；实现后只回填验证数据，不改变语义

### 背景

FR-TOOL-01~05 要求「写操作工具带幂等键、失败走补偿、断网重放不产生第二张工单」。M1 已有成熟的
「至少一次 + 唯一键仲裁 + 补偿/对账」方法论（ADR-005/006），但工具侧有两个新问题：
① **状态机要回答重放仲裁**——同一个幂等键上会出现哪些合法状态、每种状态下重放该干什么
（ADR-005 修订段的教训：键上的合法多次流转没想清楚就会静默跳过）；② **「补偿而非重试」的边界在哪**——
确定失败的调用能不能重放？不确定状态（PROCESSING 卡死）又怎么办？本 ADR 在编码前定死。

### 决策

1. **幂等键**：`idemKey = 业务键 + ':' + 会话ID + ':' + 步骤指纹`，步骤指纹 = `sha256(tool|stepNo|输入规范 JSON)` 前 16 hex（输入按 key 排序序列化，同输入同键）；唯一约束 `(tenant_id, idem_key)` 仲裁。
2. **状态机与重放仲裁矩阵**（`t_tool_invocation.status`）：

   | 重放时的现有状态 | 处理 |
   |---|---|
   | （无行）| INSERT `PROCESSING` → 执行 |
   | `SUCCEEDED` | 返回首次结果（`replayed=true`），不重执行 |
   | `FAILED` | 返回首次失败记录（`replayed=true`），**不重执行**——「补偿而非重试」：确定失败不重放，恢复走补偿链路 |
   | `PROCESSING` 未超租约 | 409（另一执行中，调用方稍后重放） |
   | `PROCESSING` 超租约（lease 可配，默认 5 分钟） | CAS 重抢后执行——「不确定状态可重试」：原执行者可能已崩溃 |

   核心语义：**不确定（in-doubt）可重试，确定失败不重试**——与「至少一次 + 幂等」一脉相承而不与「补偿而非重试」相矛盾。
3. **双重幂等**：幂等键仲裁是应用层防线；业务表唯一约束是最终防线——`t_ticket.idem_key = 业务键` 唯一（同一来源单号在任何会话 / 任何重放下都只建一张单），工具自身的执行也幂等（`ON CONFLICT DO NOTHING` 后读现有）。
4. **补偿**：失败调用落库后，对**同会话更早（id < 失败调用）的 `SUCCEEDED` 且工具声明可补偿**的调用，**生成 `PENDING` 补偿计划**（`t_compensation_log`，`UNIQUE(invocation_id)` 幂等）；**补偿执行器显式触发**（`POST /api/v1/agent/compensations/run`），按 `invocation_id DESC`（**逆序**）逐个执行可补偿工具的 `compensate`，每行动作落 `SUCCEEDED/FAILED` + `attempt++`，并写审计（动作顺序可断言）。
5. **工具可见面（FR-PERM-03）**：装配期配置 allow 白名单定死（`aiwarden.agent.tools.allowed`）+ 代码级 `EXCLUDED`（显式禁 `web_search`/`web_fetch`，配置误加也不可见）；未可见面工具调用 **403 + 审计 `TOOL_DENIED`**（不泄露存在性）；可见面列表 API 只返回白名单内工具（「不进模型请求体」的读侧）。
6. **计量**：工具调用随事务写 outbox 计量（tool 维度，复用 M1 的 CallMeteringPayload 骨架）。

### 依据

- PRD 引文：FR-TOOL-01（幂等键三要素）/ FR-TOOL-02（补偿而非重试）/ FR-TOOL-04（主演示：断网重放工单数 == 1）/ FR-PERM-03（可见面装配期定死）/ §4.3（补偿链路）/ §10（工单是零资金风险演示替身）。
- 方法论复用：M1 的「至少一次 + 唯一键仲裁 + 失败留痕」直接平移（ADR-005 修订段的教训已内化进决策 2 的矩阵——先把键上的合法流转列完再写仲裁）。

### 代价与放弃

- 放弃「FAILED 自动重试队列」：与「补偿而非重试」纪律冲突；失败调用的恢复靠补偿 / 人工（与 FR-ING-04 的摄入重试语义不同——摄入是基础设施重试，工具是业务副作用）。
- `PROCESSING` 超租约重抢意味着**执行窗口内可能有两个执行者**（原执行者只是慢而不是死）——由双重幂等（决策 3）兜底：重复执行最多多一次工具调用，不可能重复落库副作用。
- 不做审批会签 / 多级审批（FR-TOOL-03 二态开关为应做档，随后落地：`PENDING_APPROVAL` 状态 + approve/reject，挂起上下文 = 已落库的输入快照，恢复不丢上下文）。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 断网重放工单数 == 1（**主演示**）| `ToolInvocationContainersTest`：首次调用成功后丢弃响应（客户端未收到）→ 同键重放 → `replayed=true` + 结果与首次一致 + 工单表仍 1 行 |
| 并发重放只执行一次 | 16 线程同键并发 → 工单 1 行 + 无第二张（其余线程得到复用结果或 409）|
| PROCESSING 卡死可租约重抢 | 手工将已成功调用置回 PROCESSING 并回拨 updated_at → 重放重抢执行成功且工单不重复 |
| FAILED 重放不重执行 | 失败调用重放 → 返回首见失败记录，无新执行痕迹 |
| 补偿逆序清算 | 两个成功建单（主单、关联单）+ 指派失败 → 生成 2 条 PENDING 计划 → 执行后两单 CANCELLED，审计顺序 = 先关联单后主单（逆序）|
| 工具可见面 4 条越权样本 | `ToolWhitelistContainersTest`：web_search / web_fetch / unknown_tool / 注册未授权工具 均 403 + 审计；可见面列表只含白名单工具（含入总 20 条门禁）|

## 修订（2026-10，切片②实现回填）

三个测试类 12 测试全绿（`ToolInvocationContainersTest` 5 / `ToolWhitelistContainersTest` 5 / `ToolApprovalContainersTest` 2），
验证表逐行达成。实现期补充三条落地细节（语义不变）：

1. **二态审批已随切片②落地**（原「随后落地」项）：`PENDING_APPROVAL` 挂起、`approve`（CAS → 从输入快照恢复执行）、`reject`（CAS → `REJECTED` 终态，**不触发补偿**——驳回是「不执行」不是「执行失败」）；重放矩阵扩展为「SUCCEEDED / FAILED / PENDING_APPROVAL / REJECTED 均返回首见状态」。开关为类级配置 `aiwarden.agent.tools.require-approval`（默认空，不影响既有调用）。
2. **工具注册表保持有序**：`Map.copyOf` 会丢失 TreeMap 顺序（实测：可见面列表断言失败暴露），改用 `Collections.unmodifiableSortedMap`——列表接口输出稳定字典序。
3. **计量挂点**：每次工具调用（成功/失败）随事务写 outbox 计量（tool 维度），P4a 四维归因的工具维度由此归位。

## 修订（2026-10，M2 复核后的 P3 修正）

对 P3 做独立复核后发现三处**静默不生效 / 静默假成功**缺陷并修正，其中两条改动了 SPI 与响应契约：

1. **幂等指纹必须保留数字精度**（决策 1 的正确性前提）。默认 Jackson 把 JSON 小数反序列化为
   `Double`——精度在进入业务代码**之前**就已丢失，于是两个仅在 17 位有效数字之后不同的输入会得到
   **同一个指纹**，第二次调用静默重放首次结果（不报错、不执行、无日志）。本机实测（Jackson 3.1.5）：
   `12345678901234567890.1234…890` 与 `……891` 的规范形式**完全相同**；`readTree` 也不救
   （得到的是 `DoubleNode`，同样已丢精度）——治本点只能在**反序列化配置**上。
   落地：`JacksonPrecisionConfiguration`（start 装配根）对共享 ObjectMapper 开
   `USE_BIG_DECIMAL_FOR_FLOATS`——必须放在共享 mapper 上，因为 `@RequestBody` 在 Controller 边界
   就已完成解析，修在指纹计算处等于没修。回归门禁
   `idempotencyFingerprint_preservesNumericPrecision`（已验证可失败：关掉该开关即红）。

2. **幂等键成分的长度语义上收到入口**（决策 1 的健壮性）。键 = 业务键 + 会话 + 16 hex，
   原先两侧口径不一致：契约无长度约束而列宽是 `VARCHAR(160)`，200 字符业务键会走到 `INSERT`
   由 PostgreSQL 报 `value too long` → **500**（不是 400）。落地：入口显式校验
   （`businessKey` / `sessionId` ≤ 64、`stepNo` ∈ [0, 100000]，越界 400；三者上限之和 146 ≪ 列宽），
   并由 **V7 迁移**把列宽放宽到 256 留出余量。回归门禁
   `oversizedIdempotencyKeyParts_areRejectedWith400_beforeHittingDb`。

3. **补偿的「成功」必须有证据，失败必须可重跑**（决策 4 的语义收口）。三条修正：
   - **SPI 契约变更**：`ToolExecutor.compensate` 返回类型 `void` → **`int`（受影响行数）**，
     默认实现由「空实现」改为**抛 `UnsupportedOperationException`**（空实现会让误用表现为静默成功）。
     执行器按行数分流：`>0` 记 `SUCCEEDED`，`=0` 记 **`NO_OP`**（终态，单列计数）——
     否则会出现「审计写 SUCCEEDED + 计数器 +1，而数据库什么都没变」的假成功。
   - **`compensable()` 执行期再判一次**：计划生成时已过滤，但装配变更后已存在的脏计划仍可能
     指向不可补偿工具，执行期守卫给出有指向性的失败原因。
   - **FAILED 可重跑**：执行集合从「仅 PENDING」改为 `PENDING ∪ FAILED(attempt < 上限)`——
     原先补偿失败即永久卡在「半补偿」且第二次 run 恒返回 `executed=0`，与 SPI javadoc
     承诺的「等待人工/重跑」自相矛盾。超过上限的单列 `exhausted`（真正需人工介入的那部分）。
   - **响应契约变更**：`CompensationRunResponse` 增 `noOp` 与 `exhausted` 两个计数字段
     （succeeded 与 noOp 混在一起会让「补偿成功率」失去意义）。
   - 指标：`aiwarden_compensation_total{result=succeeded|noop|failed}`、
     `aiwarden_compensation_exhausted_total`。回归门禁
     `compensation_marksNoOpInsteadOfFakeSuccess_andRetriesFailedPlans`。

> 本批与 ADR-010 修订段同源：三处都是「不报错、只是静默不生效」形态（`AGENTS.md` §4 的归纳规则）。

---