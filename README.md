# AIWarden

> **多租户 AI 数据与用量治理层**（形态：**数据面治理组件**）
> 把大模型的**不确定性**（删不干净、越权、重复副作用、用量失控、长任务中断）关进 Java 的**确定性**里。

[![Status](https://img.shields.io/badge/status-M3%20%E5%AE%8C%E6%88%90-brightgreen)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)]()

> **当前阶段：M3 完成**（M2 的 MVP 基线 + M3 编排内核 / 评测门禁 / Vue 前后端全部落地）。
> **M0+M1+M2 = MVP，P1 / P2 / P3 / P4a 四项承诺成立**；P4b 已砍（裁决 19）、P5 归 M4。
> 逐切片交付清单与**实测数字**见 [`docs/STATUS.md`](docs/STATUS.md) §1 / §2，里程碑定义见
> [`docs/PRD.md`](docs/PRD.md) §8——**本页不复制进度描述**（README 每次里程碑重写是本仓库文档之乱的旧形态）。

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

> 本表是 [`docs/STATUS.md`](docs/STATUS.md) §2 结论表的**对外投影**——数字只在 STATUS 更新，这里同步复制；
> 每行「该填什么、目标线是多少」的验收口径见 [`docs/PRD.md`](docs/PRD.md) §12。
> 机器规格统一写法：**`i7-7700（4 物理核 / 8 逻辑核）+ 32GB`**。`没做的写「待验证」，不写漂亮的 99%`。

| 承诺 | 指标 | 结果 |
|---|---|---|
| P1 删除即失效 | 删除生效 **P95**（目标 ≤ 5s）/ 超窗残留进不一致清单的条数 | 自动化用例 **5 轮全 ≤ 5s**（单次断言口径）；P95 分位数度量待 M4 压测；超窗残留由对账发现（M1 用例已证） |
| P2 检索层隔离 | 越权样本拦截率（20 条） | **100%**（16 条检索类 + 4 条工具类，随 `mvn verify` 门禁） |
| P3 副作用幂等 | 断网重放后工单数（期望 1） | **1**（自动化用例：重放复用首见结果；`t_ticket` 唯一约束兜底） |
| P4a 用量可归因 | 四维归因覆盖率 / 配额检查（预扣减 / 幂等）正确性 | 工具链路四维齐备（租户/会话/步骤/工具）；预扣减幂等 / 并发不超卖 / 超限 429 / 对账差异 0（自动化用例）。**注**：模型调用维度的四维归因依赖 M4 的 OTel span，当前仅工具链路 |
| P4b 预算降级 | 两档降级触发正确性 | **已砍**（2026-10 裁决 19）；地基已就位，重启属新范围 |
| P5 长任务续跑 | `kill -9` 后续跑成功率 / 副作用重复数 | 待验证（M4） |
| 评测 | 样本通过率 / P95 延迟 / 单次成本 | **24/24 全绿**、风险样本拦截 **7/7**、重复建单 **0**、P95 **52ms**、单次成本 **0.000509 元**（Mock 替身 + 真实治理管道；**演示单价口径**。逐轮抖动与 nearest-rank 口径见 [`docs/STATUS.md`](docs/STATUS.md) §2） |
| 治理税 | 逐环节开销四项 / 每请求治理总开销 / 吞吐拐点 / P99 | 四项已实测（P95）：可见集计算 **3.2ms** / filter 下推 **7.1ms** / 计量事件 **0.9ms** / 配额检查 **4.3ms**；**审计留痕开销**与总开销 / 吞吐拐点 / P99 待 M4 压测 |
| 测试 | `mvn -B -ntp verify` 全绿测试数 | **127**（0 失败 0 跳过；前端 `aiwarden-web` 走独立 `pnpm build` 口径，不计入——见 [`docs/STATUS.md`](docs/STATUS.md) §2） |

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
> **每一行为什么这么选（选型理由与答辩依据）见 [`docs/PRD.md`](docs/PRD.md) §7.1 / §7.2，本页不复制理由。**

| 层 | 选型 |
|---|---|
| 语言 / 框架 | **Java 21** · **Spring Boot 4.1.1** · Spring WebMVC（非 WebFlux）+ 虚拟线程 |
| AI 层 | **LangChain4j** 核心件 `1.21.0` + SB4 starter 线 `1.21.0-beta31`（自研 SPI 屏蔽，业务代码不直接依赖） |
| 存储 | **PostgreSQL 16 + pgvector（HNSW）**（与业务表同库事务）· `tsvector` 全文检索 · **Redis 7** · MinIO |
| 消息 | **Kafka（KRaft 单节点）** |
| 可观测性 | **Micrometer + OpenTelemetry** + Prometheus / Grafana / Tempo / Loki |
| 前端 | **Vue 3 + TypeScript + Vite + Element Plus**（`aiwarden-web/`，路由级切分 `/chat` C 端 + `/admin` B 端三页；**独立 pnpm 工程，不经 Maven 构建、不进 `mvn verify`**） |
| 测试 / 迁移 / 部署 | JUnit 5 + Mockito + Testcontainers + ArchUnit · Flyway · **Docker Compose（主形态）**；K8s/Helm + HPA 为 M4 可选实验，非交付承诺 |

> 🚫 **版本红线**：不得说 Spring Boot 3.5（OSS 已于 2026-06-30 EOL）、不得说 Spring Boot 4.0（OSS 止于 2026-12-31）、
> 不得用 LangChain4j 1.19.1（官方标记 *published in error, do not use*）。当前底座 = **SB 4.1.1 + LangChain4j 1.21.0 / starter `1.21.0-beta31`**。
>
> **为什么不写成 `1.21.0`**：`-betaNN` 是 LangChain4j 对 starter / 集成模块的发布惯例，不代表 API 不稳定；
> 核心件与 starter 的版本号形态不同、必须分别书写。**完整依据、EOL 数据与实测命令的唯一正文见
> [`docs/PRD.md`](docs/PRD.md) §7.2 与 [`ADR-001`](docs/adr/ADR-001-bootstrap-versions.md)。**

---

## 本地怎么跑起来

```bash
# 1) 基础设施（PostgreSQL+pgvector / Redis / Kafka / MinIO）——忘了这步，应用启动必失败
docker compose up -d
docker compose ps                 # 确认 postgres 是 Up (healthy)

# 2) 建库并首次插入一个租户（Flyway 随应用启动自动迁移；本仓库不提供开户接口）
#    首次启动后执行（租户 id 会成为后续请求头 X-Aiwarden-Tenant-Id 的值）：
docker exec aiwarden-postgres psql -U aiwarden -d aiwarden \
  -c "INSERT INTO t_tenant (name) VALUES ('demo-tenant') ON CONFLICT (name) DO NOTHING;"

# 3) 把各模块装进本地仓库（只需在首次、或改了兄弟模块后执行一次）
mvn -B -ntp -DskipTests install

# 4) 启动后端（port 8080）——`spring-boot:run` 会现场编译 aiwarden-start，改代码不必重新 install
#    Windows 本机必须带 local profile（原因见 docs/dev/troubleshooting.md 第 2 条）
mvn -B -ntp -pl aiwarden-start spring-boot:run -Dspring-boot.run.profiles=local

# 5) 前端（port 5173；/api 与 /actuator 经 Vite 代理到 8080）
cd aiwarden-web && pnpm install && pnpm dev
```

> **用 IDE 启动**（IntelliJ / VS Code / Qoder）：直接运行 `com.aiwarden.start.AiwardenApplication#main` 即可，
> **不需要第 3/4 两步**——IDE 会用自身配置的模块 classpath 编译并启动，天然绕开 Maven reactor 问题。
> 仓库已带 [`.vscode/launch.json`](.vscode/launch.json)（装 "Extension Pack for Java" 后在「运行和调试」里选
> **「AIWarden 后端（aiwarden-start，profile=local）」** 直接 F5；文件里 `javaExec` 是本机 JDK 21 的实测路径，换机器请改成自己的）。
> IntelliJ 则在 Run/Debug Configuration 的 **Active profiles** 填 `local`。
> **两个硬前提**：① 基础设施必须已起；② **Windows 本机必须加 profile `local`**。
>
> ### ⚠️ 跑不起来、或跑起来「不报错但少数据」→ 看 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)
> 那里是**本机环境类问题的唯一正文**（症状 → 判据 → 处置）。实测过的五条：
> ① `spring-boot:run` 为什么**不能**写成 `-pl aiwarden-start -am`（三种死法）+ 改了兄弟模块必须重跑 `install`
> （否则跑到旧 jar，而 `target/classes` 里新 class 明明在）；
> ② `Connection to localhost:5432 refused` = **库没起**，不是代码问题（异常链会伪装成装配故障）；
> ③ `Port 8080 was already in use` = 上一次启动的进程没退；
> ④ **Windows 把 TCP 9092 划进了保留端口区间** → Kafka 静默不监听、outbox 全卡 `PENDING`、用量看板恒为空
> ——这正是本机必须带 profile `local`（走 `kafka-proxy` 顶到 29092）的原因；
> ⑤ B 端「评测报告」页在本地开发库**必为 404 且属预期**（`t_eval_report` 只存在于 Testcontainers 的临时库）。
>
> **跑测试**：`mvn -B -ntp verify` 需要 Docker（Testcontainers 起真实 PostgreSQL / Kafka）。
> 本机若遇 `Could not find a valid Docker environment`，用 [`AGENTS.md`](AGENTS.md) §4 记录的 npipe 绕行命令。
>
> **身份头**：后端所有接口要求 `X-Aiwarden-Tenant-Id` / `X-Aiwarden-User-Id`（缺失即 400，**不回落默认租户**），
> org 头可选。前端在页头「身份」抽屉里配置（存 `localStorage`）。细节见 [`aiwarden-web/README.md`](aiwarden-web/README.md)。

---

## 文档地图

> **索引正文在 [`docs/README.md`](docs/README.md)**（活文档 vs 冻结资产的职责边界 + 各子目录入口）。
> 本表只留外部读者最常要的六条，不再维护第二份完整地图。

| 我想… | 看这里 |
|---|---|
| 知道**要做什么、验收标准是什么** | [`docs/PRD.md`](docs/PRD.md)（需求唯一权威源；**不存实测数字**） |
| 知道**当前进度与已验证的实测数字** | [`docs/STATUS.md`](docs/STATUS.md)（**数字的唯一落点**；本页结论表是它的对外投影） |
| 改代码前，知道**要守哪些铁律与坑** | [`AGENTS.md`](AGENTS.md)（编码代理协作约定；随代码生长） |
| 本机**跑不起来 / 跑起来少数据** | [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)（环境类排障唯一正文） |
| 知道**某个技术决策为什么这么选** | [`docs/adr/`](docs/adr/README.md)（ADR-001 ~ 012，一文件一 ADR；被演进的原条目不改写） |
| 知道**选题论证 / 竞品 / 裁决过程** | [`docs/research/`](docs/research/README.md)（03 竞品 · 04 选题 · 05 交叉质询 · 06 裁决清单） |
| 看这个项目**承认自己失败在哪** | [`docs/FAILURE.md`](docs/FAILURE.md)（失败索引，定义见 [`docs/PRD.md`](docs/PRD.md) §8.1） |

> **内部材料说明**（公开范围）：唯一落点是 [`docs/STATUS.md`](docs/STATUS.md) §5——本仓库为公开版，编号 01 / 02
> 的前期调研、3 份**原始调研报告**（`inbox/`，是 `docs/research/03` 点名的取证底稿，**不可删**）与个人规划类材料
> （含原 PRD §14 的对外话术）**未随仓库公开**；正文对它们的引用**保留文字叙述、不提供链接**，以免死链。
> （`docs/research/03` 里「见根 README『内部材料说明』」这句指向的就是本段——节名是锚点，别改。）

---

## 调研方法声明（诚实边界）

- **调研时间**：2026-10；竞品 **star 数据**来自第三方仓库索引站（ungh.cc / repos.ecosyste.ms / Gitee OpenAPI v5 / PickGithub / relatedrepos / mygit.top / star-history），**同一项目多口径时并列保留，不取平均**；查不到权威口径的一律标注「未查证」。**这些数据只在调研文档内引用，README 与对外材料一律不写竞品 star 数。**
- 本环境 `web_fetch` **无法直连 github.com / raw.githubusercontent.com**（DNS 解析到非公网 IP 被拒），因此**未做源码级核验**，结论来自官方文档站 + 索引站 + 中文技术社区交叉取证。
- 已发现第三方 AI 索引站会编造技术栈（DEV.co 的 ragent 页与官方模块文档直接矛盾），**所有技术事实以官方文档为准**。
- **本文档与 PRD 均为开工前产物**，与完结后回写的内容会明确区分。

---

## License

MIT
