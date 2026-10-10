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
