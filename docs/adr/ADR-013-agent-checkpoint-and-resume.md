## ADR-013 · Agent Checkpoint 与断点续跑（确定性 DAG + 版本 CAS + 崩溃注入验收）

**状态**：已采纳（2026-10-19）｜M4 开工前落盘

### 背景

P5 是 MVP 之外的一条承诺：Agent 执行持久化为状态机，**进程重启 / 超时 / 人工中断后从最近检查点续跑，
且重放不产生重复副作用**（PRD §5.7 FR-CKPT-01~04）。M3 的编排是进程内线性管线：步骤只在 SSE 时间线里
可见，进程一死，进度与中间结果全部消失；重放只能靠客户端重新提问。

风险不在「能不能存表」，而在**崩溃窗口**：工具的副作用（建单）已提交、检查点还没提交时进程消失——
此时「从最近检查点续跑」必然重做工具步骤，正确性只能由 P3 的幂等键兜底。**这个窗口无法用同 JVM 的
异常 mock 证明**，必须让被测进程真的死掉。

### 决策

1. **确定性 DAG（10 个节点）**：`init → visibility → retrieval → prompt → model → metering → tool →
   cost → outcome → finalize`。节点只读前序步骤的输出快照，不持有运行期可变状态；每一步完成后落
   `t_agent_step`（步骤号 + 输入 / 输出快照 + 版本号），运行主体落 `t_agent_session`。
2. **步骤执行与检查点提交分离**：节点执行不包在数据库事务里（模型调用可能很久）；检查点提交是独立短事务。
   这承认了崩溃窗口的存在，也是为什么正确性必须落在「幂等」而不是「事务」上。
3. **版本 CAS**：提交步骤时 `UPDATE t_agent_session SET version = version + 1 WHERE version = ?`
   且与步骤行插入**同事务**；冲突即拒绝（FR-CKPT-03「过期实例必须丢弃」）。步骤行
   `UNIQUE(session_id, step_no)` 是第二道防线。
4. **租约独占**：`owner / lease_until` 保证同一 run 只有一个执行实例；崩溃实例的租约过期后，续跑实例
   用 CAS 接管；租约未过期时续跑返回 409，而不是两个实例并行执行副作用。
5. **续跑 = 重建，而不是复用**：从 `t_agent_step.output` 反序列化前序输出，构造**新的**节点与状态对象；
   旧实例（可能在另一个进程里）直接丢弃。这是 FR-CKPT-03 那条「实例复用必须丢弃过期实例」的落地方式。
6. **工具幂等是第二道防线**：崩在「副作用之后、检查点之前」时，续跑会重做 tool 节点，但幂等键
   （businessKey + session + 步骤指纹）命中 `t_tool_invocation` 的 SUCCEEDED 终态 → `replayed=true`，
   `t_ticket` 不增加。tool 步骤的检查点已提交时，续跑直接复用快照，**根本不重做**。
7. **崩溃注入用独立进程**：`aiwarden.agent.checkpoint.crash-before-checkpoint=<node>` 让该节点动作完成后、
   检查点提交前 `Runtime.halt(137)`。测试父进程用 `Process.waitFor` 观察退出码 137，再启动第二个进程续跑；
   这比同 JVM 抛异常更接近 `kill -9`（无关闭钩子、无 flush）。
8. **续跑接口是 JSON 而不是 SSE**：崩溃 / 断连之后原连接已不存在，续跑的观察者是重新连上来的人；
   要的是确定性的「结局 + 完整时间线 + 成本」，不是逐 token 体验。接口仍要求租户 + 主体，且运行必须
   属于当前 user（否则 403）。

### 依据

- PRD §5.7 FR-CKPT-01~04、§8 M4 验收（`kill -9` 续跑且副作用不重复）、§9 的 `t_agent_session` /
  `t_agent_step` 数据模型；
- P3 已有 `t_tool_invocation` 唯一键 + 终态重放（ADR-009）——P5 的「重放不重复副作用」不是新机制，
  而是复用 P3 的幂等裁决；
- ADR-003 的显式 capture/apply 方法论：续跑需要恢复主体上下文，不能依赖线程可复用性。

### 代价与放弃

- **每请求多 10 次数据库写入**（每步一次 checkpoint + run 状态）。端到端延迟因此上升：压测 C=1 时
  p50 约 35ms（含检索、模型 Mock、工具与 10 次检查点写），其中检查点本身是主要增量。
  这是 P5 的明确代价——要可续跑就必须持久化进度，不接受「只在内存里记进度」。
- **检查点快照会重复存储前序上下文**（prompt / model 输出等），演示规模可接受；大流量下应改为
  「输出引用 + 对象存储」而不是全文 JSONB，本轮不做。
- **不实现分布式协调器**：租约 + CAS 足以防止两个实例同时推进；跨机房 / 时钟偏斜场景需要更强的一致性
  协议，超出单机演示范围。
- **内部错误（FAILED）不做自动续跑**：同一输入重放只会得到同一错误；留给人看检查点，而不是无限重试。
- **多轮上下文 / 并行工具调用仍不做**：P5 只承诺「同一次运行的续跑」，不把 M3 的单轮范围扩成通用 Agent。

### 验证

| 断言（PRD M4 验收） | 证据（随 `mvn verify`） |
|---|---|
| `kill -9` 后续跑成功、副作用不重复 | `AgentCrashResumeContainersTest` 场景 1：崩在 tool 之后 / 检查点之前 → 续跑 `replayed=true`，工单数 **1**，调用账本 **1** 条 |
| 从已完成检查点续跑不重做副作用 | 同测试场景 2：崩在 cost → 续跑从 cost 继续，tool 步骤版本保持 7、`replayed=false` |
| 版本 CAS / 租约拒绝过期实例 | `AgentCheckpointStoreContainersTest` 4 用例（版本冲突、租约独占、终态版本校验） |
| 崩溃实例租约未过期时不得并行 | `AgentCrashResumeContainersTest` 场景 3：续跑 409 / 非零退出，运行状态仍 `RUNNING` |
| 人工中断（ABORTED）后按需续跑 | 同测试场景 4：首个 token 后断连 → 运行 `ABORTED`、工具零执行；续跑把 `ABORTED` CAS 回 `RUNNING` 并完成，工单 1 |
| 检查点时间线可按 traceId 还原 | 同测试断言 10 个步骤共享同一 `trace_id`（含崩溃前后两个进程） |

## 修订（2026-10）

**计量步骤的重复副作用窗口已补齐**（原文「副作用只发生在 tool 节点」仅对工具成立）：
计量事件带 `dedupKey`（模型 `model:<runId>:<stepNo>`、工具 `tool:<invocationId>`），
`t_llm_call_log.dedup_key` 唯一索引 + 消费端 `ON CONFLICT DO NOTHING`；新增 crash-at-metering 用例
（崩在 metering、续跑后 outbox 有 2 条同键模型事件，消费后只落 1 条明细）。
嵌入计量暂不带键（摄入侧由 `t_ingest_ledger` 幂等保护）。
