# AGENTS.md — AIWarden 编码代理协作约定

> **本文件是什么**：AI 编码代理在本仓库工作时的**入口**——只放核心红线、技术栈坐标、极简命令。
> 长篇内容一律在 `docs/`（地图见 [`docs/README.md`](docs/README.md)）：需求 [`docs/PRD.md`](docs/PRD.md) ·
> 决策 [`docs/adr/`](docs/adr/README.md) · 实测数字与状态 [`docs/STATUS.md`](docs/STATUS.md) ·
> 机制坑正文与索引 [`docs/PITFALLS.md`](docs/PITFALLS.md) · 环境排障 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)。
>
> ⚠️ 本文件**不写业务逻辑、不写计划、不写会腐化的状态**：描述未来状态的内容会腐化，而 `**/*.md` 检索命中后
> 会把过期的未来状态当成待办。**体量硬上限 100 行**——超了就往 `docs/` 归位，别靠删别人的条目腾地方。

## 1. 文档职责与指针

「我该看哪个文件」的地图正文在 [`docs/README.md`](docs/README.md)；本文件只持有 §2 那张**权威源映射表**和「一条事实只有一个正文」这条铁律——索引不复制地图，地图不复制规则。

## 2. 铁律：一条事实只有一个正文，其余只允许指针

**任何「口径级」事实**——版本号、承诺增删、范围裁剪、术语改名、指标口径、实测数字、环境排障步骤——
**必须能在下表唯一的权威源里找到正文**；其它文件只允许**一句结论 + 指针**。
**发现第二份正文即视为文档缺陷**，就地删除并改成指针。**归位而非同步**：旧纪律要求同一口径同时改 4 个文件，
结果是同一批数字散在 4 处各有正文并开始措辞漂移——**副本数决定漂移概率，与是否细心无关**（教训见 06 号裁决 21 / 32）。

| 事实类别 | 唯一权威源 | 其余位置允许的形态 |
|---|---|---|
| 要做什么、验收标准、范围、里程碑定义 | [`docs/PRD.md`](docs/PRD.md) | 一行指针 |
| 技术 / 版本选型为什么这么定 | [`docs/adr/`](docs/adr/README.md)（与 PRD 冲突以 ADR 为准） | 一行指针 |
| **实测数字与验证口径** + 当前阶段 / 已知边界 / 公开范围 | [`docs/STATUS.md`](docs/STATUS.md) §2 / §1·§3·§5 | 结论摘要 + 指针；README 第一屏是 §2 的**对外投影** |
| **压测报告**（逐项治理税 / 吞吐与 P99 / 原始输出 / 复现命令） | [`docs/benchmarks/M4-load-report.md`](docs/benchmarks/M4-load-report.md) | STATUS §2 只留摘要 + 指针（裁决 26） |
| **本机环境排障**（端口、进程、容器、profile、Maven reactor） | [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md) | 症状一句话 + 指针（**小节序号是锚点，不可重排**） |
| **代码 / 配置层面的机制坑**（写错会静默不生效的东西） | [`docs/PITFALLS.md`](docs/PITFALLS.md) | 本文件 §4 只留最常踩的五条红线 |
| **文档改动的收尾校验命令** | [`docs/dev/doc-selfcheck.md`](docs/dev/doc-selfcheck.md) | 一行指针 |
| 对外表述红线（禁词 / 话术 / 竞品证据分级） | [`docs/PRD.md`](docs/PRD.md) §15 | 指针 |
| 走过并被记录在案的失败 / 放弃 / 未达成 | [`docs/FAILURE.md`](docs/FAILURE.md)（**只是索引**，定义见 PRD §8.1） | 一行结论 + 指针 |
| 争议怎么拍板的 | [`docs/research/06-PRD修订裁决清单.md`](docs/research/06-PRD修订裁决清单.md)（编号顺延追加） | 指针 |
| 版本号 | [`pom.xml`](pom.xml)；基础设施镜像 tag 见 [`docker-compose.yml`](docker-compose.yml) | 引用处必须能在此找到坐标 |
| **对外口径正文 / 求职排位** | **仓库外的内部材料目录**（公开仓库只留 PRD §14 的空壳指针） | **禁止检索、禁止提交、禁止在公开文档里给链接** |

**例外——冻结资产不算副本**：`docs/research/`、各 ADR 的「验证 / 修订」段、`docs/benchmarks/` 里出现的旧数字是
**决策时点的证据**，只增不改、不回改也不迁走。判据：**活文档漂移是缺陷，冻结资产里的旧数字是记录。**

**口径级变更的动作序列**（一次改动内做完，不允许「先改 PRD，外围文档下轮再说」）：① 定位权威源，只改那一处正文
→ ② 新决策落 ADR（新文件，或在被演进条目末尾追加 `## 修订（YYYY-MM）` 段、**不改写原文**）并同步 `docs/adr/README.md`
索引 → ③ 06 号清单追加裁决（编号顺延）→ ④ `grep` 该口径关键词，**残留正文就地改成指针**
→ ⑤ README 若投影数字，**单向**从 STATUS §2 复制（方向不可逆，永不反向维护）。

**改完文档的收尾**：四条基础校验 + 四条锚点断言，命令正文见 [`docs/dev/doc-selfcheck.md`](docs/dev/doc-selfcheck.md)
（2026-10-10 起为硬要求；跑完再报完成，不能靠眼看）。

**提交前自查**：

1. **版本号**：出现的版本号都能在 `pom.xml` 找到坐标（当前 `Spring Boot 4.1.1`、`LangChain4j 1.21.0` +
   starter `1.21.0-beta31`）。
2. **已作废的口径**：`算力`、`分库分表` / `ShardingSphere`、`gRPC`、`语义缓存`、`三档`、`HITL 三态`、`60 条`——
   只允许出现在「已裁剪 / 不得说 / 不做」这类否定语境里，不得作为当前选型或当前承诺（本清单与 PRD §15
   的禁令清单本身不计入）。
3. **相对链接基准**：内容在 `docs/` 与其子目录之间移动时**必须改链接基准**，否则静默断链。指向仓库外内部材料的
   文字**不得改成链接**（点了打不开）；其**节名 / 编号是锚点**，活文档改名会让冻结资产里的指向失效。

## 3. 技术栈坐标、极简命令与提交风格

- **技术栈**：Java 21 · Spring Boot 4.1.1（WebMVC + 虚拟线程，非 WebFlux）· LangChain4j `1.21.0` /
  starter `1.21.0-beta31`（**两处版本号形态不同**，见 PITFALLS P01）· PostgreSQL 16 + pgvector（HNSW）· Redis 7 ·
  Kafka（KRaft 单节点）· Flyway · JUnit 5 + Testcontainers + ArchUnit ·
  前端 `aiwarden-web/` 是**独立 pnpm 工程，不进 Maven 生命周期、不计入测试数**。
  每一行为什么这么选，见 [`docs/PRD.md`](docs/PRD.md) §7.1 / §7.2 与 [`docs/adr/`](docs/adr/README.md)。
- **日常命令**（启动序列的正文在根 [`README.md`](README.md)；`spring-boot:run` 的三种死法在
  [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md) 第 4 条——这里不重抄）：

  ```bash
  mvn -B -ntp verify                # 全量测试（需 Docker；计数只认各模块 Tests run 汇总行，见 STATUS §2）
  mvn -B -ntp -o clean test-compile # 改了共享契约（构造函数 / SPI 签名 / 枚举）后必须先 clean
  mvn -B -ntp -pl aiwarden-start -am package -DskipTests && java -jar aiwarden-start/target/aiwarden-start-0.1.0-SNAPSHOT.jar
                                    # 改依赖（尤其 scope）后的启动冒烟：测试全绿 ≠ 应用能启动
  ```
- **提交信息**：`type: 中文描述`（`docs` / `feat` / `fix` / `test` / `chore`），**单行 subject + 中文正文**；
  正文要能回答「凭什么说这个改动是对的」——有实测就写实测命令与结果，有取舍就写放弃了什么。
- **禁止 force-push 到 `main`**（历史是公开的）。**不要在仓库里留过期的计划 / 实施状态文档**（见文首 ⚠️）。

## 4. 红线速查（20 条坑的正文与认症状索引见 [`docs/PITFALLS.md`](docs/PITFALLS.md)）

**先记那条可迁移规则**：多数坑共享同一个失效形态——**不报错、只是静默不生效**。所以：引入任何技术栈或机制后，
必须验证它**真的生效**（有日志 / 有表 / 有行为 / 有指标），做验证时必须**断言机制确实被触发**。原文见
[`docs/PITFALLS.md` §0](docs/PITFALLS.md)。

写代码前最容易踩、踩了代价最大的五条（一句红线，细节回 PITFALLS）：

- 改依赖（尤其 scope）后必须做一次 `package` + `java -jar` 启动冒烟——**测试全绿不等于应用能启动**（P05）
- **不要为「自动透传」引入 TTL**：租户上下文统一显式 capture → apply，缺失即拒绝、不回落默认租户（P19）
- 写幂等仲裁前先问一句：**这个键上会不会出现合法的多次流转？**（P15）
- 写「发现类」判据（对账 / 审计 / 健康检查）前先问一句：**这条判据的盲区是什么形态？**（P16）
- 改共享契约后先 `clean test-compile`——留下半旧 class 的表现是一大片看不懂的崩溃（P12）

**标题号 `## 4.` 被约 25 处历史文档按节号引用（含冻结资产，不回改），不要改序号、不要删掉本节。**
**环境类症状不在这里**：`5432 refused`、Kafka 9092 连不上但容器 healthy、`8080` 被占、评测报告页 404——
判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)（第 1 / 2 / 3 / 5 条）。

## 5. 状态

见 [`docs/STATUS.md`](docs/STATUS.md)——阶段 / 测试数 / **已验证的实测数字（§2 是它们唯一的落点）** / 已知边界 /
下一步 / 公开范围都在那里（**易过期**，与本文件「只增不减的坑索引」性质相反，故分文件承载）。
阶段推进时按 §2 的动作序列改它；`README.md` 第一屏的结论表是它的对外投影。
