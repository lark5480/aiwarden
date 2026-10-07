-- V3：t_vector.meta 可见集字段与下推地基（ADR-007 落实清单 #1 / #2）
-- 背景：M1 过渡期的 meta 只有 docId/version/kbId；下推（metadata filter）要求可见集字段
-- （tenantId 必含，orgId/kbId/docId）在 meta 中可就地过滤。
-- 注：新写入路径（DocumentIngestStore）自本版本起产出完整字段；以下回填为防御性语句
--（当前无存量数据，空库执行），保证任何既有数据也被覆盖。

-- ① 回填 t_chunk：补齐 tenantId（从列复制）
UPDATE t_chunk
SET meta = meta || jsonb_build_object('tenantId', tenant_id)
WHERE NOT jsonb_exists(meta, 'tenantId');

-- ② 对齐 t_vector：meta 与 t_chunk.meta 一致（filter 可下推与对账的前提）
UPDATE t_vector v
SET meta = c.meta
FROM t_chunk c
WHERE v.chunk_id = c.id
  AND (v.meta IS DISTINCT FROM c.meta);

-- ③ tenantId 表达式索引——服务「精确回退路径的先过滤后排序」与运维统计。
-- 正面说明（ADR-007）：btree/GIN 无法限制 HNSW 候选的遍历顺序；主路径的「凑不满 K」
-- 由 hnsw.iterative_scan + 应用层精确回退承接，本索引不是那件事的解法。
CREATE INDEX idx_t_vector_tenant ON t_vector ((meta->>'tenantId'));
