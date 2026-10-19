# 代码 / 配置层面的机制坑（唯一正文）

> **本文件是什么**：**「写错不报错、只是静默不生效」**那类坑的唯一正文。
> **本文件即旧「`AGENTS.md` §4」的正文落点**（2026-10 文档重构迁出，裁决 32）——历史文档里出现的
> 「见 `AGENTS.md` §4」一律解析到这里（[`../AGENTS.md`](../AGENTS.md) §4 现在只剩「最常踩五条」的红线速查，
> 完整的认症状索引在本文件开头）。
>
> **边界**：本机环境类排障（端口 / 进程 / 容器 / profile / Maven reactor）不在这里，
> 唯一正文见 [`dev/troubleshooting.md`](dev/troubleshooting.md)；决策依据见 [`adr/`](adr/README.md)。
>
> **编号纪律**：`P01…` 扁平零填充，**本版一次性分配、后续新增只追加 P21、P22…，永不重排也不复用号**
> （条目只增不减；正文迁走时降级为一行指针，保留「一眼认出症状」的能力）。

---

## 0. 先记两条可迁移的判断规则

**规则一——本文件多数条目共享同一个失效形态：不报错、只是静默不生效**：

- **名字写错**：`-betaNN` 版本后缀、Jackson 属性名少一个连字符、`flyway-core` 与 `spring-boot-starter-flyway` 的区别、
  Micrometer 导出时剥掉 Gauge 的 `_total` 后缀；
- **依赖被挤掉**：同名依赖在装配模块以更近的 test scope 重复声明，运行期依赖静默消失；
- **跑的不是你以为的代码**：`spring-boot:run` 只编译自身模块，兄弟模块走本地仓库旧 jar；
- **绕过了代理**：往响应式数组 push 原始对象后再改那个局部变量，数据变了但不重渲染；
- **装配歧义**：`CommonErrorHandler` 候选不唯一（工厂干脆谁都不用，自定义重试与 DLT 一起失效）；
- **语义设计**：幂等仲裁键混用「重复投递」与「合法状态流转」；
- **精度被吞**：JSON 小数默认落成 `Double`，高精度输入得到同一指纹（静默重放首次结果）；
- **配置作用域**：GUC / 开关设了不复位，同一事务内后续路径静默退化。

在新一代框架（SB 4 / 模块化拆包）与异步管道里，「不报错但不生效」比「报错」更常见。
**因此：引入任何技术栈或机制后，必须验证它真的生效（有日志 / 有表 / 有行为 / 有指标），不能只看构建通过。**

**规则二——推论：验证本身也要自证**。做行为 / 性能验证时，必须断言**机制确实被触发**（执行计划走了目标索引、
消费组确实提交了位移、指标确实已注册），否则小样本上极易得到「全对、但什么都没证明」的假结论。
本项目已**三次**栽在这点上：ArchUnit 选择器拼错仍全绿、HNSW 选择性实验退化成顺序扫描、
以及 M2 的「HNSW 三重假绿」（小表走精确路径 / 数据插入顺序把入口点放进可见团 / 审计被事务回滚——见 P13）。

## 索引：认症状（一行一条，细节见下面正文）

| 症状（认出这个就够） | 条目 |
|---|---|
| starter 照抄核心件版本号 → 依赖解析直接失败 | P01 |
| Jackson 属性名多一个连字符只静默失效；两条线各有一套 Jackson | P02 |
| 只加 `flyway-core` → 迁移静默不执行（SB4 把自动配置拆了模块） | P03 |
| Testcontainers 在 SB4 要显式 BOM、模块改名；测试分层是硬约定 | P04 |
| `mvn verify` 全绿而 `java -jar` 起不来：test scope 把运行期依赖挤掉 | P05 |
| 多个 `CommonErrorHandler` 候选 → 自定义重试与 DLT 一起静默失效 | P06 |
| 面板永远采不到点：Micrometer 导出剥掉 Gauge 名的 `_total` | P07 |
| MCP handler 里 ThreadLocal 身份不可用，必须经 transport context | P08 |
| 改了兄弟模块仍跑到旧逻辑：`target/classes` 里的 class 是新的 ≠ 生效 | P09 |
| 本机 Testcontainers 偶发探测失败（npipe），抖动窗口内重试不恢复 | P10 |
| 容器测试加载失败：`PER_CLASS` × `@DynamicPropertySource` 端口求值过早 | P11 |
| 只跑 `test-compile` 留下半旧 class，表现为一成片看不懂的崩溃 | P12 |
| HNSW 三重假绿：小表走精确路径 / 可见行插入顺序 / 审计被回滚 | P13 |
| 子进程测试连错库、读不到日志（配置优先级 / 控制台编码） | P14 |
| 幂等键混用「重复投递」与「合法状态流转」→ 删除事件被当重复跳过 | P15 |
| 判据按「链路命名」而非「数据形态」穷举 → 最安静的失败漏网 | P16 |
| 对账重驱动不区分事件类型 + 版本 → 清空新文档 / 旧事件晚到覆盖新版 | P17 |
| 先写持久化副作用、后提交检查点 → 续跑重做时这条写入不幂等 | P18 |
| 租户上下文图省事引入 TTL → 虚拟线程 / 池化线程上串租户 | P19 |
| push 原始对象后再改那个局部变量：数据变了但 UI 不重渲染 | P20 |

---

## A. 依赖坐标与版本形态

### P01 · LangChain4j 核心件与 starter 的版本号形态不同

核心件是 `dev.langchain4j:langchain4j:1.21.0`，而 starter 模块是
`dev.langchain4j:langchain4j-spring-boot4-starter:1.21.0-beta31`。
**照抄核心件的版本号去写 starter 会直接依赖解析失败。** `-betaNN` 是 LangChain4j 对 starter / 集成模块
的发布惯例，不是「API 不稳定」的标记。依据与实测命令见 [`ADR-001`](adr/ADR-001-bootstrap-versions.md)。

### P02 · Jackson 在两条依赖线上各有一套，且配置属性名有陷阱

SB 4.1.1 的**应用层默认是 Jackson 3**（`tools.jackson.core`，注意 groupId **不是** `com.fasterxml.jackson`），
而 **LangChain4j 侧默认 Jackson 2**（`com.fasterxml.jackson.core`）——两套 groupId 不同，同一 classpath 上共存不冲突。
回退应用层到 Jackson 2 默认行为的属性是 **`spring.jackson.use-jackson2-defaults`**（**`jackson2` 中间没有连字符**）；
写成 `use-jackson-2-defaults` **不报错，只静默失效**。依据见
[`ADR-001` 修订段](adr/ADR-001-bootstrap-versions.md)。

### P03 · Spring Boot 4 把自动配置拆进了独立模块：只加底层库可能「完全不装配且不报错」

集成某技术时优先找对应 `spring-boot-starter-*`。实测踩坑：只加 `org.flywaydb:flyway-core` 时应用照常启动、
**Flyway 静默不执行任何迁移**（无日志、无表）；换用 `org.springframework.boot:spring-boot-starter-flyway`
才恢复「启动即迁移」。引入任何原本应存在自动配置的技术栈时，先核对 Starter 坐标。

### P04 · Testcontainers 在 SB 4 下的两个硬约束 + 测试分层是硬约定

① SB 4 **不再代管其版本**，必须在根 pom 显式导入 `org.testcontainers:testcontainers-bom`
（照 Boot 3.x 写法直接引用会构建失败）；
② 2.x 起所有模块改名为 `testcontainers-*` 前缀（如 `testcontainers-junit-jupiter`、
`testcontainers-postgresql`），照 1.x 旧名会解析失败。
**测试分层**：普通 `@SpringBootTest` 不依赖外部服务（根 pom 里 surefire 全局：`spring.flyway.enabled=false`、
`spring.kafka.listener.auto-startup=false`、`aiwarden.outbox.relay.enabled=false`，DataSource 仅装配不连接）；
真实 PostgreSQL / Kafka（pgvector）验证收敛在 `*ContainersTest` 类，类内显式 `properties` 开启 +
`@ServiceConnection` 指向容器。

### P05 · 同名依赖以不同 scope 在「装配模块」重复声明，会静默把运行期依赖降级掉

（切片③④联调实测，本轮最隐蔽的一条）`aiwarden-governance` 经 `spring-boot-starter-data-redis` 带来
compile scope 的 `lettuce-core`，而 `aiwarden-start` 又**直接**声明了 `lettuce-core` + `<scope>test</scope>`
（M1 对照实验残留）。Maven 的就近声明压过传递依赖 → **测试 JVM 拿得到 lettuce（`mvn verify` 全量测试全绿），
打出的 fat jar 却没有 Redis 客户端** → 自动配置静默不装配 → `StringRedisTemplate` 无候选 bean →
`java -jar` 启动即失败，**而没有任何一个测试能发现它**。

**推论：测试分层（根 pom 的 surefire 全局属性）让「装配面」与「运行面」脱钩后，`mvn verify` 全绿
不再等于「应用能启动」。判据：凡改依赖（尤其 scope）后，必须做一次
`mvn -pl aiwarden-start -am package -DskipTests` + `java -jar` 的启动冒烟；
`dependency:tree` 也要看**最终装配模块**（`-pl aiwarden-start`），或直接核对 jar 的 `BOOT-INF/lib`。**

## B. 装配歧义与命名口径

### P06 · 多个 `CommonErrorHandler` 候选会让 Kafka 错误处理「静默回退默认」

`@SpringBootTest` 全上下文里，产品代码（knowledge 的 `DocumentIngestMessagingConfig`）与测试配置各注册一个
`CommonErrorHandler` 时，候选不唯一 → 工厂不采用任何一个 → 自定义重试次数与 DLT 全部失效
（实测现象：决策测试的 DLT 断言 60s 超时、无报错提示原因）。**需要哪个生效就给它 `@Primary`**。
同类风险适用于一切「按类型唯一装配」的扩展点（TaskDecorator、HandlerInterceptor、TaskScheduler 等），
多 bean 共存时都要显式表态。

### P07 · Micrometer 导出时会剥掉 Gauge 名尾部的 `_total`

（切片③④联调实测）代码里注册的是 `aiwarden_vector_orphan_total`（`ConsistencyReconciler` 的 `Gauge.builder`），
而 `/actuator/prometheus` 实际导出的是 **`aiwarden_vector_orphan`**——`_total` 是 counter 的命名约定，
Micrometer 统一剥离。PRD / README / ADR 引用**注册名**（作为指标身份是对的），但**任何按名字抓取该指标的代码
必须同时认两种形态**，否则表现是「面板永远采不到点、不报错」——与 Jackson 属性名、`flyway-core`
同属「名字写错即静默失效」家族。同类：`aiwarden_ingest_stuck_total` → 导出 `aiwarden_ingest_stuck`。
**判据：按指标名做字符串匹配前，先 `curl /actuator/prometheus | grep aiwarden_` 看真实导出名，不要照抄注册名。**

### P08 · MCP SDK 的 handler 执行线程与 Servlet 请求线程不同

（切片④实测）ThreadLocal 的租户 / 主体上下文在 handler 里**不可用**（实测：调用被拒——「租户上下文缺失」）。
身份必须经 transport 层显式携带：`contextExtractor(HttpServletRequest)` 把身份头写进 `McpTransportContext`，
handler 里用 `exchange.transportContext()` 取出并 `TenantContext.callWithTenant` + `PrincipalContext.set/clear`
恢复——ADR-003 的显式 capture/apply 在第三方 SDK 线程边界上再次适用。

### P09 · 跑的不是你以为的代码：`spring-boot:run` 只现场编译 start 自身模块

其余模块走本地仓库已安装的 jar，于是「改了 `aiwarden-agent` 的代码 → 只跑 `spring-boot:run`」会**跑到旧逻辑**，
而 `target/classes` 里的 class 明明是新的——实测症状极具迷惑性：HTTP 响应返回的是**几小时前的旧文案**，
而 `MockModelClient.class` 里新方法、新字符串常量**全都在**（那是 `mvn verify` 编出来的），
查源码、查 class、查全仓文本都找不到「旧文本从哪来」。
**判据：`target/classes/**` 新 ≠ 生效；要核对的是本地仓库
`…\repository\com\aiwarden\<兄弟模块>\0.1.0-SNAPSHOT\*.jar` 的时间戳。**
纪律（改了任一兄弟模块先 `install` 再 run；`java -jar` 只在 `package` 时生成）与可用命令、
含**为何不能写 `-pl aiwarden-start -am spring-boot:run` 的三种死法**，唯一正文见
[`dev/troubleshooting.md`](dev/troubleshooting.md) 第 4 条。

## C. 测试与容器

### P10 · 本机 Testcontainers 偶发「Could not find a valid Docker environment」

报错常见 `MalformedChunkCodingException (Bad chunk header)`，出在 NpipeSocket 策略的探测实现里；
npipe 抖动窗口内**重试多次不恢复**。**处理（2026-10-07 实测更新）**：
① 先证明引擎本体健康——PowerShell 用 `NamedPipeClientStream` 连 `\\.\pipe\docker_engine` 发 `GET /version`
应返回 200（沙箱下 docker CLI 被禁时这是唯一探针）；
② **稳定绕行（推荐）**：`$env:DOCKER_HOST = "npipe:////./pipe/docker_engine"` + maven 参数
`-Ddocker.client.strategy=org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy`
——走不同探测实现，立即可用（勿入 pom：CI Linux 上无 npipe，会反向破坏）；
③ 删 `~/.testcontainers.properties` 的 `docker.client.strategy` 行**只对当次启动生效**——Testcontainers
探测成功后会把策略**缓存写回**（2026-10-07 实测：删后一次成功运行即写回），**勿指望一劳永逸**；
复发时用 ② 或再删一次。
偶发失败时不要先去怀疑测试代码；CI（Linux）不受此影响。
（**归属说明**：症状出现在本机，但处置是「测试怎么跑起来」的 maven 参数与探测策略，属本文件的机制坑；
[`dev/troubleshooting.md`](dev/troubleshooting.md) 第 10 条只留症状 + 指针到这里。）

### P11 · `@TestInstance(PER_CLASS)` 本身不会炸容器测试；炸的是它与 `@DynamicPropertySource` 的组合

（2026-10-07 实测更正）PER_CLASS 下 JUnit **先实例化测试类**（→ Spring 注入 → 上下文创建 →
`@DynamicPropertySource` 求值），**之后**才跑扩展的 beforeAll（Testcontainers 在此启动静态容器）——
于是 supplier 求值时容器未启动，报 `Mapped port can only be obtained after the container is started`，
表现为 ApplicationContext 加载失败。**真正的触发条件是 `@DynamicPropertySource`**：
`RetrievalVisibilityContainersTest` / `RetrievalHnswPushdownContainersTest` / `RetrievalExactFallbackContainersTest`
三个类都是 `PER_CLASS` + 静态 `@Container` + `@ServiceConnection`（不写 supplier），实测全绿；
而 `GovernanceTaxContainersTest` 需要 `@DynamicPropertySource`，就必须避开 PER_CLASS。
**判据：容器属性用 supplier 延迟取值的 → 不能 PER_CLASS；用 `@ServiceConnection` 的 → 可以。**

### P12 · 改共享契约后必须先 `clean test-compile`，不能只 `test-compile`

（2026-10-07 实测）改构造函数 / SPI 签名 / 枚举后，增量编译可能判定某模块「无变化」而留用旧 class，
随后 `-pl <下游> -am test` 会拿**半旧的 class** 去跑，症状是一大片看不懂的
`java.lang.Error: Unresolved compilation problem` + `Failed to load ApplicationContext`
（实测一次 24 个用例同时红，看起来像系统性崩溃）。`mvn -B -ntp -o clean test-compile` 后只剩 **1 个真实错误**。
**判据：只要改的是别人也依赖的类型，就先 `clean`。** 另外 `-q` 会把编译错误藏起来，
"无输出"不等于"编译通过"——要看退出码。

### P13 · HNSW 行为学测试的三个「静默假绿」陷阱，本轮全踩过

（依据见 [`ADR-008` 修订段](adr/ADR-008-visibility-acl-model.md)，测试类有完整注释）
① **planner 不到大表不走 HNSW**——5000 / 20000 / 50000 行实测全选精确路径（Seq Scan → 并行
`Gather Merge` → btree 表达式索引 → 主键索引，随 JSONB 过滤的选择性估算在 1/250/50000 间横跳），
`hnsw.*` 参数根本不参与。要在测试里观察 HNSW，必须受控：事务内 `enable_seqscan=off` + 关
`max_parallel_workers_per_gather` + 移除 btree / 主键索引，并 EXPLAIN 断言索引名。
② **`hnsw.max_scan_tuples` 只限 iterative 绕回额度，首次 `ef_search` 候选不受限**——若测试数据可见行
先插入，HNSW 图入口点落在可见团内，默认配置（off）也能凑满 K（假绿）；**可见行必须后插入**，
「凑满 K + 回退计数零增量」才是 iterative_scan 生效的证据。**插入顺序是 HNSW 断言的一部分。**
③ **「写审计 → 抛异常」形态的留痕写入必须 `REQUIRES_NEW`**——同事务会被异常回滚滚掉，实测审计计数为 0。

### P14 · 子进程集成测试的两个静默坑（M4 实测）

① `SpringApplicationBuilder.properties(...)` 是**默认值**，会被 `application.yml` 里显式写的
`spring.datasource.url` 覆盖——子进程于是连到 `localhost:5432` 而不是测试容器，报错看起来像「容器没起」；
要用**命令行参数** `--spring.datasource.url=...`（最高优先级）。
② Windows 下子进程 stdout 重定向到文件时按控制台代码页写，父进程用 UTF-8 读日志会抛
`MalformedInputException: Input length = 1`（断言里只看到「读不到日志」）；要显式给子进程
`-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。
判据：**子进程连错库先查配置优先级，读不到日志先查编码。**

## D. 数据面判据与幂等

### P15 · 幂等仲裁的键必须区分「重复投递」与「合法状态流转」

M1 初版用同一行 `(doc_id, version)` 账本同时服务索引与删除事件，`tryClaim` 只允许 FAILED 重抢——
于是**「索引完成（INDEXED）后的删除事件」被当成重复而静默跳过**，向量清理不执行；
HTTP 全链路测试以 `1/DELETED/INDEXED` **状态超时**暴露，**全程无报错、无指向性日志**。
修正：拆开 `tryClaimForIndex`（FAILED 可重抢）与 `tryClaimForDelete`（**INDEXED / FAILED 可重抢**）。
**写幂等仲裁前先问一句：这个键上会不会出现合法的多次流转？** P3 工具幂等会复用同一套方法论，尤其注意。
依据见 [`ADR-005` 修订段](adr/ADR-005-outbox-idempotent-consumption.md)。

### P16 · 「发现类」判据（对账 / 审计 / 健康检查）必须按「数据能呈现的形态」穷举，不按「链路的命名」穷举

M1 的对账三条判据是按「删除链路会残留什么」写的（残留切片 / 孤儿向量 / 账本 `PROCESSING` 超时），
于是**「事件永久失败 → 账本根本没建起来」这个形态结构性地落在判据之外**——实测本机一条
`t_outbox_event` 重试到上限置 `FAILED`，文档停 `PENDING` **九天**，而 `mismatch_count=0`、
`aiwarden_ingest_stuck 0.0`：**不报错、不进不一致清单、指标恒 0**，症状只在 B 端页面一句「1 个待摄入」。
修正：`mismatchCount` 第二项扩为**三条子判据按 `doc_id` 去重的并集**（事件 `FAILED` / 账本超时 /
文档状态超时），`repair` 同步增加第二类显式动作（重驱动 `FAILED → PENDING`）。
**写判据时先问一句：这条判据的「盲区」是什么形态？被它排除的那些行，会不会正好是最安静的那种失败？**
另注：重驱动**不等于收敛**——事件置回 PENDING 后文档仍停摆（reason 退化为 `DOCUMENT_OVERDUE`），
验收要断言到状态真的完成，不能拿「接口返回 200」当「已修好」。
**补一条守卫（同一轮注入实验抓到的假阳性）**：`FAILED` 事件**只在文档未收敛**（仍 PENDING/PROCESSING）
时才算停摆——把一条已被成功消费的索引事件改回 `FAILED`，无此限制就把已 `INDEXED` 的文档
报成不一致；文档行不存在也没有可重驱动目标，不再计入 `OUTBOX_FAILED`。且**判据、`failedOutboxEvents` 计数、
`repair` 的重驱动三处必须用同一个过滤条件**，口径分叉的下一个症状就是「清单为空 / 计数 0 / 却重驱动了 1 条」。
依据见 [`ADR-006` 修订段](adr/ADR-006-reconcile-readonly.md)。
（**演示配方**不在此文件：怎么注入一份可回滚的不一致、复原时为何不能按 `doc_id` 取嵌入，见
[`dev/troubleshooting.md`](dev/troubleshooting.md) 第 8 条。）

### P17 · 对账 `repair` 的重驱动必须带「事件类型 + 当前版本」双守卫

`type LIKE 'document.%'` 会把 `document.delete.requested` 也放回；删除事件重放会重新 purge **全部版本**，
可能删掉同名重传的新文档。旧版本索引事件重放则会用当前内容按旧 version 写回，并删掉新版本切片。
幂等键 `(docId, version)` 只挡重复投递，**挡不住"旧事件晚到"**。判据 / 计数 / 重驱动三处共用同一条件
（`REDRIVABLE_FAILED_INDEX_EVENT` = 当前版本 + 索引类 + 文档 PENDING/PROCESSING），
消费端再按 `t_document.version` 拒一次（`DocumentEventConsumer` + handler 双守卫）。
依据与现场路径见 [`ADR-006` 修订段](adr/ADR-006-reconcile-readonly.md)。

### P18 · 检查点提交之前的持久化动作在崩溃续跑时会重做（M4 实测）

`MeteringNode` 在检查点提交前写 outbox，崩在两者之间会让续跑再写一条计量事件。计量事件必须带幂等键
（模型 `model:<runId>:<stepNo>`、工具 `tool:<invocationId>`），消费端以 `t_llm_call_log.dedup_key` 唯一索引 +
`ON CONFLICT DO NOTHING` 仲裁；嵌入计量暂为 NULL（摄入侧由 ledger 保护）。
判据：**凡是「先写持久化副作用、后提交检查点」的节点，都要问一句续跑重做时这条写入是否幂等。**
证据见 `AgentCrashResumeContainersTest` 的 crash-at-metering 用例。

## E. 上下文与前端

### P19 · 租户上下文不引入 TransmittableThreadLocal / InheritableThreadLocal

虚拟线程是不可复用的一次性线程、池化线程（`@Scheduled`）会被复用——两类边界统一走 `TenantContext`
的显式 capture（`snapshot()`）→ apply，缺失时 `requireTenantId()` 拒绝（**不回落默认租户**）。
写异步 / 消费 / 定时任务代码时不要为了「自动透传」引入 TTL 依赖；依据与单测证据见
[`ADR-003`](adr/ADR-003-tenant-context-capture.md)。

### P20 · 前端把「原始对象」的 mutation 打在响应式代理之外：数据变了但不重渲染

（2026-10-10 实测，靠真实浏览器定位）`ChatView` 原写法 `const turn = newTurn(); messages.value.push(turn)` ——
`push(raw)` 之后**模板渲染读的是 Vue 包出来的代理**，而局部变量 `turn` 仍指向**原始对象**；
后续所有 `turn.citations.push(...)` / `turn.outcome = ...` 都绕过了代理的 `set`/`add` 拦截，
**目标数据确实被改了（DevTools 读 setupState 能看到 2 条），但依赖通知不触发 → 面板停在初始状态**。
实测症状极具误导性：检索明细写着 `hits=2`、computed 读出来也是 2，**而引用侧栏永远「0 条」**；
步骤时间线因为同样的原因只是「侥幸跟随」（数组被整体替换式的操作才会通知）。
**修正**：`messages.value.push(newTurn())` 后**从数组里取回代理**再改：
`const turn = messages.value[messages.value.length - 1]`。
**判据：凡是「先造对象 → push 进响应式数组 → 再改这个局部变量」的写法都有此坑；
要么 push 后从数组取回，要么直接用 `reactive()` 造对象。**
**定位手法（可复用）**：这类「数据对但 UI 不对」的问题，靠截图/读 DOM 会绕很久——
用 CDP 在页面里**同时**读 `setupState` 与 DOM，并做「直插数据 → 观察 DOM 是否跟随」的对照实验即可一次定性
（本次对照：经代理直插立刻变 3 条，而业务路径的 push 不生效）。

---

## 认症状用（不在本文件展开）

环境类四条最容易误判成「代码 / 装配问题」的现象——`5432 refused`、Kafka 9092「连不上但容器 healthy」、
`8080 already in use`、`t_eval_report` 表空时 404——判据与处置的唯一正文见
[`dev/troubleshooting.md`](dev/troubleshooting.md)（第 1 / 2 / 3 / 5 条）。
