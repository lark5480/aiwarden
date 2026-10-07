<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-007 · 可见集过滤机制：向量表 metadata filter 下推（JOIN 退场）

**状态**：已采纳（2026-10-07）｜M2 可见集机制定型（开工前落盘，实现后不再回填）

### 背景

M1 的检索最小版（`RetrievalService`）用 `JOIN t_chunk ... WHERE c.tenant_id = ?` 完成租户过滤——这是**过渡实现**。
M2 要实现 P2 的四级可见集（tenant ∩ org ∩ kbACL ∩ docACL），过滤载体必须先定型：
**继续 JOIN 过滤，还是把可见性条件作为 metadata filter 下推到向量表（t_vector.meta）？**

### 决策

**metadata filter 下推为主**：可见集条件与向量排序在**同一条针对 t_vector 的 HNSW 扫描**中表达
（`WHERE meta->>'tenantId' = ? AND meta->>'docId' = ANY(...) ...`）；`JOIN t_chunk` 仅保留「按 chunkId 回表取内容」的职责，**不再承担任何可见性过滤**。

### 依据（为什么不是 JOIN）

1. **风险同源、可控性不同**：JOIN 过滤不是「没有风险」——HNSW 候选 + join 验证同样是 post-filter（凑不满 K 静默发生）；
   且执行计划会在「hash join + 精确排序（无 ANN、全量算距离）」与「HNSW post-filter」之间随统计信息漂移，
   **应用层无从得知走了哪条**（性能与结果条数都不可控）。下推路径的行为可配、可测、可回退，与「确定性治理」主张相符。
2. **口径一致**：PRD §7.3 / §9 / P2 原文即为「metadata filter 下推」；t_vector.meta 的选型理由
   （「保证 filter 可下推」）只有真下推才成立——**本决策使 PRD 零改动**；反之需改写 §9 理由并降级 P2 话术。
3. **维度扩张**：可见集四级条件在 meta 上是一条 jsonb 条件 + 集合谓词；在 JOIN 上会随 ACL 模型膨胀为多表 JOIN / OR。

### 风险 8 的正面回答（本栈实测：PostgreSQL 16.15 / pgvector 0.8.7）

1. **iterative_scan 可用**（实测 GUC 存在：`hnsw.iterative_scan` / `hnsw.max_scan_tuples` / `hnsw.scan_mem_multiplier`）：
   查询级 `SET LOCAL hnsw.iterative_scan = strict_order` + `max_scan_tuples` 上限——绕回继续扫描直到凑满 K 或达上限；
2. **两段式精确回退**：下推路径返回 < K 且可见集规模大于返回数时，**显式**走精确过滤排序补足（不静默接受不足 K）；回退次数打点可观测；
3. **边界自洽**（B1/B2）：不承诺召回率，承诺不越权；「高选择性查询下的召回率」列入压测报告量化（§11 风险 8 原文已列）。

**实测（2026-10-07，独立可复现；5000 行 / 租户 A 占 1% / 过滤条件与距离故意不相关）**

前提：`EXPLAIN` 自证计划为 `Index Scan using idx_t_hnsw`（`Filter: tenant = 'A'`）。**这一步不能省**——
小表上规划器会退化成顺序扫描 + 精确排序，此时 `hnsw.*` 参数根本不参与，会得到「全对」的假结论（本 ADR 的第一版实验即栽在这里）。

| 配置 | 返回条数（目标 K=10） | 与精确答案重合 |
|---|---|---|
| `iterative_scan=off` / `ef_search=40`（**默认**） | **1** | 1 |
| `off` / `ef_search=1000`（合法上限） | 10 | 10 |
| **`strict_order` / `ef_search=40`** | **10** | **10** |
| `relaxed_order` / `ef_search=40` | 10 | 10 |
| `off` / 窄可见集（20 行可见，K=10） | **1** | — |
| `strict_order` / 窄可见集 | 10 | — |

三条读数：

- **默认配置的召回损失是实打实的**：1% 选择性下返回 **1 条而非 10 条**，**无报错、无日志**——正是
  `AGENTS.md` §4 归纳的「不报错、只是静默不生效」形态；
- **`strict_order` 足够，且不牺牲精确性**：返回的就是精确 top-10（10/10 重合）。「可能不足 K」的真实触发条件是
  **`max_scan_tuples` 耗尽**（本次 5000 行远未触及默认 20000），而它一旦发生，恰由第 2 条的精确回退承接——
  **两段式回退不是备选方案，而是本设计的组成部分**；
- **调大 `ef_search` 不是解法**：它确实能修好（1000 与 `strict_order` 同效），但它是**固定候选预算、不自适应**
  （本次等于扫 20% 的表），且**合法上限就是 1000**，堆不出可预测的行为。

### 落实清单（M2 开工执行；**#1 / #2 / #3 已于 2026-10-07 落地**：V3 迁移回填 + 表达式索引 + metaJson 写入 + 断言）

1. V3 迁移：`UPDATE t_vector SET meta = c.meta FROM t_chunk c WHERE t_vector.chunk_id = c.id`（回填 meta 一致）；
   补 meta 必含可见集字段（tenantId / orgId / kbId / docId）；
2. `CREATE INDEX idx_t_vector_tenant ON t_vector ((meta->>'tenantId'))`——**正面说明**：btree/GIN 无法限制 HNSW 候选遍历顺序，
   该索引用途是（a）精确回退路径的「先过滤后排序」（b）运维统计；主路径的 K 不足由 iterative_scan + 回退解决（此点必须如实写进答辩材料）；
3. `DocumentIngestStore.metaJson` 增加 tenantId（M1 过渡版 meta 缺它）；
4. `RetrievalService` 过滤改写（filter 在 t_vector 侧）+ 查询级 `SET LOCAL hnsw.iterative_scan = strict_order`；
5. 测试：窄可见集（单文档可见）仍可命中（iterative_scan 生效证据）；越权 0 命中样本迁移到 filter 路径；回退路径单测；
   ⚠️ **并必须断言执行计划确实走了 HNSW 索引**（`EXPLAIN` 含 `idx_t_hnsw`）——否则小表上规划器退化为顺序扫描 +
   精确排序，召回率「全对」，**测试通过却什么都没证明**（本 ADR 第一版实验即栽在此处）；
6. 压测：高选择性查询召回率对照（iterative_scan on/off、strict/relaxed），数字进结论表。
   注意 `ef_search` 合法上限为 **1000**，参数取点需在此范围内。

### 代价与放弃

- 放弃「JOIN 简单直白」的短期便利：多一层 meta 字段治理（写路径保持 meta 与 t_chunk.meta 一致，由同一 Store 写入口保证）；
- 接受「近似索引 + 过滤」的固有边界：以 iterative_scan + 精确回退 + 压测量化承接，而非回避——这正是与「换库回避问题」的分界线。

---