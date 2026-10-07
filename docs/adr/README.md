# 决策记录（ADR）索引

> **这份目录是什么**：本项目的架构与关键技术决策记录，[PRD §7.4](../PRD.md) 规定其为首批 ADR 的正式落点。
> **组织方式**：**一文件一 ADR**（`ADR-00X-<slug>.md`），本页是索引。拆分自原单文件 `docs/DECISIONS.md`
> （2026-10-07 拆分，内容逐行校验零改动）；`docs/DECISIONS.md` 保留为重定向页，指向本目录。
>
> **体例**（每个 ADR 文件内含）：
> - `状态 / 背景 / 决策 / 依据 / 代价与放弃 / 验证`；
> - **不改写已采纳条目的原文**：决策被演进时在**末尾追加** `## 修订（YYYY-MM）` 段说明现状；
>   整条废止则把「状态」标为 **已被取代** 并指向现行条目（这类条目保留的价值是「当时为什么会走到这一步」，不是现状）；
> - 版本号类结论**必须能在 `pom.xml` 找到对应坐标**。
>
> **与代码的关系**：ADR 与 [PRD §7.3](../PRD.md) 的 ArchUnit 规则互相引用。

**索引**

| 编号 | 标题 | 状态 | 含修订段 |
|---|---|---|---|
| [ADR-001](ADR-001-bootstrap-versions.md) | 底座选 Spring Boot 4.1 + LangChain4j 1.21 | 已采纳（2026-10-06） | ✔（2026-10） |
| [ADR-002](ADR-002-virtual-thread-webmvc.md) | Web 层虚拟线程 WebMVC（非 WebFlux） | 已采纳（2026-10-06） | — |
| [ADR-003](ADR-003-tenant-context-capture.md) | 租户上下文显式 capture/apply（不引入 TTL） | 已采纳（2026-10-06） | — |
| [ADR-004](ADR-004-keep-kafka.md) | 消息中间件保留 Kafka（决策点提前实测） | 已采纳（2026-10-07） | — |
| [ADR-005](ADR-005-outbox-idempotent-consumption.md) | Outbox 发布与幂等消费时序（轮询 Relay + 至少一次 + 唯一键仲裁） | 已采纳（2026-10-07） | ✔（2026-10 切片③） |
| [ADR-006](ADR-006-reconcile-readonly.md) | 对账只读发现、修复显式触发（不自动改数据） | 已采纳（2026-10-07） | — |
| [ADR-007](ADR-007-visibility-pushdown.md) | 可见集过滤机制：向量表 metadata filter 下推（JOIN 退场） | 已采纳（2026-10-07） | — |
| [ADR-008](ADR-008-visibility-acl-model.md) | 可见集 ACL 模型与下推算法（主体、四级语义、拒绝与审计） | 已采纳（2026-10-07） | ✔（切片①实测 / M2 复核） |
| [ADR-009](ADR-009-tool-side-effect-governance.md) | 工具副作用治理机制（幂等键状态机、补偿逆序、工具可见面） | 已采纳（2026-10-07） | ✔（切片②回填 / M2 复核） |
| [ADR-010](ADR-010-quota-strong-consistency.md) | 配额强一致机制（Redis Lua 预扣减、幂等、对账、限流） | 已采纳（2026-10-07） | ✔（切片③回填 / M2 复核） |
| [ADR-011](ADR-011-governance-tax-and-mcp.md) | 治理税度量方法（四项微计时口径）与 MCP 最小版取舍 | 已采纳（2026-10-07） | ✔（切片④回填） |

**新增 ADR 的步骤**（保持本目录可用）：

1. 复制任一文件的体例，取名 `ADR-00X-<英文 slug>.md`（**slug 用英文**：中文标题会带全角标点，且改标题就断链）；
2. 在本页索引表补一行（编号 / 标题 / 状态 / 含修订段），标题与文件内 `## ADR-00X · …` 保持一致；
3. 若该 ADR 属于**口径级变更**，同一次改动里还要回写
   `docs/PRD.md`、`README.md`、`docs/research/06-PRD修订裁决清单.md`——见 [`AGENTS.md` §2](../../AGENTS.md)。
> ⚠️ **文件内相对链接的基准是 `docs/adr/`**：指向 PRD 用 `../PRD.md`、指向调研文档用 `../research/...`。
> 从 `docs/` 根目录搬进来时**必须改基准**，否则链接静默失效（拆分时 ADR-001 的 `research/…` 就踩过）。
