<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

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