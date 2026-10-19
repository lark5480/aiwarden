# aiwarden-web

AIWarden 的前端单页应用：C 端问答页 + B 端四页（一致性报告 / 知识库与文档 / 用量看板 / 评测报告）。
技术栈：Vue 3（`<script setup>` + TypeScript）+ Vite + Element Plus + vue-router；Markdown 渲染用 `marked` + `DOMPurify`，曲线用 `echarts` + `vue-echarts`。

## 怎么跑

前置：Node.js（本机实测 v24.16.0）、pnpm v11。

```bash
pnpm install
pnpm dev          # 开发服务器，缺省 http://localhost:5173
```

其它脚本：

```bash
pnpm build        # vue-tsc --noEmit && vite build（类型检查不通过则构建失败）
pnpm preview      # 预览 dist/
```

## 后端依赖

前端自身不需要数据库，但接口来自后端，**必须先起后端**：基础设施 → 把模块装进本地仓库 → 带 profile `local` 启动。
**命令序列的正文在根 [`../README.md`](../README.md)「本地怎么跑起来」**；`spring-boot:run` 三种写法各自的死法、
IDE 启动方式、`5432 refused` 与 Kafka 保留端口的判据——唯一正文见
[`../docs/dev/troubleshooting.md`](../docs/dev/troubleshooting.md)（第 1 / 2 / 4 / 9 条）。两个硬前提不变：
**基础设施已起** + **Windows 本机带 profile `local`**。

后端就绪判断：`GET http://localhost:8080/actuator/health`。

> **评测报告页初始 404、页面上演示不到「缺失即 400」**都不是故障：判据与演示分工的唯一正文见
> [`../docs/dev/troubleshooting.md`](../docs/dev/troubleshooting.md) 第 5 / 11 / 12 条。

### 接口与代理

Vite 的 `server.proxy` 把 `/api` 与 `/actuator` 都代理到 `http://localhost:8080`（见 `vite.config.ts`）。
如需直连其它地址，用环境变量覆盖 API 基地址（缺省空串 = 走代理）：

```bash
# .env.local
VITE_API_BASE=http://localhost:8080
```

### 身份请求头（必读）

后端**所有**接口都要求身份头，缺失或非数值即 400，**不回落默认值**：

| 请求头 | 必填 | 说明 |
|---|---|---|
| `X-Aiwarden-Tenant-Id` | 是 | 数值字符串；缺失即拒绝 |
| `X-Aiwarden-User-Id` | 是 | 数值字符串；缺失即拒绝 |
| `X-Aiwarden-Org-Id` | 否 | 留空 = 无组织归属（仅可见租户公共内容）；留空时前端**不发送**该头 |

前端把这三个值作为开发态全局配置存在浏览器 `localStorage`（键 `aiwarden.identity`），缺省
`tenant=1 / user=1 / org=空`；点页头右上角的「身份」按钮可编辑，保存后页面会刷新一次让新身份对所有请求生效。
所有 API 请求统一由 `src/api/client.ts` 注入这三个头。

## 目录

```
src/
├── api/          # fetch 薄客户端 + 各接口函数与 TS 类型（字段名照抄 Java record 组件名）
├── components/   # MarkdownBlock（渲染+消毒+引用锚点）、IdentityDrawer（身份配置）
├── router/       # / → /chat；/admin/{consistency,knowledge,usage,eval}
├── stores/       # identity（reactive，不引 Pinia）
├── styles/
└── views/        # ChatView（C 端）、admin/（B 端四页）
```

## 实现边界（如实说明）

- **`aiwarden_vector_orphan_total` 曲线不是历史序列**：该 Micrometer `Gauge` 只有当前值，后端没有历史查询接口。
  页面上的曲线是**本页对 `GET /actuator/prometheus` 轮询采样自绘**（手动「采样一次」或自动采样），
  只在页面打开期间累计点位，刷新即清空；持久化曲线需接入 Prometheus 查询，本次未做。
- **按 traceId 查完整步骤时间线（一致性报告页底部）**：走 `GET /api/v1/agent/runs/by-trace/{traceId}`，
  展示数据库检查点时间线；span 明细需在 Grafana → Tempo 查看（本机 Tempo 未实跑，见 STATUS §3）。
- **知识库与文档管理页**：建库 / 传文档 / 看摄入状态；上传接口返回体不含 `kbId`，当前库由页面存 `localStorage`。
  摄入是异步的，状态 PENDING → INDEXED 由 Kafka 消费者推进，页面轮询列表（不做假实时）。
- **用量看板的聚合在前端完成**：基于当前查询返回的同一份明细分组求和，因此只覆盖本次 `limit` 命中的行（不是全量统计）。
  后端 `limit` 上限 500（1–500，缺省 100）。
- **成本金额是演示单价口径**：由后端按 `aiwarden.chat.cost.prompt-per-1k` / `completion-per-1k` 估算，非真实价目表。
- **页面不做任何假数据回落**：接口报错就显示后端返回的错误原文（`ProblemDetail.detail`）；404 显示后端的原文提示；
  空结果显示空态，不用占位数字填充。
