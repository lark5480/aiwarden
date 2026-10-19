# 文档收尾校验（改完文档必须跑，不能靠眼看）

> **这份文件是什么**：活文档改动后的**收尾校验工序**唯一正文。2026-10-10 起为硬要求，
> 2026-10 重构轮从 [`../../AGENTS.md`](../../AGENTS.md) §2 迁出（AGENTS 只留红线与指针）。
> **纪律本体（谁是权威源、什么算副本）仍在 [`../../AGENTS.md`](../../AGENTS.md) §2**，本页只放「怎么自证改对了」。
> **命令按 POSIX shell（Git Bash / WSL）书写**；PowerShell 下 `find` / `wc` / `grep` 需等价改写。

---

## 四条基础校验

```bash
# ① 相对链接：抽出全部 .md 链接并测目标存在（改了相对基准就会静默断链）
#    遍历全仓，不维护文件清单——清单本身就是会漏掉新文件的第二份正文
find . -name "*.md" -not -path "*/node_modules/*" -not -path "*/target/*" | while read f; do
  d=$(dirname "$f")
  grep -ohE '\]\([^)]+\.md(#[^)]*)?\)' "$f" | sed -E 's/^\]\(//; s/\)$//; s/#.*$//' | sort -u \
    | while read l; do case "$l" in http*|/*) continue;; esac; [ -f "$d/$l" ] || echo "BROKEN $f -> $l"; done
done

# ② 副本计数：被迁走的关键判据应当只剩一处正文
grep -rl --include="*.md" --exclude-dir=node_modules --exclude-dir=target "<关键短语>" README.md AGENTS.md docs aiwarden-web/README.md

# ③ 权威源纯度：把 STATUS §2 结论表里的「实测数字」自动提出来反查别处
#    （注意区分：**目标线 / 验收口径的数字 PRD 里合法**；只有实测结果数字——
#     ms / 元 / x/x 计数——不允许离开 STATUS。README 是允许的投影，不查它。
#     扫描集里 AGENTS.md 已被 docs/PITFALLS.md 替换：机制坑正文现在住在那儿。）
awk '/^### 结论表/,/^### 复现口径/' docs/STATUS.md \
  | grep -oE '[0-9]+(\.[0-9]+)?ms|[0-9]+\.[0-9]+ ?元|[0-9]+/[0-9]+' | sort -u \
  | while IFS= read -r v; do for f in docs/PRD.md docs/FAILURE.md docs/PITFALLS.md docs/dev/troubleshooting.md; do
      grep -qF -- "$v" "$f" && echo "COPY 「$v」 in $f"; done; done
#    必须用 `while IFS= read -r` 逐行读——直接 for 会把「数字 + 空格 + 单位」的值拆成两个词，误报一条
#    （另注：本代码块里不要照抄任何实测数字，否则这条检查第一个把它判成副本——就是这样误报过一次的）
#    已知的**误报形态**：`x/x` 这条正则也会命中 `裁决 28/30`（PRD FR-ING-06）这类**裁决编号**——命中后要读上下文再判，
#    别把它当成副本去改（2026-10 重构轮实测命中一次）。

# ④ 自述数字与实测对齐：文件里写了「共 N 条 / N 行」的，当场数一遍
#    核法要正确，否则会误报成「文档有缺陷」：
#      表数据行 = grep -c '^|' 文件 − 表头行数 − 分隔行数
#      ✗ 不要用 '^| [0-9]' 数行——以「M2」「来源未标日期」开头的行会被漏掉（实测把 38 条数成 26）
#      行数用 wc -l，节长用 awk '/^## 起/,/^## 止/' 量，别凭印象写进裁决记录
```

## 结构锚点断言（2026-10 重构轮新增）

活文档里大量指向是**按节号 / 条号**写的，搬家时最容易静默打断：

```bash
# ⑤-a 受众体量：AGENTS.md 是编码代理入口，硬上限 100 行
n=$(wc -l < AGENTS.md); [ "$n" -le 100 ] && echo "OK AGENTS lines=$n" || echo "FAIL AGENTS lines=$n"

# ⑤-b `## 4.` 标题号必须还在：全仓约 25 处按「AGENTS.md §4」引用（含冻结资产，不回改）
#     它现在只是索引，标题号一断，冻结资产里的指向全部失效
grep -q '^## 4\.' AGENTS.md && echo "OK AGENTS §4" || echo "FAIL AGENTS §4"

# ⑤-c troubleshooting 的小节序号是活锚点（「第 1–12 条」被外部引用）
#     只允许就地改文字，不允许插入或重排小节；数量与上一轮记录相等才算没漂
n=$(grep -c '^## ' docs/dev/troubleshooting.md); [ "$n" -eq 13 ] && echo "OK troubleshooting sections=$n" || echo "FAIL troubleshooting sections=$n"

# ⑤-d 活文档里不得再有把「AGENTS.md §4」当**机制坑正文**的表述。
#     预期命中：PITFALLS 文首兼容声明（两行）+ docs/README 的路由指针（一行）；
#     AGENTS §4 自身用「本文件 §4」指代，不在本正则命中范围内；
#     research/adr/benchmarks 等冻结资产在本命令里排除，读上下文确认即可。
grep -rn --include="*.md" "AGENTS\.md.*§ *4\|AGENTS §4" README.md AGENTS.md docs aiwarden-web/README.md \
  | grep -v "^docs/research/" | grep -v "^docs/adr/" | grep -v "^docs/benchmarks/" \
  | grep -v "^docs/dev/doc-selfcheck.md"
```

## 判读规则

- 出现 `BROKEN` 即失败；
- ③ 除 PRD 的 `裁决 28/30` 已知误报外，出现 `COPY` 即失败；
- ⑤ 出现 `FAIL` 即失败。

## 抽查（判据层面）

随机取 3 条机制坑，逐条确认三件事：**`docs/PITFALLS.md` 里有正文**（含「先问一句」那句判据）、
**AGENTS §4 有一行症状**、**其余各处只有指针**。副本数决定漂移概率，与是否细心无关。
