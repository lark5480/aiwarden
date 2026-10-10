# AGENTS.md — AIWarden 编码代理协作约定

> **本文件是什么**：AI 编码代理（Claude Code / Codex / Cursor 等）在本仓库工作时的**入口文档**。
> 只放「**写错代价高、不翻代码发现不了**」的铁律与坑；能指针就不重抄——产品需求看 [`docs/PRD.md`](docs/PRD.md)，
> 技术决策看 [`docs/adr/`](docs/adr/README.md)，对外表述红线看 [`docs/PRD.md`](docs/PRD.md) §15。
>
> **本文件处于「随代码生长」状态**（2026-10 起，已有业务代码）：地图 + 纪律 + 实测踩过的坑。
> 每踩一个坑、每定一条与代码相关的约定，就往 §4 追加一条。
> **本文件不写里程碑 / 阶段状态**（那是会腐化的描述，见 [`docs/STATUS.md`](docs/STATUS.md) §1）。
>
> ⚠️ **不要在本文件写「计划」**。描述未来状态的内容会腐化，而 `**/*.md` 检索命中后会把过期的
> 未来状态当成待办——存量项目已吃过这个亏（原 `docs/compose/plans/` 因同类原因被删）。

---

## 1. 文档职责与指针

> **索引正文见 [`docs/README.md`](docs/README.md)**（活文档 vs 冻结资产的职责边界、各子目录入口、「我该看哪个」路由）——
> 本文件**不再复制那张表**。「哪条事实归哪个文件」的工作用映射表在下一节 §2，那张表只服务于口径变更时的定位。
>
> 一句话入口：需求看 [`docs/PRD.md`](docs/PRD.md)，决策看 [`docs/adr/`](docs/adr/README.md)，
> 进度与实测数字看 [`docs/STATUS.md`](docs/STATUS.md)，环境排障看 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)，
> 失败索引看 [`docs/FAILURE.md`](docs/FAILURE.md)，对外门面是根 [`README.md`](README.md)。

---

## 2. 铁律：一条事实只有一个正文，其余只允许指针（**本项目文档之乱源于缺这条**）

**任何「口径级」事实**——版本号、承诺增删、范围裁剪、术语改名、指标口径、实测数字、环境排障步骤——
**必须能在下表唯一的权威源里找到正文**；其它文件只允许出现**一句结论 + 指针**，不允许出现第二份正文。
**发现第二份正文即视为文档缺陷**，就地删除并改成指针。

| 事实类别 | 唯一权威源 | 其余位置允许的形态 |
|---|---|---|
| 要做什么、验收标准、范围、里程碑定义 | [`docs/PRD.md`](docs/PRD.md) | 一行指针 |
| 技术 / 版本选型为什么这么定 | [`docs/adr/`](docs/adr/README.md)（与 PRD 冲突以 ADR 为准） | 一行指针 |
| **实测数字与验证口径**（测试数 / 评测结论 / 治理税 / P95 / 单次成本） | [`docs/STATUS.md`](docs/STATUS.md) §2 | 结论摘要 + 指针；README 第一屏是它的**对外投影** |
| 当前阶段 / 已知边界 / 公开范围 | [`docs/STATUS.md`](docs/STATUS.md) §1/§3/§5 | 摘要 + 指针 |
| **本机环境排障**（端口、进程、容器、profile、Maven reactor） | [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md) | 症状一句话 + 指针 |
| **代码 / 配置层面的机制坑**（写错会静默不生效的东西） | 本文件 §4 | 指针 |
| 对外表述红线（禁词 / 话术 / 竞品证据分级） | [`docs/PRD.md`](docs/PRD.md) §15 | 指针 |
| 走过并被记录在案的失败 / 放弃 / 未达成 | [`docs/FAILURE.md`](docs/FAILURE.md)（**只是索引**，定义见 [`docs/PRD.md`](docs/PRD.md) §8.1；机制正文仍各归其权威源） | 一行结论 + 指针 |
| 争议怎么拍板的 | [`docs/research/06-PRD修订裁决清单.md`](docs/research/06-PRD修订裁决清单.md)（编号顺延追加） | 指针 |
| 版本号 | [`pom.xml`](pom.xml)；基础设施镜像 tag 见 [`docker-compose.yml`](docker-compose.yml) | 任何文档引用的版本号都必须能在此找到坐标 |
| **对外口径正文 / 求职排位** | **仓库外的内部材料目录**（不在本工作树；公开仓库只留 PRD §14 的空壳指针） | **禁止检索、禁止提交、禁止在公开文档里给链接**——理由见 [`docs/README.md`](docs/README.md)「不随仓库公开」 |

**例外——冻结资产不算副本**：`docs/research/`（01–05 与 06 的裁决条目）、各 ADR 的「验证」段与「修订」段里出现的
实测数字，是**裁决/决策时点的证据**，属只增不改的历史，**不回改也不迁走**（06 号清单里「落到 README 的动作」
这类描述同理）。判据一句话：**活文档（PRD / README / STATUS / 本文件）漂移是缺陷，冻结资产里的旧数字是记录。**

**口径级变更的动作序列**（一次改动内做完，不允许「先改 PRD，外围文档下轮再说」）：

1. 按上表定位权威源，**只改那一处正文**；
2. 属于新决策的：在 `docs/adr/` 落 `ADR-00X-<slug>.md`，或在被演进条目末尾追加 `## 修订（YYYY-MM）` 段
   （**不改写原文**），并同步 `docs/adr/README.md` 索引；
3. 在 06 号清单追加裁决条目（编号顺延）；
4. **检查扩散面**：`grep` 该口径的关键词，确认除权威源外各处都是指针——**残留正文就地改成指针**；
5. README 若投影了数字，从 STATUS §2 **单向复制**（方向不可逆：永远 STATUS → README，不反向维护）。

**文档改动的收尾校验（不能靠眼看，2026-10-10 起为硬要求）**——四条命令跑完再报完成：

```bash
# ① 相对链接：抽出全部 .md 链接并测目标存在（改了相对基准就会静默断链）
for f in README.md AGENTS.md docs/README.md docs/PRD.md docs/STATUS.md docs/FAILURE.md \
         docs/dev/troubleshooting.md docs/adr/README.md docs/research/README.md docs/adr/ADR-*.md; do
  d=$(dirname "$f"); grep -ohE '\]\([^)]+\.md(#[^)]*)?\)' "$f" | sed -E 's/^\]\(//; s/\)$//; s/#.*$//' | sort -u \
    | while read l; do case "$l" in http*|/*) continue;; esac; [ -f "$d/$l" ] || echo "BROKEN $f -> $l"; done; done

# ② 副本计数：被迁走的关键判据应当只剩一处正文
grep -rl --include="*.md" "<关键短语>" README.md AGENTS.md docs | grep -v node_modules

# ③ 权威源纯度：把 STATUS §2 结论表里的「实测数字」自动提出来反查别处
#    （注意区分：**目标线 / 验收口径的数字 PRD 里合法**，如「P95 < 800ms」「20–30 条」；
#     只有实测结果数字——ms / 元 / x/x 计数——不允许离开 STATUS。README 是允许的投影，不查它。）
awk '/^### 结论表/,/^### 复现口径/' docs/STATUS.md \
  | grep -oE '[0-9]+(\.[0-9]+)?ms|[0-9]+\.[0-9]+ ?元|[0-9]+/[0-9]+' | sort -u \
  | while IFS= read -r v; do for f in docs/PRD.md docs/FAILURE.md AGENTS.md docs/dev/troubleshooting.md; do
      grep -qF -- "$v" "$f" && echo "COPY 「$v」 in $f"; done; done
#    必须用 `while IFS= read -r` 逐行读——直接 for 会把「数字 + 空格 + 单位」的值拆成两个词，误报一条
#    （另注：本代码块里不要照抄任何实测数字，否则这条检查第一个把它判成副本——就是这样误报过一次的）

# ④ 自述数字与实测对齐：文件里写了「共 N 条 / N 行」的，当场数一遍
#    核法要正确，否则会误报成「文档有缺陷」：
#      表数据行 = grep -c '^|' 文件 − 表头行数 − 分隔行数
#      ✗ 不要用 '^| [0-9]' 数行——以「M2」「来源未标日期」开头的行会被漏掉（实测把 38 条数成 26）
#      行数用 wc -l，节长用 awk '/^## 起/,/^## 止/' 量，别凭印象写进裁决记录
#    （本轮就靠这条抓到 06 号里两处估错的行数：「约 180」实为 186、「98 → 20」实为 98 → 47）
```

**另两条边界**：`docs/` 与子目录之间移动内容时**必须同步改相对链接基准**；指向仓库外内部材料的文字
**不得改成链接**（点了打不开），其**节名 / 编号是锚点**，活文档改名会让冻结资产里的指向失效。

**为什么立这条**——两个都是实测教训：

- **缺清单会漏同步**：2026-10 那轮 PRD v2 已把底座从 SB 3.5 反转到 SB 4.1 并写进版本红线，
  但 **README 整轮没更新**，公然挂着 `Spring Boot 3.5` 徽章和「与 SB 3.5 无冲突」的红线表述，
  而 README 是外部读者第一眼看的东西。
- **但「同步 N 份正文」是纪律的对立面**：上一条旧纪律要求同一口径同时改 PRD / README / ADR / STATUS，
  实际结果是同一批实测数字散在 4 个文件里各有正文、并开始措辞漂移（同一台机器一处写「8 核 32G 单机」、
  一处写「i7-7700 4C8T/32GB」）；环境排障的同一套 `netsh` 判据写了 4 份。**副本数决定漂移概率，与是否细心无关。**
  所以本条把「同步」换成「**归位**」：事实写一次，别处指过去。
- **测试数只能用 Maven 的模块汇总行**：`[INFO] Tests run: N`（各模块 `Results:` 段那两行）。
  **不要**把每个测试类的逐行输出、或 `target/surefire-reports/TEST-*.xml` 直接相加——聚合测试类会 fork 多 JVM，
  同名类出现两行，另有 `@Disabled` 不计入；实测按后者算得 151，而真值 **127**，差点把正确的文档改成错的。
  判据：**只信汇总行**；要复核就把三个有测试的模块的 `Tests run` 相加（`common` + `knowledge` + `start`）。

**提交前自查**：

1. **版本号**：所有出现的版本号都能在 `pom.xml` 找到对应坐标（当前为 `Spring Boot 4.1.1`、
   `LangChain4j 1.21.0` + starter `1.21.0-beta31`）。
2. **已作废的口径**：`算力`、`分库分表` / `ShardingSphere`、`gRPC`、`语义缓存`、`三档`、`HITL 三态`、`60 条`——
   这些词**只允许出现在「已裁剪 / 不得说 / 不做」这类否定语境里**，不得作为当前选型或当前承诺出现。
   （`AGENTS.md` 本节与 `docs/PRD.md` §15 的**禁令清单本身**同理，不计入。）
3. **相对链接基准**：内容在 `docs/` 与其子目录之间移动时**必须改链接基准**，否则静默断链
   （ADR 拆分时踩过，见 [`docs/adr/README.md`](docs/adr/README.md) 末注）。

---

## 3. 提交与代码风格

- **提交信息**：`type: 中文描述`（`docs` / `feat` / `fix` / `test` / `chore`），**单行 subject + 中文正文**。
  正文要能回答「凭什么说这个改动是对的」——有实测就写实测命令与结果，有取舍就写放弃了什么。
- **版本号的唯一事实源是 `pom.xml`**：PRD / README 里出现的任何版本号，必须能在 `pom.xml` 找到对应坐标。
- **禁止 force-push 到 `main`**（历史是公开的）。
- **不要在仓库里留过期的计划 / 实施状态文档**（见文首 ⚠️）。

---

## 4. 已知的坑与约定（随代码生长）

**本节只放「代码 / 配置层面」的坑**（写错会静默不生效的那类）。
**本机环境类排障（端口、进程、容器、profile、Maven reactor）不在这里，见 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)。**
条目只增不减；正文迁走时降级为一行指针（保留「一眼认出症状」的能力）。

> **先记一条可迁移的判断规则**：本节多数条目共享同一个失效形态——**不报错、只是静默不生效**：
> - **名字写错**：`-betaNN` 版本后缀、Jackson 属性名少一个连字符、`flyway-core` 与 `spring-boot-starter-flyway` 的区别、
>   Micrometer 导出时剥掉 Gauge 的 `_total` 后缀；
> - **依赖被挤掉**：同名依赖在装配模块以更近的 test scope 重复声明，运行期依赖静默消失；
> - **跑的不是你以为的代码**：`spring-boot:run` 只编译自身模块，兄弟模块走本地仓库旧 jar；
> - **绕过了代理**：往响应式数组 push 原始对象后再改那个局部变量，数据变了但不重渲染；
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
> 以及 M2 的「HNSW 三重假绿」（小表走精确路径 / 数据插入顺序把入口点放进可见团 / 审计被事务回滚——见本节 HNSW 条）。

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
- **跑的不是你以为的代码：`spring-boot:run` 只现场编译 start 自身模块，其余模块走本地仓库已安装的 jar**：
  于是「改了 `aiwarden-agent` 的代码 → 只跑 `spring-boot:run`」会**跑到旧逻辑**，而 `target/classes` 里的 class
  明明是新的——实测症状极具迷惑性：HTTP 响应返回的是**几小时前的旧文案**，而 `MockModelClient.class` 里新方法、
  新字符串常量**全都在**（那是 `mvn verify` 编出来的），查源码、查 class、查全仓文本都找不到「旧文本从哪来」。
  **判据：`target/classes/**` 新 ≠ 生效；要核对的是本地仓库
  `…\repository\com\aiwarden\<兄弟模块>\0.1.0-SNAPSHOT\*.jar` 的时间戳。**
  纪律（改了任一兄弟模块先 `install` 再 run；`java -jar` 只在 `package` 时生成）与可用命令、
  含**为何不能写 `-pl aiwarden-start -am spring-boot:run` 的三种死法**，唯一正文见
  [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md) 第 4 条。
- **本机环境类症状不在本节，判据与处置的唯一正文见 [`docs/dev/troubleshooting.md`](docs/dev/troubleshooting.md)**。
  四条最容易被误判成「代码 / 装配问题」的现象，认症状即可（正文各自展开）：
  ① **B 端评测报告页在本地开发库必为 404**（`t_eval_report` 由门禁测试写进 Testcontainers 的临时库，容器即销毁）——
  先查表行数，别怀疑路由；路由存在性用「不带身份头应返回 400」证明。
  ② **`Connection to localhost:5432 refused`** = 库没起；异常链会伪装成 `flywayInitializer` 装配故障，
  真因在链最底部的 TCP 层；`28P01` 才是凭据错，`08001` 两种都会有、别拿它判因。
  ③ **Kafka 在宿主机 9092「连不上但容器全部 healthy」** = Windows 把 9092 划进了保留端口区间，
  宿主机不允许 bind → **Docker 的发布不 bind 也不报错**；表现是 outbox 卡 `PENDING`、摄入不完成、用量看板恒空。
  本仓库走 `kafka-proxy` → **29092** + profile `local` 绕行（Linux / CI 不需要）。
  ④ **`Port 8080 was already in use`** = 上一次启动的进程没退（IDE 的 Stop 偶发不回收子进程），
  且残留进程还跑着旧 profile，于是「刷 Kafka 连不上」与「端口占用」两个症状同源——先杀旧实例再怀疑配置。
- **前端把「原始对象」的 mutation 打在响应式代理之外：数据变了但不重渲染（2026-10-10 实测，靠真实浏览器定位）**：
  `ChatView` 原写法 `const turn = newTurn(); messages.value.push(turn)` ——
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

## 5. 状态

> **见 [docs/STATUS.md](docs/STATUS.md)**——阶段 / 测试数 / **已验证的实测数字（§2 是它们的唯一落点）** /
> 已知边界 / 下一步 / 公开范围都在那里（**易过期**，与本文件「只增不减的坑清单」性质相反，故拆出）。
> 阶段推进时按 §2 的动作序列改它；`README.md` 第一屏的结论表是它的对外投影。