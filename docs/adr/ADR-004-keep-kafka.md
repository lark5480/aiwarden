<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

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