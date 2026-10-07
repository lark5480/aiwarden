<!-- 本文件由 docs/DECISIONS.md 拆分而来（一文件一 ADR）。体例：**已采纳条目的原文不改写**，只在末尾追加 ## 修订（YYYY-MM） 段；版本号类结论必须能在 pom.xml 找到坐标。 -->

## ADR-001 · 底座选 Spring Boot 4.1 + LangChain4j 1.21

**状态**：已采纳（2026-10-06）｜**取代** PRD v1.0 的「Spring Boot 3.5 + Spring AI 2.0」原选型（[06-PRD修订裁决清单 §3](../research/06-PRD修订裁决清单.md) 反转）

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

> **本条闭合了** [05 §六](../research/05-独立验证与交叉质询报告.md) 遗留项 1（原验证命令写作 `...spring-boot4-starter:1.21.0`，该坐标不存在，已按实测修正为 `1.21.0-beta31`）。

## 修订（2026-10）

M0 骨架落地后（`pom.xml` 已建立；Maven 3.9.9 / JDK 21.0.8 / SB 4.1.1 实测）追加两条实测结论，正文不改写：

- **双坐标共存已在真实构建中验证**：`aiwarden-start` 的 `mvn dependency:tree` 显示 `dev.langchain4j:langchain4j:1.21.0` 与 `dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31` 同树解析、无版本仲裁冲突；`@SpringBootTest` 上下文冒烟测试在 SB 4.1.1 下通过。
- **Jackson 共存实测**：SB 4.1.1 应用层默认 JSON 栈已是 **Jackson 3**（`tools.jackson.core:jackson-databind:3.1.5`，经 `spring-boot-starter-jackson`；注意 groupId 是 `tools.jackson`，不是 `com.fasterxml.jackson`）；LangChain4j 1.21.0 默认 Jackson 2（`com.fasterxml.jackson.core:jackson-databind:2.21.5`）。两方 groupId 不同、共存不冲突——上文「默认 Jackson 2」指 **LangChain4j 侧默认**。① 要把**应用层**回退到 Jackson 2 默认行为，设 **`spring.jackson.use-jackson2-defaults=true`**——⚠️ 属性名是 `jackson2`，**中间没有连字符**，写成 `use-jackson-2-defaults` 会**静默无效**（依据：`spring-boot-jackson-4.1.1.jar` 的 `spring-configuration-metadata.json`）；② 要让 **LangChain4j 侧**也切到 Jackson 3，再引入 `dev.langchain4j:langchain4j-jackson3:1.21.0-beta31`（同样以 `-betaNN` 形态发布）。

---