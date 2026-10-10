# AIWarden

> **多租户 AI 数据与用量治理层**（形态：**数据面治理组件**）
> 把大模型的**不确定性**（删不干净、越权、重复副作用、用量失控、长任务中断）关进 Java 的**确定性**里。

[![Status](https://img.shields.io/badge/status-M3%20%E5%AE%8C%E6%88%90-brightgreen)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)]()

> ⚠️ **当前仓库状态：M2 完成（MVP 达成）+ M3 完成**（2026-10，Java 侧本地 `mvn -B -ntp verify` **112 测试全绿**；前端 `aiwarden-web` 独立构建通过；GitHub Actions 已在推送 / PR 上触发，状态以流水线为准）。
> **M0+M1+M2 = MVP，P1 / P2 / P3 / P4a 四项承诺成立**（见下方结论表）——除 M1 已交付的删除即失效 / Outbox→对账 / 计量外，新增：
> ① 可见集四级下推 + 越权样本门禁 **20/20（拦截率 100%）**；② 工具副作用治理（断网重放**工单数 == 1** + 补偿逆序 + 二态审批）；
> ③ 配额强一致（Redis Lua 预扣减/幂等/对账，超限 429）；④ 审计检索 / 用量明细 / 账单对账接口；
> ⑤ **MCP 最小版**（2 个内置工具经 Streamable HTTP 暴露，复用 P3 管道）；⑥ **四项治理税开销实测**（见结论表）。
>
> **M3 已交付**：① 问答编排内核（`ModelClient` SPI + Mock 替身 + 录制回放双轨 + SSE 七类事件）；
> ② 评测门禁 **24 条全绿**（通过率 24/24、风险样本拦截 7/7、重复建单 0、P95 52ms、单次成本 0.000509 元）；
> ③④ Vue 3 前后端 **`aiwarden-web`**（C 端对话/引用/时间线/成本 + B 端一致性/用量/评测三页，独立 pnpm 工程）。
> 并已用**真实 HTTP 端到端联调**验证：SSE 帧序、引用溯源、human_handoff → approve、断网重放 `replayed=true`
> 且工单数 == 1、跨租户 deny + 审计留痕。P4b 已砍（裁决 19）/ P5 归 M4，进度以 [`docs/PRD.md`](docs/PRD.md) §8 为准。

---

## 一句话说清这个项目做什么

AIWarden 是一个**面向 Java 技术栈的 AI 应用数据面治理组件**。它不做「又一个能聊天的 RAG 平台」，只做几件**能被验证**的事：

| # | 承诺 | 验收方式 |
|---|---|---|
| **P1** | **删除即失效** —— 删除一份文档后，它在向量与全文索引中的痕迹在 **P95 ≤ 5s**内消失；**超出窗口的残留不是被放过，而是必须出现在对账报告的「不一致清单」里** | 删除后 5s 的检索断言（**断言 P95，不是单次**）+ `aiwarden_vector_orphan_total` 指标 |
| **P2** | **检索层隔离** —— 租户/组织/知识库/文档四级权限在**检索前**算成可见集，下推到向量 metadata filter；越权查询 **0 命中** | **20 条越权样本进 CI**，断言拦截率 **100%** |
| **P3** | **副作用幂等** —— 写操作工具带幂等键；失败走**补偿**而非重试；断网重放**不产生第二张工单** | 故障注入用例「模型已决定建单、工具未返回时断开通道」+ 数据库终态断言（工单数 == 1）——**主演示场景** |
| **P4a** | **用量可归因 + 配额强一致** —— Token 与耗时按**租户/会话/步骤/工具**四维归因；租户级配额**预扣减 + 幂等 + 对账**，超限直接 429；审计日志不可变留痕 | Prometheus 指标 + 配额检查单测 |
| **P4b** | **预算决定推理档位**（**已决议砍除**，2026-10；地基已就位，重启属新范围）—— 配额检查 → 超限 429 / 切换小模型（**两档**，不做四级链，不叫「优先级调度」） | （已砍，不适用） |
| **P5** | **长任务可续跑** —— Agent 执行持久化为状态机；进程重启/超时/人工中断后从最近检查点续跑，**重放不产生重复副作用** | `kill -9` 重启后续跑用例 + 副作用计数断言 |

**两条边界声明**（有意写在第一屏，主动交代边界）：

- **B1 治理数据面**：本项目治理数据面（一致性 / 检索权限 / 副作用 / 用量配额），**不承诺检索质量**（召回率 / 引用准确率不在承诺范围）。
- **B2 一致性有 SLO**：一致性是**有 SLO 的可对账**（延迟有界、不一致可查询可修复），**不是绝对一致**。

### 结论表（可复现，不是形容词）

> 本表为**实测值**（M2 已回填 P1–P4a 与治理税四项）；`没做的写「待验证」，不写漂亮的 99%`。

| 承诺 | 指标 | 结果 |
|---|---|---|
| P1 删除即失效 | 删除生效 **P95**（目标 ≤ 5s）/ 超窗残留进不一致清单的条数 | 自动化用例 **5 轮全 ≤ 5s**（单次断言口径）；P95 分位数度量待 M4 压测；超窗残留由对账发现（M1 用例已证） |
| P2 检索层隔离 | 越权样本拦截率（20 条） | **100%**（16 条检索类 + 4 条工具类，随 `mvn verify` 门禁） |
| P3 副作用幂等 | 断网重放后工单数（期望 1） | **1**（自动化用例：重放复用首见结果；`t_ticket` 唯一约束兜底） |
| P4a 用量可归因 | 四维归因覆盖率 / 配额检查（预扣减 / 幂等）正确性 | 工具链路四维齐备（租户/会话/步骤/工具）；预扣减幂等 / 并发不超卖 / 超限 429 / 对账差异 0（自动化用例）。**注**：模型调用维度的四维归因依赖 M4 的 OTel span，当前仅工具链路 |
| P4b 预算降级 | 两档降级触发正确性 | **已砍**（2026-10 裁决 19）；地基已就位，重启属新范围 |
| P5 长任务续跑 | `kill -9` 后续跑成功率 / 副作用重复数 | 待验证（M4） |
| 评测 | 20–30 条样本通过率 / P95 延迟 / 单次成本 | **24 条全绿**：通过率 **24/24**、风险样本拦截 **7/7**、重复建单 **0**、P95 **52ms**、单次成本 **0.000509 元**（Mock 替身 + 真实治理管道；演示单价口径，本机 i7-7700 4C8T/32GB。**P95 逐轮抖动**：首轮 44ms / 本轮 52ms，样本量 24） |
| 治理税 | 逐环节开销 / 每请求治理总开销 / 吞吐拐点 / P99（**8 核 32G 单机**） | **M2 四项已实测**（i7-7700 4 物理核/8 逻辑核 + 32GB，warmup 50 + 采样 200，P95）：可见集计算 **3.2ms** / filter 下推 **7.1ms** / 计量事件 **0.9ms** / 配额检查 **4.3ms**；**审计留痕开销**与总开销 / 吞吐拐点 / P99 待 M4 压测 |

---

## 有了 Ragent / RuoYi AI，为什么还要这个项目？

| 问题 | 它们的现状 | AIWarden 的做法 |
|---|---|---|
| 删文档后向量什么时候消失？ | Ragent 作者专文**承认有窗口**，解法锁进付费墙（**一手核验，可作支柱论据**） | 同事务 Outbox → Kafka → 幂等删向量 → 补偿（指数退避）→ 死信 → 5 分钟对账兜底 |
| 谁越权检索到了什么？ | **第三方实践与社区分析指出** RuoYi 系 MyBatis 拦截器方案在 AI 场景存在绕过面 | 检索前算可见集并下推 filter + **20 条越权样本进 CI 门禁** |
| 重试会不会建两张单？ | Spring AI / LangChain4j **原生无 Checkpoint**（Checkpoint 是 LangGraph 概念） | 工具调用幂等键 + 补偿日志 + 工具级**二态审批开关** |
| 用量花在哪个租户的哪一步？ | 网关只到**请求级**；应用侧熔断依据是**健康度**不是用量 | **四维归因** + 租户预算决定推理档位（**两档**，P4b 可选） |
| 改一行 Prompt 会不会变差？ | 竞品只有**评测教程章节**，没有可执行门禁 | **24 条样本进 `mvn verify`**（Mock 替身 + 录制回放双轨，断言治理管道对模型决策的约束 + 数据库终态） |

**一句话差异化**：别人在做「AI 能聊天」，本项目在做「**AI 的账、AI 的门、AI 的后悔药**」。

> 完整竞品分析（30+ 个 Java AI 项目 + 15 个 Java 后端方向饱和度）见 [`docs/research/03-竞品调研与饱和度分析.md`](docs/research/03-竞品调研与饱和度分析.md)。

---

## 定位纪律

- ✅ 自称「**数据面治理组件**」——在 ModelClient / VectorStore / ToolExecutor 三条 SPI 边界上做治理，治理动作挂在 SPI 切面，不侵入业务模块
- ❌ **不自称「平台 / 中台」**
- ❌ **也不称「网关」**——网关形态会激活「多模型接入 / 协议转换 / 密钥管网运营」预期，而那是 Go / Envoy 的主场；在治理的比较框架里比较，才站得住
- ❌ **不做通用 LLM 网关**
- ❌ **不写竞品 star 数**（见 PRD §15 文档纪律）

---

## 技术栈（版本号以 `pom.xml` 为准）

> **版本号的事实源**：Java / Spring Boot / LangChain4j / MCP SDK / Testcontainers 由 [`pom.xml`](pom.xml) 锁定；
> PostgreSQL / Redis / Kafka / MinIO 由 [`docker-compose.yml`](docker-compose.yml) 的**镜像 tag** 锁定（不在 pom 内）。

| 层 | 选型 |
|---|---|
| 语言 | **Java 21**（虚拟线程承载 LLM 长阻塞 IO；`ReentrantLock` 规避 JDK 21 pinning） |
| Web 层 | **Spring WebMVC（非 WebFlux）+ 虚拟线程**（`spring.threads.virtual.enabled=true`） |
| 框架 | **Spring Boot 4.1.1**（OSS 支持至 2027-07-31） |
| AI 层 | **LangChain4j 核心件 `1.21.0` + SB4 starter 线 `1.21.0-beta31`**（框架中立；用自研 SPI 屏蔽，业务代码不直接依赖） |
| 向量/关系库 | **PostgreSQL 16 + pgvector（HNSW）**——与业务表同库事务，少一个分布式组件 |
| 全文检索 | PostgreSQL `tsvector`（倾向）或 ES，二选一，必须支持单路降级 |
| 消息 | **Kafka（KRaft 单节点）** |
| 缓存/限流 | **Redis 7**（配额预扣减 Lua + 租户级限流） |
| 可观测性 | **Micrometer + OpenTelemetry** + Prometheus + Grafana + Tempo + Loki |
| 部署 | **Docker Compose（主形态）**；K8s/Helm + HPA 为 **M4 可选实验**，非交付承诺 |
| 前端 | **Vue 3 + TypeScript + Vite + Element Plus**（`aiwarden-web/` 单仓库，路由级切分 `/chat` C 端 + `/admin` B 端三页；SSE 走 fetch + ReadableStream 手工解析——`EventSource` 不能发 POST）——**M3 交付**，独立 pnpm 工程，**不经 Maven 构建、不进 `mvn verify`** |
| 测试 | JUnit 5 + Mockito + Testcontainers + ArchUnit |
| 迁移 | Flyway |

> 🚫 **版本红线**
> **不得说 Spring Boot 3.5**（OSS 已于 2026-06-30 EOL，末版 3.5.16）；**不得说 Spring Boot 4.0**（OSS 支持止于 2026-12-31）；**不得用 LangChain4j 1.19.1**（官方标记 *published in error, do not use*）。
>
> ✅ **正确表述**：「底座选 **Spring Boot 4.1.1** + **LangChain4j 1.21.0**（starter 线 `1.21.0-beta31`）——LangChain4j 自 1.13.0（2026-04）起官方适配 SB4 并设独立 `spring-boot4-starter` 线，**『迁移断裂』的前提已不存在**；反过来，SB 3.5 已 EOL，把项目建在断供线上才是红线。」
>
> ⚠️ **starter 为什么是 beta 版号**：核心件是 `1.21.0`，而 starter 模块是 `1.21.0-beta31`——这是 LangChain4j 对 **starter / 集成模块**的发布惯例（SB3 与 SB4 两条 starter 线同为 `-betaNN` 序列，最新版号一致），**不代表 API 不稳定**。两处版本号形态不同，必须分别书写。依据与实测见 [`ADR-001`](docs/adr/ADR-001-bootstrap-versions.md)。

---

## 本地怎么跑起来

```bash
# 1) 基础设施（PostgreSQL+pgvector / Redis / Kafka / MinIO）
docker compose up -d

# 2) 建库并首次插入一个租户（Flyway 随应用启动自动迁移；本仓库不提供开户接口）
#    首次启动后执行（租户 id 会成为后续请求头 X-Aiwarden-Tenant-Id 的值）：
docker exec aiwarden-postgres psql -U aiwarden -d aiwarden \
  -c "INSERT INTO t_tenant (name) VALUES ('demo-tenant') ON CONFLICT (name) DO NOTHING;"

# 3) 把各模块装进本地仓库（只需在首次、或改了兄弟模块后执行一次）
mvn -B -ntp -DskipTests install

# 4) 启动后端（port 8080）——`spring-boot:run` 会现场编译 aiwarden-start，改代码不必重新 install
mvn -B -ntp -pl aiwarden-start spring-boot:run

# 5) 前端（port 5173；/api 与 /actuator 经 Vite 代理到 8080）
cd aiwarden-web && pnpm install && pnpm dev
```

> ### ⚠️ 为什么不能写成 `mvn -pl aiwarden-start -am spring-boot:run`（**本项目实测踩坑**）
> 从仓库根这样跑必定失败，两种错法各有原因：
> - **带 `-am`**：`-am` 会把兄弟模块与**根聚合工程**一并放进 reactor，而 reactor 里**每个模块都会执行该 goal**——
>   根 pom 没有 main class，于是报 `Unable to find a suitable main class`（报错项目名是 `aiwarden`，不是 `aiwarden-start`）。
> - **不带 `-am`**：兄弟模块的 `0.1.0-SNAPSHOT` 不在本地仓库 → `Could not resolve dependencies`。
>
> 另外**在子模块目录里跑 `-am` 也没用**：Maven 只有「从根跑的聚合构建」才有完整 reactor，
> 在 `aiwarden-start/` 目录下执行只会看到它自己。
> **推论：多模块工程的 `spring-boot:run` 是「先 install，再单模块 run」两步，不能一步到位。**
>
> **等效替代（不需要 install）**：`mvn -B -ntp -pl aiwarden-start -am -DskipTests package`
> 然后 `java -jar aiwarden-start/target/aiwarden-start-0.1.0-SNAPSHOT.jar`（改代码后需重新 package）。
>
> ### 用 IDE 启动（IntelliJ / VS Code 等）
> 直接运行 `com.aiwarden.start.AiwardenApplication#main` 即可，**不需要上面那两步 install/run**——
> IDE 会用自身配置的模块 classpath 编译并启动，天然绕开了 CLI 的 reactor 问题。
> **唯一的硬前提是基础设施必须先起来**：
> ```bash
> docker compose up -d          # ← 忘了这步，应用启动必失败
> docker compose ps             # 确认 postgres 是 Up (healthy)
> ```
> **高频误读：`Connection to localhost:5432 refused` 不是代码问题，是数据库没起。**
> 这个报错会包成一大段 `UnsatisfiedDependencyException → flywayInitializer → JdbcTemplate` 的 bean 链，
> 看起来像装配 / 依赖问题，**真正原因在异常链最底部**：`java.net.ConnectException: Connection refused: getsockopt`
> ——TCP 层就连不上（**不是**账号密码错，那种会是 `28P01` / `password authentication failed`）。
> 判据：`Test-NetConnection localhost -Port 5432`，或 `docker compose ps`。
>
> **身份头**：后端所有接口要求 `X-Aiwarden-Tenant-Id` / `X-Aiwarden-User-Id`（缺失即 400，**不回落默认租户**），
> org 头可选。前端在页头「身份」抽屉里配置（存 `localStorage`）。细节见 [`aiwarden-web/README.md`](aiwarden-web/README.md)。
>
> **跑测试**：`mvn -B -ntp verify` 需要 Docker（Testcontainers 起真实 PostgreSQL / Kafka）。
> 本机若遇 `Could not find a valid Docker environment`，用 [`AGENTS.md`](AGENTS.md) §4 记录的 npipe 绕行命令。
>
> **B 端「评测报告」页在本地开发库里初始是 404**（`{"detail":"暂无评测报告…"}`）——这是**预期行为不是故障**：
> `t_eval_report` 由评测门禁测试在 **Testcontainers 的临时数据库**里写入，测试结束容器即销毁，
> 因此本地开发库永远是空的。要看真实数据只有两条路：跑 `mvn verify` 读测试输出里的 `EVAL-SUMMARY`，
> 或自行把结论 INSERT 进本地库（表结构见 `V9__eval_report.sql`）。

---

## 文档地图

| 我想… | 看这里 |
|---|---|
| 知道**要做什么、验收标准是什么** | [`docs/PRD.md`](docs/PRD.md)（v2.2：立项版 + 13 条裁决回写 + M2 交付期裁决 14–18 + M3 交付期状态回写） |
| 改代码前，知道**要守哪些铁律与坑** | [`AGENTS.md`](AGENTS.md)（编码代理协作约定；随代码生长） |
| 知道**某个技术决策为什么这么选** | [`docs/adr/`](docs/adr/README.md)（**ADR-001 ~ ADR-012**，一文件一 ADR；含开工前与落地实测证据。被演进的原条目不改写，末尾追加修订段） |
| 知道**为什么选这个题、凭什么和竞品共存** | [`docs/research/04-选题论证与差异化声明.md`](docs/research/04-选题论证与差异化声明.md) |
| 知道**有哪些竞品、哪些方向已经饱和** | [`docs/research/03-竞品调研与饱和度分析.md`](docs/research/03-竞品调研与饱和度分析.md) |
| 知道**结论经不经得起质疑** | [`docs/research/05-独立验证与交叉质询报告.md`](docs/research/05-独立验证与交叉质询报告.md) |
| 知道**争议是怎么拍板的** | [`docs/research/06-PRD修订裁决清单.md`](docs/research/06-PRD修订裁决清单.md)（**裁决 1–13** 为开工前；**裁决 14–18** 为 M2 交付期；**裁决 19–21** 为 M2 收官 / M3 开工与收官期） |
| 看清**调研序列的编号与关系** | [`docs/research/`](docs/research/README.md)（索引：01–06 各在回答什么问题） |

> **公开范围**：本仓库为公开版。编号 01、02 的两份前期调研文档与原始调研报告（`research/inbox/`）属内部材料，未随仓库公开；正文中对它们的引用**保留文字叙述、不提供链接**，以免出现死链。

---

## 调研方法声明（诚实边界）

- **调研时间**：2026-10；竞品 **star 数据**来自第三方仓库索引站（ungh.cc / repos.ecosyste.ms / Gitee OpenAPI v5 / PickGithub / relatedrepos / mygit.top / star-history），**同一项目多口径时并列保留，不取平均**；查不到权威口径的一律标注「未查证」。**这些数据只在调研文档内引用，README 与对外材料一律不写竞品 star 数。**
- 本环境 `web_fetch` **无法直连 github.com / raw.githubusercontent.com**（DNS 解析到非公网 IP 被拒），因此**未做源码级核验**，结论来自官方文档站 + 索引站 + 中文技术社区交叉取证。
- 已发现第三方 AI 索引站会编造技术栈（DEV.co 的 ragent 页与官方模块文档直接矛盾），**所有技术事实以官方文档为准**。
- **本文档与 PRD 均为开工前产物**，与完结后回写的内容会明确区分。

---

## License

MIT
