# 本机环境排障（唯一正文）

> **这份文件是什么**：**「这台机器上为什么跑不起来 / 跑起来为什么少数据」**的**唯一正文**。
> 症状 → 判据 → 处置，面向本地开发与启动验证，不需要改代码。
>
> **边界**（与 [`../PITFALLS.md`](../PITFALLS.md) 的分工判据）：
> - 本文件回答「**环境没给对**」——端口、进程、容器、profile、Maven reactor；
> - `PITFALLS.md` 回答「**代码/配置写错**」——坐标版本号、scope、装配扩展点、幂等键语义、静默不生效的机制坑。
> - **同一坑只允许一处有正文，另一处只留一行指针。**
>
> **小节序号是锚点**：外部文档按「第 1–12 条」引用本节，**只允许就地改文字，
> 不允许插入或重排小节**（判据见 [`../doc-selfcheck.md`](doc-selfcheck.md) 的锚点断言）。
>
> **性质**：会随本机环境过期（与 [`docs/STATUS.md`](../STATUS.md) 同类），**不是**只增不改的历史资产。

---

## 0. 先确认基础设施（一切失败的第一判据）

```bash
docker compose ps                     # postgres 必须是 Up (healthy)
Test-NetConnection localhost -Port 5432
```

`docker-compose.yml` 四个服务已带 `restart: unless-stopped`（避免机器/Docker Desktop 重启后「昨天还好今天炸」）。
**别在收尾时无脑 `docker compose stop`**——IDE 启动依赖它常驻。

---

## 1. `Connection to localhost:5432 refused` = 库没起，不是代码问题

应用启动要连库跑 Flyway 迁移，容器不在就抛一长串 bean 链
（`UnsatisfiedDependencyException` → `flywayInitializer` → `jdbcTemplate`），**看起来像装配/依赖故障**，
但**根因在异常链最底部**：`java.net.ConnectException: Connection refused: getsockopt`（TCP 层不可达）。

**区分口径**：

| 现象 | 判据 |
|---|---|
| TCP `Connection refused` | **库没起** |
| `28P01` / `password authentication failed` | 库起了但**凭据错** |
| `08001` | 只是 JDBC 的连接失败 SQLState，**两种都会有，别拿它判因** |

---

## 2. Kafka：宿主机 9092「连不上但容器一切正常」——Windows 保留端口区间

症状：后端日志每秒刷 `Connection to node -1 (localhost:9092) could not be established`，
outbox 全部卡 `PENDING` → **文档摄入永不完成、计量不落库、用量看板恒为空**；
而 `docker port` 显示映射存在、容器内 LISTEN 正常、`docker compose ps` 报 healthy。

**真因**：`netsh interface ipv4 show excludedportrange protocol=tcp` 显示本机 `9003-9102` / `9103-9202`
被保留（Hyper-V/WSL 动态端口段），**9092 落在里面——被保留的端口宿主机上任何进程都不允许 bind**，
于是 Docker Desktop 的发布**不 bind 也不报错**，`netsh interface portproxy` 加了规则同样不监听。

**判据（照顺序做）**：

1. `docker compose ps` 看 kafka 是否 healthy；
2. `Get-NetTCPConnection -LocalPort <port>`——**若「容器内 LISTEN 正常但宿主机无监听者」，就是保留端口问题**，
   不是代码也不是 Docker 故障；
3. `netsh interface ipv4 show excludedportrange protocol=tcp` 确认端口是否落在区间内。

**本仓库的绕行**（已内置在 `docker-compose.yml`）：kafka 改内部端口 `19092/19093`，
由 `kafka-proxy`（`alpine/socat`，compose 网络内直连 kafka，绕开宿主机转发层）把 Kafka 顶到宿主机
**29092**（已确认未被保留），`KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:29092`。
应用侧靠**被 gitignore 的** `aiwarden-start/src/main/resources/application-local.yml`
（激活 profile `local`）把 `spring.kafka.bootstrap-servers` 指向 `localhost:29092`。

**因此本机启动后端必须带 profile `local`**（IDE 运行配置填 `local`，或 VM option
`-Dspring.profiles.active=local`，或命令行 `-Dspring-boot.run.profiles=local`）。

自检：`Test-NetConnection localhost -Port 29092` 应为 True。

**Linux / CI 不需要这套**：把 `ports: ["9092:9092"]` 加回 kafka 服务、删掉 kafka-proxy，且**不要**激活 `local`
（`application-local.yml` 不会被提交，不影响 CI）。

**注意**：`socat` 代理被强杀（如 `Stop-Process -Force`）后可能不自动恢复，重启 `kafka-proxy` 即可
（已加 `restart: unless-stopped`，正常 Docker 生命周期会自愈）。

---

## 3. `Port 8080 was already in use` = 上一次启动的进程没退

IDE 里再点一次「运行」**不会自动停掉旧实例**；IDE 的 Stop 按钮偶发不回收子进程。
实测残留的 `java -cp ...spring-boot-4.1...` 进程一直占着 8080，且它跑的还是**旧 profile**——
于是表现为「日志一直刷 Kafka 连不上」+「再次启动报端口占用」**两个症状同一个根因**。

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

**推论：IDE 启动失败时先确认「没有旧实例还在跑」，再怀疑配置。**

---

## 4. 多模块启动：`spring-boot:run` 必须「先 install，再单模块 run」

根 pom 的 `spring-boot-maven-plugin` 只在 `aiwarden-start` 里有 `<goal>repackage</goal>`，
但 `spring-boot:run` 是**命令行 goal**——reactor 里**每个模块**都会执行它，于是三种写法各有死法：

| 写法 | 死法 |
|---|---|
| `mvn -pl aiwarden-start -am spring-boot:run`（从根） | `-am` 把根聚合工程与兄弟模块一并入 reactor，根 pom 无 main class → `Unable to find a suitable main class`（**报错里的项目名是 `aiwarden`，不是 `aiwarden-start`，极易误读成「start 模块坏了」**） |
| `mvn -pl aiwarden-start spring-boot:run`（不带 `-am`） | 兄弟模块 `0.1.0-SNAPSHOT` 不在本地仓库 → `Could not resolve dependencies` |
| 在 `aiwarden-start/` 目录里跑 `-am` | Maven 只有「从根跑的聚合构建」才有完整 reactor，在子模块目录执行只看到它自己，`-am` 无兄弟可加 |

**可用写法**（本项目实测通过，app 正常 `Started AiwardenApplication`）：

```bash
mvn -B -ntp -DskipTests install                                   # 首次 / 改了兄弟模块后
mvn -B -ntp -pl aiwarden-start spring-boot:run -Dspring-boot.run.profiles=local
```

（`run` 会现场编译 start 自身，改 start 代码不必重 install；`local` 的理由见上面第 2 条，Linux / CI 可省略。）

**等效替代（不需要 install）**：

```bash
mvn -B -ntp -pl aiwarden-start -am -DskipTests package
java -jar aiwarden-start/target/aiwarden-start-0.1.0-SNAPSHOT.jar
```

**IDE 启动不需要上面两步**：直接运行 `com.aiwarden.start.AiwardenApplication#main`——
IDE 用自身配置的模块 classpath 编译并启动，天然绕开 reactor 问题。
两个硬前提仍是：基础设施已起 + profile `local` 已激活（VS Code / Qoder 见仓库自带的 [`.vscode/launch.json`](../../.vscode/launch.json)；
IntelliJ 在 Run/Debug Configuration 的 **Active profiles** 填 `local`）。

### 4.1 改了兄弟模块必须重跑 `install`（否则跑到旧代码）

`spring-boot:run` **只现场编译它自己那个模块**，其余模块走**本地仓库的已安装 jar**。
于是「改了 `aiwarden-agent` 的代码 → 只跑 `spring-boot:run`」会**跑到旧逻辑**，而 `target/classes` 里的 class
明明是新的。实测症状极具迷惑性：HTTP 返回的是**几小时前的旧文案**，而新 class 里新方法、新字符串常量**全都在**
（那是 `mvn verify` 编出来的），查源码、查 class、查全仓文本都找不到「旧文本从哪来」。

**判据**：`target/classes/**` 新 ≠ 生效。要比的是**本地仓库 jar 的时间戳**
（`...\repository\com\aiwarden\aiwarden-agent\0.1.0-SNAPSHOT\*.jar`）。
`java -jar` 同理——fat jar 只在 `package` 时生成，改了依赖模块要重新 `package`。

**另注**：本机 `mvn install` **不能加 `-o`**——`maven-install-plugin` 自身依赖未缓存，离线会
`PluginResolutionException`（`verify` 可以离线，`install` 不行）。

---

## 5. B 端「评测报告」页在本地开发库通常是 404（表为空时），不是故障

`GET /api/v1/admin/eval/report` 返回 404 + `{"detail":"暂无评测报告…"}` 是**预期行为**：
`t_eval_report` 由评测门禁测试写入 **Testcontainers 的临时数据库**，测试结束容器即销毁——
本地 `docker compose` 起的那套库里永远是空的。

**判据**：看该接口 404 时**先查 `t_eval_report` 行数**，别去怀疑路由或 controller。
路由存在性可用「不带身份头应返回 400」证明——400 说明请求已到我们的 controller。

**「必为 404」的前提是这张表为空**（2026-10-19 实测补正）：一旦按下面第二条路 INSERT 过，
接口就返回 **200**，页面显示的是**那一轮**的 `runAt` / P95 / 平均成本——它与
[`docs/STATUS.md`](../STATUS.md) §2 的对外投影**不是同一轮**，别把页面上的数字当成 README 的那组。
判据一句话：**先看 `runAt`**，它早于本轮实测就不是本轮。

要看真实数据只有两条路：跑 `mvn verify` 读测试输出里的 `EVAL-SUMMARY`（单类复跑用 `-am` 形态时
必须带 `-Dsurefire.failIfNoSpecifiedTests=false`，否则在没有匹配类的兄弟模块上直接 BUILD FAILURE；
完整命令见 [`docs/STATUS.md`](../STATUS.md) §2），
或自行把结论 INSERT 进本地库（表结构见 `V9__eval_report.sql`）。

---

## 6. 前端页面「打不开 / 全白」：只有 `http://localhost:5173` 能开

**实测（2026-10-10，本机 Vite 6.4.4 dev server）**：

| 入口 | 结果 |
|---|---|
| `http://localhost:5173/admin/consistency` | ✅ 200 |
| `http://127.0.0.1:5173/...` | ❌ **连接被拒** |
| `http://localhost:8080/admin/...` | ❌ Whitelabel 404（后端**不托管前端**：任何模块都没有 `static/`，`/` 与 `/index.html` 全 404） |
| 双击 `aiwarden-web/dist/index.html`（`file://`） | ❌ **纯白页**（实测 `document.body.innerText === ""`：`index.html` 用绝对路径 `/assets/...`，`file://` 下全部 404） |

**真因（第一条）**：`vite.config.ts` 没设 `server.host`，Node 把 `localhost` 解析成 `::1`，于是 Vite
**只 bind 了 `[::1]:5173`**；`Get-NetTCPConnection -LocalPort 5173` 会显示 `LocalAddress = ::1`，
**IPv4 的 `127.0.0.1` 上根本没有监听者**。判据：`localhost` 能开而 `127.0.0.1` 被拒 → 就是这条，
不是后端挂了、也不是前端构建坏了。

**处置**：① 用 `http://localhost:5173`（最省事）；② 想让 IPv4 也能开，在 `vite.config.ts` 的 `server`
里加 `host: '127.0.0.1'`（改完就**只能**用 `127.0.0.1` 访问了，`localhost` 会解析到 `::1` 再次落空）；
③ 要验证 `dist` 产物，用 `pnpm preview`（vite 的 preview 继承 `server.proxy`，`/api` → 8080 仍通），
**不要**用 `file://` 直接打开。

**为什么容易误判成「前端 bug」**：这四个入口的失败形态完全不同（拒连 / 404 / 纯白），而**真正的页面问题**
不会表现为纯白。**判据：能看到顶部 AIWarden 导航 = 前端 shell 正常，问题在数据或接口；
连导航都没有 = 先查入口 URL。**
（本条原写「身份头缺失或非法时，页面会渲染红色告警并附后端原文（实测：非法租户头 → 400 + 原文）」——
**2026-10-19 浏览器实测证伪并就地修正**：那道告警与那条实测都不在浏览器里，判据见下面第 11 条。）

---

## 7. B 端页面「有数据却看不到内容」：先分清平台级空态与手动采样

两条都在 2026-10-10 实测过，症状是「接口明明有数据，页面一片空」：

**① 一致性报告**：`GET /api/v1/admin/consistency/report` 有 37+ 份报告并不等于有内容——
`t_reconcile_report` 是**平台级表（无 tenant 维度）**，对账每 5 分钟自动写一条，实测
**37 份报告里 `mismatch_count > 0` 的有 0 份**，最新几条 `detail_ref` 都是
`{"staleDocuments":[],"orphanVectors":0,"stuckIngest":[],"failedOutboxEvents":0,...}`。
所以「不一致清单为空：没有已删除文档的残留切片」是**正确空态，不是故障**。
要看到非空清单只有制造一次不一致（软删文档后让残留留下）再等扫描 / 调 `POST /scan`。
**另注**：该表无 tenant 列，`POST /repair` 是**清全租户残留**、不按租户过滤，多租户演示时别踩。
（该接口要求**租户 + 主体双必填**，缺任一即 400——这条纪律的正文见
[`docs/STATUS.md`](../STATUS.md) §3 裁决 22 条。）

**② 孤儿向量曲线**：`aiwarden_vector_orphan` 是 Micrometer `Gauge`，**只有当前值、没有历史序列**，
页面曲线是**本页轮询采样自绘**，且 `autoSample` **默认关闭**——所以刚打开时必然显示
「还没有采样点：点『采样一次』或打开自动采样」。指标本身是好的（实测导出 `aiwarden_vector_orphan 0.0`，
注意**导出名没有 `_total` 后缀**，前端两种名字都认）。**这条不是故障，是设计边界。**

---

## 8. 对账「修复录屏」怎么演：可回滚的不一致演示（实测跑通）

**为什么需要配方**：一致性页平时显示「不一致清单为空」——那是对的，但对账的价值要**看见一次真修复**才立得住。
自己硬试会发现第一道坎：**正常删除路径几秒内就收敛了**（实测 17:34:05 调
`DELETE /api/v1/documents/4`，17:34:32 扫描时残留已为 0），根本没有可演示的窗口。
所以残留必须**从数据面注入**（模拟「删除事件丢失 / 消费停摆窗口」），而不是等它自然发生。

**判据与守卫的正文不在这里**：对账按「库内数据形态」判定（含 outbox 终态）的三条子判据、假阳性守卫、
重驱动的「事件类型 + 当前版本」双守卫，唯一正文见 [`../PITFALLS.md`](../PITFALLS.md) P16 / P17
（验收定义见 [`docs/PRD.md`](../PRD.md) FR-ING-06，决策理由见
[`docs/adr/ADR-006`](../adr/ADR-006-reconcile-readonly.md) 修订段）。
本节只留演示配方用得上的两句：
① 残留必须**从数据面注入**（见上面第一段——正常删除路径几秒内就收敛，等不到窗口）；
② `repair` 只重驱动**当前版本、文档存在且未收敛的索引事件**，它会**真删掉**向量与切片，
所以演完必须复原（见下表第 5 步与下面的复原坑）。

**实测走通的步骤**（2026-10-10，脚本阶段化，每步都可单独核对）：

| 步 | 动作 | 实测结果 |
|---|---|---|
| 1 | 给 doc3 复制一份残留产物，并把 `deleted_at` 置为 now()（**不写 outbox** → 不会被消费者清掉） | `chunks=2 vecs=2`，`status=DELETED` |
| 2 | `POST /api/v1/admin/consistency/scan` | `mismatchCount=1`，清单 `[{"doc_id":3,"tenant_id":1,"stale_chunks":2}]`，`orphanVectors=2` |
| 3 | `POST /api/v1/admin/consistency/repair` | `{"repairedDocuments":1,"redrivenEvents":0}` |
| 4 | 再 `POST /scan` | `mismatchCount=0`、`orphanVectors=0`；Prometheus `aiwarden_vector_orphan 0.0` |
| 5 | 复原（补回切片与向量、文档与账本置回 `INDEXED`） | doc3 恢复可检索（C 端 `kbId=2` 能查到并抽取作答） |

**修复段实测踩到的坑（务必按此写复原）**：`repair` 会**真删掉**向量与切片（这是它的职责），
所以复原必须重新插入向量；而**插入时的嵌入不能指向某个具体 `doc_id`**——
实测按「从 doc2 取嵌入」写，结果 doc2 的向量早被删了，`embedding` 为 NULL、违反非空约束，
留下**「文档状态 INDEXED 但没有向量」的假复原状态**（查询接口都正常，只是检索查不到）。
正确写法是从**当前库里任意现存向量**取（`CROSS JOIN (SELECT embedding FROM t_vector LIMIT 1)`），
并且复原后**必须核对向量真的回来了**（只看 `status` 会漏判）。

> **口说无凭的判据**：这一步的验收不是「接口返回 200」，而是
> **做一次真实检索**（C 端 `kbId=2` 问「配送政策是什么」）拿到该文档的命中——机制确实被触发才算数。

> **「可回滚」的准确含义（别过度承诺）**：`repair` 真删产物，复原靠重新插入，因此**复原态与原状不逐字节相同**——
> 补回的嵌入是从**库里现存向量借的**（内容、元数据、`kbId` 归属与可检索性都恢复，唯有嵌入本身不自证同一）。
> 若要**完全不动库**地演示，替代路线是让后端**以 Kafka 监听关闭的方式**启动
> （`--spring.kafka.listener.auto-startup=false`：应用与 REST 接口都在，但删除事件不被消费 → 残留自然保留），
> 演完重启正常实例即可。两条路都按用途选：前者快、可重复；后者对数据零改动，但整段期间摄入/计量也停摆。

**脚本不入库**：演示脚本含本机数据库写操作，按 `.gitignore` 的 `demo-*.ps1` 排除；
配方与实测证据就是本节正文（本机脚本可随时按下表重写，不需要进仓库）。

---

## 9. IDE 启动通道（`.vscode/launch.json`）与它的 `JAVA_HOME` 前置

**`.vscode/` 只放行 `launch.json` 一个文件**（`.gitignore` 里 `.vscode/*` + `!.vscode/launch.json`），
进库的是**通用**配置：`javaExec` 写的是 **`${env:JAVA_HOME}/bin/java.exe`**，不含任何机器专属路径。
因此**前置条件是环境变量 `JAVA_HOME` 指向 JDK 21**——没设或指向别的版本时，IDE 启动会报找不到 java。

- 本机实测：`JAVA_HOME = E:\Software\openjdk-21.0.8`（`java -version` = 21.0.8），与 `which java` 同一份；
- **兜底写法**：不想依赖 `JAVA_HOME` 时，把 `javaExec` 改成本机绝对路径即可，但**别把它提交**——
  个人路径、以及任何机器专属设置，放**不入库的** `.vscode/settings.json`（该文件被 `.vscode/*` 覆盖），
  或干脆只改本地而不 add；
- 配置里两个条目的差别只有一个 `--spring.profiles.active=local`（理由见上面第 2 条）；
  支持 WSL / 容器的场景也可以改用 `type: "java"` + `request: "attach"`，本仓库不预置该条目。

**为什么不让这个文件承载排障正文**：它一度把「9092 为什么走 29092」的完整判据抄了近 10 行，
与本文件第 2 条构成两份正文（违反 [`AGENTS.md`](../../AGENTS.md) §2）。现在只留一句结论 + 指针。

---

## 10. 跑测试：Testcontainers 的 Docker 探测偶发失败

`mvn -B -ntp verify` 需要 Docker（起真实 PostgreSQL / Kafka）。本机偶发
`Could not find a valid Docker environment`，报错常见 `MalformedChunkCodingException (Bad chunk header)`，
出在 NpipeSocket 策略的探测实现里；**npipe 抖动窗口内重试多次不恢复**。

处置命令与「先证明引擎本体健康」的探针见 [`../PITFALLS.md`](../PITFALLS.md) P10（该条正文住在 PITFALLS，
因为它是「测试怎么跑起来」的 maven 参数与探测策略，属测试装配层面的坑；本处只指路）。

---

## 11. 「缺失即 400」演示不到屏幕上：前端身份层有两道守门（2026-10-19 浏览器实测）

**症状**：想在页面上演示「身份头缺失 / 非法 → 红色告警 + 后端原文」，**做不到**——不是接口没校验，
是浏览器根本发不出那样的请求。

**两道守门**（`aiwarden-web/src/components/IdentityDrawer.vue` + `aiwarden-web/src/stores/identity.ts`）：
① 抽屉保存时按「必须是数字串」同步校验，非法值 toast 拦下、**不落 localStorage、不发请求**（实测：填 `abc`
点「保存并刷新」只弹文案）；② 即便绕过抽屉直接往 localStorage 写空串，读回时空 / 空白值
**回落缺省 `tenant=1 / user=1`**（实测：写 `{tenantId:""}` 后刷新，顶部标签仍是 `tenant 1`）。
所以 `App.vue` 那句「身份头非法，接口会 400」的红色告警分支与页面上展示 400 原文的分支，
**当前 UI 流程走不到，属防御性代码**。

**判据与分工**：「缺身份即 400」在后端与测试两层证——`AdminIdentityBoundaryContainersTest`
逐端点断言缺租户 400 / 缺主体 400，浏览器侧要演 400 只能用 curl / DevTools 直接发请求。
页面能演的只有两件事：**前端拦截**（非法值不发）与**跨租户空态**（下面第 12 条）。
**别把「页面没报错」当成「校验没生效」**——租户维度生效时它给的是空态，不是告警。

**为什么不改前端去「能演 400」（2026-10-19 裁决 31 定调，别反复讨论）**：回落缺省是开发态的
有意取舍——填一次常驻，否则每次刷新都要重填身份。安全上无缺口（后端自己校验，前端守门不构成信任边界）。
真要在页面上演 400，代价最小的做法是**在抽屉里加一个「不发送该头」的显式开关**（让「不发」成为用户动作，
而不是把空值重新解释成「不发」）；**不要**直接删掉 `normalize()` 的回落。

---

## 12. 把身份头设成不存在的租户，B 端页面全空但接口全 200（2026-10-19 实测）

**症状**：演示时把租户改成非 1 的值（如 99），KB 页变成「还没有知识库：先在下方创建一个」、
文档表 0 条、Trace 查询报「没有找到 traceId=… 的运行（当前租户内）」——**接口都正常返回，页面无一条报错**。

**真因**：租户维度是**作用域过滤**，不是准入校验。`GET /api/v1/knowledge-bases` 对不存在的租户返回
`200 []`（不验 `t_tenant` 存在性）；`GET /api/v1/agent/runs/by-trace/{traceId}` 用别的租户查**真实存在**
的 trace 返回 **404**（不是 403）。同一条数据在租户 1 下 10 步全 `SUCCEEDED`，在租户 99 下就是空态。

**判据**：B 端整片空 + 控制台 / network 无错误 = **先查当前租户身份**（顶部标签 `tenant N`），
再怀疑数据。要演「租户隔离」，就用这个对照：同一 traceId 切租户 → 时间线变「没找到」，
**别把空态讲成「没有数据」**。另注：换租户后 KB 页选中的 `localStorage.aiwarden.kbId` 会被
`loadKbs()` 纠正为当前租户的第一个库（当前库为空则清成 null 并显示空态），不是状态残留。
