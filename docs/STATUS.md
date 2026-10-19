# 项目状态与公开范围

> **这份文件是什么**：项目**当前状态**与**公开范围**的唯一落点。
> 从 [`AGENTS.md`](../AGENTS.md) §5 拆出（2026-10-07）——原因是**它的半衰期极短**：每次阶段推进都要改，
> 而 AGENTS.md 是编码代理的入口文档，不适合承载每周都过期的内容
> （AGENTS.md 文首自己立过规矩：不要在那里写会腐化的状态描述）。
>
> **本文件的性质**：**会随时间过期**，与 `docs/adr/`（只增不改的历史资产）相反。
> 更新纪律：阶段推进、承诺状态变化、公开范围调整时**同一次改动**更新本文件，
> 并按 [`AGENTS.md` §2](../AGENTS.md) 同步 PRD / README / ADR / 06 裁决清单。

---

## 1. 当前阶段

**M4 完成**（2026-10-19）——M3 的 MVP 基线 + M4 五块增量全部落地：

| 里程碑 | 切片 | 内容 |
|---|---|---|
| M2（已完成） | ① | P2 检索层隔离（可见集四级下推 + 20 条越权样本门禁） |
| M2（已完成） | ② | P3 工具副作用治理（幂等键 + 补偿 + 二态审批 + 故障注入用例） |
| M2（已完成） | ③ | P4a 配额强一致（Redis Lua 预扣减 / 幂等 / 对账，超限 429 + 审计留痕不可变） |
| M2（已完成） | ④ | 治理税四项输出 + **MCP 最小版**（2 个内置工具经 Streamable HTTP 暴露，复用 P3 管道）；审计检索 / 用量明细 / 账单对账读取接口 |
| M3（已完成） | ① | 问答编排内核（`ModelClient` SPI + Mock 替身 + 录制回放双轨 + SSE 七类事件） |
| M3（已完成） | ② | 评测门禁 24 条样本（引擎三件套 + 全栈驱动 + `t_eval_report` + 读取 API） |
| M3（已完成） | ③④ | Vue 3 前后端 `aiwarden-web`（C 端四要素 + B 端三页；独立 pnpm 工程） |
| M3（已完成） | ⑤ | 文档收尾回填（本文件 / PRD / README / 06 裁决 21 / ADR-012 修订段） |
| M4（已完成） | ① | **P5 断点续跑**：10 节点确定性 DAG + `t_agent_session/t_agent_step` 检查点 + 版本 CAS + 租约；`kill -9`/halt 后从检查点续跑、副作用不重复（[ADR-013](adr/ADR-013-agent-checkpoint-and-resume.md)） |
| M4（已完成） | ② | **OTel 全链路 Trace**：`http.chat → agent.run → 10 节点 → model/tool`，四类异步边界透传；`traceId` 落库 + 响应头 + 按 traceId 查询接口（[ADR-014](adr/ADR-014-observability-trace.md)） |
| M4（已完成） | ③ | **压测报告**：六项治理税（含审计留痕 / OTel span）+ 六档吞吐 / P99 / 拐点（[报告](benchmarks/M4-load-report.md)） |
| M4（已完成） | ④ | **`docs/FAILURE.md`** 增补 M4 条目并逐条核对既有指针 |
| M4（已完成） | ⑤ | **前端**：知识库 / 文档管理页；一致性页补「近 N 轮扫描」空态上下文 + 按 traceId 查步骤时间线 |

**M0–M4 完成；P1 / P2 / P3 / P4a / P5 五项承诺成立**（数据见 §2），M3/M4 两项硬交付
（评测门禁 + 前后端 / 续跑 + 可观测）均已达成。**P4b 已砍（裁决 19）**；
**K8s/Helm + HPA 可选实验未做（裁决 27，非交付承诺）**；Tempo / Loki 的端到端实跑未完成（§3）。

> **M4 收口后的同日审计修复（2026-10-19，裁决 28 / 29 / 30）**：全项目冒烟 + 验收审计抓出一个静默缺口并就地补代码——
> **对账 `mismatchCount` 第二项扩为「摄入停摆文档数」**（三条子判据按 `doc_id` 去重的并集）+
> **`repair` 增加「当前版本索引事件重驱动」**（响应契约加 `redrivenEvents`，前端一致性页同步换磁贴与停摆清单），
> 新增回归用例 `ConsistencyReconcileContainersTest` 摄入停摆判据段（测试数 166 → **167**）；同日第二轮审计
> 又补**删除事件/旧版本事件不重驱动 + 旧版本账本不误报** 三个回归，当前 **170**；
> 同时校正三处数字/判据口径（评测 P95 改抖动范围、压测拐点判据、单类复跑命令的必需 flag）。
> 依据：[ADR-006 修订段](adr/ADR-006-reconcile-readonly.md)、[AGENTS.md](../AGENTS.md) §4、
> [FAILURE.md](FAILURE.md) C/D 类。
里程碑定义见 [`docs/PRD.md`](PRD.md) §8（唯一权威源），
承诺结论表见本文件 §2（[`README.md`](../README.md) 第一屏是它的对外投影）。

## 2. 可复现的验证口径（**实测数字的唯一落点**）

> **本节是全仓库「已验证数字」的唯一正文**（测试数 / 评测门禁结论 / 治理税四项 / 机器规格 / 抖动说明）：
> 数字**只在这里更新**，[`README.md`](../README.md) 第一屏结论表与 [`docs/PRD.md`](PRD.md) §12 都只是它的投影或口径定义。
> 更新方向不可逆：**STATUS → README**，不要反向在 README 里改数字。口径见 [`AGENTS.md`](../AGENTS.md) §2。
>
> **机器规格的唯一写法**（对外材料引用时必须逐字一致，不要另造一种说法）：
> **`i7-7700（4 物理核 / 8 逻辑核）+ 32GB`**——即 PRD 裁决 13 所说「8 核 32G 单机」的实测口径。

### 结论表（真身）

> 每行**该填什么数字、目标线是多少**由 [`docs/PRD.md`](PRD.md) §12 定义（验收口径）；本表填**实测结果**。
> 诚实原则：没做的写「待验证」，不写漂亮的 99%。

| 承诺 | 指标 | 结果 |
|---|---|---|
| P1 删除即失效 | 删除生效 **P95**（目标 ≤ 5s）/ 超窗残留进入不一致清单的条数 | **P95 = 1975ms**（2026-10-19 补测：**30 轮**，含 relay 的 **2s 调度等待**，计时终点为「按原文检索 0 命中」；P50 **1870ms** / max **2076ms**，**30/30 < 5s**；两次独立运行 P95 = 1935 / 1975ms）；M1 的 **5 轮单次断言**用例保留为确定性回归门禁；超窗残留由对账发现（M1 用例已证）。**2026-10-19 审计修复：`mismatchCount` 的第二项由「账本超时」扩为「摄入停摆文档数」**（当前版本索引事件 `FAILED` / 当前版本账本 `PROCESSING` 超时 / 文档状态超时，三条子判据按 `doc_id` 去重；删除事件与旧版本事件不重驱动），现场差分为同库同数据「修复前 `mismatch_count=0` → 修复后 `1`」——依据见 [ADR-006 修订段](adr/ADR-006-reconcile-readonly.md)、裁决 28/30。复现命令见下 |
| P2 检索层隔离 | 越权样本拦截率（20 条） | **100%**（16 条检索类 + 4 条工具类，随 `mvn verify` 门禁） |
| P3 副作用幂等 | 断网重放后工单数（期望 1） | **1**（重放复用首见结果；`t_ticket` 唯一约束兜底） |
| P4a 用量可归因 + 配额强一致 | 四维归因覆盖率 / 配额检查（预扣减 / 幂等）正确性 | 工具链路四维齐备（租户/会话/步骤/工具）；预扣减幂等 / 并发不超卖 / 超限 429 / 对账差异 0。**M4 起模型调用维度也带 trace/step 上下文**（`t_llm_call_log.trace_id` + `cost` 回填），四维归因的「步骤」维度可由 traceId 串联 |
| P4b 预算降级 | 两档降级触发正确性 | **已砍**（2026-10 裁决 19）；地基已就位，重启属新范围 |
| P5 长任务续跑 | `kill -9` 后续跑成功率 / 副作用重复数 | **已达成（M4）**：崩在「tool 副作用之后、检查点之前」→ 续跑重做 tool 但幂等命中，`replayed=true`、工单数 **1**；崩在检查点之后 → 续跑复用快照、tool 步骤版本不变、不重做；**崩在 metering 之后、检查点之前** → 续跑重做计量，但 `dedupKey` 唯一键仲裁只落 **1** 条模型明细；**人工断连（ABORTED）后可续跑**（CAS 回 RUNNING）；租约未过期时续跑 409（不并行执行副作用）。证据见 [ADR-013](adr/ADR-013-agent-checkpoint-and-resume.md) |
| 评测 | 24 条样本通过率 / P95 延迟 / 单次成本 | **24/24 全绿**、风险样本拦截 **7/7**、重复建单 **0**、单次成本 **0.000869 元**（Mock 替身 + 真实治理管道；**演示单价口径**，非真实价目表——该值确定性可复算，2026-10-19 三次独立实测逐字一致）。**P95 不写单一结论值、只报同日抖动范围（66–224ms，3 次样本）**（裁决 29）：同日三次实测 **66 / 116 / 224ms**，历史轮次 23 / 44 / 52 / 58ms——门禁与 Testcontainers 容器、后端实例**同机**跑，P95 由同机负载主导，nearest-rank、样本量仅 24；**不要把任何单点 P95 当容量结论引用**，达标判定看「通过率 / 拦截率 / 重复建单」三项 |
| 治理税 | 逐环节开销（M2 四项 → M4 六项）/ 每请求总开销 / 可用并发上界 / P99 | M4 补齐六项（P95）：可见集 **2.7ms** / filter 下推 **5.9ms** / 计量事件 **0.7ms** / 配额检查 **3.9ms** / **审计留痕 1.5ms** / **OTel span 19µs**；六项 P50 相加约 **10.3ms/请求**；**可用并发上界 ≈ 4**（该档 ≈**70.9 RPS**、P99 **91.6ms**；C8/C16 约 1.6×/档恶化、C32 约 2.6×/档，C32 P99 **656.6ms**）。**措辞已按裁决 29 收紧**：拐点判据取「P99 抬升为主、RPS 平台为次」——C16 的 80.61 RPS **高于** C4，落在报告自陈的 10–30% 抖动带内，不足以据此上移拐点，故不写成「RPS 不再随并发增长」。逐项/逐轮数字与复现命令见 [压测报告](benchmarks/M4-load-report.md)（**单次采样，轮间抖动 10–30%**） |

### 复现口径（原始证据）

- 本地 `mvn -B -ntp verify`：**170 测试全绿**（0 失败 0 跳过）；
  **单次运行口径**（`clean` 后跑一次），机器 `i7-7700（4 物理核 / 8 逻辑核）+ 32GB`。
  其中 M2 收尾为 102 → M3 切片①② 净增 10（112）→ 收官后补测净增 15（**127**：
  `AdminIdentityBoundaryContainersTest` 13 + `MockModelClientTest` 3，扣除随口径调整的 1）
  → **Mock 改抽取式作答后净减 1（126**：`MockModelClientTest` 的「默认回答」与「内联标记」两个用例
  合并为一个「抽取式作答含片段原文与标记」用例，断言更严但方法数少 1）。
  - **M4 收尾计数（2026-10-19 上午，历史行保留不回改）**：`mvn -B -ntp verify` → **exit 0 / BUILD SUCCESS / 5:27 min**，
    三模块汇总 `aiwarden-common` **16** + `aiwarden-knowledge` **8** + `aiwarden-start` **142** = **166**，
    0 失败 0 跳过。增量（相对上轮 150）：M4 新增 **14**（checkpoint 4 / crash-resume 5 /
    trace 边界 4 / trace 全链路 1）+ 管理端点参数化 **2**（`AdminIdentityBoundaryContainersTest` 6 → 7 个端点）。
    上轮的「150」是 `16 + 8 + 126` 的合计。
  - **上轮审计修复计数（2026-10-19 下午，历史行保留不回改）**：`mvn -B -ntp verify` →
    **exit 0 / BUILD SUCCESS / 5:33 min**，`aiwarden-common` **16** + `aiwarden-knowledge` **8** +
    `aiwarden-start` **143** = **167**，0 失败 0 跳过。
    增量 **+1**：`ConsistencyReconcileContainersTest` 的摄入停摆判据用例（摄入停摆 → 去重 → 重驱动仍未收敛 →
    摄入完成才归零，裁决 28）。
  - **C1/C2/C3 修复轮计数（2026-10-19，接续上条；本轮唯一当前值）**：`mvn -B -ntp verify` →
    **exit 0 / BUILD SUCCESS / 5:40 min**，`aiwarden-common` **16** + `aiwarden-knowledge` **8** +
    `aiwarden-start` **146** = **170**，0 失败 0 跳过。
    增量 **+3**：删除事件 FAILED + 同名重传不重驱动 / 旧版本索引事件不重驱动 / 旧版本账本 `PROCESSING` 不误报
    （裁决 30）。**两个探针都不计入，但机制不同（照抄错会误导）**：
    `GovernanceLoadProbe` 被根 pom 的 `surefire.excludedGroups=load` 按 tag 排除；
    `P1DeleteSloProbe` 虽然带 `@Tag("slo")`，**但 `slo` 不在 excludedGroups 里**——它不被执行是因为
    **类名不以 `Test` 结尾**（surefire 默认 include 规则）。把它改名成 `*Test` 就会被计入并拖慢 verify。
  - **复现记录**：2026-10-10 本轮文档重划后在**同一工作树**上重跑 `mvn -B -ntp verify`（不带 `-o`，
    需要 Testcontainers 起真实 PostgreSQL / Kafka），**exit 0 / BUILD SUCCESS / 4:13 min**，
    reactor 三个有测试的模块各报 `Tests run` 汇总：`aiwarden-common` 16 + `aiwarden-knowledge` 8 +
    `aiwarden-start` 103 = **127**，0 失败 0 跳过——与本行口径一致，数字未变。
  - **Mock 改抽取式作答后的复现**（同日稍后）：同样 `mvn -B -ntp verify`，**exit 0 / BUILD SUCCESS**，
    三模块汇总 `16 + 8 + 126` = **150**（其中 start 由 103 降至 **102**，即上面那条净减 1），0 失败 0 跳过。
  - **数数口径（踩过一次，务必按此核）**：测试数只能取 **Maven 的模块汇总行**（`[INFO] Tests run: N`）；
    **不要**把每个测试类的逐行输出或 `target/surefire-reports/TEST-*.xml` 直接相加——聚合测试类会 fork
    多个 JVM，同名类会出现两行（如 `ArchitectureTest` 分两次输出；`@Disabled` 不计入），
    相加会**重复计数**（本轮实测相加得 151，而真值 127；一度据此误判文档有错，核了三遍才确认文档无误）。
- **评测门禁的原始输出**（`ChatEvalGateContainersTest` 单类复跑，2026-10-10，**Mock 改抽取式作答之后**）：
  `total=24 passed=24 denyBlocked=7/7 duplicateTickets=0 p95=23ms avgCost=0.000869`
  ——**逐字照抄，不再用散文复述一遍**；口径与抖动说明见上面结论表的「评测」行。
  - **2026-10-19 全项目审计的三次复跑**（干净 clone + 本机）：`p95=116ms`、`p95=224ms`（单类安静复跑）、
    `p95=66ms`（`mvn verify` 全量内），`avgCost=0.000869` **三次逐字一致**。结论：通过率 / 拦截率 /
    重复建单 / 成本是可复算口径，**P95 不是**——已按裁决 29 改为区间书写。
  - **单类复跑命令（照抄可用，flag 不可省）**：
    `mvn -B -ntp -pl aiwarden-start -am test -Dtest=ChatEvalGateContainersTest -Dsurefire.failIfNoSpecifiedTests=false`
    （本机需 `DOCKER_HOST` npipe 绕行，见 [`AGENTS.md`](../AGENTS.md) §4）。
    **`-Dsurefire.failIfNoSpecifiedTests=false` 是必需的**：`-am` 会把 `aiwarden-common` 等
    没有匹配类的兄弟模块拉进 reactor，缺这个 flag 直接 `BUILD FAILURE: No tests matching pattern`
    （2026-10-19 实测踩过，1.5s 就失败，看起来像测试坏了、其实是模块选择问题）。
  **数字为何比上一轮变**：抽取式作答让回答正文变长（每条命中片段都被引入正文），
  completion tokens 随之增加 → 单次成本上升；P95 反而下降是**本轮样本的抖动**（nearest-rank、24 条），
  不构成「改动让系统变快」的结论。
- **P1 删除 SLO 的复现口径（2026-10-19 补测）**：`aiwarden-start` 的 `P1DeleteSloProbe`
  （类名不以 Test 结尾，**默认 `mvn verify` 不执行**）走 HTTP → outbox → Kafka → 消费删向量的真实链路，
  **30 轮、relay 按生产默认 2000ms 调度、全程不手动 `pollOnce()`**（`INDEXED` 能到达本身就是调度生效的自证），
  计时终点为「按原文检索 0 命中」。命令：
  `mvn -B -ntp -pl aiwarden-start -am test -Dtest=P1DeleteSloProbe -Dsurefire.failIfNoSpecifiedTests=false`
  （需 Docker；本机 npipe 绕行见 [`AGENTS.md`](../AGENTS.md) §4）；逐轮样本与 P50/P95/max 写在
  `aiwarden-start/target/p1-delete-slo.txt`。
- **前端独立口径**：`aiwarden-web` 是独立 pnpm 工程，**不进 `mvn verify`、不计入上面的 170**；
  其验证是 `pnpm build`（含 `vue-tsc --noEmit` 类型检查）通过 + `pnpm dev` 启动无编译错误，
  并已用真实后端做端到端联调（经 Vite 代理 5173→8080 打通 `/api`、`/actuator/prometheus` 与 SSE 流）。
  **M4 新增的 KB 管理页 / 一致性页 Trace 查询已在 2026-10-19 补做真实浏览器实测**（干净 clone 起 dev server、
  打真实后端）：KB 页创建 → 切换（`localStorage.aiwarden.kbId`）→ 上传 → 列表轮询 `PENDING→INDEXED` →
  删除二次确认；一致性页 scan 抓到注入的孤儿向量（`mismatchCount=1`）→ repair → 复扫归零、
  近 20 轮扫描上下文、孤儿曲线手动采样出点、**按 traceId 渲染出 10 步检查点时间线**；
  用量页 58 条明细与前端自算聚合；评测页读数 + 逐样本表；C 端问答页引用侧栏 4 条、步骤时间线、
  成本与结局（同时回归了 [`AGENTS.md`](../AGENTS.md) §4 那条「mutation 打在代理之外」的坑——引用侧栏正常计数）。
  **手法限制（如实标注）**：本机 Qoder 内置浏览器无可见 surface（指针事件报
  `NATIVE_BROWSER_VIEWPORT_UNAVAILABLE`），交互经 CDP 在同一页面里派发**真实 DOM 事件**并断言 DOM，
  与 M3 的点击实测等价但**未产出截图**；需要截图存证时把内置浏览器面板打开再跑。
  **C 端与 B 端的时间线口径不同**（别当成矛盾）：C 端 SSE 只发 5 类 `step`（visibility / retrieval /
  prompt / model / metering，tool·cost·outcome 各有独立事件），**完整 10 步**在 B 端
  `GET /api/v1/agent/runs/by-trace/{traceId}`（DB 检查点）——「10 步时间线」这句承诺指的是后者。
- **M4 压测的复现口径**：`aiwarden-start` 的 `GovernanceLoadProbe`（类名不以 Test 结尾，**不参与 `mvn verify`**）
  一次跑完六项治理税 + 六档吞吐；命令、机器规格、逐轮原始输出与边界见
  [`docs/benchmarks/M4-load-report.md`](benchmarks/M4-load-report.md)（逐项数字的权威源）。
- **M4 新增的自动化验证**（随 `mvn verify`）：`AgentCrashResumeContainersTest` 5（含崩溃在 metering 的计量去重）+
  `AgentCheckpointStoreContainersTest` 4 + `TracePropagationTest` 4 + `AgentTraceContainersTest` 1；
  另在既有容器测试里补了文档列表接口、一致性历史报告接口的断言。
- **启动命令口径**：`mvn -B -ntp -DskipTests install`（一次；改了兄弟模块后重跑）
  → `mvn -B -ntp -pl aiwarden-start spring-boot:run -Dspring-boot.run.profiles=local`（本机）。
  **不能写成 `-pl aiwarden-start -am spring-boot:run`**。三种死法、IDE 启动、改兄弟模块为何必须重 `install`
  ——唯一正文见 [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 4 条。
- **启动冒烟（本轮新增的必做验证）**：`mvn -pl aiwarden-start -am package -DskipTests` + `java -jar` 能启动——
  因为**测试全绿不等于应用能启动**（本轮实测：装配模块的 test scope 依赖把运行期 Redis 客户端挤掉，
  112 个测试全绿而 `java -jar` 直接失败）。机制、判据与同类「装配面/运行面脱钩」坑见 [`AGENTS.md`](../AGENTS.md) §4。
- 本机 Testcontainers 偶发 npipe 抖动的复现命令见 [`AGENTS.md`](../AGENTS.md) §4（环境类处置另见
  [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 10 条）；CI（Linux）不受影响。
- GitHub Actions 已在 push(main) / PR 上触发；**CI 结果以流水线为准**——本开发环境无法直连 github.com
  （`web_fetch` 解析到非公网 IP 被拒），仓库内不复制 CI 结论。
- **治理税四项与评测门禁的具体数值**：见上面「结论表」，以及评测门禁的原始输出行（上面第 2 条）——
  **同一数字不在本文件里写两遍**。

## 3. 已知边界（不在承诺范围，主动交代）

- **不承诺检索质量**（召回率 / 引用准确率）——承诺的是数据面确定性（PRD §3 边界声明 B1）。
  评测门禁同理：**验证的是「治理管道对任意模型决策的约束执行」**，不验证「真实模型会不会做出该决策」
  （[ADR-012](adr/ADR-012-chat-orchestration-and-eval.md) 决策 3）。首轮曾用 `expectedDocs` 断言「语义相关必命中」，
  实测在哈希伪嵌入下是抛硬币，已删除该类断言——门禁只断言可确定判定的数据面。
- **一致性是「有 SLO 的可对账」**，不是绝对一致（B2）；残余窗口可查询、有指标、可修复。
- **P4a 四维归因**：工具链路四维齐备；M4 起模型调用维度带 `trace_id` 与 `cost` 回填，
  「步骤」维度可由 traceId + `t_llm_call_log.step_no` 串联（不再依赖「M4 的 OTel span 未实现」这一边界）。
- **M3 模型侧**：无真实模型端点，问答链路走 **Mock 替身**（确定性剧本）；录制回放机制已就绪，
  **fixture 待真实端点补录**（不伪造数据）。M4 的 P5 是「同一次运行的续跑」，**多轮对话上下文仍未做**。
- **人工确认卡片默认不出现（演示需显式打开开关）**：`aiwarden.agent.tools.require-approval` 缺省为空，
  即默认配置下 C 端「待处理」卡片永不出现（机制本身有容器测试覆盖，缺的是演示开关）。
  **展示 human_handoff 链路时必须带参数**：`-Daiwarden.agent.tools.require-approval=create_ticket`。
  未改缺省值的取舍：默认开启会让「建单」这一主演示动作多一步人工停顿，而容器测试已覆盖该路径。
  界面层闭环实测：卡片「待处理」→ 点「批准执行」→ 卡片变 `SUCCEEDED`，
  DB 侧 `t_tool_invocation` 该行为 SUCCEEDED + 审计 `TOOL_APPROVED`。
- **B 端管理接口身份口径已统一（2026-10-10 收口，裁决 22）**：`/api/v1/admin/**` 全部端点统一要求
  **租户 + 主体双必填**（缺失即 400、不回落默认值），经 `AdminAccess.requireIdentity()` 单点收口。
  收口前的实测不一致：`/audit`、`/usage`、`/eval/report`、`/billing/*` 只校验租户（缺主体也 200），
  而 **`/consistency/*` 连租户都没校验**（裸扫全表即返回报告）。口径已写进
  `AdminIdentityBoundaryContainersTest`（逐端点断言缺租户 400 / 缺主体 400 / 齐备非 4xx 非 5xx，
  **端点清单单一事实源**，新增接口只补一处即被覆盖）。**仍不做租户维度过滤**——这些是平台级接口
  （`t_reconcile_report` / `t_eval_report` 无租户维度），真实鉴权接入时应升级为角色校验。
- **内联引用标记已补齐（2026-10-10，裁决 22）**：`MockModelClient` 现按检索片段编号产出 `[1][2]…`，
  与 C 端 `MarkdownBlock` 既有的 `[n]` → 可点击引用上标机制（`citationCount` / `pick-citation`）接通——
  此前前端实现了该链路、后端从不产出标记，**功能存在但不可达**（FR-APP-02 的「正文可点引用」演示中实际缺失）。
- **前端不在 Maven 生命周期内**：`aiwarden-web/` 是独立 pnpm 工程（**不进 `mvn verify`、不计入测试数**），
  验证口径是 `pnpm build`（含 `vue-tsc --noEmit`）+ dev server 冒烟——**CI 绿不等于前端可构建**。
- **两个 B 端页在新环境里「看起来是空的」，各有各的原因（2026-10-10 实测，别当成故障）**：
  ① **评测报告**：`t_eval_report` 只由评测门禁写进 **Testcontainers 的临时库**，本地开发库为空 →
  `/eval/report` **在表为空时** 404（要看数据得跑 `mvn verify` 读 `EVAL-SUMMARY`，或把结论 INSERT 进本地库——
  **一旦手工 INSERT 过就不再 404**，页面显示的是那一轮的 `runAt` / P95 / 成本，与 §2 投影不同源，
  判据是看 `runAt`；2026-10-19 实测补正，正文见 [`dev/troubleshooting.md`](dev/troubleshooting.md) 第 5 条）；
  ② **一致性报告**：报告行**每 5 分钟自动新增**（本机实测已有 37 份），但 `mismatch_count` 全为 0 ——
  「不一致清单为空」是**正确空态**；孤儿向量曲线是**手动采样**自绘的，不点就永远是空的。
  **判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 5 / 7 条。**
- **本机 Kafka 端口问题已解决（2026-10-10）**：Windows 把 TCP 9092 划进了保留端口区间，宿主机无法 bind，
  导致 outbox 卡 PENDING、摄入与计量停摆。绕行已内置进 `docker-compose.yml`
  （kafka 内部 19092 + `kafka-proxy` 顶到宿主机 **29092**），本机启动需激活 profile `local`，Linux / CI 不需要。
  **根因、判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 2 条。**
  实测已全链路打通：outbox 全 SENT、文档摄入 INDEXED、计量落 `t_llm_call_log`、用量接口返回真实明细。
- **M4 的已知边界（如实标注，不写成已达成）**：
  ① **Tempo / OTel Collector 未实跑、也未加入 compose**：本机 Docker Hub 不可直连（`manifest` / `pull` 超时），
     可观测栈镜像拉不到，因此不写无法验证的镜像 tag；trace 正确性由 OTel SDK 内存 exporter 的全链路测试证明，
     `AIWARDEN_TRACE_EXPORT_ENABLED` 默认关闭，起好 collector 后再打开（配置在 `application.yml`）；
  ② **Loki 日志关联未接入**：FR-OBS-04 的三信号互跳只完成 Metric ↔ Trace 与业务 ID；
  ③ **K8s/Helm + HPA 未做**（裁决 27，非承诺）；
  ④ **P5 的「kill -9」用 `Runtime.halt(137)` 复现**：同为无关闭钩子、无 flush 的进程终止，
     但未在 Linux 上真的发 SIGKILL；测试形态与退出码断言见 ADR-013。
     **补一条同类边界（2026-10-19 实测）**：想用手外的 `taskkill /F` / SIGKILL 命中「某个节点正在执行」的窗口，
     在 Mock 模型下**做不到**——一次问答总耗时 < 100ms，外部信号打不进去。本轮用 OS 级强杀演的是
     「副作用跨进程死亡只执行一次」（待批准的工具调用在旧进程被强杀后由新进程批准、`t_ticket` 不重复），
     而「崩在 tool 之后、检查点之前」仍只有 `halt(137)` 注入这一条路；**真·in-flight SIGKILL 需要慢模型
     fixture 或人为延时**，接真实端点后再补演；
  ⑤ **前端 KB 页 / Trace 查询的浏览器实测已补**（2026-10-19，口径与限制见 §2「前端独立口径」）——
     原「只过了 `pnpm build`」项关闭；
  ⑥ **摄入停摆判据的覆盖边界**：判据能**发现**「当前版本索引事件永久失败 / 当前版本账本卡死 / 文档超时」
     三种形态，`repair` 只**重驱动当前版本、文档存在且未收敛的索引事件**；删除事件与旧版本索引事件
     **不重驱动**（删除事件重放会 purge 全部版本，可能清掉同名重传的新文档）。若 outbox 事件行根本
     没写进来，判据照样报不一致，但**不自动补发**（那等于替业务伪造一条从未提交的事件），留人工重传。
     另注：**重驱动 ≠ 收敛**——事件置回 `PENDING` 后文档仍停摆，直到消费者真的补齐。
     **反向守卫**：`FAILED` 事件只在文档**未收敛**时才算停摆（已 `INDEXED` 的文档不因事件行被标 `FAILED`
     而误报），且判据 / `failedOutboxEvents` 计数 / `repair` 重驱动三处共用同一过滤条件——
     两处口径分叉的下一症状就是「清单为空、计数 0、却重驱动了 1 条」。
     **账本去噪**：`PROCESSING` 只算 `d.version = l.version` 且文档未收敛的当前版本行；旧版本卡死
     （当前版本已 `INDEXED`）不再永久污染清单；孤儿账本（文档行不存在）仍保留为可见异常。
- **未做（汇总）**：P4b（预算降级，裁决 19 砍除）、用户鉴权（身份头为认证层输出的模拟）、
  Loki 日志采集、K8s/Helm、真实模型 fixture。

## 4. 下一步

M4 已收口。若继续，按「先补可验证性、再扩范围」排序：

- **可观测整套实跑**：拉取 Collector / Tempo / Grafana 镜像（需先解决本机 Docker Hub 代理），
  用真实 OTLP 链路复跑 `AgentTraceContainersTest` 的同一 traceId，补 Loki 日志关联。
- **真实模型 fixture**：录制回放轨的 fixture 仍待真实端点补录（不伪造数据）。
  **接真实慢模型后顺手补两件事**：① 真·外部 SIGKILL 命中「在飞节点」的续跑演示（Mock 下窗口 <100ms 打不进去，见 §3 ④）；
  ② 评测 P95 才有可引用的容量含义（现在只是同机抖动）。
- **前端浏览器实测（已补，2026-10-19）**：KB 管理页 / 一致性页 / Trace 查询 / 用量 / 评测 / C 端问答
  全部真实浏览器交互通过，口径与手法限制（CDP DOM 事件、无截图）见 §2「前端独立口径」。
  下一步只剩**指针级点击 + 截图存证**（需把内置浏览器面板打开让 surface 可见）。
- **摄入停摆的收口**：`repair` 现在能重驱动 `FAILED` 事件；**事件行压根没写入**的停摆仍是人工重传
  （不自动补发，见 §3 ⑥）——若要做成产品动作，需要「按文档重新发布索引事件」的显式入口，属新范围。
- **可选**：K8s/Helm + HPA + 探针（裁决 4/27，非承诺）。

里程碑定义见 [`docs/PRD.md`](PRD.md) §8（唯一权威源）。

## 5. 公开范围

本仓库为**公开版**。以下属**内部材料，存放于仓库外**——**不要把它们加回本仓库**：

- 编号 **01、02** 的两份前期调研文档；
- **3 份原始调研报告**（`inbox/`，共 158 KB）——**不可删**：公开仓库的 `docs/research/03` 第 7 行明确点名其中
  **2 份**为它的取证底稿，而 `inbox/README.md` 自己立了规矩「正文结论以这三份为源，**分歧时以原始报告为准并回修正文**」；
  删掉等于剪断 03 / 04 的 star 口径与饱和度结论的证据链，也让「多口径并列不取平均」「未查证清单」失去可翻查的出处；
- 个人规划类文档（简历 / 作品集策略 / 对外口径正文等）——含原 `docs/PRD.md` §14 的全部内容，
  2026-10-10 迁至**仓库外的内部材料目录**（不在本仓库工作树内，也不预留 gitignore 的隐藏目录，理由见
  [`docs/README.md`](README.md)「不随仓库公开」）。

正文中对它们的引用**保留文字叙述、不提供链接**，以免出现死链（[`README.md`](../README.md) 文末同款声明）。
