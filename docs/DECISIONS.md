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
