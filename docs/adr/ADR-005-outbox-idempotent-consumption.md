<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-005 · Outbox 发布与幂等消费时序（轮询 Relay + 至少一次 + 唯一键仲裁）

**状态**：已采纳（2026-10-07）｜M1 切片②落地

### 背景

P1（删除即失效）主链路是「业务表 + outbox 同事务 → Kafka → 幂等消费 → 终态可查」。发布机制与消费仲裁有多个可选做法（CDC 抽 binlog vs 应用内轮询 relay；消费端恰好一次 vs 至少一次 + 幂等）。

### 决策

1. **发布用应用内轮询 Relay**（`OutboxRelay`）：`SELECT PENDING → 逐条发送（同步等 broker 确认）→ CAS 标记 SENT`，失败累计 retry_count（≥5 降 FAILED）。不用 Debezium/CDC：少一个重组件；发送与标记之间的崩溃窗口由消费端幂等兜底——**系统语义是「至少一次 + 幂等消费」，不追求恰好一次**。
2. **事件类型即 topic 名**（`document.index.requested` / `document.delete.requested`），聚合 id 作消息 key（同聚合有序）；租户随消息头跨进程传递（M0 载体接口 `TenantContextCarrier` 的首次生产接入：`KafkaHeadersCarrier`）。
3. **消费端唯一键仲裁**（`IngestLedgerGuard`）：`(doc_id, version)` 主键 + `ON CONFLICT DO NOTHING` 抢占；状态机 `PROCESSING → INDEXED / DELETED / FAILED`，**FAILED 允许重抢占**（与 Kafka 重试配合，避免重试消息被仲裁挡死）；PROCESSING 卡死的超时回收由对账任务兜底（切片④）。
4. **失败策略**：指数退避重试 ≤3 次（1s/2s/4s）→ `<topic>.DLT` 死信（FR-ING-04）。

### 依据（实测）

- `OutboxIngestLoopContainersTest`：写入 → 发布 → 消费 → ledger INDEXED 闭环；重复投递处理次数不变；租户经消息头跨进程还原断言通过。
- `IngestLedgerGuardContainersTest`：16 线程并发抢占恰好 1 个赢家（M1 验收②的仲裁层先行验证；端到端向量计数随切片③）。

### 代价与放弃

- **放弃 CDC**：换库 / 运维成本与演示价值不匹配；轮询延迟（默认 2s）占用「P95 ≤ 5s」删除 SLO 预算（发送 2s 节奏 + 消费近瞬时，仍在线内；最终实测值随 M1 验收回填）。
- **放弃「恰好一次」幻想**：at-least-once + 幂等是分布式系统的正确语义；好处是重试 / 重放天然安全——P3（工具幂等）与 P5（重放续跑）将复用同一方法论。

### 修订（2026-10，切片③实测补充）

**删除与索引共用 `(doc_id, version)` 账本行时的状态流转缺口（已在实现中修正）**：初版 `tryClaim` 仅允许 FAILED 重抢，
导致「索引完成（INDEXED）后的删除事件」被幂等仲裁**静默跳过**（向量清理不执行；HTTP 全链路测试以 `1/DELETED/INDEXED` 超时暴露，无指向性报错）。
修正：拆分 `tryClaimForIndex`（FAILED 可重抢）与 `tryClaimForDelete`（**INDEXED / FAILED 可重抢**——删除是索引之后的
正常状态流转；DELETED 为文档级终态，且 `markDeleted` 按 doc_id 将全部版本行标记为 DELETED）。证据：
`DocumentLifecycleContainersTest`（上传 → 索引 → 删除 → 产物清零）与 `IngestLedgerGuardContainersTest`
（INDEXED → 删除抢占 → DELETED 终态）随 `mvn verify` 执行。

---