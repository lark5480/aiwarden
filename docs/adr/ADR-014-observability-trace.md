## ADR-014 · 全链路 Trace（OTel + 四类异步边界 + traceparent 持久化）

**状态**：已采纳（2026-10-19）｜M4 开工前落盘

### 背景

FR-OBS-01~05 要求：一次问答从「接收请求 → 可见集 → 检索 → Prompt → 模型 → 工具 → 后处理」在同一
traceId 下，每步一个自定义 span；traceId 跨**线程池 / Kafka 消费 / 定时任务 / 虚拟线程**四类边界不丢；
关键业务 ID（租户 / 会话 / 步骤 / 单据号）注入 span，能按 traceId 还原一次调用。

M3 的 SSE 时间线只是**本次连接可见**的耗时，进程崩溃或连接断开后无法还原；P4a 的模型维度归因也因此
一直缺 trace 上下文（STATUS §3 的未做项）。

### 决策

1. **实现选型**：`spring-boot-starter-opentelemetry`（Boot 4.1.1 管理版本）+ OTLP → OTel Collector →
   Tempo；Micrometer `Tracer` / `Propagator` 是两个抽象边界，业务代码不直接依赖 OTel SDK。
2. **span 树**：`http.chat`（请求）→ `agent.run`（新建运行）/ `agent.resume`（续跑）→
   `agent.node.<name>`（10 个 DAG 节点）→ `model.chat` / `tool.invoke.<tool>`（外部边界）。
   属性注入 `aiwarden.tenant_id / user_id / session_id / run_id / step_no / tool`。
3. **四类边界的显式传播**（不引入 TransmittableThreadLocal，沿用 ADR-003 的 capture → apply）：
   - **虚拟线程**：请求线程创建 `http.chat`，把 `Span` 显式 apply 到执行线程，异步任务结束时 end；
   - **线程池**：同一 API（`TraceSupport.withSpan`），由测试用固定线程池证明；
   - **Kafka 消费**：生产端把 W3C `traceparent` 写进消息头，消费端 `Propagator.extract` 建子 span；
   - **定时任务**：outbox 在**写入时**把 `traceparent` 落到 `t_outbox_event.trace_parent`（发送可能晚于
     请求几分钟，发送线程的 ThreadLocal 早已清空）；Relay 发布时按事件恢复父子关系并注入消息头。
4. **traceId 持久化与查询**：`t_agent_session.trace_id / trace_parent` 与 `t_agent_step.trace_id` 落库；
   SSE 响应头返回 `X-Trace-Id`；`GET /api/v1/agent/runs/by-trace/{traceId}` 返回该次运行的检查点时间线；
   span 明细在 Grafana → Tempo 按同一 traceId 查看。续跑用会话里保存的 traceparent 接回原 trace。
5. **测试不依赖外部 collector**：生产走 OTLP，测试用 OTel SDK 的 `InMemorySpanExporter` 收集 span，
   按 traceId 断言父子关系与属性；`management.tracing.export.otlp.enabled=false` 在 surefire 全局关闭导出。

### 依据

- PRD §5.8 FR-OBS-01~05、§8 M4「Trace 可按 traceId 还原一次调用」、裁决 4（OTel 为实现依赖，不可裁剪）；
- ADR-003：虚拟线程是一次性线程、池化线程会复用——统一显式捕获 / 恢复，而不是依赖 TTL；
- ADR-005：outbox 事件在事务内写入，traceparent 也必须与事件同事务落库，才能在异步发送时恢复。

### 代价与放弃

- **span 创建 / 结束不是零成本**：实测 p50 约 **12.8µs** / p95 **19.0µs**（单机、OTel SDK、内存 exporter
  之外的采样口径见压测报告）；每个请求约 12–15 个 span，合计约 0.15–0.3ms，相对治理链路可忽略，
  但它确实存在，不写成「零开销」。
- **Loki 日志关联未接入**：本轮只做 Metric / Trace 与业务 ID 的关联；日志仍走 stdout，没有日志采集与
  traceId 注入的日志查询。FR-OBS-04 的「三信号互跳」只完成了 Metric ↔ Trace 与 DB 业务 ID，如实标注。
- **Tempo 端到端未在本机跑通**：本机 Docker Hub 不可直连，observability 镜像无法拉取；trace 的正确性由
  OTel SDK 内存 exporter 的全链路测试证明，**Collector / Tempo 未加入 `docker-compose.yml`**
  （不写无法验证的镜像 tag），如实标注（见 STATUS §3）。
- **不对日志做手动 MDC 注入**：Micrometer 的 Slf4J 事件监听器可把 traceId 放进 MDC，但本轮没有配套的
  日志采集与查询，单独打开只会增加日志体积，不产生可验证能力。
- **采样率演示环境 1.0**：真实部署应按流量调低；采样策略不是本 ADR 的承诺。

### 验证

| 断言（PRD M4 验收） | 证据（随 `mvn verify`） |
|---|---|
| 一次调用同一 traceId、每步一个 span | `AgentTraceContainersTest`：真实 HTTP → 响应头 traceId；span 树含 `http.chat` / `agent.run` / 10 个 `agent.node.*` / `model.chat` / `tool.invoke.create_ticket`，父子关系与属性逐项断言 |
| 四类异步边界 traceId 不丢 | `TracePropagationTest` 4 用例：虚拟线程 / 固定线程池 / 定时任务（traceparent 捕获后另线程恢复）/ Kafka 消息头 |
| 跨进程续跑接回同一 trace | `AgentCrashResumeContainersTest`：崩溃前后两个进程的 10 个步骤共享同一 `trace_id`，outbox 的 `trace_parent` 含该 traceId |
| 可按 traceId 还原一次调用 | `GET /api/v1/agent/runs/by-trace/{traceId}` + 前端一致性页的 Trace 查询区块 |

## 使用配方：按 traceId 定位一次「答错」（FR-OBS-05）

> **这一节是运维配方的唯一正文**（2026-10 从根 `README.md` 迁入，裁决 32——README 只留一句 + 指针）。
> 上面的「决策 2 / 决策 4」定义了 span 树与 traceId 的落点，这里只回答「拿到一个 traceId，先看哪四个位置」。

一次问答的所有 span 共享同一 traceId（`X-Trace-Id` 响应头 / `t_agent_session.trace_id`）；按 traceId 查
`GET /api/v1/agent/runs/by-trace/{traceId}` 能看到 10 个节点的检查点时间线。**在 Grafana → Tempo 看 span 树
这一步本轮未实跑**（Collector / Tempo 未进 compose，本机拉不到镜像；span 树由 OTel SDK 内存 exporter 的
全链路测试逐名断言——边界见 [`../STATUS.md`](../STATUS.md) §3 ①）。

| 看的 span | 关键属性 / 输出 | 能排除什么 |
|---|---|---|
| `agent.node.retrieval` | `aiwarden.retrieval.hits` / `min_distance` | 命中 0 条或距离接近 1 → **不是模型幻觉**，是检索没给到上下文（B1） |
| `agent.node.prompt` | `snippets` / `tools` | 片段进了 Prompt、工具可见面正确 → 排除「上下文没传进去」 |
| `model.chat` | `aiwarden.model` / tokens | 模型确实被调用；输出文本在 `agent.node.model` 的检查点快照 |
| `tool.invoke.*` | `aiwarden.tool` / 状态 / `replayed` | 模板 / 工具参数错误 vs 治理拦截（deny / 重放） |

**把它套在真实案例上**：M3 评测首轮 6 条样本红——`expectedDocs` 断言「语义相关必命中」失败。四个位置逐一排除后：
检索有命中但 `min_distance` 接近 1（确定性哈希伪嵌入不保留语义），Prompt 与模型输出都正常——**定位为检索语义
边界（B1），不是 Prompt 理解偏差、也不是模型幻觉**，据此删除该类断言（该轮处置的决策记录在
[ADR-012 修订段](ADR-012-chat-orchestration-and-eval.md) 与 [`../STATUS.md`](../STATUS.md) §3，本处只讲怎么读）。
这正是「可观测」与「承诺边界」应当互相解释的地方。

**C 端与 B 端的时间线口径不同**（别当成矛盾）：C 端 SSE 只发 5 类 `step`（visibility / retrieval / prompt /
model / metering，tool·cost·outcome 各有独立事件），**完整 10 步**在上述按 traceId 的接口（DB 检查点）——
「10 步时间线」这句承诺指的是后者。
