# 决策记录（ADR）

> **这份文件是什么**：本项目的架构与关键技术决策记录，[PRD §7.4](PRD.md) 规定其为首批 ADR 的正式落点。
> **体例**：每条含 `状态 / 背景 / 决策 / 依据 / 代价与放弃 / 验证`。版本号类结论**必须能在 `pom.xml` 找到对应坐标**。
> **决策被演进时**：**不改写原文**，在条目末尾追加 `## 修订（YYYY-MM）` 段说明现状；整条废止则把「状态」标为 **已被取代** 并指向现行条目（这类条目保留的价值是「当时为什么会走到这一步」，不是现状）。
> **与代码的关系**：ADR 与 PRD §7.3 的 ArchUnit 规则互相引用。

**索引**

| 编号 | 标题 | 状态 |
|---|---|---|
| ADR-001 | 底座选 Spring Boot 4.1 + LangChain4j 1.21 | 已采纳（2026-10-06） |
| ADR-002 | Web 层虚拟线程 WebMVC（非 WebFlux） | 已采纳（2026-10-06） |
| ADR-003 | 租户上下文显式 capture/apply（不引入 TTL） | 已采纳（2026-10-06） |
| ADR-004 | 消息中间件保留 Kafka（决策点提前实测） | 已采纳（2026-10-07） |
| ADR-005 | Outbox 发布与幂等消费时序（轮询 Relay + 至少一次 + 唯一键仲裁） | 已采纳（2026-10-07） |
| ADR-006 | 对账只读发现、修复显式触发（不自动改数据） | 已采纳（2026-10-07） |
| ADR-007 | 可见集过滤机制：向量表 metadata filter 下推（JOIN 退场） | 已采纳（2026-10-07） |
| ADR-008 | 可见集 ACL 模型与下推算法（主体、四级语义、拒绝与审计） | 已采纳（2026-10-07） |
| ADR-009 | 工具副作用治理机制（幂等键状态机、补偿逆序、工具可见面） | 已采纳（2026-10-07） |
| ADR-010 | 配额强一致机制（Redis Lua 预扣减、幂等、对账、限流） | 已采纳（2026-10-07） |
| ADR-011 | 治理税度量方法（四项微计时口径）与 MCP 最小版取舍 | 已采纳（2026-10-07） |

---

## ADR-001 · 底座选 Spring Boot 4.1 + LangChain4j 1.21

**状态**：已采纳（2026-10-06）｜**取代** PRD v1.0 的「Spring Boot 3.5 + Spring AI 2.0」原选型（[06-PRD修订裁决清单 §3](research/06-PRD修订裁决清单.md) 反转）

### 背景

PRD v1.0 把「LangChain4j 1.x + Spring Boot 3.x」写进了版本红线，理由是「Spring AI 2.0 会迫使升 Spring Boot 4，而 LangChain4j 尚不支持 SB4，会造成迁移断裂」。第二组独立评审对这条前提做了**一手核验**，结论是前提已失效，且原方案自身踩在断供线上。

### 决策

- 底座：**Spring Boot 4.1.1**（4.1 线当前最新补丁）
- AI 层：核心件 `dev.langchain4j:langchain4j:1.21.0` + starter 线 `dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31`
- 版本红线随之反转：**不得**再写 SB 3.5 或 SB 4.0

### 依据（三条）

1. **LangChain4j 官方已适配 SB4**：自 1.13.0（2026-04）起设独立 `spring-boot4-starter` 线；1.20.0 提供 Jackson 3 opt-in 模块 `langchain4j-jackson3`。**「迁移断裂」这个反方前提已不存在。**
2. **SB 3.5 已 EOL**：OSS 支持止于 2026-06-30，末版 **3.5.16**；断供线后 158 条 advisory 中 96 条（61%）无 OSS 修复版。**一个把「版本红线」写进 PRD 的项目，自己踩在断供线上无法自圆其说。**
3. **SB 4.0 也将 EOL**：其 OSS 支持止于 2026-12-31，所以升级目标必须直接锁 4.1（OSS 至 2027-07-31），**不能停在 4.0**。

### Jackson 2/3 共存策略

默认 **Jackson 2**；引入 `langchain4j-jackson3` opt-in 模块即全库切 Jackson 3，**删依赖即回退**；应用层以 `spring.jackson.use-jackson-2-defaults=true` 兜底存量序列化行为。

### 代价与放弃

- **放弃 Spring AI 2.0**：它要求 SB4，且当时其治理挂点尚未就绪；本项目要治理的是「数据面确定性」，LangChain4j 的**框架中立**与向量库生态（30+）更合用。代价是失去 Spring 官方生态的自动配置便利——用 `aiwarden-core` 的 SPI 屏蔽，业务代码不直接依赖 LangChain4j API（PRD §7.3 ArchUnit 规则 2 强制）。
- **放弃 SB 3.5**：短期迁移成本更低，但等于把项目建在断供线上。
- **`-betaNN` 后缀的代价**：starter 模块是 beta 版号（见下），需要在 README / 答辩中主动解释——**不解释就等于留了个可以一句话击穿的点**。

### 验证（开工前已执行，2026-10-06）

| 检查 | 方式 | 实测结果 |
|---|---|---|
| SB 4.1 线是否已发布 | Maven Central `spring-boot-starter-parent` metadata | **4.1.0 / 4.1.1 已发布**（4.0.x 至 4.0.8；4.2 仍为 M2 里程碑） |
| SB 3.5 末版是否为 3.5.16 | 同上 | ✓ 3.5.x 止于 **3.5.16**（与 06 号文档的调研一致） |
| SB4 starter artifact 是否存在 | `mvn dependency:get -Dartifact=dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31` | ✓ **解析并下载成功** |
| 核心件坐标是否可用 | `mvn dependency:get -Dartifact=dev.langchain4j:langchain4j:1.21.0` | ✓ 成功 |
| 底座坐标是否可用 | `mvn dependency:get -Dartifact=org.springframework.boot:spring-boot-starter-parent:4.1.1:pom` | ✓ 成功 |
| starter 版本号形态 | 两条 starter 线的 metadata 对比 | SB3 线 `langchain4j-spring-boot-starter` 与 SB4 线**同为 `-betaNN` 序列**（最新版号一致 = `1.21.0-beta31`）→ 属其**集成模块发布惯例**，不是「API 不稳定」的标记 |

> ⚠️ **写 `pom.xml` 时的坑（已实测）**：核心件与 starter 的版本号形态**不同**——`langchain4j` 是 `1.21.0`，`langchain4j-spring-boot4-starter` 是 `1.21.0-beta31`。**照抄核心件版本号会直接依赖解析失败。**

> **本条闭合了** [05 §六](research/05-独立验证与交叉质询报告.md) 遗留项 1（原验证命令写作 `...spring-boot4-starter:1.21.0`，该坐标不存在，已按实测修正为 `1.21.0-beta31`）。

## 修订（2026-10）

M0 骨架落地后（`pom.xml` 已建立；Maven 3.9.9 / JDK 21.0.8 / SB 4.1.1 实测）追加两条实测结论，正文不改写：

- **双坐标共存已在真实构建中验证**：`aiwarden-start` 的 `mvn dependency:tree` 显示 `dev.langchain4j:langchain4j:1.21.0` 与 `dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31` 同树解析、无版本仲裁冲突；`@SpringBootTest` 上下文冒烟测试在 SB 4.1.1 下通过。
- **Jackson 共存实测**：SB 4.1.1 应用层默认 JSON 栈已是 **Jackson 3**（`tools.jackson.core:jackson-databind:3.1.5`，经 `spring-boot-starter-jackson`；注意 groupId 是 `tools.jackson`，不是 `com.fasterxml.jackson`）；LangChain4j 1.21.0 默认 Jackson 2（`com.fasterxml.jackson.core:jackson-databind:2.21.5`）。两方 groupId 不同、共存不冲突——上文「默认 Jackson 2」指 **LangChain4j 侧默认**。① 要把**应用层**回退到 Jackson 2 默认行为，设 **`spring.jackson.use-jackson2-defaults=true`**——⚠️ 属性名是 `jackson2`，**中间没有连字符**，写成 `use-jackson-2-defaults` 会**静默无效**（依据：`spring-boot-jackson-4.1.1.jar` 的 `spring-configuration-metadata.json`）；② 要让 **LangChain4j 侧**也切到 Jackson 3，再引入 `dev.langchain4j:langchain4j-jackson3:1.21.0-beta31`（同样以 `-betaNN` 形态发布）。

---

## ADR-002 · Web 层虚拟线程 WebMVC（非 WebFlux）

**状态**：已采纳（2026-10-06）

### 背景

本项目的主要负载形态是 **LLM 长阻塞 IO**（首 token 可达秒级），同时需要 SSE 流式返回，还要承载治理动作（可见集计算、配额检查、审计留痕）。

### 决策

Web 层采用 **Spring WebMVC + `spring.threads.virtual.enabled=true`**（Java 21 虚拟线程），**不采用 WebFlux**。

### 依据（四条）

1. **技能匹配**：存量 JD 里没有 WebFlux / Reactor 关键词，写上去不加分。
2. **场景匹配**：LLM 长阻塞 IO 正是虚拟线程的标准答案——用平台线程会被池大小卡住，用响应式则把复杂度引入了不需要它的地方。
3. **SSE 有实战可复用**：hospital-system 与 inkwell 已有两处 SSE 落地经验。
4. **「评估过 WebFlux 并放弃」本身是选型叙事**：能讲清为什么不选，比只用过一种更有说服力。

### 代价与放弃

- **放弃 WebFlux**：响应式模型与虚拟线程在本场景**收益重叠**，却额外引入 Reactor 的学习与调试成本（栈轨迹难读、阻塞调用易踩坑）。评估后放弃。
- **需要处理的坑**：JDK 21 下 `synchronized` 包住长 IO 会 **pinning**（虚拟线程被钉在载体线程上）。约定：**长 IO 段一律改用 `ReentrantLock`**；该问题在 JDK 24 由 JEP 491 彻底解决，代码注释需写明这一时间线。

---

## ADR-003 · 租户上下文用显式 capture/apply（不引入 TransmittableThreadLocal）

**状态**：已采纳（2026-10-06）

### 背景

FR-TEN-02 要求租户上下文贯穿 **HTTP → 虚拟线程 → Kafka 消费 → 定时任务** 四类边界；FR-OBS-03 要求明确虚拟线程与 `TransmittableThreadLocal`（TTL）的语义差异并写明结论。

### 决策

以 `aiwarden-common` 的 `TenantContext`（`ThreadLocal` + 快照 capture/apply + 缺失即抛 `MissingTenantContextException`）为**唯一**租户上下文载体：

- **不引入 TTL**：TTL 解决的是「池化线程复用导致装饰丢失」；虚拟线程是**不可复用的一次性线程**，TTL 的 decorate 语义不适用——两类线程形态统一走显式 capture/apply，一条规则覆盖。
- **不依赖 `InheritableThreadLocal`**：只在线程**创建**时刻继承，且执行器创建的线程继承的是「创建者线程」而非「提交任务者」的上下文，语义不可靠。
- **缺失即拒绝**：不回落默认租户（FR-TEN-02）；HTTP 侧由 `MissingTenantContextExceptionHandler` 统一转 400。

### 验证（单测证据，均随 `mvn verify` 执行）

| 边界 | 测试 | 断言要点 |
|---|---|---|
| 核心语义 | `TenantContextTest` | 作用域退出还原（含嵌套）/ 缺失拒绝 / 空值参数错误 |
| 虚拟线程 | `TenantContextVirtualThreadPropagationTest` | 显式快照在新虚拟线程内可见；未 apply 的虚拟线程 `require` 即拒绝 |
| Kafka 消费 | `TenantContextKafkaBoundaryTest` | 生产端盖戳 / 消费端恢复 / 缺头即拒绝（内存载体先冻结机制，真实 Kafka 适配在 M1） |
| 定时任务 | `TenantContextScheduledTaskBoundaryTest` | 池化线程作用域内可见、退出不残留（下一任务不串租户） |
| HTTP | `TenantContextHttpBoundaryTest`（start 模块） | 真实 Tomcat + 虚拟线程：请求头 → 控制器可见；缺头 → 400 |

### 代价与放弃

放弃 TTL 的「自动装饰」便利：调用方多写一行显式 capture/apply——换来一套规则同时覆盖虚拟线程与池化线程，语义可测。HTTP 过滤器与异常处理在 `aiwarden-start`（装配根）；M2 接入 API Key 后把租户来源从请求头切换为密钥解析即可，传播机制不变。

---

## ADR-004 · 消息中间件保留 Kafka（第 4 周末决策点提前执行的实测裁决）

**状态**：已采纳（2026-10-07）｜兑现 PRD §11 风险 7「Kafka → Redis Streams 决策点」——**提前至 M1 首日执行**（原文「第 4 周末」是 deadline 不是下界，提前执行不违背承诺）

### 背景

PRD §11 风险 7 给 Kafka 记了两笔账：运维负担与 **Testcontainers 反馈循环长**；并约定若收益不足退化为 Redis Streams。为避免「做完摄入管道再回头换实现」，M1 首日先做证伪实验：**判定标准实验前写死**（防确认偏误），再用同一组消费语义断言跑 Kafka 与 Redis Streams 两侧对照。

### 判定标准（实验前写死，2026-10-07）

- **退化条件（任一命中）**：a) Kafka Testcontainers 单类反馈循环 > 90s 且复用/预热后无法压入；b) 语义清单存在 Kafka 无法以合理复杂度覆盖的项；c) 消费语义上手成本无一项优于 Redis Streams（纯摩擦、零语义收益）。
- **保留条件**：语义全绿 + 反馈循环 ≤ 90s。
- 90s 依据：日常「改完就知道结果」的忍耐上限；超过它 TDD 会绕开测试跑。

### 实测数据（2026-10-07，Windows/JDK 21/Maven 3.9.9；镜像均已本地缓存）

| 维度 | Kafka `apache/kafka:4.0.0` | Redis Streams `redis:7.4-alpine` |
|---|---|---|
| ① 至少一次投递 | ✓ | ✓ |
| ② 手动 ack | ✓（`ack-mode=manual`） | ✓（PEL + XACK，未 ack 可查询） |
| ③ 失败重试 | ✓（FixedBackOff 重试 1 次，断言尝试数==2） | ✓（XCLAIM 重投） |
| ④ 死信 | ✓（DLT topic，断言原消息内容+尝试次数） | ✓（DLT stream，应用层流转） |
| ⑤ 幂等仲裁（DB 唯一键）可行 | ✓（以 ①② 为前提） | ✓（同） |
| 容器启动 | 5.19s | 1.39s |
| 单类测试耗时（surefire） | 18.07s（含 Spring 上下文） | 6.59s |
| 单类总墙上时间 | 27.5s | 13.3s |
| 实现形态 | 声明式（配置 + listener，~150 行） | 手写 PEL 轮询 / XCLAIM 调度 / DLT 流转（~100 行，语义全在应用层） |
| 镜像大小（CI 首拉成本） | 660MB | 57MB |

测试代码即证据：`KafkaRoundTripContainersTest` / `RedisStreamsRoundTripContainersTest`（`aiwarden-start` 的 `com.aiwarden.start.decision` 包，随 `mvn verify` 执行；全量含三容器 48.1s）。

### 决策

**保留 Kafka**——三条退化条件均未命中（27.5s ≪ 90s；语义 5/5；消费组 / 位移 / 重试 / DLT 均为框架级能力，Redis Streams 侧需全部手写）。技能叙事收益（补 MQ 事实标准缺口，PRD §7.1）成立。

### 代价与放弃

- **CI 首次拉取 660MB 镜像**（一次性 1–3 分钟）；全量 `mvn verify` 由 M0 无 Kafka 时的约 23s 增至 **48.1s**——为 Kafka 付的实测代价，可接受。
- **放弃 Redis Streams 作为主选**：它保留为**已验证的逃生梯**——对照测试留在仓库中，若未来摩擦超出预期，退化路径的可行性有代码级证据（本实验的第二个产出）。
- 本地开发不新增摩擦：compose 的 Kafka 是常驻容器（启动约 8s）。

### 观察点（不设新决策日，随 M1 事实校准）

M1 实现幂等摄入链路时记录真实摩擦（如消费测试 flaky 率）；若显著高于本实验数据，按 PRD §11.1 止损纪律处理（缩小承诺 + 如实披露），逃生梯已就绪。

---

## ADR-005 · Outbox 发布与幂等消费时序（轮询 Relay + 至少一次 + 唯一键仲裁）

**状态**：已采纳（2026-10-07）｜M1 切片②落地

### 背景

P1（删除即失效）主链路是「业务表 + outbox 同事务 → Kafka → 幂等消费 → 终态可查」。发布机制与消费仲裁有多个可选做法（CDC 抽 binlog vs 应用内轮询 relay；消费端恰好一次 vs 至少一次 + 幂等）。

### 决策

1. **发布用应用内轮询 Relay**（`OutboxRelay`）：`SELECT PENDING → 逐条发送（同步等 broker 确认）→ CAS 标记 SENT`，失败累计 retry_count（≥5 降 FAILED）。不用 Debezium/CDC：少一个重组件；发送与标记之间的崩溃窗口由消费端幂等兜底——**系统语义是「至少一次 + 幂等消费」，不追求恰好一次**。
2. **事件类型即 topic 名**（`document.index.requested` / `document.delete.requested`），聚合 id 作消息 key（同聚合有序）；租户随消息头跨进程传递（M0 载体接口 `TenantContextCarrier` 的首次生产接入：`KafkaHeadersCarrier`）。
3. **消费端唯一键仲裁**（`IngestLedgerGuard`）：`(doc_id, version)` 主键 + `ON CONFLICT DO NOTHING` 抢占；状态机 `PROCESSING → INDEXED / DELETED / FAILED`，**FAILED 允许重抢占**（与 Kafka 重试配合，避免重试消息被仲裁挡死）；PROCESSING 卡死的超时回收由对账任务兜底（切片④）。
4. **失败策略**：指数退避重试 ≤3 次（1s/2s/4s）→ `<topic>.DLT` 死信（FR-ING-04）。

### 依据（实测）

- `OutboxIngestLoopContainersTest`：写入 → 发布 → 消费 → ledger INDEXED 闭环；重复投递处理次数不变；租户经消息头跨进程还原断言通过。
- `IngestLedgerGuardContainersTest`：16 线程并发抢占恰好 1 个赢家（M1 验收②的仲裁层先行验证；端到端向量计数随切片③）。

### 代价与放弃

- **放弃 CDC**：换库 / 运维成本与演示价值不匹配；轮询延迟（默认 2s）占用「P95 ≤ 5s」删除 SLO 预算（发送 2s 节奏 + 消费近瞬时，仍在线内；最终实测值随 M1 验收回填）。
- **放弃「恰好一次」幻想**：at-least-once + 幂等是分布式系统的正确语义；好处是重试 / 重放天然安全——P3（工具幂等）与 P5（重放续跑）将复用同一方法论。

### 修订（2026-10，切片③实测补充）

**删除与索引共用 `(doc_id, version)` 账本行时的状态流转缺口（已在实现中修正）**：初版 `tryClaim` 仅允许 FAILED 重抢，
导致「索引完成（INDEXED）后的删除事件」被幂等仲裁**静默跳过**（向量清理不执行；HTTP 全链路测试以 `1/DELETED/INDEXED` 超时暴露，无指向性报错）。
修正：拆分 `tryClaimForIndex`（FAILED 可重抢）与 `tryClaimForDelete`（**INDEXED / FAILED 可重抢**——删除是索引之后的
正常状态流转；DELETED 为文档级终态，且 `markDeleted` 按 doc_id 将全部版本行标记为 DELETED）。证据：
`DocumentLifecycleContainersTest`（上传 → 索引 → 删除 → 产物清零）与 `IngestLedgerGuardContainersTest`
（INDEXED → 删除抢占 → DELETED 终态）随 `mvn verify` 执行。

---

## ADR-006 · 对账只读发现、修复显式触发（不自动改数据）

**状态**：已采纳（2026-10-07）｜M1 切片④落地

### 背景

P1 承诺「删除即失效有 SLO + 不一致可查询可修复」。对账任务发现残留（孤儿向量 / 卡死任务）后有两种演变：自动修复（发现即清），或只读发现 + 显式修复。

### 决策

**对账任务只读发现**（扫描 → 落 `t_reconcile_report` → 更新指标），**修复由显式动作触发**
（`POST /api/v1/admin/consistency/repair`，复用删除链路的幂等清理语义：先向量后切片、账本置 DELETED）。

### 依据

- **可观察性**：自动修复会让不一致清单在演示 / 运维视角永远为空——而对账的价值正是「发现并记录」；
  修复动作本身可追溯（报告 → 修复 → 复扫归零）。
- **安全边界**：repair 只处理 `t_document.deleted_at 非空` 的文档产物——语义上只是补做既定删除，
  不引入新状态；幂等可重跑。
- 测试证据：`ConsistencyReconcileContainersTest`（制造删除事件丢失缺口 → scan 报告 mismatch ≥ 2 →
  指标 ≥ 1 → repair → 复扫归零），随 `mvn verify` 执行。

### 代价与放弃

放弃「全自动自愈」叙事的一部分：演示录屏中「修复」需要一个显式触发（POST /repair）——
这反而与 FR-ADM-03「一键重试」的产品形态一致（M3 页面直接调用本端点）。

---

## ADR-007 · 可见集过滤机制：向量表 metadata filter 下推（JOIN 退场）

**状态**：已采纳（2026-10-07）｜M2 可见集机制定型（开工前落盘，实现后不再回填）

### 背景

M1 的检索最小版（`RetrievalService`）用 `JOIN t_chunk ... WHERE c.tenant_id = ?` 完成租户过滤——这是**过渡实现**。
M2 要实现 P2 的四级可见集（tenant ∩ org ∩ kbACL ∩ docACL），过滤载体必须先定型：
**继续 JOIN 过滤，还是把可见性条件作为 metadata filter 下推到向量表（t_vector.meta）？**

### 决策

**metadata filter 下推为主**：可见集条件与向量排序在**同一条针对 t_vector 的 HNSW 扫描**中表达
（`WHERE meta->>'tenantId' = ? AND meta->>'docId' = ANY(...) ...`）；`JOIN t_chunk` 仅保留「按 chunkId 回表取内容」的职责，**不再承担任何可见性过滤**。

### 依据（为什么不是 JOIN）

1. **风险同源、可控性不同**：JOIN 过滤不是「没有风险」——HNSW 候选 + join 验证同样是 post-filter（凑不满 K 静默发生）；
   且执行计划会在「hash join + 精确排序（无 ANN、全量算距离）」与「HNSW post-filter」之间随统计信息漂移，
   **应用层无从得知走了哪条**（性能与结果条数都不可控）。下推路径的行为可配、可测、可回退，与「确定性治理」主张相符。
2. **口径一致**：PRD §7.3 / §9 / P2 原文即为「metadata filter 下推」；t_vector.meta 的选型理由
   （「保证 filter 可下推」）只有真下推才成立——**本决策使 PRD 零改动**；反之需改写 §9 理由并降级 P2 话术。
3. **维度扩张**：可见集四级条件在 meta 上是一条 jsonb 条件 + 集合谓词；在 JOIN 上会随 ACL 模型膨胀为多表 JOIN / OR。

### 风险 8 的正面回答（本栈实测：PostgreSQL 16.15 / pgvector 0.8.7）

1. **iterative_scan 可用**（实测 GUC 存在：`hnsw.iterative_scan` / `hnsw.max_scan_tuples` / `hnsw.scan_mem_multiplier`）：
   查询级 `SET LOCAL hnsw.iterative_scan = strict_order` + `max_scan_tuples` 上限——绕回继续扫描直到凑满 K 或达上限；
2. **两段式精确回退**：下推路径返回 < K 且可见集规模大于返回数时，**显式**走精确过滤排序补足（不静默接受不足 K）；回退次数打点可观测；
3. **边界自洽**（B1/B2）：不承诺召回率，承诺不越权；「高选择性查询下的召回率」列入压测报告量化（§11 风险 8 原文已列）。

**实测（2026-10-07，独立可复现；5000 行 / 租户 A 占 1% / 过滤条件与距离故意不相关）**

前提：`EXPLAIN` 自证计划为 `Index Scan using idx_t_hnsw`（`Filter: tenant = 'A'`）。**这一步不能省**——
小表上规划器会退化成顺序扫描 + 精确排序，此时 `hnsw.*` 参数根本不参与，会得到「全对」的假结论（本 ADR 的第一版实验即栽在这里）。

| 配置 | 返回条数（目标 K=10） | 与精确答案重合 |
|---|---|---|
| `iterative_scan=off` / `ef_search=40`（**默认**） | **1** | 1 |
| `off` / `ef_search=1000`（合法上限） | 10 | 10 |
| **`strict_order` / `ef_search=40`** | **10** | **10** |
| `relaxed_order` / `ef_search=40` | 10 | 10 |
| `off` / 窄可见集（20 行可见，K=10） | **1** | — |
| `strict_order` / 窄可见集 | 10 | — |

三条读数：

- **默认配置的召回损失是实打实的**：1% 选择性下返回 **1 条而非 10 条**，**无报错、无日志**——正是
  `AGENTS.md` §4 归纳的「不报错、只是静默不生效」形态；
- **`strict_order` 足够，且不牺牲精确性**：返回的就是精确 top-10（10/10 重合）。「可能不足 K」的真实触发条件是
  **`max_scan_tuples` 耗尽**（本次 5000 行远未触及默认 20000），而它一旦发生，恰由第 2 条的精确回退承接——
  **两段式回退不是备选方案，而是本设计的组成部分**；
- **调大 `ef_search` 不是解法**：它确实能修好（1000 与 `strict_order` 同效），但它是**固定候选预算、不自适应**
  （本次等于扫 20% 的表），且**合法上限就是 1000**，堆不出可预测的行为。

### 落实清单（M2 开工执行；**#1 / #2 / #3 已于 2026-10-07 落地**：V3 迁移回填 + 表达式索引 + metaJson 写入 + 断言）

1. V3 迁移：`UPDATE t_vector SET meta = c.meta FROM t_chunk c WHERE t_vector.chunk_id = c.id`（回填 meta 一致）；
   补 meta 必含可见集字段（tenantId / orgId / kbId / docId）；
2. `CREATE INDEX idx_t_vector_tenant ON t_vector ((meta->>'tenantId'))`——**正面说明**：btree/GIN 无法限制 HNSW 候选遍历顺序，
   该索引用途是（a）精确回退路径的「先过滤后排序」（b）运维统计；主路径的 K 不足由 iterative_scan + 回退解决（此点必须如实写进答辩材料）；
3. `DocumentIngestStore.metaJson` 增加 tenantId（M1 过渡版 meta 缺它）；
4. `RetrievalService` 过滤改写（filter 在 t_vector 侧）+ 查询级 `SET LOCAL hnsw.iterative_scan = strict_order`；
5. 测试：窄可见集（单文档可见）仍可命中（iterative_scan 生效证据）；越权 0 命中样本迁移到 filter 路径；回退路径单测；
   ⚠️ **并必须断言执行计划确实走了 HNSW 索引**（`EXPLAIN` 含 `idx_t_hnsw`）——否则小表上规划器退化为顺序扫描 +
   精确排序，召回率「全对」，**测试通过却什么都没证明**（本 ADR 第一版实验即栽在此处）；
6. 压测：高选择性查询召回率对照（iterative_scan on/off、strict/relaxed），数字进结论表。
   注意 `ef_search` 合法上限为 **1000**，参数取点需在此范围内。

### 代价与放弃

- 放弃「JOIN 简单直白」的短期便利：多一层 meta 字段治理（写路径保持 meta 与 t_chunk.meta 一致，由同一 Store 写入口保证）；
- 接受「近似索引 + 过滤」的固有边界：以 iterative_scan + 精确回退 + 压测量化承接，而非回避——这正是与「换库回避问题」的分界线。

---

## ADR-008 · 可见集 ACL 模型与下推算法（P2 落地：主体、四级语义、拒绝与审计）

**状态**：已采纳（2026-10-07）｜M2 切片①（P2 检索层隔离）开工前落盘；实现后只回填验证数据，不改变语义

### 背景

ADR-007 已定「过滤下推到 `t_vector.meta`」，但四级可见集（`tenant ∩ org ∩ kbACL ∩ docACL`，FR-PERM-01）
在数据模型上如何表达尚未定型：主体（用户 / 组织）从哪来、kbACL / docACL 用什么载体、空集拒绝的边界语义、
越权留痕的落点。本 ADR 在编码前把这些问题定死，避免再次出现「文档描述了不存在的机制」。

### 决策

1. **主体（Principal）**：`userId` + `orgId`（可空）。M2 过渡来源 = 请求头 `X-Aiwarden-User-Id` / `X-Aiwarden-Org-Id`
   （**认证层输出的模拟**——真实部署由 API Key / JWT 解析产出，届时只换来源、不换传播机制，与 ADR-003 对租户的处理同构）；
   `PrincipalContext`（common 模块）承载，缺失时 `requireUserId()` 拒绝——**不回落匿名主体**。
2. **四级语义**（全部在 SQL 侧计算，不允许应用层后过滤）：
   - **tenant**：来自 `TenantContext` 的硬边界（meta.tenantId）；
   - **org**：`t_knowledge_base.org_id`——`NULL` = 租户公共，非 NULL = 仅该组织可见；用户 `orgId` 为空 → 仅公共；
   - **kbACL**：`t_kb_acl(kb_id, user_id, effect[ALLOW|DENY])`——`ALLOW` = 跨组织例外授权，`DENY` = 显式拒绝；
   - **docACL**：`t_doc_acl(doc_id, user_id, effect)`——文档级例外（单独授权 / 屏蔽敏感文档）。
3. **可见集算法**（检索前，`VisibilitySetCalculator`，全部走索引点查）：
   - 可见 KB = `(org 门：org_id IS NULL OR org_id = :orgId) − DENY ∪ ALLOW`；请求指定 `kbId` 时求交集；
   - 文档例外 = `t_doc_acl` 的 ALLOW / DENY 两个集合；
   - **空集（含交集为空）→ 检索前直接拒绝**（`SecurityException` → HTTP 403），不进入向量查询（FR-PERM-01）。
4. **下推形态**（`RetrievalService`，事务内）：
   `WHERE meta->>'tenantId' = ? AND (meta->>'kbId' = ANY(?) OR meta->>'docId' = ANY(?)) AND NOT (meta->>'docId' = ANY(?))`
   + `set_config('hnsw.iterative_scan','strict_order',true)`；HNSW 路径返回条数 < K 时走**精确回退**
   （`enable_indexscan=off` 的过滤后精确排序），回退计数器 `aiwarden_retrieval_exact_fallback_total`。
5. **越权留痕**：拒绝路径写 `t_audit_log(action=RETRIEVAL_DENIED, actor=userId)`；`t_audit_log` 由数据库触发器
   拒绝 UPDATE / DELETE——「不可变留痕」具备代码级证据（测试可断言变更被拒）。

### 依据

1. **PRD 引文**：FR-PERM-01（四级 + 空集拒绝）、FR-PERM-04（20 条样本 CI 门禁）、FR-PERM-05（审计留痕，越权同样留痕）、
   §9（`t_vector.meta` 必须含过滤维度）。
2. **kbACL / docACL 为什么用「表 + effect」而不是给业务表加 visibility 枚举列**：枚举无法表达「对特定用户的跨组织例外」（ALLOW）
   与「对特定用户屏蔽」（DENY）；两张表结构与读写路径同构（一张形态两处用）；meta 下推只消费「可见集合」的产物，与载体结构解耦。
3. **拒绝为什么用 403 而不是 404**：检索是「搜索」语义——无权与不存在同为 403，不可区分（同样不泄露存在性）；
   资源读取（GET KB / 文档状态）继续按 FR-KB-01 用 404。

### 代价与放弃

- **Principal 在 M2 不做真实性校验**（请求头可伪造）——「认证层输出模拟」的已知边界，真实部署由认证层保证；
- 放弃「给 t_document / t_knowledge_base 打 visible 列」的简单方案：表达力不足（无用户级例外），且权限语义分散在多处；
- 放弃「可见集预计算缓存」：演示规模不需要，缓存会让「权限变更立即生效」复杂化——每次检索前一次轻量点查。

### 验证（随 `mvn verify` 执行；实现后回填实测数据）

| 断言 | 证据 |
|---|---|
| 检索类越权样本拦截率 100%（16 条；+P3 工具类 4 条 = 合计 20 条） | `RetrievalVisibilityContainersTest`：跨租户 / 跨组织 / 无权限 KB / 已删除各 4 条，每条先以「有权者命中同一内容」排除假阳性 |
| 下推 SQL 确实走 HNSW 索引 | `RetrievalHnswPushdownContainersTest` 的 `EXPLAIN` 自证（断言含 `idx_t_vector_embedding_hnsw`）；小表会退化为顺序扫描，故种 5000 行 |
| 窄可见集仍凑满 K（iterative_scan 生效） | 同上：5000 行中仅 10 行可见、K=10 → 返回 10 条（ADR-007 实测默认配置仅返回 1/10） |
| 精确回退不是死代码 | `max-scan-tuples=1` 触发回退：结果仍为 K + 计数器递增 |
| 越权尝试留痕且不可变 | 拒绝后 `t_audit_log` 出现 `RETRIEVAL_DENIED`；UPDATE / DELETE 被触发器拒绝 |
| 检索入口不可绕过可见集 | `ArchitectureTest`：`RetrievalService` 公开检索方法签名必须含 `VisibilitySet`；`*Service/*Store/*Calculator` 不得声明全量查询方法（含自证夹具） |

## 修订（2026-10，切片①实现实测）

P2 落地过程中实测出五条与「机制是否真的生效」直接相关的结论（全部有可复现测试，正文不改写）：

1. **受控实验的完整旁路清单**（5000/20000/50000 行逐一实测）：planner 对检索 SQL 的真实选择依次是
   ① `Seq Scan + Sort`；② 关串行序扫后绕道「并行序扫 + Gather Merge + Sort」；③ 再关并行后首选
   `idx_t_vector_tenant`（btree 表达式索引，估算 rows=250）；④ 移除 btree 后转头 `t_vector_pkey`
   （估算 rows=1 的幻觉）——**根因是 planner 对 JSONB `meta` 过滤的选择性估算在 1/250/50000 间横跳**。
   影响两面：a) 中小规模下 HNSW 本就不被执行（精确路径无召回风险——与 §11 风险 8「不承诺召回率」自洽）；
   b) 要观察 HNSW 路径，测试必须受控：事务内 `enable_seqscan=off` + 关并行 gather + seed 阶段移除
   btree 与主键索引（见 `RetrievalHnswPushdownContainersTest` 类注释）。
2. **`hnsw.max_scan_tuples` 只限制 iterative 绕回额度，首次 `ef_search` 候选不受限**：实测「可见行全部
   先插入」时，HNSW 图入口点落在可见团内，默认配置（off）也能凑满 10——「凑满 K」不再能证明
   iterative_scan 生效（假绿）；把可见行改为**后插入**后，off 实测返回 0 条，「凑满 K + 回退计数=0」
   才成为真证据。**测试数据的插入顺序是 HNSW 行为学断言的一部分。**
3. **审计写入必须独立事务（REQUIRES_NEW）**：拒绝路径的形态是「写审计 → 抛拒绝异常」，同事务则异常
   回滚把审计一起滚掉——「越权留痕」实测为零条。`AuditLogWriter.append` 已改为 `REQUIRES_NEW`。
4. **回退路径必须重开 `enable_seqscan`**：若调用环境（外层事务 / 运维设置）设过 `enable_seqscan=off`，
   回退的「顺序扫描 → 过滤 → 排序」会被迫再走索引——回退等于没回退。`exactFallback` 已重开该开关。
5. **回退触发条件收敛为「HNSW 返回数 < 可见总数」**：先做精确计数（同谓词）再决定是否回退——
   「可见总数本来就 < K」不回退（避免小可见集的无谓全扫）；计数与回退都属治理税，有计数器可观测。

验证回填：检索类越权样本 16/16 拦截（`RetrievalVisibilityContainersTest`，含基线假阳性排除与审计不可变）；
HNSW 受控自证 2/2（`RetrievalHnswPushdownContainersTest`）；塌陷回退 1/1
（`RetrievalExactFallbackContainersTest`，计数器递增证明回退不是死代码）；工具类 4 条样本随 P3 切片合计 20 条。

## 修订（2026-10，M2 复核后的 P2 修正）

独立复核 P2 时发现三处「防线 / 证据」缺口，正文决策语义不变，补三条落地细节：

1. **租户谓词此前是「零证据的承重墙」**：常规跨租户样本其实是被 OR 的**左支**
   `meta->>'kbId' = ANY(kbIds)` 挡住的（`kbIds` 只可能含本租户 KB），把
   `AND v.meta->>'tenantId' = ?` 整行删掉，那批用例**依然全绿**。现补
   `tenantPredicateIsLoadBearing_andCrossTenantAclRowIsImpossible`：用**同一条下推 SQL 去掉租户谓词**
   （且 `kbIds` 故意留空，使命中只能来自 `allowDocIds`，否则本租户文档会一起通过而使断言失去判别力
   ——实测踩过一次：count=3 而非 1）直查向量表，证明「外租户 docId 一旦进入可见集即可命中」，
   因此产品路径的 0 命中只能归因于租户谓词。
2. **跨租户 ACL 行的 DB 层防线（V8，对应决策 2 的 kbACL / docACL）**：两张 ACL 表原先只有
   `UNIQUE (kb_id, user_id)` / `(doc_id, user_id)`，**没有任何约束把 `tenant_id` 绑定到所指向对象的
   属主租户**——一行「租户 B 写、指向租户 A 的 kb_id」的 `DENY` 会遮蔽租户 A 的用户，并凭唯一约束
   占掉其 ACL 槽位（**跨租户拒绝向量**）。现补 `(tenant_id, id)` 组合唯一键 + 组合外键
   （`t_kb_acl → t_knowledge_base`、`t_doc_acl → t_document`，`ON DELETE CASCADE`）：
   这类行在写入时即被拒绝。应用层也已给 DENY 子查询补上 `a.tenant_id = kb.tenant_id`
   （原实现漏了，ALLOW 分支本来就有）——**双防线**。
3. **回退的 GUC 必须复位（补齐决策 4）**：`set_config(..., true)` 是事务本地，不会随方法返回而消失。
   `exactFallback` 原先把 `enable_indexscan/bitmapscan` 设为 `off` 后**不复位**——调用方在同一事务内
   再次检索时下推路径会**静默不再被使用**（结果仍正确，机制失效、治理税暴涨）。
   现 `finally` 复位，并由 `RetrievalExactFallbackContainersTest` 断言回退后
   `enable_indexscan/bitmapscan` 回到 `on`（同事务内取值）。

> 另：`RetrievalService.search` 增加与 `TenantContext` 的**交叉校验**（可见集是值对象，
> ArchUnit 只能守护签名含 `VisibilitySet`，守护不了**来源**）；越界即 403 + 审计。见第二部分裁决 15。

---

## ADR-009 · 工具副作用治理机制（幂等键状态机、补偿逆序、工具可见面）

**状态**：已采纳（2026-10-07）｜M2 切片②（P3）开工前落盘；实现后只回填验证数据，不改变语义

### 背景

FR-TOOL-01~05 要求「写操作工具带幂等键、失败走补偿、断网重放不产生第二张工单」。M1 已有成熟的
「至少一次 + 唯一键仲裁 + 补偿/对账」方法论（ADR-005/006），但工具侧有两个新问题：
① **状态机要回答重放仲裁**——同一个幂等键上会出现哪些合法状态、每种状态下重放该干什么
（ADR-005 修订段的教训：键上的合法多次流转没想清楚就会静默跳过）；② **「补偿而非重试」的边界在哪**——
确定失败的调用能不能重放？不确定状态（PROCESSING 卡死）又怎么办？本 ADR 在编码前定死。

### 决策

1. **幂等键**：`idemKey = 业务键 + ':' + 会话ID + ':' + 步骤指纹`，步骤指纹 = `sha256(tool|stepNo|输入规范 JSON)` 前 16 hex（输入按 key 排序序列化，同输入同键）；唯一约束 `(tenant_id, idem_key)` 仲裁。
2. **状态机与重放仲裁矩阵**（`t_tool_invocation.status`）：

   | 重放时的现有状态 | 处理 |
   |---|---|
   | （无行）| INSERT `PROCESSING` → 执行 |
   | `SUCCEEDED` | 返回首次结果（`replayed=true`），不重执行 |
   | `FAILED` | 返回首次失败记录（`replayed=true`），**不重执行**——「补偿而非重试」：确定失败不重放，恢复走补偿链路 |
   | `PROCESSING` 未超租约 | 409（另一执行中，调用方稍后重放） |
   | `PROCESSING` 超租约（lease 可配，默认 5 分钟） | CAS 重抢后执行——「不确定状态可重试」：原执行者可能已崩溃 |

   核心语义：**不确定（in-doubt）可重试，确定失败不重试**——与「至少一次 + 幂等」一脉相承而不与「补偿而非重试」相矛盾。
3. **双重幂等**：幂等键仲裁是应用层防线；业务表唯一约束是最终防线——`t_ticket.idem_key = 业务键` 唯一（同一来源单号在任何会话 / 任何重放下都只建一张单），工具自身的执行也幂等（`ON CONFLICT DO NOTHING` 后读现有）。
4. **补偿**：失败调用落库后，对**同会话更早（id < 失败调用）的 `SUCCEEDED` 且工具声明可补偿**的调用，**生成 `PENDING` 补偿计划**（`t_compensation_log`，`UNIQUE(invocation_id)` 幂等）；**补偿执行器显式触发**（`POST /api/v1/agent/compensations/run`），按 `invocation_id DESC`（**逆序**）逐个执行可补偿工具的 `compensate`，每行动作落 `SUCCEEDED/FAILED` + `attempt++`，并写审计（动作顺序可断言）。
5. **工具可见面（FR-PERM-03）**：装配期配置 allow 白名单定死（`aiwarden.agent.tools.allowed`）+ 代码级 `EXCLUDED`（显式禁 `web_search`/`web_fetch`，配置误加也不可见）；未可见面工具调用 **403 + 审计 `TOOL_DENIED`**（不泄露存在性）；可见面列表 API 只返回白名单内工具（「不进模型请求体」的读侧）。
6. **计量**：工具调用随事务写 outbox 计量（tool 维度，复用 M1 的 CallMeteringPayload 骨架）。

### 依据

- PRD 引文：FR-TOOL-01（幂等键三要素）/ FR-TOOL-02（补偿而非重试）/ FR-TOOL-04（主演示：断网重放工单数 == 1）/ FR-PERM-03（可见面装配期定死）/ §4.3（补偿链路）/ §10（工单是零资金风险演示替身）。
- 方法论复用：M1 的「至少一次 + 唯一键仲裁 + 失败留痕」直接平移（ADR-005 修订段的教训已内化进决策 2 的矩阵——先把键上的合法流转列完再写仲裁）。

### 代价与放弃

- 放弃「FAILED 自动重试队列」：与「补偿而非重试」纪律冲突；失败调用的恢复靠补偿 / 人工（与 FR-ING-04 的摄入重试语义不同——摄入是基础设施重试，工具是业务副作用）。
- `PROCESSING` 超租约重抢意味着**执行窗口内可能有两个执行者**（原执行者只是慢而不是死）——由双重幂等（决策 3）兜底：重复执行最多多一次工具调用，不可能重复落库副作用。
- 不做审批会签 / 多级审批（FR-TOOL-03 二态开关为应做档，随后落地：`PENDING_APPROVAL` 状态 + approve/reject，挂起上下文 = 已落库的输入快照，恢复不丢上下文）。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 断网重放工单数 == 1（**主演示**）| `ToolInvocationContainersTest`：首次调用成功后丢弃响应（客户端未收到）→ 同键重放 → `replayed=true` + 结果与首次一致 + 工单表仍 1 行 |
| 并发重放只执行一次 | 16 线程同键并发 → 工单 1 行 + 无第二张（其余线程得到复用结果或 409）|
| PROCESSING 卡死可租约重抢 | 手工将已成功调用置回 PROCESSING 并回拨 updated_at → 重放重抢执行成功且工单不重复 |
| FAILED 重放不重执行 | 失败调用重放 → 返回首见失败记录，无新执行痕迹 |
| 补偿逆序清算 | 两个成功建单（主单、关联单）+ 指派失败 → 生成 2 条 PENDING 计划 → 执行后两单 CANCELLED，审计顺序 = 先关联单后主单（逆序）|
| 工具可见面 4 条越权样本 | `ToolWhitelistContainersTest`：web_search / web_fetch / unknown_tool / 注册未授权工具 均 403 + 审计；可见面列表只含白名单工具（含入总 20 条门禁）|

## 修订（2026-10，切片②实现回填）

三个测试类 12 测试全绿（`ToolInvocationContainersTest` 5 / `ToolWhitelistContainersTest` 5 / `ToolApprovalContainersTest` 2），
验证表逐行达成。实现期补充三条落地细节（语义不变）：

1. **二态审批已随切片②落地**（原「随后落地」项）：`PENDING_APPROVAL` 挂起、`approve`（CAS → 从输入快照恢复执行）、`reject`（CAS → `REJECTED` 终态，**不触发补偿**——驳回是「不执行」不是「执行失败」）；重放矩阵扩展为「SUCCEEDED / FAILED / PENDING_APPROVAL / REJECTED 均返回首见状态」。开关为类级配置 `aiwarden.agent.tools.require-approval`（默认空，不影响既有调用）。
2. **工具注册表保持有序**：`Map.copyOf` 会丢失 TreeMap 顺序（实测：可见面列表断言失败暴露），改用 `Collections.unmodifiableSortedMap`——列表接口输出稳定字典序。
3. **计量挂点**：每次工具调用（成功/失败）随事务写 outbox 计量（tool 维度），P4a 四维归因的工具维度由此归位。

## 修订（2026-10，M2 复核后的 P3 修正）

对 P3 做独立复核后发现三处**静默不生效 / 静默假成功**缺陷并修正，其中两条改动了 SPI 与响应契约：

1. **幂等指纹必须保留数字精度**（决策 1 的正确性前提）。默认 Jackson 把 JSON 小数反序列化为
   `Double`——精度在进入业务代码**之前**就已丢失，于是两个仅在 17 位有效数字之后不同的输入会得到
   **同一个指纹**，第二次调用静默重放首次结果（不报错、不执行、无日志）。本机实测（Jackson 3.1.5）：
   `12345678901234567890.1234…890` 与 `……891` 的规范形式**完全相同**；`readTree` 也不救
   （得到的是 `DoubleNode`，同样已丢精度）——治本点只能在**反序列化配置**上。
   落地：`JacksonPrecisionConfiguration`（start 装配根）对共享 ObjectMapper 开
   `USE_BIG_DECIMAL_FOR_FLOATS`——必须放在共享 mapper 上，因为 `@RequestBody` 在 Controller 边界
   就已完成解析，修在指纹计算处等于没修。回归门禁
   `idempotencyFingerprint_preservesNumericPrecision`（已验证可失败：关掉该开关即红）。

2. **幂等键成分的长度语义上收到入口**（决策 1 的健壮性）。键 = 业务键 + 会话 + 16 hex，
   原先两侧口径不一致：契约无长度约束而列宽是 `VARCHAR(160)`，200 字符业务键会走到 `INSERT`
   由 PostgreSQL 报 `value too long` → **500**（不是 400）。落地：入口显式校验
   （`businessKey` / `sessionId` ≤ 64、`stepNo` ∈ [0, 100000]，越界 400；三者上限之和 146 ≪ 列宽），
   并由 **V7 迁移**把列宽放宽到 256 留出余量。回归门禁
   `oversizedIdempotencyKeyParts_areRejectedWith400_beforeHittingDb`。

3. **补偿的「成功」必须有证据，失败必须可重跑**（决策 4 的语义收口）。三条修正：
   - **SPI 契约变更**：`ToolExecutor.compensate` 返回类型 `void` → **`int`（受影响行数）**，
     默认实现由「空实现」改为**抛 `UnsupportedOperationException`**（空实现会让误用表现为静默成功）。
     执行器按行数分流：`>0` 记 `SUCCEEDED`，`=0` 记 **`NO_OP`**（终态，单列计数）——
     否则会出现「审计写 SUCCEEDED + 计数器 +1，而数据库什么都没变」的假成功。
   - **`compensable()` 执行期再判一次**：计划生成时已过滤，但装配变更后已存在的脏计划仍可能
     指向不可补偿工具，执行期守卫给出有指向性的失败原因。
   - **FAILED 可重跑**：执行集合从「仅 PENDING」改为 `PENDING ∪ FAILED(attempt < 上限)`——
     原先补偿失败即永久卡在「半补偿」且第二次 run 恒返回 `executed=0`，与 SPI javadoc
     承诺的「等待人工/重跑」自相矛盾。超过上限的单列 `exhausted`（真正需人工介入的那部分）。
   - **响应契约变更**：`CompensationRunResponse` 增 `noOp` 与 `exhausted` 两个计数字段
     （succeeded 与 noOp 混在一起会让「补偿成功率」失去意义）。
   - 指标：`aiwarden_compensation_total{result=succeeded|noop|failed}`、
     `aiwarden_compensation_exhausted_total`。回归门禁
     `compensation_marksNoOpInsteadOfFakeSuccess_andRetriesFailedPlans`。

> 本批与 ADR-010 修订段同源：三处都是「不报错、只是静默不生效」形态（`AGENTS.md` §4 的归纳规则）。

---

## ADR-010 · 配额强一致机制（Redis Lua 预扣减、幂等、对账、限流）

**状态**：已采纳（2026-10-07）｜M2 切片③（P4a 配额强一致）开工前落盘

### 背景

P4a 承诺「租户级配额**预扣减 + 幂等 + 对账**，超限直接 429」。问题不是想象出来的：New API 官方漏洞库
GO-2026-6242/6243（整数溢出致**负数自增额度**、Redis 配额缓存覆盖绕过）与 issue #5290（quota **连预扣缓冲都没有**）
/#5441（部分模型 0 元购）就是「配额逻辑没写对」的现实样本。本 ADR 定死：预扣减的存储与原子性、幂等键、
结算/释放、挂点顺序（与幂等仲裁/限流的关系）、429 语义、对账口径。

### 决策

1. **存储与原子性**：配额账本在 **Redis（Lua 脚本原子执行）**；键：
   `aiwarden:quota:usage:{tenant}:{period}`（INCRBY 用量）、`aiwarden:quota:reserve:{requestKey}`（值=预扣量，TTL 72h）、
   `aiwarden:quota:settled:{requestKey}`（结算标记）；**不引 PG 行锁**（高频扣减不适合 PG；且与限流共用 Redis 管道——PRD FR-COST-07 原文）。
2. **三步语义**：`reserve`（幂等：**requestKey 复用调用幂等键**——同一逻辑调用的重放/重抢不重复扣）→
   `settle`（差额校正 `actual − reserved`，结算标记保证只应用一次）→ `release = settle(0)`（归还预扣）。
3. **挂点顺序**（以工具调用为例）：可见面 → **限流**（租户+工具双维度，Lua ZSET 滑动窗口）→ 幂等仲裁
   （**终态重放不消耗配额**）→ 新执行/重抢路径才 `reserve` → 执行 → 成功 `settle` / 业务失败 `release` /
   审批挂起 `release`（approve 恢复执行时再 `reserve`）。
4. **超限 429 且不留残留**：`reserve` 超限抛 `QuotaExceededException` → **事务回滚**（claim 的 PROCESSING 行同步消失，
   配额恢复后同键可重试）；指标 `aiwarden_budget_reject_total`；审计 `QUOTA_EXCEEDED`（REQUIRES_NEW 独立留痕，不被回滚）。
5. **对账口径**（FR-COST-05）：`GET /api/v1/admin/billing/reconcile` 比对 **Redis usage vs 明细表 SUM(tokens)**——
   前提：消耗配额的操作其明细 token == 扣减当量（工具调用写 `prompt_tokens=当量`）；差异清单 + 差异率落 `t_reconcile_report(type=BILLING)`。
   语义对齐 B2 边界：**不是绝对一致**——reserve 后宕机的泄漏窗口由对账发现。
6. **限流**（FR-COST-07）：与配额共用 Redis 管道；Lua ZSET 滑动窗口（租户+工具双维度）；超限 429 带 `Retry-After`；
   指标 `aiwarden_rate_limit_reject_total`；限流不写审计（它防的是频率，不是越权）。
7. **预算载体**：`t_budget`（tenant_id + period 唯一，period=YYYY-MM）；**未配置 = 不启用配额检查**（演示默认零门槛）。
8. **演示当量**：无真实 LLM 时，工具调用以配置当量计价（`aiwarden.quota.tool-token-cost`，默认 100）——
   口径为「操作当量」；真实模型接入时替换为真实 token 数（CallMeteringPayload 已预留模型维度）。

### 依据

- PRD 引文：P4a（§3）、FR-COST-01/02/04/05/07、§4.2 时序（幂等键 → 配额检查 → 超限 429）；New API 漏洞证据（GO-2026-6242/6243、#5290/#5441）。
- 与方法论对齐：预扣减的「先占后结」与 M1 对账体系同源（发现与修复分离；残余窗口如实披露）。

### 代价与放弃

- **放弃「强一致不丢」**：Redis 与明细双写存在窗口（reserve 后进程宕机 → 预扣未结算）——由对账发现，不假装不存在（B2）。
- **放弃 PG 行锁方案**：扣减频率×行锁冲突不可接受；Redis Lua 单线程原子足够本场景。
- **审批挂起时 release、approve 时再 reserve**：挂起期间不占用配额（避免长期挂起耗尽额度）；代价是 approve 可能失败（429）——语义正确。
- **限流参数按「租户+工具」维度落地**（无模型路由时的「模型维度」替身），真实模型接入后加模型维度。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 超限 429 + 指标 + 审计 + 零残留 | `QuotaEnforcementContainersTest`：小预算下第 N+1 次调用 429、`aiwarden_budget_reject_total` ≥1、审计 `QUOTA_EXCEEDED`、账本无该调用行；提额后同键可成功 |
| 预扣减幂等与差额结算 | `QuotaContainersTest`（Redis 容器）：同 requestKey 二次 reserve 不重复扣；settle(actual≠reserved) 正确校正；release 归还；16 线程并发 reserve 不超卖（limit 内恰好 N 个成功）|
| 对账零差异与缺口发现 | 正常扣减后 reconcile 差异 0；手工向 Redis 注入缺口 → 差异可见 + 报告落库 |
| 限流 429 + Retry-After | `RateLimitContainersTest`：限额压低后第 N+1 个请求 429 且带 `Retry-After` |
| 四维归因落库 | 工具调用明细含 session/step/tool（`t_llm_call_log`），GET /api/v1/admin/usage 可按多维过滤 |
| 审计检索接口 | GET /api/v1/admin/audit 可查越权与配额拒绝留痕 |

## 修订（2026-10，切片③实现回填）

四个测试类 14 测试全绿（`QuotaEnforcementContainersTest` 3 / `RateLimitContainersTest` 1 /
`ToolInvocationContainersTest` 5 / `ToolWhitelistContainersTest` 5，工具两类的 Redis 容器适配含在内）。实现期补充四条落地细节：

1. **决策 3 的挂起语义落地为「挂起保留预扣」**（原文写作「挂起 release、approve 再 reserve」）：实现时发现
   release 会写「已结算」标记，导致 approve 时的幂等 reserve 命中不扣、settle 又被标记挡住——该逻辑调用**永不结算**。
   修正：挂起时保留预扣（资源已预留），**驳回时释放**（release 无预扣时为安全 no-op）；
   approve 时 reserve 幂等命中（不重复扣；挂起后才配预算也能生效）。
2. **settle 仅在配额激活时调用**（reserve 返回值携带 active）：避免「未启用预算也写结算标记」的污染；
   而 release / 超限拒绝无需条件——前者无预扣时差额为 0，后者事务回滚不留残留。
3. **限流先于幂等仲裁**：被限流的请求不产生任何账本行（测试断言恰好 N 行）；限流维度落地为「租户 + 工具」。
4. **计量消费体抽为可直调方法**（`MeteringEventConsumer.consume(payloadJson)`）：测试以真实消费逻辑验证
   四维明细落库（无需 Kafka broker）；旧工具测试类补 Redis 容器（调用入口已触碰配额/限流管道）。

## 修订（2026-10，M2 复核后的配额修正）

对 P4a 做了一次独立复核（跨租户 / 幂等 / 泄漏面），发现三处**静默不生效**缺陷并修正。以下为现状描述，
上文决策 1 / 2 的键形态与 `release = settle(0)` 两处表述已被本节取代：

1. **预扣 / 结算键必须带租户**（决策 1 的 `{requestKey}` → `{tenant}:{requestKey}`）。DB 侧唯一约束是
   `(tenant_id, idem_key)`，因此两个租户**可以**持有完全相同的 `idemKey`；键不带租户时租户 B 会命中
   租户 A 留下的键 → 走幂等分支「允许且不扣减」→ **免费调用**，而明细仍写入 B 的账 → 对账负差异。
   实测证据（本机 redis:7.4-alpine）：修前同 requestKey 下租户 2 的 usage 恒为空、返回 `{allowed, 0, 幂等命中}`；
   修后为 `{allowed, 100, 非幂等}`、两租户各扣 100。回归门禁 `sameRequestKeyAcrossTenants_deductsIndependently`。
2. **`release` 不再是 `settle(0)`，改为「回退用量 + 删除预扣键与结算标记」**（决策 2 的 `release = settle(0)` 作废）。
   原因：`settle(0)` 只写标记、保留预扣键，导致同一逻辑调用释放后再次执行时 `reserve` 命中残留键**免扣**，
   而随后 `settle(actual == reserved)` 差额为 0 不执行 INCRBY → **这次真实执行完全不计费**（usage 恒 0，明细却有行）。
   同时 `release` 增加「已结算则 no-op」守卫——否则一次多余的 release 会把已计入的用量抹掉。
   为实现幂等，`release` 独立为 `redis/quota_release.lua`。回归门禁 `releaseThenReserveThenSettle_chargesExactlyOnce`。
3. **用量 key 补 TTL、结算标记 TTL 取 2× 预扣 TTL、预扣脚本拒绝非正数入参**：前者消除「每租户每账期一个
   常驻键」的无界增长；中者避免「预扣过期而标记仍在」造成的重复扣减；后者是 New API
   GO-2026-6242/6243（负值反向自增额度）同形态的第二道防线（当前调用方恒传正数，属防御性）。

**预算管理接口补租户边界**：`GET/PUT /api/v1/admin/budgets/{tenantId}` 原先只调 `requireTenantIdAsLong()`
**却丢弃返回值**，从不校验路径租户与调用方租户一致 → 租户 A 可读**并改写**任意租户预算（改大即解除限流、
改小即 DoS）。现统一校验，不一致返回 403。回归门禁 `budgetEndpoints_rejectCrossTenantAccess`。

**同批修正（P2 / P3 复核发现，与配额无关但同源「静默不生效」形态）**：
- `RetrievalService.search` 增加与 `TenantContext` 的交叉校验——可见集是值对象，ArchUnit 只能守护签名，
  守护不了来源；此前任何调用方 `new VisibilitySet(别的租户, …)` 即可越权读取（当前仓库唯一构造点是
  Controller，故不可达，属 latent）。同时补 `t_doc_acl` 跨租户脏行的判别性用例。
- `ToolInvocationService` 原先只 `catch (ToolExecutionException)`，工具抛 `IllegalArgumentException` /
  其它 `RuntimeException` 时事务回滚而 **Redis 预扣不回滚** → 残留预扣键变成「免扣券」。
  现按异常类型分区处理：参数 / 状态非法 → 归还预扣后原样抛出（无副作用，不生成补偿计划）；
  其它未包装异常 → 落失败账 + 归还预扣 + 生成补偿计划（不假定「无副作用」）。
- 计量明细的 `prompt_tokens` 由 `(int)` 强转改为 `Math.toIntExact`：溢出截断会静默破坏
  「明细 token == 扣减当量」这条对账前提。

> ⚠️ **验证表原列 `QuotaContainersTest` 未按该形态创建**——实际覆盖见上文列出的
> `QuotaEnforcementContainersTest` / `RateLimitContainersTest`；本修订段的测试计数已按实际类名书写。

---

## ADR-011 · 治理税度量方法（四项微计时口径）与 MCP 最小版取舍

**状态**：已采纳（2026-10-07）｜M2 切片④（收官）开工前落盘

### 背景

M2 验收 ⑥ 要求输出**四项治理税开销**（可见集计算 / filter 下推 / 计量事件 / 配额检查，附机器规格）；
PRD §11.1 止损条款 **L4**：M2 末测不出 → 写「待验证」并说明缺哪个指标，**不得写估算值**。
同时 FR-TOOL-05（MCP 最小版）为**可做档**（超预算降 roadmap）。度量方法与 MCP 取舍需先定。

### 决策

1. **度量方法：组件级微计时**（warmup 50 次 + 采样 200 次，取 P50 / P95 / max），在容器测试内执行；
   机器规格由测试自动输出（JVM `availableProcessors` + 最大堆）并人工核实物理规格。**不引 JMH**（口径明确优先）；
   HTTP 全链路口径、吞吐拐点、P99、每请求总开销留 **M4 压测报告**（与 PRD §12 分层一致）。
2. **四项口径定义**（一次问答链路的治理环节）：
   - ① 可见集计算 = `VisibilitySetCalculator.calculate` 全程（点查 SQL 含在内存）；
   - ② filter 下推 = `RetrievalService.search` 全程（构建参数 + 下推 SQL 执行；**不含**①，口径不重叠）；
   - ③ 计量事件 = `OutboxWriter.append`（写 outbox 行；Kafka 发送在 Relay 异步，不计入本环节——如实标注）；
   - ④ 配额检查 = `QuotaGuard.reserveForToolInvocation`（预算点查 + Redis Lua）。
3. **判定标准：宽松上界断言防灾难性退化**（如 P95 < 50ms），**不做硬性能门禁**——CI 机器差异大，硬门禁会 flaky；
   数字如实进结论表，趋势留报告。
4. **基准数据规模如实标注**：微基准用小数据（可见集/检索/配额均为点查或小 SQL），
   结果代表「治理环节的固定开销」，不代表大表下的绝对耗时——后者属 M4 压测范围。
5. **MCP 最小版取舍**：先实测官方 SDK 坐标（`io.modelcontextprotocol.sdk`）的解析与 SB4 兼容性；
   可行则实现最小版（内置工具 + 工具名装配期归一化 + 复用 P3 幂等/审计/配额管道），
   **不可行或超预算则按 PRD 预批降 roadmap 并在本文档留档**——不在结论表承诺。
   无论 MCP 是否落地，**工具名归一化**（非法字符→下划线 + 撞名加哈希后缀）都随本切片实施（FR-TOOL-05 的踩坑前置）。

### 依据

- PRD：M2 验收 ⑥、§6 治理税口径（裁决 13）、§12 结论表分层（M2 四项 / M4 总开销）、§11.1 L4。
- 直接进结论表的数字必须**当场可复现**——微计时测试随 `mvn verify` 执行，输出块即证据。

### 代价与放弃

- 放弃 JMH / 独立压测框架：引入成本与输出价值不匹配（M4 压测会补端到端口径）；
- 放弃「吞吐旁路对比」（开/关配额的吞吐差）：需 HTTP 压测口径，M4 补；
- 机器规格如实标注：本机为 **i7-7700（4 物理核 / 8 逻辑核）+ 32GB**——与 PRD「8 核 32G」的
  逻辑核口径对齐，报告同时标注物理核数。

### 验证（随 `mvn verify` 执行；实现后回填）

| 断言 | 证据 |
|---|---|
| 四项开销输出（带规格）| `GovernanceTaxContainersTest`：输出四行 GOVERNANCE-TAX 块（P50/P95/max）+ 规格行；宽松上界断言 |
| 工具名归一化 | 单测：非法字符替换 / 撞名哈希后缀 / 稳定可重复 |
| MCP 可行性 | `mvn dependency:get` 实测记录（可行→实现；不可行→降 roadmap 留档）|

## 修订（2026-10，切片④实现回填）

**四项治理税实测数字**（i7-7700：4 物理核 / 8 逻辑核 + 32GB；JVM availableProcessors=8、堆 8GB；warmup 50 + 采样 200）：

| 环节 | P50 | P95 | max |
|---|---|---|---|
| ① 可见集计算 | 1.542ms | 3.243ms | 4.016ms |
| ② filter 下推（检索全程）| 4.776ms | 7.118ms | 9.787ms |
| ③ 计量事件（写 outbox 行）| 0.458ms | 0.923ms | 1.749ms |
| ④ 配额检查（预算点查 + Redis Lua）| 2.709ms | 4.260ms | 5.398ms |

口径边界（如实标注）：微基准小数据、组件级调用——代表治理环节的**固定开销**；总开销边界 / 吞吐拐点 / P99 留 M4 压测报告。

**MCP 最小版已落地**（属可做档，未降 roadmap）：官方 SDK **2.0.1** 实测可用——注意 2.x 模块结构变化：
独立 `mcp-spring-webmvc` / `server-servlet` artifact 停于 0.18.4，**Servlet Streamable transport 已并入核心件**
（`io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider`，随 `io.modelcontextprotocol.sdk:mcp` 聚合件）。
实现：白名单工具经**工具名归一化**后暴露（`McpServerContainersTest` 断言 tools/list = create_ticket/assign_ticket）；
tools/call 复用 P3 管道（幂等重放 replayed=true、不建第二张单）；身份经 `McpTransportContext` 显式携带。
两条新坑已入 AGENTS §4（PER_CLASS × Testcontainers 顺序；MCP handler 线程边界）。

