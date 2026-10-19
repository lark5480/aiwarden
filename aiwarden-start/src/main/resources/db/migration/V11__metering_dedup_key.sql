-- V11：计量明细的消费端幂等键（P5 检查点重做 / outbox 至少一次投递）
-- 模型调用：model:<runId>:<stepNo>；工具调用：tool:<invocationId>。
-- 嵌入计量暂为 NULL（摄入侧已由 t_ingest_ledger 幂等保护；唯一索引允许多个 NULL）。
ALTER TABLE t_llm_call_log ADD COLUMN dedup_key VARCHAR(128);

CREATE UNIQUE INDEX uk_t_llm_call_log_dedup ON t_llm_call_log (dedup_key);
