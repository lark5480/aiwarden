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

## 6. 跑测试：Testcontainers 的 Docker 探测偶发失败

`mvn -B -ntp verify` 需要 Docker（起真实 PostgreSQL / Kafka）。本机偶发
`Could not find a valid Docker environment`，报错常见 `MalformedChunkCodingException (Bad chunk header)`，
出在 NpipeSocket 策略的探测实现里；**npipe 抖动窗口内重试多次不恢复**。

处置命令与「先证明引擎本体健康」的探针见 [`AGENTS.md`](../../AGENTS.md) §4（该条正文留在 AGENTS，
因为它是测试代码装配层面的坑；本处只指路）。
