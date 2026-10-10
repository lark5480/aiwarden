<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-012 · 问答编排与评测口径（Mock 模型替身 + 真实治理管道）

**状态**：已采纳（2026-10-07）｜M3 切片① 开工前落盘

### 背景

M1/M2 均为确定性数据面（摄入 / 检索 / 工具 / 配额），全项目**尚无问答链路**——
评测样本的 `expect.outcome`（`answer` / `deny` / `human_handoff`）与 C 端展示都挂在这条链上；
`aiwarden-eval` 模块此前只有 package-info。M3 的两大硬项（评测门禁 + 前后端）以本条为前提。

约束与风险：

- 本环境**无真实模型 API 端点**（FR-EVAL-05 已预判：评测 CI 走 Mock / 录制回放双轨，不打真实 API）；
- 若 Mock 只是「返回固定文本」，评测将退化为**断言替身自己写的答案**（循环论证）——
  必须先把「评测验证什么、不验证什么」的口径钉死，再写代码。

### 决策

1. **编排链路是确定性管线，治理管道全真走**。链路步骤固定为：
   `会话初始化 → 可见集计算 → 检索（下推）→ Prompt 组装 → 模型生成（流式）→（可选）工具调用 → 计量事件 → 成本汇总`。
   除「模型生成」由替身供货外，每一步都是 M1/M2 已交付的**真实组件**
   （`VisibilitySetCalculator` / `RetrievalService` 下推检索 / `ToolInvocationService` 幂等与配额 /
   `OutboxWriter` 计量 / `AuditLogWriter` 留痕）。
2. **ModelClient SPI（core）**：`chat(ChatRequest, TokenListener) → ChatResult`（含文本、工具调用决策、
   token 统计）；流式 token 走回调。业务模块不依赖 LangChain4j（ArchUnit 红线在册），
   真实模型接入 = 新增适配器（届时在 start 装配根替换 Mock 实现）。**SPI 即治理切面**：
   计量 / 配额挂在调用边界，不侵入编排。
3. **Mock 替身 = 确定性剧本身份**。替身按输入模式（问题文本 + 检索上下文 + 工具可见面）确定性地
   产生「模型决策」：正常回答（拼接真实检索片段）/ 工具调用（写操作意图）/ 风险决策（注入诱导、
   越权指令）。**评测验证的分层口径**：
   - **验证**：治理管道对**任意模型决策**的约束执行 100% 正确（越权决策被拒、禁工具调用被拦、
     写操作走幂等、超限被 429、需确认挂起）——这是本项目的承诺域；
   - **不验证**：真实模型会不会做出该决策（属 B1 检索质量 / 模型行为域，不在承诺范围）。
   与 P3 故障注入用例同构：注入的是「危险决策」，断言的是「治理拦住了它」。
4. **双轨机制（FR-EVAL-05）**：Mock 轨（替身跑全部样本进 CI）+ 录制回放轨
   （`RecordingModelClient` 装饰器 + `ReplayModelClient` + JSON fixture 格式）。
   CI 以 **roundtrip 测试**证明录制 / 回放保真；**fixture 的真实模型录制待可用端点后补**
   （当前如实标注「机制就绪、fixture 待录」，不伪造数据）。
5. **会话口径（范围控制）**：M3 编排为「**单轮问答 + 会话标识**」——`sessionId` 请求携带或服务端生成；
   多轮上下文与 Checkpoint（`t_agent_session` / `t_agent_step`，P5）归 M4。`sessionId` 在 M3 承担
   四维归因维度与工具幂等键成分两个职责。
6. **SSE 事件契约（7 类）**：`step`（时间线）/ `token`（流式文本）/ `citation`（引用溯源，含
   docId + chunkId + 片段 + 相似度）/ `tool`（工具调用结果，含 `replayed` / `pending`）/
   `outcome`（`answer` / `deny` / `human_handoff` + reason）/ `cost`（本次 tokens 与演示单价成本）/
   `done`（含 sessionId）。事件 payload 契约落 `aiwarden-contract`。
7. **outcome 的产生规则**（映射自治理管道，不由替身自由发挥）：`deny` = `SecurityException`
   （可见集空 / 工具不可见）/ `QuotaExceededException` / `RateLimitExceededException`（治理拒绝）；
   `human_handoff` = 工具 `PENDING_APPROVAL`（二态审批挂起）；其余 = `answer`
   （含工具业务失败——失败走补偿而非重试，DB 终态断言覆盖）。**deny 是治理行为，不是替身台词**。
8. **工具调用经真实 P3 管道**：替身决策的工具调用走 `ToolInvocationService.invoke`
   （白名单 → 限流 → 幂等键仲裁 → 配额预扣 → 审批分流）；步骤号固定（模型生成 1 / 工具调用 2），
   业务键缺省 = 会话 + 消息指纹——**同一请求重放捕获为同一逻辑调用**（断网重放主场景的编程模型）。
9. **评测资产落点**：样本定义 + 断言引擎在 `aiwarden-eval/src/main`（纯逻辑，可被测试驱动消费）；
   全栈门禁测试驱动在 `aiwarden-start/src/test`（与 20 条越权样本门禁同落点）；评测结论写
   `t_eval_report`（V9 迁移，B 端评测报告页的数据源）。

### M3 范围裁决（携带 06 号清单裁决 19）

- **P4b（预算决定推理档位）砍除**：不随 M3 交付；地基（`t_budget.degrade_policy` + 配额管道）
  已就位，重启属新范围；
- **用量看板 Vue 自绘**（数据来自已有 `/api/v1/admin/usage` 等接口）；Grafana 不随 M3 引入，
  与 Prometheus / Tempo / Loki 在 M4 成套搭建；
- **租户限流（FR-COST-07）已随 M2 提前交付**，M3 行不重复列。

### 依据

- PRD §4.2（问答链路时序图：可见集 → 检索 → 组装 → 模型 → 计量 → 审批 → SSE）、§5.9（FR-EVAL-01~05）、
  §5.11（FR-APP-01~05）、§7.1「模型接入 = OpenAI 兼容协议 + 适配器 SPI；测试用 Mock 模型」、裁决 19；
- 「可验证交付」哲学：结论表列「20–30 条通过率 / 拦截率 / 重复建单数 / P95 / 单次成本」——
  这些数字必须来自可复现的门禁，不能来自形容词。

### 代价与放弃

- **放弃 M3 接真实模型**：无端点（且 FR-EVAL-05 明确评测不打真实 API）；真实模型的行为差异
  （幻觉、格式漂移）不在本 ADR 的验证域——接入时新增适配器 + 录制 fixture 即可复用本机制；
- **放弃多轮对话上下文**：C 端定位是「治理能力的演示载体」（PRD §5.11 范围控制），非通用对话产品；
- **替身 token 统计是估算口径**（按文本长度），非真实分词器——**计量链路真实、数值口径如实标注**；
- **`t_llm_call_log.cost` 列回填（含工具计价）留 M4**：演示单价不是真实价目表，晚填无损失；
  结论表「单次成本」用编排层演示单价并标注口径。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 编排全链路（SSE 7 类事件 + 引用 + 成本 + 时间线） | `ChatSseContainersTest`（含 1 条 HTTP SSE 冒烟） |
| 治理管道真走（不是影子链路） | 容器测试断言：引用来自真实下推检索 / 工具调用落 `t_tool_invocation` 且重放 `replayed=true` / 计量进 `t_llm_call_log` |
| 断连取消 | SSE 断连后编排终止（监听器断言）+ 无泄漏 |
| 双轨保真 | 录制 → 回放 roundtrip 测试（fixture 待真实模型补录，机制先行） |
| 20–30 条评测门禁 | 切片②：`EvalGate` 全样本断言（outcome / forbid_tools / max_tickets + DB 终态） |

**切片③④⑤ 追加的验收证据（2026-10-10，无头 Chrome 真实交互）**：

| 断言（PRD §8 M3 验收） | 证据 |
|---|---|
| C 端四要素可演示（对话 / 引用 / 时间线 / 成本） | `/chat` 真实渲染：流式回答 + **引用 2 条（docId/chunkId/snippet/相似度）** + 步骤时间线 5–6 步 + 本次成本 ¥0.000282 |
| **B 端一致性报告页可演示**（PRD M3 明写的验收项） | `/admin/consistency` 真实报告 reportId 17 + 指标卡 + 不一致清单 + 一键重试按钮 |
| B 端用量看板可演示 | `/admin/usage` 真实明细 4 条 / 总 402 tokens + 四维过滤 + 前端聚合（Kafka 修复前此页恒为空数组） |
| B 端评测报告页 | `/admin/eval` 无数据时 404 且如实显示原因（预期行为，非故障） |
| 人工确认闭环（FR-APP-05） | 界面出现「待处理」卡片（工具 / invocationId / 批准 / 驳回）→ 点「批准执行」→ 卡片变 `SUCCEEDED`；DB 侧 `t_tool_invocation` id=4 SUCCEEDED + 审计 `TOOL_APPROVED target=4` |

## 修订（2026-10，切片①②落地回填）

**切片①（编排内核 + SSE）已落地**：

- `ModelClient` SPI（core；含 `TokenListener` 与 `ModelChatRequest/ChatResult/ToolSpec/ToolCall`）；
- `MockModelClient`（剧本 5 规则：注入→`web_search` / 破坏→`delete_all_tickets` / 建单→`create_ticket` / 转派→`assign_ticket` / 默认 answer）；`RecordingModelClient` + `ReplayModelClient` + `ModelFixtures`（双轨机制）；
- `ChatOrchestrator`（确定性管线；**双取消检查点**——入口 + 模型后，工具绝不因断连执行、模型 tokens 照常计量）；
- `ChatController`（`POST /api/v1/chat` SSE 七类事件；断连检测 = sink 写失败置位）；
- `ChatMeteringWriter`（模型调用计量进 outbox，与工具计量同一消费骨架）；
- `ToolCatalog`（工具规格单一来源：MCP 视角 schema ⊇ 模型视角 schema——`businessKey/sessionId` 不进模型请求体）。

| 断言 | 证据（均随 `mvn verify`） |
|---|---|
| 编排全链路（SSE 七类事件 + 引用 + 成本 + 时间线） | `ChatSseContainersTest` 9 用例：正常回答 / 工具建单+重放 / 跨租户 deny / 注入拦截 / 破坏拦截 / human_handoff / 中途取消 / 入口取消 / 配额 deny |
| 治理管道真走（不是影子链路） | 同上：引用来自真实下推检索；工具落 `t_tool_invocation` 且重放 `replayed=true`；计量进 `t_outbox_event`（`llm.call.recorded`）；审计 `RETRIEVAL_DENIED`/`TOOL_DENIED` 落库 |
| 断连取消 | 中途取消：工具零执行 + 工单不落库 + 计量照记；入口取消：零动作零计量 |
| 双轨保真 | `ModelFixturesRoundTripTest`：录制→回放全等 / 缺失显式异常 / 篡改拒绝 |
| Mock 剧本确定性 | `MockModelClientTest` 8 用例 |

**切片②（评测门禁）已落地**：样本 24 条（14 正常 + 10 风险：跨租户 / 不存在 kb / 组织边界 / 注入×2 / 破坏×2 / 重复提交 / 工具失败 / 信息冲突）；引擎三件套（`EvalCaseLoader` 加载即校验 / `EvalAssertions` 6 类硬断言 / `EvalReport` 结论表）；全栈驱动 `ChatEvalGateContainersTest`（进程内直调编排）；`EvalAssertionsTest` 9 用例自证「断言能被违反」；结论落 `t_eval_report`（V9 迁移）。

**首轮全绿数据**：`total=24 passed=24 denyBlocked=7/7 duplicateTickets=0 p95=44ms avgCost=0.000509 元`（演示单价，i7-7700 4C8T/32GB 本机口径）。

**实测修正（如实记录）**：首轮 6 条样本红——`expectedDocs` 在「查询≠文档文本」时失败。根因：确定性伪嵌入（SHA-256 播种随机向量）**不保留语义相似度**（同文本距离 0，异文本近似正交），「语义相关必命中」类断言实为哈希抛硬币——**属 B1 检索质量域，本就不该进治理门禁**。修订：删除该类断言，只保留「同文本精确命中」（N05）——**门禁只断言可确定判定的数据面**。

**遗留**：切片③（Vue C 端）/ ④（B 端三页 + 评测报告读取 API）/ ⑤（PRD / README / STATUS 收尾回填）待做。

**切片④ 半项已落地（评测报告读取 API）**：`EvalReportController` `GET /api/v1/admin/eval/report`
（只读 `t_eval_report`，无数据抛 404 由前端显示原文）；容器测试内 `assertReportApiReadable` 断言该 API 可读
且与报告一致——**B 端评测报告页的数据源在切片②就已顺带交付**，切片④ 剩余的是纯前端。

## 修订（2026-10，切片③④联调：端到端实测与三个真实缺陷）

**切片③④（Vue 3 前后端）已落地**：`aiwarden-web/`（独立 pnpm 工程，不经 Maven 构建、不进 `mvn verify`）——
`/chat` C 端四要素（流式 Markdown + 引用侧栏 + 步骤时间线 + 本次成本 + 人工确认卡片）、
`/admin/consistency|usage|eval` 三页；SSE 走 `fetch` + `ReadableStream` 手工解析（`EventSource` 不能发 POST）。

### 端到端实测（真实 HTTP + 真实基础设施，非容器测试内直调）

| 验证项 | 实测结果 |
|---|---|
| SSE 帧契约 | 真实帧序 `step*`(visibility/retrieval/prompt/model/metering) → `token*` → `cost` → `outcome` → `done`，与决策 6 逐条一致 |
| 引用溯源 | 同文本精确命中：`citation.snippet` 为原文、`score=1.0`；非同文本 `score=0.0078`（哈希伪嵌入，印证 B1 边界） |
| human_handoff（FR-APP-05） | `tool.status=PENDING_APPROVAL` + `invocationId` → `approve` 返回 `SUCCEEDED` 且 `t_ticket` 落 1 行；`outcome=human_handoff` 带原因 |
| 断网重放（P3 主演示场景） | 同一请求重放：`tool.status=SUCCEEDED` + **`replayed=true`**，工单数**仍为 1** |
| deny 路径 | 跨租户 kb：`visibility kbIds=0` → `outcome=deny` + 原因；**无 token / cost 事件**（证实决策 6 的假设）；审计落 `RETRIEVAL_DENIED` |
| 身份边界 | 缺租户头 → 400 `{"detail":"租户上下文缺失：拒绝执行（不回落默认租户，FR-TEN-02）"}` |
| 前端代理链路 | 经 Vite 代理（5173→8080）：`/api` 与 `/actuator/prometheus` 均通，SSE 完整流过 |
| 前端构建 | `pnpm build`（含 `vue-tsc --noEmit`）通过；dev server 启动无编译错误 |

### 真实缺陷（都是「不报错但不生效」，且当时的 112 个测试一个都发现不了）

1. **`aiwarden-start` 的 test scope `lettuce-core` 把运行期 Redis 客户端挤掉了**：Maven 就近声明压过
   `spring-boot-starter-data-redis` 的 compile 传递依赖 → 测试 JVM 有 lettuce（全绿）、fat jar 没有 →
   `StringRedisTemplate` 无候选 bean → **`java -jar` 启动即失败**。修正：删除该 test 依赖。
   **推论已入 [`AGENTS.md`](../../AGENTS.md) §4：`mvn verify` 全绿 ≠ 应用能启动，改依赖后必须做 `java -jar` 启动冒烟。**
2. **Micrometer 导出时剥掉 Gauge 名尾部 `_total`**：注册名 `aiwarden_vector_orphan_total` →
   导出名 `aiwarden_vector_orphan`；前端按注册名抓取 → 曲线**永远采不到点**。修正：解析器两种形态都认。
3. **`aiwarden.agent.tools.require-approval` 缺省为空**——即默认配置下 C 端人工确认卡片永不出现
   （机制有容器测试覆盖，但演示开关未打开）。**本次未改缺省值**：默认开启人工确认会让「建单」这一主演示动作
   多一步人工停顿，而容器测试已覆盖该路径；**演示时需显式打开**：
   `-Daiwarden.agent.tools.require-approval=create_ticket`（本次 human_handoff 实测即用此参数）。
   界面层闭环亦已验证：卡片「待处理」→ 点「批准执行」→ 卡片变 `SUCCEEDED`，
   DB 侧 `t_tool_invocation` id=4 SUCCEEDED + 审计 `TOOL_APPROVED target=4`。
4. **B 端管理接口身份口径不一致——已于收官后统一（裁决 22）**：原状为
   `GET /api/v1/admin/audit`、`/usage` **只带租户头（缺 `X-Aiwarden-User-Id`）也返回 200**，
   而 `/api/v1/chat` 与检索入口会以 `MissingPrincipalContextException` 拒绝；
   更进一步，**`/admin/consistency/*` 连租户都没校验**（裸扫全表即返回报告，`ConsistencyReconcileContainersTest`
   原先裸调即 200 正是此因）。根因是这些 Controller 只调 `TenantContext.requireTenantIdAsLong()`。
   **收口**：新增 `AdminAccess.requireIdentity()`（租户 + 主体双必填，缺失即 400 由既有 handler 映射），
   五个管理 Controller 全部改走它；`AdminIdentityBoundaryContainersTest` 逐端点固化该口径
   （缺租户 400 / 缺主体 400 / 齐备非 4xx 非 5xx，**端点清单单一事实源**）。
   **仍不做租户维度过滤**——`t_reconcile_report` 与 `t_eval_report` 是平台级数据（对账为全表扫描），
   真实鉴权接入时应在此处升级为角色校验。
5. **前端引用面板永远 0 条**（接口层完全看不见，见下节「浏览器实测」）：`messages.value.push(turn)` 后
   继续改**原始对象**，绕过 Vue 代理拦截 → 数据变了但不重渲染。修正：push 后从数组取回代理。
6. **「功能存在但不可达」：内联引用标记前端实现了、后端从不产出**——编排的 system prompt 一直要求
   「引用以 [序号] 标注」，C 端 `MarkdownBlock` 也早已实现 `[n]` → 可点击引用上标
   （`citationCount` + `pick-citation` 事件），但 `MockModelClient` 从不产出标记。
   后果：FR-APP-02 的「正文里点引用跳转」在演示中**实际不可用**，而侧栏文案却写着「回答中的 [序号]
   与上面条目一一对应」——**界面在替不存在的功能做承诺**。
   这类缺陷比「没实现」更隐蔽：没有报错、没有 TODO，只有真去点一次才会发现。
   **修正**：替身按检索片段编号产出 `[1][2]…`（与真实模型遵循同一句 system prompt 的行为对齐），
   并用 3 个单测固化（含「0 命中时不得产出任何标记」——否则上标会指向不存在的条目）。

### 本轮修订的测试口径
`mvn -B -ntp -o verify` **112 测试全绿**；评测门禁单类复跑 `total=24 passed=24 denyBlocked=7/7
duplicateTickets=0 p95=52ms avgCost=0.000509`（演示单价；P95 逐轮抖动：首轮 44ms / 本轮 52ms）。
`aiwarden-web/` 不在 Maven 生命周期内，其验证口径是 `pnpm build`（含 `vue-tsc --noEmit`）+ dev server 冒烟
（已写入该目录 README）。

### 浏览器实测（2026-10-10 补：无头 Chrome + CDP 真实交互，非仅接口层）

前一轮「端到端」只到 HTTP 层（curl），**接口全对但界面可能仍错**——为堵这个缺口，
本轮用无头 Chrome 通过 CDP 驱动真实交互（填输入框 → 点「发送」→ 等渲染落定 → 截图 + 读组件状态），
四个页面逐一验证，并**因此抓到一个接口层完全看不见的真缺陷**：

| 页面 | 实测结果 |
|---|---|
| `/chat` C 端 | 用户消息 / 流式回答 / **引用溯源 2 条** / 步骤时间线 5 步 / 本次成本 ¥0.000356 / 结局「正常回答」全部正确渲染 |
| `/admin/consistency` | 真实报告（reportId 17）、四项指标卡、口径说明、Trace 区块明确标「待 M4」 |
| `/admin/usage` | **真实明细 4 条 / 总 402 tokens**（Kafka 修复前此页恒为空数组） |
| `/admin/eval` | 本地库无数据 → 404 且如实显示后端原文与原因说明（预期行为） |

**缺陷 5（接口层无法发现，已修）**：`ChatView` 里
`const turn = newTurn(); messages.value.push(turn)` —— `push(raw)` 之后模板渲染读的是 **Vue 代理**，
而局部变量 `turn` 仍指向**原始对象**，后续 12 处 `turn.citations.push(...)` / `turn.outcome = ...`
全部绕过代理的 set/add 拦截：**数据确实变了（DevTools 读 `setupState` 能看到 2 条）、面板却停在初始状态**
——检索明细写着 `hits=2`，而引用侧栏永远「0 条」。
修正：`messages.value.push(newTurn())` 后**从数组取回代理**（`messages.value[len-1]`）再改。
**方法论沉淀**（已入 [`AGENTS.md`](../../AGENTS.md) §4）：这类「数据对但 UI 不对」的问题靠看截图会绕很久，
用 CDP **同时**读 `setupState` 与 DOM，再做「从代理直插一条 → 观察 DOM 是否跟随」的对照实验即可一次定性。

### 本机环境已知限制（与代码无关，如实记录）

~~`docker compose up -d` 起 Kafka 后，host 侧 `localhost:9092` 发布端口转发不通……~~
**已于同日解决并复测（2026-10-10，保留原因分析以免后人重踩）**：
根因是 **Windows 把 TCP 9092 划进了保留端口区间**（`netsh interface ipv4 show excludedportrange protocol=tcp`
→ `9003-9102` / `9103-9202`），**被保留的端口宿主机上任何进程都不允许 bind**——因此 Docker Desktop
的发布**既不 bind 也不报错**、`netsh portproxy` 亦不生效，表现为「容器 healthy、宿主机无监听者、
客户端一路 `Connection to node -1 (localhost:9092) could not be established`」。
绕行已内置进 `docker-compose.yml`：kafka 内部端口 `19092/19093`，由 `kafka-proxy`（socat，compose 网络内直连）
顶到宿主机 **29092**，应用侧用 gitignore 的 `application-local.yml`（profile `local`）指向 29092。
**复测证据**：outbox 31 条全 `SENT`、文档摄入 `PENDING → INDEXED`、计量落 `t_llm_call_log`、
`/api/v1/admin/usage` 返回真实明细。**Linux / CI 不需要这套绕行**（判据与步骤见 AGENTS §4）。
