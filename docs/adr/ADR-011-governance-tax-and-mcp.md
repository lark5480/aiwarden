<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

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
