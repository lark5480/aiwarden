# 失败索引（FAILURE.md）

> **这份文件是什么**：本项目走过并被记录在案的**失败 / 放弃 / 未达成**的**索引**——
> 每条一行结论 + 指向正文的链接。**交付物定义见 [`PRD.md`](PRD.md) §8.1。**
>
> **它不是什么**：不复述机制、不写 RCA 过程、不填实测数字。解释写第二句即视为文档缺陷
> （纪律见 [`../AGENTS.md`](../AGENTS.md) §2「一条事实只有一个正文」）。
>
> **登记纪律**：只增不改；后续翻案立新条目，不回改旧条目（与 `docs/adr/` 同一历史逻辑）。
> 「登记时点」列**照抄来源自己的标注**，来源没写日期的就写「来源未标日期」，不补造。

---

## A. 被推翻 / 校准的调研结论

| 登记时点 | 一行结论 | 正文落点 |
|---|---|---|
| 2026-10 | 「35% 岗位要求 AI」类百分比一律禁用（培训机构来源） | [`research/README.md`](research/README.md) §口径细节 · [`PRD.md`](PRD.md) §15 条 1 |
| 2026-10 | RuoYi-AI「官方自认」表述被推翻，只能写「第三方实践与社区分析指出」 | [`research/README.md`](research/README.md) §序列关系 · [`PRD.md`](PRD.md) §15 条 3 |
| 2026-10 | MaxKB4j `dualWrite` 证据未经源码级核验，不得引用 | [`PRD.md`](PRD.md) §15 条 3 |
| 2026-10 | 两套命中率口径不同源（12/14 与 6/13），对外一律按 6/13 | [`research/README.md`](research/README.md) §口径细节 |

## B. 裁剪掉的承诺与放弃的选型

| 登记时点 | 一行结论 | 正文落点 |
|---|---|---|
| 2026-10-07（裁决 19） | P4b「预算决定推理档位」整条砍除，地基保留 | [`PRD.md`](PRD.md) §10 · [`research/06`](research/06-PRD修订裁决清单.md) 裁决 19 |
| 2026-10-06 | 语义缓存（原 FR-RET-04）裁剪 | [`PRD.md`](PRD.md) §10 Out of Scope |
| 2026-10-06 | 分库分表 / ShardingSphere、gRPC 内部调用、算力档位——全部裁剪 | [`PRD.md`](PRD.md) §10 · [`../AGENTS.md`](../AGENTS.md) §2 自查 2 |
| 2026-10-06（裁决 4） | K8s/Helm + HPA 从主线降为 M4 可选实验、非交付承诺 | [`PRD.md`](PRD.md) §6 / §10 |
| 2026-10 | 多租户隔离三档 → 行级单档（SHARED_TABLE）实现，三档降为设计文档 | [`PRD.md`](PRD.md) §5.1 / §9 |
| 2026-10 | HITL 三态 → 只保留工具级二态审批开关 | [`PRD.md`](PRD.md) §10 · [`adr/ADR-009`](adr/ADR-009-tool-side-effect-governance.md) |
| 2026-10 | B 端控制台 7 页 → 3 页 | [`PRD.md`](PRD.md) §10 Out of Scope |
| 2026-10 | 评测样本 60 条 → 20–30 条规模口径（含 20 条降级开关） | [`../AGENTS.md`](../AGENTS.md) §2 自查 2 · [`PRD.md`](PRD.md) §5.9 |
| 2026-10-06（ADR-002） | WebFlux 评估后放弃，改 WebMVC + 虚拟线程 | [`adr/ADR-002`](adr/ADR-002-virtual-thread-webmvc.md) 放弃记录 |
| 2026-10-07（ADR-004） | Kafka 曾定「第 4 周末判定退化 Redis Streams」的止损点，实测后保留 Kafka | [`adr/ADR-004`](adr/ADR-004-keep-kafka.md) · [`PRD.md`](PRD.md) §11 风险 7 |

## C. 写出来才发现不生效的机制缺陷

| 登记时点 | 一行结论 | 正文落点 |
|---|---|---|
| M1 初版 | 幂等仲裁键混用「重复投递」与「合法状态流转」，删除事件被当重复静默跳过 | [`adr/ADR-005`](adr/ADR-005-outbox-idempotent-consumption.md) 修订段 · [`../AGENTS.md`](../AGENTS.md) §4 |
| M2 | 「HNSW 三重假绿」：小表走精确路径 / 入口点落在可见团 / 审计被事务回滚 | [`adr/ADR-008`](adr/ADR-008-visibility-acl-model.md) 修订段 · [`../AGENTS.md`](../AGENTS.md) §4 |
| 来源未标日期 | 只加 `flyway-core` 时 Flyway 静默不执行任何迁移（无日志、无表） | [`../AGENTS.md`](../AGENTS.md) §4 |
| 来源未标日期 | `CommonErrorHandler` 候选不唯一 → 自定义重试与 DLT 一起失效且不提示 | [`../AGENTS.md`](../AGENTS.md) §4 |
| 切片③④联调实测 | 装配模块的 test scope `lettuce-core` 挤掉运行期依赖：`mvn verify` 全绿而 `java -jar` 启动失败 | [`../AGENTS.md`](../AGENTS.md) §4 · [`adr/ADR-012`](adr/ADR-012-chat-orchestration-and-eval.md) 缺陷表 |
| 切片③④联调实测 | Micrometer 导出剥掉 Gauge 名尾部 `_total` → 按注册名抓取的面板永远空 | [`../AGENTS.md`](../AGENTS.md) §4 · [`adr/ADR-012`](adr/ADR-012-chat-orchestration-and-eval.md) 缺陷表 |
| 来源未标日期 | Jackson 属性名写成 `use-jackson-2-defaults`（多一个连字符）静默失效 | [`../AGENTS.md`](../AGENTS.md) §4 · [`adr/ADR-001`](adr/ADR-001-bootstrap-versions.md) 修订段 |
| 切片④实测 | MCP SDK handler 不在 Servlet 线程，ThreadLocal 身份不可用 | [`../AGENTS.md`](../AGENTS.md) §4 |
| 2026-10-07 实测 | 改共享契约只跑 `test-compile` → 半旧 class 造成成片假崩溃 | [`../AGENTS.md`](../AGENTS.md) §4 |
| 2026-10-07 实测更正 | 先前把容器测试失败归因于 `@TestInstance(PER_CLASS)`，真触发条件是 `@DynamicPropertySource` | [`../AGENTS.md`](../AGENTS.md) §4 |
| 2026-10-10 实测 | 前端把 mutation 打在响应式代理之外：数据变了但不重渲染 | [`../AGENTS.md`](../AGENTS.md) §4 · [`adr/ADR-012`](adr/ADR-012-chat-orchestration-and-eval.md) 缺陷表 |
| M3 评测首轮 | `expectedDocs`「语义相关必命中」在哈希伪嵌入下是抛硬币，该类断言删除 | [`adr/ADR-012`](adr/ADR-012-chat-orchestration-and-eval.md) 修订段 · [`STATUS.md`](STATUS.md) §3 |

## D. 承诺与默认配置之间的缝隙、以及未达成项

| 登记时点 | 一行结论 | 正文落点 |
|---|---|---|
| 2026-10-10（裁决 22） | 内联引用标记前端已实现、后端从不产出——**功能存在但不可达** | [`STATUS.md`](STATUS.md) §3 |
| 2026-10-10 | `require-approval` 缺省为空 → 人工确认卡片默认永不出现，演示需显式开参数 | [`STATUS.md`](STATUS.md) §3 |
| 2026-10-10（裁决 22） | 收口前 `/admin/consistency/*` 连租户都没校验（裸扫全表即返回报告） | [`STATUS.md`](STATUS.md) §3 |
| 裁决 18 | P1 实际执行的是「5 轮单次断言 ≤5s」，P95 分位数尚未度量 | [`STATUS.md`](STATUS.md) §2 结论表 · [`PRD.md`](PRD.md) §12 |
| M3 | P4a 四维归因当前只覆盖工具链路，模型调用维度依赖 M4 的 OTel span | [`STATUS.md`](STATUS.md) §2 结论表 |
| M3 | 评测走 Mock 替身，录制回放 fixture 待真实端点补录（不伪造数据） | [`STATUS.md`](STATUS.md) §3 · [`adr/ADR-012`](adr/ADR-012-chat-orchestration-and-eval.md) 决策 3 |
| — | 治理税的审计留痕开销 / 每请求总开销 / 吞吐拐点 / P99 未测 | [`STATUS.md`](STATUS.md) §2 结论表 |
| — | P5 断点续跑整条待验证（M4） | [`STATUS.md`](STATUS.md) §2 结论表 · [`PRD.md`](PRD.md) §8 |

## E. 环境类静默失效（与项目代码无关）

| 登记时点 | 一行结论 | 正文落点 |
|---|---|---|
| 2026-10-10 实测定位 | Windows 把 TCP 9092 划进保留端口区间 → Docker 不 bind 也不报错，outbox 全卡 `PENDING` | [`dev/troubleshooting.md`](dev/troubleshooting.md) §2 |
| 2026-10-07 实测 | 本机 Testcontainers 的 npipe 探测偶发失败，且删配置只对当次生效（策略会被写回缓存） | [`../AGENTS.md`](../AGENTS.md) §4 |
| 2026-10-10 实测 | 本地开发库的评测报告页必为 404（`t_eval_report` 只存在于临时容器库） | [`dev/troubleshooting.md`](dev/troubleshooting.md) §5 |
| 2026-10-10 实测 | 残留的旧实例进程占着 8080 且跑旧 profile，同时表现为「Kafka 连不上」和「端口占用」 | [`dev/troubleshooting.md`](dev/troubleshooting.md) §3 |
| 2026-10-10 实测 | 前端只 bind 了 `[::1]:5173`，`127.0.0.1:5173` 被拒；`file://` 直开 `dist` 是纯白页——「什么都看不到」不等于前端坏了 | [`dev/troubleshooting.md`](dev/troubleshooting.md) §6 |
| 2026-10-10 实测 | 一致性报告 37 份里不一致数**全为 0**（「清单为空」是正确空态却读起来像故障）；孤儿向量曲线需手动采样、且不累积历史 | [`dev/troubleshooting.md`](dev/troubleshooting.md) §7 |

---

**当前覆盖**：A 4 条 · B 10 条 · C 12 条 · D 8 条 · E 6 条（共 40 条，M1–M3 已登记部分）。
M4 收官时按 [`PRD.md`](PRD.md) §8.1 增补本期条目并逐条核对指针仍有效。
