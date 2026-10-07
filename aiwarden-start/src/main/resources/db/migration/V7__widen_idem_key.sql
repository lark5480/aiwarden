-- V7：幂等键列宽放宽（FR-TOOL-01 的「双处口径一致」）
--
-- 背景：幂等键 = 业务键 + ':' + 会话ID + ':' + 步骤指纹(16 hex)。
--   契约侧（ToolInvokeRequest）原先对 businessKey / sessionId 无长度约束，
--   而列宽是 VARCHAR(160)：业务键 128 + 会话 64 + 16 + 2 = 210 → 真实 PG 报
--   「value too long for type character varying(160)」→ 500（不是 400）。
--
-- 处置（双处对齐，避免再次漂移）：
--   ① 入口侧显式校验 businessKey / sessionId / stepNo 的长度与范围（越界 400，ToolInvocationService）；
--   ② 列宽放宽到 256，与入口侧上限（64 + 64 + 16 + 2 = 146）之间留出余量——
--      即使未来调整上限也不会再次撞列宽；长度语义由入口负责，DB 只兜底。
--
-- 不改唯一约束：uk_t_tool_invocation_idem (tenant_id, idem_key) 保持原样（放宽列宽不影响其判定）。

ALTER TABLE t_tool_invocation ALTER COLUMN idem_key TYPE VARCHAR(256);
