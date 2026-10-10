# AGENTS.md — AIWarden 编码代理协作约定

> **本文件是什么**：AI 编码代理（Claude Code / Codex / Cursor 等）在本仓库工作时的**入口文档**。
> 只放「**写错代价高、不翻代码发现不了**」的铁律与坑；能指针就不重抄——产品需求看 [`docs/PRD.md`](docs/PRD.md)，
> 技术决策看 [`docs/adr/`](docs/adr/README.md)，对外表述红线看 [`docs/PRD.md`](docs/PRD.md) §15。
>
> **本文件处于「随代码生长」状态**（2026-10 起；M2 完成，仓库已有业务代码）：地图 + 纪律 + 实测踩过的坑。
> 每踩一个坑、每定一条与代码相关的约定，就往 §4 追加一条。
>
> ⚠️ **不要在本文件写「计划」**。描述未来状态的内容会腐化，而 `**/*.md` 检索命中后会把过期的
> 未来状态当成待办——存量项目已吃过这个亏（原 `docs/compose/plans/` 因同类原因被删）。

---

## 1. 文档职责与指针

| 我想知道… | 看这里 |
|---|---|
| 产品要做什么、验收标准、里程碑 | [`docs/PRD.md`](docs/PRD.md)（**需求唯一权威源**） |
| 某个技术决策为什么这么选 | [`docs/adr/`](docs/adr/README.md)（**决策唯一权威源**，一文件一 ADR；与 PRD 冲突时以 ADR 为准） |
| 立项前的调研与论证序列 | [`docs/research/`](docs/research/README.md)（含编号说明与口径提醒） |
| 对外表述红线（README / 对外材料 / 公开表述） | [`docs/PRD.md`](docs/PRD.md) §15 |
| 项目进度与公开范围 | [`docs/STATUS.md`](docs/STATUS.md)（**易过期**，与 ADR 相反）· 对外范围见 [`README.md`](README.md) |

---

## 2. 铁律：口径变更必须一次性同步（**本项目已因缺此条踩过坑**）

**任何「口径级」变更**——选型反转、承诺增删、范围裁剪、术语改名、指标口径调整、评测样本数量变化——
**必须在同一次改动里同步下列文件**，**不允许「先改 PRD，外围文档下轮再说」**：

| 文件 | 要同步的位置 |
|---|---|
| `docs/PRD.md` | 受影响的 §1 / §3 / §6 / §7 / §8 / §10 / §12 / §14 / §15，并更新文首「文档版本」 |
| `README.md` | **第一屏承诺表 + 两条边界声明 + 结论表 + 技术栈表 + 版本红线** |
| `docs/adr/` | 新增 `ADR-00X-<slug>.md`，或在其末尾追加 `## 修订（YYYY-MM）` 段（**不改写原文**）；索引同步 |
| `docs/research/06-PRD修订裁决清单.md` | 追加新裁决条目（编号顺延） |

**为什么立这条**：2026-10 本轮就吃过亏——PRD v2 已把底座从 SB 3.5 反转到 SB 4.1 并写进版本红线，
但 **README 整轮没更新**，公然挂着 `Spring Boot 3.5` 徽章和「与 SB 3.5 无冲突」的红线表述，
而 README 是外部读者第一眼看的东西。**根因不是疏忽，是没有清单。**

**提交前自查**（`README.md` 与 `docs/PRD.md` 的正文）：

1. **版本号**：两处一致，且都能在 `pom.xml` 找到对应坐标（当前应为 `Spring Boot 4.1.1`、
   `LangChain4j 1.21.0` + starter `1.21.0-beta31`）。
2. **已作废的口径**：`算力`、`分库分表` / `ShardingSphere`、`gRPC`、`语义缓存`、`三档`、`HITL 三态`、`60 条`——
   这些词**只允许出现在「已裁剪 / 不得说 / 不做」这类否定语境里**，不得作为当前选型或当前承诺出现。
   （`AGENTS.md` 本节与 `docs/PRD.md` §15 的**禁令清单本身**同理，不计入。）

---

## 3. 提交与代码风格

- **提交信息**：`type: 中文描述`（`docs` / `feat` / `fix` / `test` / `chore`），**单行 subject + 中文正文**。
  正文要能回答「凭什么说这个改动是对的」——有实测就写实测命令与结果，有取舍就写放弃了什么。
- **版本号的唯一事实源是 `pom.xml`**：PRD / README 里出现的任何版本号，必须能在 `pom.xml` 找到对应坐标。
- **禁止 force-push 到 `main`**（历史是公开的）。
- **不要在仓库里留过期的计划 / 实施状态文档**（见文首 ⚠️）。

---

## 4. 已知的坑与约定（随代码生长）

**本节现有十六条，都是实测得来、且不翻代码发现不了的。**

> **先记一条可迁移的判断规则**：下面十六条里有**八条**共享同一个失效形态——**不报错、只是静默不生效**：
> - **名字写错**：`-betaNN` 版本后缀、Jackson 属性名少一个连字符、`flyway-core` 与 `spring-boot-starter-flyway` 的区别、
>   Micrometer 导出时剥掉 Gauge 的 `_total` 后缀；
> - **依赖被挤掉**：同名依赖在装配模块以更近的 test scope 重复声明，运行期依赖静默消失；
> - **装配歧义**：`CommonErrorHandler` 候选不唯一（工厂干脆谁都不用，自定义重试与 DLT 一起失效）；
> - **语义设计**：幂等仲裁键混用「重复投递」与「合法状态流转」；
> - **精度被吞**：JSON 小数默认落成 `Double`，高精度输入得到同一指纹（静默重放首次结果）；
> - **配置作用域**：GUC / 开关设了不复位，同一事务内后续路径静默退化。
>
> 在新一代框架（SB 4 / 模块化拆包）与异步管道里，「不报错但不生效」比「报错」更常见。
> **因此：引入任何技术栈或机制后，必须验证它真的生效（有日志 / 有表 / 有行为 / 有指标），不能只看构建通过。**
>
> **推论——验证本身也要自证**：做行为 / 性能验证时，必须断言**机制确实被触发**（执行计划走了目标索引、
> 消费组确实提交了位移、指标确实已注册），否则小样本上极易得到「全对、但什么都没证明」的假结论。
> 本项目已**三次**栽在这点上：ArchUnit 选择器拼错仍全绿、HNSW 选择性实验退化成顺序扫描、
> 以及 M2 的「HNSW 三重假绿」（小表走精确路径 / 数据插入顺序把入口点放进可见团 / 审计被事务回滚——见本节末条）。

- **LangChain4j 的两处坐标版本号形态不同**：核心件是 `dev.langchain4j:langchain4j:1.21.0`，而 starter 模块是
  `dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31`。
  **照抄核心件的版本号去写 starter 会直接依赖解析失败。** `-betaNN` 是 LangChain4j 对 starter / 集成模块
  的发布惯例，不是「API 不稳定」的标记。依据与实测命令见 [`ADR-001`](docs/adr/ADR-001-bootstrap-versions.md)。
- **租户上下文不引入 TransmittableThreadLocal / InheritableThreadLocal**：虚拟线程是不可复用的一次性线程、
  池化线程（`@Scheduled`）会被复用——两类边界统一走 `TenantContext` 的显式 capture（`snapshot()`）→ apply，
  缺失时 `requireTenantId()` 拒绝（**不回落默认租户**）。写异步 / 消费 / 定时任务代码时不要为了「自动透传」
  引入 TTL 依赖；依据与单测证据见 [`ADR-003`](docs/adr/ADR-003-tenant-context-capture.md)。
- **Jackson 在两条依赖线上各有一套，且配置属性名有陷阱**：SB 4.1.1 的**应用层默认是 Jackson 3**
  （`tools.jackson.core`，注意 groupId **不是** `com.fasterxml.jackson`），而 **LangChain4j 侧默认 Jackson 2**
  （`com.fasterxml.jackson.core`）——两套 groupId 不同，同一 classpath 上共存不冲突。
  回退应用层到 Jackson 2 默认行为的属性是 **`spring.jackson.use-jackson2-defaults`**（**`jackson2` 中间没有连字符**）；
  写成 `use-jackson-2-defaults` **不报错，只静默失效**。依据见 [`ADR-001` 修订段](docs/adr/ADR-001-bootstrap-versions.md)。
- **Spring Boot 4 把自动配置拆进了独立模块：集成某技术时优先找对应 `spring-boot-starter-*`，只加底层库可能「完全不装配且不报错」**——实测踩坑：只加 `org.flywaydb:flyway-core` 时应用照常启动、**Flyway 静默不执行任何迁移**（无日志、无表）；换用 `org.springframework.boot:spring-boot-starter-flyway` 才恢复「启动即迁移」。引入任何原本应存在自动配置的技术栈时，先核对 Starter 坐标。
- **Testcontainers 在 SB 4 下有两个与 Boot 3.x 时代不同的硬约束**：① SB 4 **不再代管其版本**，
  必须在根 pom 显式导入 `org.testcontainers:testcontainers-bom`（照 Boot 3.x 写法直接引用会构建失败）；
  ② 2.x 起所有模块改名为 `testcontainers-*` 前缀（如 `testcontainers-junit-jupiter`、`testcontainers-postgresql`），
  照 1.x 旧名会解析失败。另外**测试分层是硬约定**：普通 `@SpringBootTest` 不依赖外部服务（根 pom 里 surefire 全局：`spring.flyway.enabled=false`、`spring.kafka.listener.auto-startup=false`、`aiwarden.outbox.relay.enabled=false`，DataSource 仅装配不连接）；真实 PostgreSQL / Kafka（pgvector）验证收敛在 `*ContainersTest` 类，类内显式 `properties` 开启 + `@ServiceConnection` 指向容器。
- **本机 Testcontainers 偶发「Could not find a valid Docker environment」**：报错常见 `MalformedChunkCodingException (Bad chunk header)`，出在 NpipeSocket 策略的探测实现里；npipe 抖动窗口内**重试多次不恢复**。**处理（2026-10-07 实测更新）**：
  ① 先证明引擎本体健康——PowerShell 用 `NamedPipeClientStream` 连 `\\.\pipe\docker_engine` 发 `GET /version` 应返回 200（沙箱下 docker CLI 被禁时这是唯一探针）；
  ② **稳定绕行（推荐）**：`$env:DOCKER_HOST = "npipe:////./pipe/docker_engine"` + maven 参数
  `-Ddocker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy`
  ——走不同探测实现，立即可用（勿入 pom：CI Linux 上无 npipe，会反向破坏）；
  ③ 删 `~/.testcontainers.properties` 的 `docker.client.strategy` 行**只对当次启动生效**——Testcontainers 探测成功后会把策略**缓存写回**（2026-10-07 实测：删后一次成功运行即写回），**勿指望一劳永逸**；复发时用 ② 或再删一次。
  偶发失败时不要先去怀疑测试代码；CI（Linux）不受此影响。
- **@SpringBootTest 全上下文里多个 `CommonErrorHandler` 候选会让 Kafka 错误处理「静默回退默认」**：产品代码（knowledge 的 `DocumentIngestMessagingConfig`）与测试配置各注册一个 `CommonErrorHandler` 时，候选不唯一 → 工厂不采用任何一个 → 自定义重试次数与 DLT 全部失效（实测现象：决策测试的 DLT 断言 60s 超时、无报错提示原因）。**需要哪个生效就给它 `@Primary`**。同类风险适用于一切「按类型唯一装配」的扩展点（TaskDecorator、HandlerInterceptor、TaskScheduler 等），多 bean 共存时都要显式表态。
- **幂等仲裁的键必须区分「重复投递」与「合法状态流转」**：M1 初版用同一行 `(doc_id, version)` 账本同时服务
  索引与删除事件，`tryClaim` 只允许 FAILED 重抢——于是**「索引完成（INDEXED）后的删除事件」被当成重复而静默跳过**，
  向量清理不执行；HTTP 全链路测试以 `1/DELETED/INDEXED` **状态超时**暴露，**全程无报错、无指向性日志**。
  修正：拆开 `tryClaimForIndex`（FAILED 可重抢）与 `tryClaimForDelete`（**INDEXED / FAILED 可重抢**）。
  **写幂等仲裁前先问一句：这个键上会不会出现合法的多次流转？** P3 工具幂等会复用同一套方法论，尤其注意。
  依据见 [`ADR-005` 修订段](docs/adr/ADR-005-outbox-idempotent-consumption.md)。
- **HNSW 行为学测试的三个「静默假绿」陷阱，本轮全踩过（依据见 [`ADR-008` 修订段](docs/adr/ADR-008-visibility-acl-model.md)，测试类有完整注释）**：
  ① **planner 不到大表不走 HNSW**——5000 / 20000 / 50000 行实测全选精确路径（Seq Scan → 并行
  `Gather Merge` → btree 表达式索引 → 主键索引，随 JSONB 过滤的选择性估算在 1/250/50000 间横跳），
  `hnsw.*` 参数根本不参与。要在测试里观察 HNSW，必须受控：事务内 `enable_seqscan=off` + 关
  `max_parallel_workers_per_gather` + 移除 btree / 主键索引，并 EXPLAIN 断言索引名。
  ② **`hnsw.max_scan_tuples` 只限 iterative 绕回额度，首次 `ef_search` 候选不受限**——若测试数据可见行
  先插入，HNSW 图入口点落在可见团内，默认配置（off）也能凑满 K（假绿）；**可见行必须后插入**，
  「凑满 K + 回退计数零增量」才是 iterative_scan 生效的证据。**插入顺序是 HNSW 断言的一部分。**
  ③ **「写审计 → 抛异常」形态的留痕写入必须 `REQUIRES_NEW`**——同事务会被异常回滚滚掉，实测审计计数为 0。
- **`@TestInstance(PER_CLASS)` 本身不会炸容器测试；炸的是它与 `@DynamicPropertySource` 的组合（2026-10-07 实测更正）**：PER_CLASS 下 JUnit **先实例化测试类**（→ Spring 注入 → 上下文创建 → `@DynamicPropertySource` 求值），**之后**才跑扩展的 beforeAll（Testcontainers 在此启动静态容器）——于是 supplier 求值时容器未启动，报 `Mapped port can only be obtained after the container is started`，表现为 ApplicationContext 加载失败。**真正的触发条件是 `@DynamicPropertySource`**：`RetrievalVisibilityContainersTest` / `RetrievalHnswPushdownContainersTest` / `RetrievalExactFallbackContainersTest` 三个类都是 `PER_CLASS` + 静态 `@Container` + `@ServiceConnection`（不写 supplier），实测全绿；而 `GovernanceTaxContainersTest` 需要 `@DynamicPropertySource`，就必须避开 PER_CLASS。**判据：容器属性用 supplier 延迟取值的 → 不能 PER_CLASS；用 `@ServiceConnection` 的 → 可以。**
- **改共享契约（构造函数 / SPI 签名 / 枚举）后必须先 `clean test-compile`，不能只 `test-compile`（2026-10-07 实测）**：增量编译可能判定某模块「无变化」而留用旧 class，随后 `-pl <下游> -am test` 会拿**半旧的 class** 去跑，症状是一大片看不懂的
  `java.lang.Error: Unresolved compilation problem` + `Failed to load ApplicationContext`（实测一次 24 个用例同时红，看起来像系统性崩溃）。`mvn -B -ntp -o clean test-compile` 后只剩 **1 个真实错误**。
  **判据：只要改的是别人也依赖的类型，就先 `clean`。** 另外 `-q` 会把编译错误藏起来，"无输出"不等于"编译通过"——要看退出码。
- **MCP SDK 的 handler 执行线程与 Servlet 请求线程不同（切片④实测）**：ThreadLocal 的租户/主体上下文在 handler 里**不可用**（实测：调用被拒——「租户上下文缺失」）。身份必须经 transport 层显式携带：`contextExtractor(HttpServletRequest)` 把身份头写进 `McpTransportContext`，handler 里用 `exchange.transportContext()` 取出并 `TenantContext.callWithTenant` + `PrincipalContext.set/clear` 恢复——ADR-003 的显式 capture/apply 在第三方 SDK 线程边界上再次适用。
- **Micrometer 导出时会剥掉 Gauge 名尾部的 `_total`（切片③④联调实测）**：代码里注册的是
  `aiwarden_vector_orphan_total`（`ConsistencyReconciler` 的 `Gauge.builder`），而 `/actuator/prometheus`
  实际导出的是 **`aiwarden_vector_orphan`**——`_total` 是 counter 的命名约定，Micrometer 统一剥离。
  PRD / README / ADR 引用**注册名**（作为指标身份是对的），但**任何按名字抓取该指标的代码必须同时认两种形态**，
  否则表现是「面板永远采不到点、不报错」——与 Jackson 属性名、`flyway-core` 同属「名字写错即静默失效」家族。
  同类：`aiwarden_ingest_stuck_total` → 导出 `aiwarden_ingest_stuck`。
  **判据：按指标名做字符串匹配前，先 `curl /actuator/prometheus | grep aiwarden_` 看真实导出名，不要照抄注册名。**
- **同名依赖以不同 scope 在「装配模块」重复声明，会静默把运行期依赖降级掉（切片③④联调实测，本轮最隐蔽的一条）**：
  `aiwarden-governance` 经 `spring-boot-starter-data-redis` 带来 compile scope 的 `lettuce-core`，而
  `aiwarden-start` 又**直接**声明了 `lettuce-core` + `<scope>test</scope>`（M1 对照实验残留）。
  Maven 的就近声明压过传递依赖 → **测试 JVM 拿得到 lettuce（`mvn verify` 112 个测试全绿），
  打出的 fat jar 却没有 Redis 客户端** → 自动配置静默不装配 → `StringRedisTemplate` 无候选 bean →
  `java -jar` 启动即失败，**而没有任何一个测试能发现它**。
  **推论：测试分层（根 pom 的 surefire 全局属性）让「装配面」与「运行面」脱钩后，`mvn verify` 全绿
  不再等于「应用能启动」。判据：凡改依赖（尤其 scope）后，必须做一次
  `mvn -pl aiwarden-start -am package -DskipTests` + `java -jar` 的启动冒烟；
  `dependency:tree` 也要看**最终装配模块**（`-pl aiwarden-start`），或直接核对 jar 的 `BOOT-INF/lib`。**
- **多模块工程的 `spring-boot:run` 必须「先 install，再单模块 run」两步，不能一步到位（2026-10-10 实测）**：
  根 pom 的 `spring-boot-maven-plugin` 只在 `aiwarden-start` 里有 `<goal>repackage</goal>`，
  但 `spring-boot:run` 是**命令行 goal**——reactor 里**每个模块**都会执行它，于是三种写法各有死法：
  ① `mvn -pl aiwarden-start -am spring-boot:run`（从根）→ `-am` 把根聚合工程与兄弟模块一并入 reactor，
  根 pom 无 main class → `Unable to find a suitable main class`（**报错里是项目 `aiwarden`，不是 `aiwarden-start`**，
  极易误读成「start 模块坏了吗」）；
  ② `mvn -pl aiwarden-start spring-boot:run`（不带 `-am`）→ 兄弟模块 `0.1.0-SNAPSHOT` 不在本地仓库 →
  `Could not resolve dependencies`；
  ③ **在 `aiwarden-start/` 目录里跑 `-am` 也无效**——Maven 只有「从根跑的聚合构建」才有完整 reactor，
  在子模块目录执行只看到它自己，`-am` 无兄弟可加。
  **可用写法**（本项目实测通过，app 正常 `Started AiwardenApplication`）：
  `mvn -B -ntp -DskipTests install`（一次，改了兄弟模块后重跑）→ `mvn -B -ntp -pl aiwarden-start spring-boot:run`
  （`run` 会现场编译该模块，改 start 自身代码不必重 install）；
  或完全绕开 plugin：`mvn -pl aiwarden-start -am -DskipTests package` + `java -jar aiwarden-start/target/*.jar`。
  **另注**：本机 `mvn install` **不能加 `-o`**——`maven-install-plugin` 自身的依赖未缓存，离线会
  `PluginResolutionException`（`verify` 可以离线，`install` 不行）。
- **评测页在本地开发库必为 404，不是故障（2026-10-10 实测）**：`t_eval_report` 由评测门禁测试写入
  **Testcontainers 的临时数据库**，测试结束容器销毁——本地 `docker compose` 起的那套库里永远是空的，
  `GET /api/v1/admin/eval/report` 必然返回 404 + `{"detail":"暂无评测报告…"}`。
  **判据：看该接口 404 时先查 `t_eval_report` 行数，别去怀疑路由或 controller**（路由存在性可用
  「不带身份头应返回 400」来证明——400 说明请求已到我们的 controller）。

---

## 5. 状态

> **见 [docs/STATUS.md](docs/STATUS.md)**——阶段 / 测试数 / 已知边界 / 下一步 / 公开范围都在那里（**易过期**，
> 与本文件「只增不减的坑清单」性质相反，故拆出）。阶段推进时改它，并按 §2 同步 PRD / README / ADR / 06。