# docs 索引

> **这份文件是什么**：`docs/` 的人类读者入口（此前缺位——只能从根 README 的表格里找路）。
> 各文档**职责边界与权威源**的纪律正文在 [`../AGENTS.md`](../AGENTS.md) §2，本页只做地图，不重述规则。

---

## 活文档（会随里程碑 / 实测更新）

| 文件 | 职责 | 不该出现在这里的东西 |
|---|---|---|
| [`STATUS.md`](STATUS.md) | **当前阶段 + 实测数字的唯一落点**（§2 结论表真身）+ 已知边界 + 公开范围 | 需求定义、选型理由、排障步骤 |
| [`PRD.md`](PRD.md) | **需求唯一权威源**：承诺、验收标准、范围、里程碑定义、技术选型理由、对外红线 | **实测数字**（归 STATUS §2）、求职叙事（已迁内部材料，见 §14） |
| [`../AGENTS.md`](../AGENTS.md) | 编码代理入口：文档纪律 + 最常踩的五条红线 + 极简命令（**≤100 行**） | 环境排障（归 dev/）、机制坑正文（归 PITFALLS）、状态描述 |
| [`PITFALLS.md`](PITFALLS.md) | **代码 / 配置层面机制坑的唯一正文**（P01…）+「认症状」索引 | 环境类症状（归 dev/）、需求与决策 |
| [`dev/troubleshooting.md`](dev/troubleshooting.md) | **本机环境排障唯一正文**：端口 / 进程 / 容器 / profile / Maven reactor | 代码机制坑（归 PITFALLS）；**小节序号是锚点，不可重排** |
| [`dev/doc-selfcheck.md`](dev/doc-selfcheck.md) | **文档收尾校验命令**：四条基础校验 + 四条锚点断言 | 实测数字、需求 |
| [`FAILURE.md`](FAILURE.md) | **失败索引**：走过的失败 / 放弃 / 未达成，一行结论 + 指向正文（定义见 `PRD.md` §8.1） | **第二句解释**——它有索引的重量而没有正文的资格 |
| [`benchmarks/M4-load-report.md`](benchmarks/M4-load-report.md) | **M4 压测的逐项数字 / 吞吐表 / 原始输出 / 复现命令**（M4 报告的详细证据源） | 需求定义、其他里程碑的数字 |

**判据一句话**：`PRD` 说「要什么」，`adr` 说「为什么这么定」，`STATUS` 说「现在到哪、测出多少」，
`AGENTS` 说「怎么写才不踩」，`PITFALLS` 说「哪里会静默不生效」，`dev` 说「这台机器为什么跑不起来」，
`FAILURE` 说「认自己错在哪」。

## 冻结资产（只增不改，历史即证据）

| 目录 | 内容 | 为什么不回改 |
|---|---|---|
| [`adr/`](adr/README.md) | **决策唯一权威源**，一文件一 ADR（编号与状态见索引表）；演进走末尾 `## 修订（YYYY-MM）` 段 | 保留「当时为什么会走到这一步」；与 PRD 冲突**以 ADR 为准** |
| [`research/`](research/README.md) | 立项前调研与论证序列 03–06（01 / 02 未公开）；06 是 PRD 回写的唯一依据 | 编号与结论是论证链条的一部分，被推翻的表述以 06 校准为准 |

> 这两类文件里出现的实测数字是**裁决 / 决策时点的证据**，与活文档里的当前值不一致属正常，
> 不要「顺手同步」——见 [`../AGENTS.md`](../AGENTS.md) §2 的冻结资产例外。

## 不随仓库公开

[`STATUS.md`](STATUS.md) §5 列了公开范围。内部材料（前期调研 01 / 02、原始报告、简历与作品集策略类）
存放在**仓库外的内部材料目录**（不在本仓库工作树内——gitignore 只挡 `git add`，挡不住 `add -f`、
目录整体复制 / 打包 / 同步，也会被 `**/*.md` 全局检索命中）。本仓库**不创建**任何 `docs/INTERNAL/`
之类的目录（`.gitignore` 里留了一条同名兜底，仅防手滑 `git add`，不代表这里该放东西），也不给它们写链接。

---

## 我该看哪个？

| 我想… | 去 |
|---|---|
| 评估这个项目做了什么、可信度如何 | 根 [`../README.md`](../README.md) 第一屏（承诺表 + 结论表）→ [`STATUS.md`](STATUS.md) §2 |
| 搞清楚某个选型为什么不是别的 | [`adr/README.md`](adr/README.md) 索引 → 对应 ADR |
| 改代码 | [`../AGENTS.md`](../AGENTS.md)（先读 §2 铁律与 §4 红线） |
| 踩到「不报错、但没生效」的东西 | [`PITFALLS.md`](PITFALLS.md)（先查「认症状」索引） |
| 本机跑不起来 / 跑起来少数据 | [`dev/troubleshooting.md`](dev/troubleshooting.md) |
| 知道某条口径是怎么拍板的 | [`research/06-PRD修订裁决清单.md`](research/06-PRD修订裁决清单.md) |
| 看这个项目承认自己失败在哪 | [`FAILURE.md`](FAILURE.md)（索引 → 各权威源） |
| 看压测怎么测的、数字多快 | [`benchmarks/M4-load-report.md`](benchmarks/M4-load-report.md) |
| 前端（`aiwarden-web`）怎么跑 | [`../aiwarden-web/README.md`](../aiwarden-web/README.md) |
