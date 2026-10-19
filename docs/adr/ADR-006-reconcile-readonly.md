<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-006 · 对账只读发现、修复显式触发（不自动改数据）

**状态**：已采纳（2026-10-07）｜M1 切片④落地

### 背景

P1 承诺「删除即失效有 SLO + 不一致可查询可修复」。对账任务发现残留（孤儿向量 / 卡死任务）后有两种演变：自动修复（发现即清），或只读发现 + 显式修复。

### 决策

**对账任务只读发现**（扫描 → 落 `t_reconcile_report` → 更新指标），**修复由显式动作触发**
（`POST /api/v1/admin/consistency/repair`，复用删除链路的幂等清理语义：先向量后切片、账本置 DELETED）。

### 依据

- **可观察性**：自动修复会让不一致清单在演示 / 运维视角永远为空——而对账的价值正是「发现并记录」；
  修复动作本身可追溯（报告 → 修复 → 复扫归零）。
- **安全边界**：repair 只处理 `t_document.deleted_at 非空` 的文档产物——语义上只是补做既定删除，
  不引入新状态；幂等可重跑。
- 测试证据：`ConsistencyReconcileContainersTest`（制造删除事件丢失缺口 → scan 报告 mismatch ≥ 2 →
  指标 ≥ 1 → repair → 复扫归零），随 `mvn verify` 执行。

### 代价与放弃

放弃「全自动自愈」叙事的一部分：演示录屏中「修复」需要一个显式触发（POST /repair）——
这反而与 FR-ADM-03「一键重试」的产品形态一致（M3 页面直接调用本端点）。

## 修订（2026-10）：判据补第三类（摄入停摆），重驱动限定「当前版本索引事件」

**触发来源**：2026-10-19 全项目冒烟审计在**本机开发库现场**抓到一条静默停摆——
`t_outbox_event` id=1（文档 1 的 `document.index.requested`）重试到上限被置 `FAILED`，
文档停在 `PENDING` **九天**、`t_ingest_ledger` **零行**；当时的两条残留判据（残留切片 / 孤儿向量）
与账本超时判据全部无感，表现为 `mismatch_count=0`、`aiwarden_ingest_stuck 0.0`——
**不报错、不进不一致清单、指标恒 0**，正违反 PRD §12 的「不允许静默放过」。
同日第二轮注入实验又抓到两个边界：重驱动若放回**删除事件**，会重新 purge 掉同名重传的新文档；
放回**旧版本索引事件**，会把旧版本产物写回并删掉新版本切片——幂等键 `(docId, version)` 只挡重复投递，
挡不住“旧事件晚到”。

**决策**：
- **`mismatchCount` 的第二项从「账本超时」扩为「摄入停摆文档数」**，定义成三条子判据
  （① **当前版本**的文档类**索引**事件 `FAILED`；② **当前版本、当前未收敛**的账本
  `PROCESSING` 超 5 分钟；③ 文档停在 `PENDING`/`PROCESSING` 超 5 分钟且未软删）
  **按 `doc_id` 去重的并集**——去重是硬要求，同一文档既「事件永久失败」又「文档超时」只算一条。
  `detailsJson.stuckIngest` 逐条带 `reason`（取最高优先级子判据），运维据此判断能否重驱动。
- **`repair` 的语义扩为两类显式动作**：原有「补做产物清理」+ 新增「把**当前版本、文档存在且未收敛**的
  `FAILED` 索引事件重驱动回 `PENDING`（`retry_count` 归零）」，由 relay 重发、消费者按既有
  幂等仲裁补齐。响应契约加 `redrivenEvents`。
- **删除事件不重驱动**：删除的后果由残留切片 / 孤儿向量判据负责；放回删除事件会重新执行
  `purgeDocumentArtifacts`（清全部版本），可能删掉同名重传的新文档。
- **旧版本索引事件不重驱动**：只认 `payload.version == t_document.version`；旧版本事件若被放回，
  消费者会用当前内容按旧 version 写回，并删除新版本切片。
- **消费者双守卫**：`DocumentEventConsumer` 在 claim 前按 `t_document.version` 拒绝**旧版本**事件；
  文档不存在时放行，让 handler 走 `DocumentNotFoundException` 终态（保持可见，不静默跳过）。
  `DefaultDocumentIngestHandler` 读取文档后再校验一次，覆盖“检查后到读取前又重传”的竞态。
- **「发现与修复分离」的本条决策不变**——重驱动同样是显式动作，不引入自动自愈。

**代价与放弃**：
- **`FAILED` 事件不单独成立判据**：只在该文档**尚未收敛**（状态仍 `PENDING`/`PROCESSING`）时才计；
  文档行不存在 / 已终态（`INDEXED` / `DELETED` / `FAILED`）都不进 `OUTBOX_FAILED`——没有可重驱动的
  当前版本目标。现场注入验证过假阳性：把一条**已被成功消费**的索引事件改回 `FAILED`，无此限制时
  文档已 `INDEXED` 却仍被报成不一致。
- **判据 / `failedOutboxEvents` 计数 / `repair` 重驱动共用同一条件**（`REDRIVABLE_FAILED_INDEX_EVENT`），
  避免「计数 0 / 清单空 / 却重驱动了 1 条」的口径分叉。
- **账本 `PROCESSING` 只算当前版本、当前未收敛**：旧版本卡死（当前版本已 `INDEXED`）是历史残留；
  已删除文档的删除卡死由残留判据负责。孤儿账本（文档行不存在）仍保留为可见异常。
- **重驱动 ≠ 收敛**：事件置回 `PENDING` 后文档仍停摆，`reason` 退化为 `DOCUMENT_OVERDUE`，
  只有摄入真的完成才归零。测试里显式断言这一过渡，避免「修复返回 200 就当已修好」。
- **不替业务伪造事件**：若连 `outbox` 事件行都不存在（写入侧就没落账），本条判据**仍能发现**，
  但 repair **不自动补发**——那等于凭空造一条业务从未提交过的删除/索引事件，风险大于收益，
  留人工重新上传。
- 指标名沿用 `aiwarden_ingest_stuck_total`（语义随判据扩大），不新增指标；导出名仍被 Micrometer
  剥掉 `_total`（见 [`AGENTS.md`](../../AGENTS.md) §4）。

**验证**：`ConsistencyReconcileContainersTest` 现有 5 个用例——原始用例播种「PENDING 超时文档 +
FAILED 索引事件、无账本行」，断言 ① scan 命中且 `reason=OUTBOX_FAILED`、同一 doc 在清单里只有一条、
`aiwarden_ingest_stuck_total` 被抬起；② `repair` 后事件真变回 `PENDING:0`（查库不是看返回码）；
③ 复扫 `failedOutboxEvents=0` 但文档仍停摆、`reason=DOCUMENT_OVERDUE`；④ 模拟摄入完成后该 doc
不再出现在清单；⑤ 已收敛文档的 FAILED 事件不计入。新增三个用例分别断言：删除事件 FAILED + 同名重传
不被重驱动（`reason=DOCUMENT_OVERDUE`、事件仍 `FAILED`）；旧版本索引事件不被重驱动；旧版本账本
`PROCESSING` 超时不被误报。**现场差分**：本机重启新 jar 后 `POST /scan` 从修复前的 `mismatchCount=0`
变 `1`（抓到那条九天未动的 FAILED 事件），`POST /repair` 重驱动 → relay 发出 → 消费者置 `INDEXED`
→ 复扫 `mismatchCount=0` 且该文档重新可检索。

---