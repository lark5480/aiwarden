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
| P1 删除即失效 | 删除生效 **P95**（目标 ≤ 5s）/ 超窗残留进入不一致清单的条数 | 自动化用例 **5 轮全 ≤ 5s**（**当前为单次断言口径**，非分位数）；**P95 分位数量化仍是残余边界**（M4 压测聚焦治理税与吞吐，未覆盖删除路径，见 §3）；超窗残留由对账发现（M1 用例已证） |
| P2 检索层隔离 | 越权样本拦截率（20 条） | **100%**（16 条检索类 + 4 条工具类，随 `mvn verify` 门禁） |
| P3 副作用幂等 | 断网重放后工单数（期望 1） | **1**（重放复用首见结果；`t_ticket` 唯一约束兜底） |
| P4a 用量可归因 + 配额强一致 | 四维归因覆盖率 / 配额检查（预扣减 / 幂等）正确性 | 工具链路四维齐备（租户/会话/步骤/工具）；预扣减幂等 / 并发不超卖 / 超限 429 / 对账差异 0。**M4 起模型调用维度也带 trace/step 上下文**（`t_llm_call_log.trace_id` + `cost` 回填），四维归因的「步骤」维度可由 traceId 串联 |
| P4b 预算降级 | 两档降级触发正确性 | **已砍**（2026-10 裁决 19）；地基已就位，重启属新范围 |
| P5 长任务续跑 | `kill -9` 后续跑成功率 / 副作用重复数 | **已达成（M4）**：崩在「tool 副作用之后、检查点之前」→ 续跑重做 tool 但幂等命中，`replayed=true`、工单数 **1**；崩在检查点之后 → 续跑复用快照、tool 步骤版本不变、不重做；**崩在 metering 之后、检查点之前** → 续跑重做计量，但 `dedupKey` 唯一键仲裁只落 **1** 条模型明细；**人工断连（ABORTED）后可续跑**（CAS 回 RUNNING）；租约未过期时续跑 409（不并行执行副作用）。证据见 [ADR-013](adr/ADR-013-agent-checkpoint-and-resume.md) |
| 评测 | 24 条样本通过率 / P95 延迟 / 单次成本 | **24/24 全绿**、风险样本拦截 **7/7**、重复建单 **0**、P95 **23ms**、单次成本 **0.000869 元**（Mock 替身 + 真实治理管道；**演示单价口径**，非真实价目表；**P95 逐轮抖动**——历史轮次 44 / 52 / 58ms，nearest-rank、样本量 24） |
| 治理税 | 逐环节开销（M2 四项 → M4 六项）/ 每请求总开销 / 吞吐拐点 / P99 | M4 补齐六项（P95）：可见集 **2.7ms** / filter 下推 **5.9ms** / 计量事件 **0.7ms** / 配额检查 **3.9ms** / **审计留痕 1.5ms** / **OTel span 19µs**；六项 P50 相加约 **10.3ms/请求**；吞吐拐点 **并发 4**（≈**70.9 RPS**、P99 **91.6ms**），C32 P99 **656.6ms**（吞吐回落）。逐项/逐轮数字与复现命令见 [压测报告](benchmarks/M4-load-report.md)（**单次采样，轮间抖动 10–30%**） |

### 复现口径（原始证据）

- 本地 `mvn -B -ntp -o verify`：**166 测试全绿**（0 失败 0 跳过）；
  **单次运行口径**（`clean` 后跑一次），机器 `i7-7700（4 物理核 / 8 逻辑核）+ 32GB`。
  其中 M2 收尾为 102 → M3 切片①② 净增 10（112）→ 收官后补测净增 15（**127**：
  `AdminIdentityBoundaryContainersTest` 13 + `MockModelClientTest` 3，扣除随口径调整的 1）
  → **Mock 改抽取式作答后净减 1（126**：`MockModelClientTest` 的「默认回答」与「内联标记」两个用例
  合并为一个「抽取式作答含片段原文与标记」用例，断言更严但方法数少 1）。
  - **M4 收尾计数（2026-10-19，本轮唯一当前值）**：`mvn -B -ntp verify` → **exit 0 / BUILD SUCCESS / 5:27 min**，
    三模块汇总 `aiwarden-common` **16** + `aiwarden-knowledge` **8** + `aiwarden-start` **142** = **166**，
    0 失败 0 跳过。增量（相对上轮 150）：M4 新增 **14**（checkpoint 4 / crash-resume 5 /
    trace 边界 4 / trace 全链路 1）+ 管理端点参数化 **2**（`AdminIdentityBoundaryContainersTest` 6 → 7 个端点）。
    **压测探针 `GovernanceLoadProbe`（`@Tag("load")`）不参与 `mvn verify`，不计入**；
    上轮的「150」是 `16 + 8 + 126` 的合计，历史行保留不回改。
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
  **数字为何比上一轮变**：抽取式作答让回答正文变长（每条命中片段都被引入正文），
  completion tokens 随之增加 → 单次成本上升；P95 反而下降是**本轮样本的抖动**（nearest-rank、24 条），
  不构成「改动让系统变快」的结论。
- **前端独立口径**：`aiwarden-web` 是独立 pnpm 工程，**不进 `mvn verify`、不计入上面的 166**；
  其验证是 `pnpm build`（含 `vue-tsc --noEmit` 类型检查）通过 + `pnpm dev` 启动无编译错误，
  并已用真实后端做端到端联调（经 Vite 代理 5173→8080 打通 `/api`、`/actuator/prometheus` 与 SSE 流）。
  **M4 新增的 KB 管理页 / 一致性页 Trace 查询**，本轮验证口径仍是 `pnpm build` 通过，
  未做新一轮浏览器点击实测（如实标注，见 §3）。
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
- **P1 的 SLO 口径**：实际执行的是「5 轮单次断言 ≤ 5s」，**非 P95 分位数**。M4 压测聚焦治理税与吞吐，
  **未覆盖删除路径**，P95 分位数量化仍是残余边界（不把「5 轮全过」写成「P95 ≤5s」）。
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
  `/eval/report` 恒 404（要看数据得跑 `mvn verify` 读 `EVAL-SUMMARY`，或把实测值 INSERT 进本地库）；
  ② **一致性报告**：报告行**每 5 分钟自动新增**（本机实测已有 37 份），但 `mismatch_count` 全为 0 ——
  「不一致清单为空」是**正确空态**；孤儿向量曲线是**手动采样**自绘的，不点就永远是空的。
  **判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 5 / 7 条。**
- **本机 Kafka 端口问题已解决（2026-10-10）**：Windows 把 TCP 9092 划进了保留端口区间，宿主机无法 bind，
  导致 outbox 卡 PENDING、摄入与计量停摆。绕行已内置进 `docker-compose.yml`
  （kafka 内部 19092 + `kafka-proxy` 顶到宿主机 **29092**），本机启动需激活 profile `local`，Linux / CI 不需要。
  **根因、判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](dev/troubleshooting.md) 第 2 条。**
  实测已全链路打通：outbox 全 SENT、文档摄入 INDEXED、计量落 `t_llm_call_log`、用量接口返回真实明细。
- **M4 的已知边界（如实标注，不写成已达成）**：
  ① **P1 的 P95 仍未量化**（压测未覆盖删除路径，见上）；
  ② **Tempo / OTel Collector 未实跑、也未加入 compose**：本机 Docker Hub 不可直连（`manifest` / `pull` 超时），
     可观测栈镜像拉不到，因此不写无法验证的镜像 tag；trace 正确性由 OTel SDK 内存 exporter 的全链路测试证明，
     `AIWARDEN_TRACE_EXPORT_ENABLED` 默认关闭，起好 collector 后再打开（配置在 `application.yml`）；
  ③ **Loki 日志关联未接入**：FR-OBS-04 的三信号互跳只完成 Metric ↔ Trace 与业务 ID；
  ④ **K8s/Helm + HPA 未做**（裁决 27，非承诺）；
  ⑤ **P5 的「kill -9」用 `Runtime.halt(137)` 复现**：同为无关闭钩子、无 flush 的进程终止，
     但未在 Linux 上真的发 SIGKILL；测试形态与退出码断言见 ADR-013；
  ⑥ **前端 KB 页 / Trace 查询本轮只过了 `pnpm build`**，未做新一轮浏览器点击实测（旧三页有浏览器实测记录）。
- **未做（汇总）**：P4b（预算降级，裁决 19 砍除）、用户鉴权（身份头为认证层输出的模拟）、
  P1 删除 P95 分位数量化、Loki 日志采集、K8s/Helm、真实模型 fixture。

## 4. 下一步

M4 已收口。若继续，按「先补可验证性、再扩范围」排序：

- **P1 删除延迟 P95 量化**：在 Kafka 消费链路下做 ≥20 轮删除 → 检索 0 命中的分位数统计，
  把 §2 的「5 轮单次断言」升级为与 PRD §12 同口径的 P95（这是当前最明确的未达成项）。
- **可观测整套实跑**：拉取 Collector / Tempo / Grafana 镜像（需先解决本机 Docker Hub 代理），
  用真实 OTLP 链路复跑 `AgentTraceContainersTest` 的同一 traceId，补 Loki 日志关联。
- **真实模型 fixture**：录制回放轨的 fixture 仍待真实端点补录（不伪造数据）。
- **前端浏览器实测**：KB 管理页与一致性页 Trace 查询做一轮无头 Chrome 真实交互（口径同 M3）。
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
