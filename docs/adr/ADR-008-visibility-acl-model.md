<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-008 · 可见集 ACL 模型与下推算法（P2 落地：主体、四级语义、拒绝与审计）

**状态**：已采纳（2026-10-07）｜M2 切片①（P2 检索层隔离）开工前落盘；实现后只回填验证数据，不改变语义

### 背景

ADR-007 已定「过滤下推到 `t_vector.meta`」，但四级可见集（`tenant ∩ org ∩ kbACL ∩ docACL`，FR-PERM-01）
在数据模型上如何表达尚未定型：主体（用户 / 组织）从哪来、kbACL / docACL 用什么载体、空集拒绝的边界语义、
越权留痕的落点。本 ADR 在编码前把这些问题定死，避免再次出现「文档描述了不存在的机制」。

### 决策

1. **主体（Principal）**：`userId` + `orgId`（可空）。M2 过渡来源 = 请求头 `X-Aiwarden-User-Id` / `X-Aiwarden-Org-Id`
   （**认证层输出的模拟**——真实部署由 API Key / JWT 解析产出，届时只换来源、不换传播机制，与 ADR-003 对租户的处理同构）；
   `PrincipalContext`（common 模块）承载，缺失时 `requireUserId()` 拒绝——**不回落匿名主体**。
2. **四级语义**（全部在 SQL 侧计算，不允许应用层后过滤）：
   - **tenant**：来自 `TenantContext` 的硬边界（meta.tenantId）；
   - **org**：`t_knowledge_base.org_id`——`NULL` = 租户公共，非 NULL = 仅该组织可见；用户 `orgId` 为空 → 仅公共；
   - **kbACL**：`t_kb_acl(kb_id, user_id, effect[ALLOW|DENY])`——`ALLOW` = 跨组织例外授权，`DENY` = 显式拒绝；
   - **docACL**：`t_doc_acl(doc_id, user_id, effect)`——文档级例外（单独授权 / 屏蔽敏感文档）。
3. **可见集算法**（检索前，`VisibilitySetCalculator`，全部走索引点查）：
   - 可见 KB = `(org 门：org_id IS NULL OR org_id = :orgId) − DENY ∪ ALLOW`；请求指定 `kbId` 时求交集；
   - 文档例外 = `t_doc_acl` 的 ALLOW / DENY 两个集合；
   - **空集（含交集为空）→ 检索前直接拒绝**（`SecurityException` → HTTP 403），不进入向量查询（FR-PERM-01）。
4. **下推形态**（`RetrievalService`，事务内）：
   `WHERE meta->>'tenantId' = ? AND (meta->>'kbId' = ANY(?) OR meta->>'docId' = ANY(?)) AND NOT (meta->>'docId' = ANY(?))`
   + `set_config('hnsw.iterative_scan','strict_order',true)`；HNSW 路径返回条数 < K 时走**精确回退**
   （`enable_indexscan=off` 的过滤后精确排序），回退计数器 `aiwarden_retrieval_exact_fallback_total`。
5. **越权留痕**：拒绝路径写 `t_audit_log(action=RETRIEVAL_DENIED, actor=userId)`；`t_audit_log` 由数据库触发器
   拒绝 UPDATE / DELETE——「不可变留痕」具备代码级证据（测试可断言变更被拒）。

### 依据

1. **PRD 引文**：FR-PERM-01（四级 + 空集拒绝）、FR-PERM-04（20 条样本 CI 门禁）、FR-PERM-05（审计留痕，越权同样留痕）、
   §9（`t_vector.meta` 必须含过滤维度）。
2. **kbACL / docACL 为什么用「表 + effect」而不是给业务表加 visibility 枚举列**：枚举无法表达「对特定用户的跨组织例外」（ALLOW）
   与「对特定用户屏蔽」（DENY）；两张表结构与读写路径同构（一张形态两处用）；meta 下推只消费「可见集合」的产物，与载体结构解耦。
3. **拒绝为什么用 403 而不是 404**：检索是「搜索」语义——无权与不存在同为 403，不可区分（同样不泄露存在性）；
   资源读取（GET KB / 文档状态）继续按 FR-KB-01 用 404。

### 代价与放弃

- **Principal 在 M2 不做真实性校验**（请求头可伪造）——「认证层输出模拟」的已知边界，真实部署由认证层保证；
- 放弃「给 t_document / t_knowledge_base 打 visible 列」的简单方案：表达力不足（无用户级例外），且权限语义分散在多处；
- 放弃「可见集预计算缓存」：演示规模不需要，缓存会让「权限变更立即生效」复杂化——每次检索前一次轻量点查。

### 验证（随 `mvn verify` 执行；实现后回填实测数据）

| 断言 | 证据 |
|---|---|
| 检索类越权样本拦截率 100%（16 条；+P3 工具类 4 条 = 合计 20 条） | `RetrievalVisibilityContainersTest`：跨租户 / 跨组织 / 无权限 KB / 已删除各 4 条，每条先以「有权者命中同一内容」排除假阳性 |
| 下推 SQL 确实走 HNSW 索引 | `RetrievalHnswPushdownContainersTest` 的 `EXPLAIN` 自证（断言含 `idx_t_vector_embedding_hnsw`）；小表会退化为顺序扫描，故种 5000 行 |
| 窄可见集仍凑满 K（iterative_scan 生效） | 同上：5000 行中仅 10 行可见、K=10 → 返回 10 条（ADR-007 实测默认配置仅返回 1/10） |
| 精确回退不是死代码 | `max-scan-tuples=1` 触发回退：结果仍为 K + 计数器递增 |
| 越权尝试留痕且不可变 | 拒绝后 `t_audit_log` 出现 `RETRIEVAL_DENIED`；UPDATE / DELETE 被触发器拒绝 |
| 检索入口不可绕过可见集 | `ArchitectureTest`：`RetrievalService` 公开检索方法签名必须含 `VisibilitySet`；`*Service/*Store/*Calculator` 不得声明全量查询方法（含自证夹具） |

## 修订（2026-10，切片①实现实测）

P2 落地过程中实测出五条与「机制是否真的生效」直接相关的结论（全部有可复现测试，正文不改写）：

1. **受控实验的完整旁路清单**（5000/20000/50000 行逐一实测）：planner 对检索 SQL 的真实选择依次是
   ① `Seq Scan + Sort`；② 关串行序扫后绕道「并行序扫 + Gather Merge + Sort」；③ 再关并行后首选
   `idx_t_vector_tenant`（btree 表达式索引，估算 rows=250）；④ 移除 btree 后转头 `t_vector_pkey`
   （估算 rows=1 的幻觉）——**根因是 planner 对 JSONB `meta` 过滤的选择性估算在 1/250/50000 间横跳**。
   影响两面：a) 中小规模下 HNSW 本就不被执行（精确路径无召回风险——与 §11 风险 8「不承诺召回率」自洽）；
   b) 要观察 HNSW 路径，测试必须受控：事务内 `enable_seqscan=off` + 关并行 gather + seed 阶段移除
   btree 与主键索引（见 `RetrievalHnswPushdownContainersTest` 类注释）。
2. **`hnsw.max_scan_tuples` 只限制 iterative 绕回额度，首次 `ef_search` 候选不受限**：实测「可见行全部
   先插入」时，HNSW 图入口点落在可见团内，默认配置（off）也能凑满 10——「凑满 K」不再能证明
   iterative_scan 生效（假绿）；把可见行改为**后插入**后，off 实测返回 0 条，「凑满 K + 回退计数=0」
   才成为真证据。**测试数据的插入顺序是 HNSW 行为学断言的一部分。**
3. **审计写入必须独立事务（REQUIRES_NEW）**：拒绝路径的形态是「写审计 → 抛拒绝异常」，同事务则异常
   回滚把审计一起滚掉——「越权留痕」实测为零条。`AuditLogWriter.append` 已改为 `REQUIRES_NEW`。
4. **回退路径必须重开 `enable_seqscan`**：若调用环境（外层事务 / 运维设置）设过 `enable_seqscan=off`，
   回退的「顺序扫描 → 过滤 → 排序」会被迫再走索引——回退等于没回退。`exactFallback` 已重开该开关。
5. **回退触发条件收敛为「HNSW 返回数 < 可见总数」**：先做精确计数（同谓词）再决定是否回退——
   「可见总数本来就 < K」不回退（避免小可见集的无谓全扫）；计数与回退都属治理税，有计数器可观测。

验证回填：检索类越权样本 16/16 拦截（`RetrievalVisibilityContainersTest`，含基线假阳性排除与审计不可变）；
HNSW 受控自证 2/2（`RetrievalHnswPushdownContainersTest`）；塌陷回退 1/1
（`RetrievalExactFallbackContainersTest`，计数器递增证明回退不是死代码）；工具类 4 条样本随 P3 切片合计 20 条。

## 修订（2026-10，M2 复核后的 P2 修正）

独立复核 P2 时发现三处「防线 / 证据」缺口，正文决策语义不变，补三条落地细节：

1. **租户谓词此前是「零证据的承重墙」**：常规跨租户样本其实是被 OR 的**左支**
   `meta->>'kbId' = ANY(kbIds)` 挡住的（`kbIds` 只可能含本租户 KB），把
   `AND v.meta->>'tenantId' = ?` 整行删掉，那批用例**依然全绿**。现补
   `tenantPredicateIsLoadBearing_andCrossTenantAclRowIsImpossible`：用**同一条下推 SQL 去掉租户谓词**
   （且 `kbIds` 故意留空，使命中只能来自 `allowDocIds`，否则本租户文档会一起通过而使断言失去判别力
   ——实测踩过一次：count=3 而非 1）直查向量表，证明「外租户 docId 一旦进入可见集即可命中」，
   因此产品路径的 0 命中只能归因于租户谓词。
2. **跨租户 ACL 行的 DB 层防线（V8，对应决策 2 的 kbACL / docACL）**：两张 ACL 表原先只有
   `UNIQUE (kb_id, user_id)` / `(doc_id, user_id)`，**没有任何约束把 `tenant_id` 绑定到所指向对象的
   属主租户**——一行「租户 B 写、指向租户 A 的 kb_id」的 `DENY` 会遮蔽租户 A 的用户，并凭唯一约束
   占掉其 ACL 槽位（**跨租户拒绝向量**）。现补 `(tenant_id, id)` 组合唯一键 + 组合外键
   （`t_kb_acl → t_knowledge_base`、`t_doc_acl → t_document`，`ON DELETE CASCADE`）：
   这类行在写入时即被拒绝。应用层也已给 DENY 子查询补上 `a.tenant_id = kb.tenant_id`
   （原实现漏了，ALLOW 分支本来就有）——**双防线**。
3. **回退的 GUC 必须复位（补齐决策 4）**：`set_config(..., true)` 是事务本地，不会随方法返回而消失。
   `exactFallback` 原先把 `enable_indexscan/bitmapscan` 设为 `off` 后**不复位**——调用方在同一事务内
   再次检索时下推路径会**静默不再被使用**（结果仍正确，机制失效、治理税暴涨）。
   现 `finally` 复位，并由 `RetrievalExactFallbackContainersTest` 断言回退后
   `enable_indexscan/bitmapscan` 回到 `on`（同事务内取值）。

> 另：`RetrievalService.search` 增加与 `TenantContext` 的**交叉校验**（可见集是值对象，
> ArchUnit 只能守护签名含 `VisibilitySet`，守护不了**来源**）；越界即 403 + 审计。见第二部分裁决 15。

---