# 本机环境排障（唯一正文）

> **这份文件是什么**：**「这台机器上为什么跑不起来 / 跑起来为什么少数据」**的**唯一正文**。
> 症状 → 判据 → 处置，面向本地开发与启动验证，不需要改代码。
>
> **边界**（与 [`AGENTS.md`](../../AGENTS.md) §4 的分工判据）：
> - 本文件回答「**环境没给对**」——端口、进程、容器、profile、Maven reactor；
> - AGENTS.md §4 回答「**代码/配置写错**」——坐标版本号、scope、装配扩展点、幂等键语义、静默不生效的机制坑。
> - **同一坑只允许一处有正文，另一处只留一行指针。**
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

## 5. B 端「评测报告」页在本地开发库必为 404，不是故障

`GET /api/v1/admin/eval/report` 返回 404 + `{"detail":"暂无评测报告…"}` 是**预期行为**：
`t_eval_report` 由评测门禁测试写入 **Testcontainers 的临时数据库**，测试结束容器即销毁——
本地 `docker compose` 起的那套库里永远是空的。

**判据**：看该接口 404 时**先查 `t_eval_report` 行数**，别去怀疑路由或 controller。
路由存在性可用「不带身份头应返回 400」证明——400 说明请求已到我们的 controller。

要看真实数据只有两条路：跑 `mvn verify` 读测试输出里的 `EVAL-SUMMARY`，
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
不会表现为纯白——身份头缺失或非法时，页面会渲染红色告警并附后端原文（实测：非法租户头 → 400 + 原文
「租户标识不是合法数值，拒绝执行」）。**判据：能看到顶部 AIWarden 导航 = 前端 shell 正常，问题在数据或接口；
连导航都没有 = 先查入口 URL。**

---

## 7. B 端页面「有数据却看不到内容」：先分清平台级空态与手动采样

两条都在 2026-10-10 实测过，症状是「接口明明有数据，页面一片空」：

**① 一致性报告**：`GET /api/v1/admin/consistency/report` 有 37+ 份报告并不等于有内容——
`t_reconcile_report` 是**平台级表（无 tenant 维度）**，对账每 5 分钟自动写一条，实测
**37 份报告里 `mismatch_count > 0` 的有 0 份**，最新几条 `detail_ref` 都是
`{"staleDocuments":[],"orphanVectors":0,"stuckProcessing":0,...}`。
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

## 8. IDE 启动通道（`.vscode/launch.json`）与它的 `JAVA_HOME` 前置

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

## 9. 跑测试：Testcontainers 的 Docker 探测偶发失败

`mvn -B -ntp verify` 需要 Docker（起真实 PostgreSQL / Kafka）。本机偶发
`Could not find a valid Docker environment`，报错常见 `MalformedChunkCodingException (Bad chunk header)`，
出在 NpipeSocket 策略的探测实现里；**npipe 抖动窗口内重试多次不恢复**。

处置命令与「先证明引擎本体健康」的探针见 [`AGENTS.md`](../../AGENTS.md) §4（该条正文留在 AGENTS，
因为它是测试代码装配层面的坑；本处只指路）。
