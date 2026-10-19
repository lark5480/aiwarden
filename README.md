# AIWarden

> **多租户 AI 数据与用量治理层**（形态：**数据面治理组件**）
> 把大模型的**不确定性**（删不干净、越权、重复副作用、用量失控、长任务中断）关进 Java 的**确定性**里。

[![Status](https://img.shields.io/badge/status-M4%20%E5%AE%8C%E6%88%90-brightgreen)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)]()

> **当前阶段：M4 完成**（M0–M4 全部落地；P1 / P2 / P3 / P4a / P5 成立，P4b 已砍）。
> 逐切片交付清单、**实测数字**与已知边界见 [`docs/STATUS.md`](docs/STATUS.md) §1 / §2 / §3，
> 里程碑定义见 [`docs/PRD.md`](docs/PRD.md) §8——**本页不复制进度描述**。

---

## 一句话说清这个项目做什么

AIWarden 是一个**面向 Java 技术栈的 AI 应用数据面治理组件**。它不做「又一个能聊天的 RAG 平台」，只做几件**能被验证**的事：

| # | 承诺 | 验收方式 |
|---|---|---|
| **P1** | **删除即失效** —— 删除文档后，它在向量与全文索引中的痕迹在 **P95 ≤ 5s** 内消失；超窗残留**必须出现在对账的「不一致清单」里**（清单同时覆盖**摄入停摆**） | 删除后 5s 的检索断言（**断言 P95，不是单次**）+ `aiwarden_vector_orphan_total` / `aiwarden_ingest_stuck_total` 指标 |
| **P2** | **检索层隔离** —— 租户 / 组织 / 知识库 / 文档四级权限在**检索前**算成可见集并下推 filter；越权查询 **0 命中** | **20 条越权样本进 CI**，断言拦截率 **100%** |
| **P3** | **副作用幂等** —— 写操作工具带幂等键；失败走**补偿**而非重试；断网重放**不产生第二张工单** | 故障注入用例 + 数据库终态断言（工单数 == 1）——**主演示场景** |
| **P4a** | **用量可归因 + 配额强一致** —— Token 与耗时按**租户 / 会话 / 步骤 / 工具**四维归因；配额**预扣减 + 幂等 + 对账**，超限 429；审计不可变留痕 | Prometheus 指标 + 配额检查单测 |
| **P4b** | 预算决定推理档位（**已裁决砍除**，2026-10） | （已砍，不适用） |
| **P5** | **长任务可续跑** —— Agent 持久化为状态机，进程重启 / 超时 / 人工中断后从最近检查点续跑，**重放不产生重复副作用** | `kill -9` 重启后续跑用例 + 副作用计数断言 |

**两条边界声明**（有意写在第一屏）：**B1** 治理的是**数据面**，**不承诺检索质量**（召回率 / 引用准确率不在范围）；
**B2** 一致性是**有 SLO 的可对账**（延迟有界、不一致可查询可修复），**不是绝对一致**。

### 结论表（可复现，不是形容词）

> 本表是 [`docs/STATUS.md`](docs/STATUS.md) §2 结论表的**对外投影**：数字只在 STATUS 更新，这里单向复制。
> 每行「该填什么、目标线多少」的验收口径见 [`docs/PRD.md`](docs/PRD.md) §12；机器规格 **`i7-7700（4 物理核 / 8 逻辑核）+ 32GB`**。
> **没做的写「待验证」，不写漂亮的 99%；解释性论证不在这里复制，跟着指针走。**

| 承诺 | 结果 |
|---|---|
| P1 删除生效 P95（目标 ≤ 5s） | **1975ms**（30 轮、含 relay 2s 调度；P50 1870ms / max 2076ms，**30/30 < 5s**）；超窗残留进不一致清单，现场差分「修复前 `mismatch_count=0` → 修复后 `1`」（[复现与口径](docs/STATUS.md)） |
| P2 越权拦截率（20 条） | **100%**（16 检索类 + 4 工具类，随 `mvn verify` 门禁） |
| P3 断网重放后工单数（期望 1） | **1** |
| P4a 四维归因 / 配额正确性 | 工具链路四维齐备；预扣减幂等 / 并发不超卖 / 超限 429 / 对账差异 **0**；M4 起模型调用也带 `trace_id` + `cost` |
| P4b 预算降级 | **已砍**（裁决 19），地基已就位 |
| P5 `kill -9` 后续跑 | **已达成**：崩在副作用后 / 检查点前 → 幂等命中 `replayed=true`、工单数 **1**（[ADR-013](docs/adr/ADR-013-agent-checkpoint-and-resume.md)） |
| 评测门禁（24 条样本） | **24/24 全绿**、风险样本拦截 **7/7**、重复建单 **0**、单次成本 **0.000869 元**（演示单价口径）；**P95 只报同日抖动区间 66–224ms**，不可当容量结论引用 |
| 治理税（六项 P95） | 可见集 **2.7ms** / filter 下推 **5.9ms** / 计量 **0.7ms** / 配额 **3.9ms** / 审计 **1.5ms** / OTel span **19µs**；六项 P50 相加约 **10.3ms/请求**；**可用并发上界 ≈ 4**（[逐项与逐轮](docs/benchmarks/M4-load-report.md)） |
| 测试 `mvn -B -ntp verify` | **170**（0 失败 0 跳过；前端独立 `pnpm build` 口径、两个探针不计入） |

**一次「答错」怎么按 traceId 定位**：四个 span 各能排除什么，配方正文见
[ADR-014「使用配方」段](docs/adr/ADR-014-observability-trace.md)（FR-OBS-05）；Grafana → Tempo 那一步本轮未实跑。

---

## 有了 Ragent / RuoYi AI，为什么还要这个项目？

三句话：**删除窗口**——竞品作者专文承认有窗口且解法锁进付费墙（一手核验），本项目走
同事务 Outbox → Kafka → 幂等删向量 → 补偿 → 死信 → 对账兜底；**越权面**——竞品系 MyBatis 拦截器方案被第三方实践
指出存在绕过面，本项目在检索前算可见集下推 filter，并把 20 条越权样本**放进 CI 门禁**；**后悔药**——
Spring AI / LangChain4j 原生无 Checkpoint，本项目做工具幂等键 + 补偿 + 断点续跑。

**一句话差异化**：别人在做「AI 能聊天」，本项目在做「**AI 的账、AI 的门、AI 的后悔药**」。
完整竞品分析（30+ 个 Java AI 项目 + 15 个 Java 后端方向）见 [`docs/research/03`](docs/research/03-竞品调研与饱和度分析.md)。

**定位纪律**：✅ 自称「**数据面治理组件**」（治理动作挂在 ModelClient / VectorStore / ToolExecutor 三条 SPI 切面，
不侵入业务模块）；❌ 不自称「平台 / 中台」，❌ 也不称「网关」（那会激活「多模型接入 / 协议转换 / 密钥管网运营」预期），
❌ 不做通用 LLM 网关，❌ 不写竞品 star 数（[PRD §15](docs/PRD.md)）。

---

## 技术栈（版本号以 `pom.xml` 为准）

> 版本号的**事实源**：Java / Spring Boot / LangChain4j / MCP SDK / Testcontainers 由 [`pom.xml`](pom.xml) 锁定；
> PostgreSQL / Redis / Kafka / MinIO 由 [`docker-compose.yml`](docker-compose.yml) 的镜像 tag 锁定。
> **每一行为什么这么选，见 [PRD §7.1 / §7.2](docs/PRD.md) 与 [`docs/adr/`](docs/adr/README.md)，本页不复制理由。**

| 层 | 选型 |
|---|---|
| 语言 / 框架 | **Java 21** · **Spring Boot 4.1.1** · Spring WebMVC（非 WebFlux）+ 虚拟线程 |
| AI 层 | **LangChain4j** 核心件 `1.21.0` + SB4 starter 线 `1.21.0-beta31`（自研 SPI 屏蔽，业务代码不直接依赖） |
| 存储 | **PostgreSQL 16 + pgvector（HNSW）**（与业务表同库事务）· `tsvector` 全文检索 · **Redis 7** · MinIO |
| 消息 | **Kafka（KRaft 单节点）** |
| 可观测性 | **Micrometer + OpenTelemetry**（W3C Trace Context，OTLP 导出）；指标走 Prometheus 端点；Tempo / Grafana 未进 compose、未实跑 |
| 前端 | **Vue 3 + TypeScript + Vite + Element Plus**（`aiwarden-web/`，**独立 pnpm 工程，不经 Maven 构建**） |
| 测试 / 迁移 / 部署 | JUnit 5 + Mockito + Testcontainers + ArchUnit · Flyway · **Docker Compose（主形态）**；K8s/Helm 为可选实验、**未做**（裁决 27） |

> 🚫 **版本红线**（不得说 Spring Boot 3.5 / 4.0，不得用 LangChain4j 1.19.1）：EOL 数据、`-betaNN` 是什么意思、
> 以及实测命令的唯一正文见 [PRD §7.2](docs/PRD.md) 与 [`ADR-001`](docs/adr/ADR-001-bootstrap-versions.md)。

---

## 本地怎么跑起来

```bash
# 1) 基础设施（PostgreSQL+pgvector / Redis / Kafka / MinIO）——忘了这步，应用启动必失败
docker compose up -d && docker compose ps      # 确认 postgres 是 Up (healthy)

# 2) 建库并首次插入一个租户（Flyway 随应用启动自动迁移；本仓库不提供开户接口）
#    这个租户 id 就是后续请求头 X-Aiwarden-Tenant-Id 的值：
docker exec aiwarden-postgres psql -U aiwarden -d aiwarden \
  -c "INSERT INTO t_tenant (name) VALUES ('demo-tenant') ON CONFLICT (name) DO NOTHING;"

# 3) 把各模块装进本地仓库（首次、或改了兄弟模块后执行一次）
mvn -B -ntp -DskipTests install

# 4) 启动后端（port 8080）——Windows 本机必须带 local profile
mvn -B -ntp -pl aiwarden-start spring-boot:run -Dspring-boot.run.profiles=local

# 5) 前端（port 5173；/api 与 /actuator 经 Vite 代理到 8080）
cd aiwarden-web && pnpm install && pnpm dev
```

- **用 IDE 启动**（IntelliJ / VS Code / Qoder）：直接运行 `com.aiwarden.start.AiwardenApplication#main`，
  **不需要第 3/4 步**（IDE 用自身模块 classpath，天然绕开 Maven reactor 问题）。仓库自带
  [`.vscode/launch.json`](.vscode/launch.json)，前提是 `JAVA_HOME` 指向 JDK 21；IntelliJ 在 **Active profiles** 填 `local`。
  两个硬前提不变：**基础设施已起** + **Windows 本机带 profile `local`**。
- **跑测试**：`mvn -B -ntp verify` 需要 Docker（Testcontainers 起真实 PostgreSQL / Kafka）。
- **身份头**：后端所有接口要求 `X-Aiwarden-Tenant-Id` / `X-Aiwarden-User-Id`（缺失即 400，**不回落默认租户**），
  org 头可选；前端在页头「身份」抽屉里配置。细节见 [`aiwarden-web/README.md`](aiwarden-web/README.md)。
- **跑不起来、或跑起来「不报错但少数据」** → 看 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)
  （本机环境类问题的唯一正文：症状 → 判据 → 处置，含端口保留、库没起、旧实例占端口、前端只 bind IPv6 等）。
  写代码踩到「不报错但不生效」的机制坑 → [`docs/PITFALLS.md`](docs/PITFALLS.md)。

---

## 文档地图

> 完整地图与「活文档 vs 冻结资产」的职责边界正文在 [`docs/README.md`](docs/README.md)；这里只留外部读者最常问的六条。

| 我想… | 看这里 |
|---|---|
| 知道**要做什么、验收标准** | [`docs/PRD.md`](docs/PRD.md)（需求唯一权威源，不存实测数字） |
| 知道**当前进度与实测数字** | [`docs/STATUS.md`](docs/STATUS.md)（数字的唯一落点；本页结论表是其投影） |
| 改代码前**守哪些铁律、踩过什么坑** | [`AGENTS.md`](AGENTS.md) + [`docs/PITFALLS.md`](docs/PITFALLS.md) |
| 本机**跑不起来 / 跑起来少数据** | [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md) |
| 某个技术决策**为什么这么选** | [`docs/adr/`](docs/adr/README.md)（一文件一 ADR，索引在该页） |
| 这个项目**承认自己失败在哪** | [`docs/FAILURE.md`](docs/FAILURE.md)（失败索引，定义见 PRD §8.1） |

> **内部材料说明**（公开范围）：唯一落点是 [`docs/STATUS.md`](docs/STATUS.md) §5——本仓库为公开版，编号 01 / 02
> 的前期调研、3 份**原始调研报告**（`inbox/`，其中 2 份是 `docs/research/03` 点名的取证底稿，**不可删**）与个人规划类材料
> **未随仓库公开**；正文对它们的引用**保留文字叙述、不提供链接**，以免死链。
> （`docs/research/03` 里「见根 README『内部材料说明』」这句指向的就是本段——**节名是锚点，别改**。）

**调研方法声明（诚实边界）**：调研时间 2026-10；竞品 star 数据来自第三方索引站，**同一项目多口径时并列保留、
不取平均**，查不到权威口径的标注「未查证」，且**这些数字只出现在调研文档里，本页与对外材料一律不写竞品 star 数**。
本环境 `web_fetch` 无法直连 github.com，因此**未做源码级核验**；已发现第三方索引站会编造技术栈，
**所有技术事实以官方文档为准**（方法与证据见 [`docs/research/03`](docs/research/03-竞品调研与饱和度分析.md) §4.3；序列索引见 [`docs/research/README.md`](docs/research/README.md)）。

---

## License

MIT
