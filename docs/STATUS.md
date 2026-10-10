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

**M3 完成**（2026-10）——M2 的 MVP 基线 + M3 四个切片全部落地：

| 里程碑 | 切片 | 内容 |
|---|---|---|
| M2（已完成） | ① | P2 检索层隔离（可见集四级下推 + 20 条越权样本门禁） |
| M2（已完成） | ② | P3 工具副作用治理（幂等键 + 补偿 + 二态审批 + 故障注入用例） |
| M2（已完成） | ③ | P4a 配额强一致（预扣减 / 幂等 / 对账 + 审计留痕） |
| M2（已完成） | ④ | 治理税四项输出 + MCP 最小版 |
| M3（已完成） | ① | 问答编排内核（`ModelClient` SPI + Mock 替身 + 录制回放双轨 + SSE 七类事件） |
| M3（已完成） | ② | 评测门禁 24 条样本（引擎三件套 + 全栈驱动 + `t_eval_report` + 读取 API） |
| M3（已完成） | ③④ | Vue 3 前后端 `aiwarden-web`（C 端四要素 + B 端三页；独立 pnpm 工程） |
| M3（已完成） | ⑤ | 文档收尾回填（本文件 / PRD v2.2 / README / 06 裁决 21 / ADR-012 修订段） |

**M0+M1+M2 = MVP，P1 / P2 / P3 / P4a 四项承诺成立**；**M3 两项硬交付（评测门禁 + 前后端）均已达成**
（数据见 §2），并已用**真实 HTTP 端到端联调**验证：SSE 帧序、引用溯源、human_handoff → approve、
断网重放 `replayed=true` 且工单数 == 1、跨租户 deny 与审计留痕。承诺结论表见 [`README.md`](../README.md) 第一屏。

## 2. 可复现的验证口径

- 本地 `mvn -B -ntp -o verify`：**112 测试全绿**（0 失败 0 跳过）；
  **单次运行口径**（`clean` 后跑一次），机器 i7-7700 4 物理核 / 8 逻辑核 + 32GB。
  其中 M3 切片①② 净增 10（`ChatSseContainersTest` 9 + `ChatEvalGateContainersTest` 1），M2 收尾为 102。
- **评测门禁结论**（`ChatEvalGateContainersTest` 单类复跑，2026-10-10）：
  `total=24 passed=24 denyBlocked=7/7 duplicateTickets=0 p95=52ms avgCost=0.000509 元`
  ——24 条样本 100% 通过、风险样本拦截 7/7、重复建单 0。**P95 为逐轮抖动值**
  （首轮 44ms，本轮 52ms，nearest-rank 口径，样本量 24）；成本为**演示单价口径**，非真实价目表。
- **前端独立口径**：`aiwarden-web` 是独立 pnpm 工程，**不进 `mvn verify`、不计入上面的 112**；
  其验证是 `pnpm build`（含 `vue-tsc --noEmit` 类型检查）通过 + `pnpm dev` 启动无编译错误，
  并已用真实后端做端到端联调（经 Vite 代理 5173→8080 打通 `/api`、`/actuator/prometheus` 与 SSE 流）。
- **启动命令口径（多模块，实测）**：`mvn -B -ntp -DskipTests install`（一次；改了兄弟模块后重跑）
  → `mvn -B -ntp -pl aiwarden-start spring-boot:run`。**不能写成 `-pl aiwarden-start -am spring-boot:run`**：
  reactor 里每个模块都会执行该 goal，根 pom 无 main class 即报 `Unable to find a suitable main class`；
  去掉 `-am` 又因兄弟模块不在本地仓库报 `Could not resolve dependencies`。完整三种死法与替代方案见
  [`AGENTS.md`](../AGENTS.md) §4 与 [`README.md`](../README.md)「本地怎么跑起来」。
- **启动冒烟（本轮新增的必做验证）**：`mvn -pl aiwarden-start -am package -DskipTests` + `java -jar` 能启动——
  因为**测试全绿不等于应用能启动**（本轮实测：装配模块的 test scope 依赖把运行期 Redis 客户端挤掉，
  112 个测试全绿而 `java -jar` 直接失败）。判据与完整坑见 [`AGENTS.md`](../AGENTS.md) §4。
- 本地 Testcontainers 偶发 npipe 抖动的复现命令见 [`AGENTS.md`](../AGENTS.md) §4；CI（Linux）不受影响。
- GitHub Actions 已在 push(main) / PR 上触发；**CI 结果以流水线为准**——本开发环境无法直连 github.com
  （`web_fetch` 解析到非公网 IP 被拒），仓库内不复制 CI 结论。
- 治理税四项实测（P95）：可见集计算 **3.2ms** / filter 下推 **7.1ms** / 计量事件 **0.9ms** / 配额检查 **4.3ms**；
  **审计留痕开销与总开销 / 吞吐拐点 / P99 归 M4**。

## 3. 已知边界（不在承诺范围，主动交代）

- **不承诺检索质量**（召回率 / 引用准确率）——承诺的是数据面确定性（PRD §3 边界声明 B1）。
  评测门禁同理：**验证的是「治理管道对任意模型决策的约束执行」**，不验证「真实模型会不会做出该决策」
  （[ADR-012](adr/ADR-012-chat-orchestration-and-eval.md) 决策 3）。首轮曾用 `expectedDocs` 断言「语义相关必命中」，
  实测在哈希伪嵌入下是抛硬币，已删除该类断言——门禁只断言可确定判定的数据面。
- **一致性是「有 SLO 的可对账」**，不是绝对一致（B2）；残余窗口可查询、有指标、可修复。
- **P1 的 SLO 口径**：实际执行的是「5 轮单次断言 ≤ 5s」，**非 P95 分位数**（分位数留 M4 压测）。
- **P4a 四维归因**：工具链路四维齐备；**模型调用维度依赖 M4 的 OTel span，当前未实现**。
- **M3 模型侧**：无真实模型端点，问答链路走 **Mock 替身**（确定性剧本）；录制回放机制已就绪，
  **fixture 待真实端点补录**（不伪造数据）。单轮问答 + 会话标识，多轮上下文与 Checkpoint 归 M4。
- **B 端管理接口只校验租户、不校验主体**（`/api/v1/admin/audit`、`/usage` 实测只带租户头也返回 200；
  而 `/api/v1/chat` 与检索入口会拒绝缺主体）——M3 身份头是「认证层输出的模拟」，租户级行隔离已生效，
  **口径统一留待真实鉴权接入**。详见 [ADR-012 修订段](adr/ADR-012-chat-orchestration-and-eval.md)缺陷 4。
- **前端不在 Maven 生命周期内**：`aiwarden-web/` 是独立 pnpm 工程（**不进 `mvn verify`、不计入测试数**），
  验证口径是 `pnpm build`（含 `vue-tsc --noEmit`）+ dev server 冒烟——**CI 绿不等于前端可构建**。
- **本机 Kafka 端口转发不通**（`docker compose` 起 Kafka 后 host 侧 `localhost:9092` 拒绝连接，
  容器内正常）：outbox 停 PENDING、摄入与计量消费不动、用量明细为空。M1/M2 的 Kafka 验证在容器测试内完成，
  不受影响；真实长链路演示前需先解决本机端口转发。
- **未做**：P4b（预算降级，裁决 19 砍除）、P5（断点续跑，M4）、用户鉴权（M3 身份头为认证层输出的模拟）；
  **`t_llm_call_log.cost` 回填与 OTel span 归 M4**。

## 4. 下一步

- **M4**：P5 断点续跑 + OTel 全链路 Trace + 压测报告（P95/P99 与吞吐拐点）+ K8s 可选实验，
  以及上一条「B 端主体校验口径统一」。

里程碑定义见 [`docs/PRD.md`](PRD.md) §8（唯一权威源）。

## 5. 公开范围

本仓库为**公开版**。以下属**内部材料，存放于仓库外**——**不要把它们加回本仓库**：

- 编号 **01、02** 的两份前期调研文档；
- `docs/research/inbox/` 原始报告；
- 个人规划类文档（简历 / 作品集策略等）。

正文中对它们的引用**保留文字叙述、不提供链接**，以免出现死链（[`README.md`](../README.md) 文末同款声明）。
