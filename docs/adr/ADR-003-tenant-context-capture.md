<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

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