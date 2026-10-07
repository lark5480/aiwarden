package com.aiwarden.knowledge.retrieval;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.governance.audit.AuditLogWriter;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 检索服务（M2/P2）：可见集下推（ADR-007/ADR-008）+ 两段式精确回退。
 *
 * <p><b>过滤纪律</b>：可见性条件只允许出现在 {@link RetrievalSql#PUSHDOWN} 的 WHERE 中；
 * 本类不存在「先查全量再在应用层过滤」的路径。检索入口强制接收 {@link VisibilitySet}，
 * 空集在计算阶段直接拒绝（FR-PERM-01），不进入向量查询，并写审计留痕（FR-PERM-05）。
 *
 * <p><b>机制自证（ADR-007 实测教训）</b>：小表上规划器会退化为顺序扫描 + 精确排序，
 * 此时 {@code hnsw.*} 参数根本不参与——「全对、但什么都没证明」。因此容器测试必须
 * EXPLAIN 断言走了 {@code idx_t_vector_embedding_hnsw}（{@code RetrievalHnswPushdownContainersTest}）。
 *
 * <p><b>两段式回退</b>：HNSW 路径（iterative_scan=strict_order）返回条数 < K 时，
 * 走精确回退（在过滤后做精确距离排序）——它不是备选方案，是设计组成部分（ADR-007）：
 * iterative_scan 的绕回上限（max_scan_tuples）一旦耗尽，就由回退承接「不静默接受不足 K」。
 */
@Service
public class RetrievalService {

    static final int DEFAULT_TOP_K = 10;
    static final int MAX_TOP_K = 50;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final AuditLogWriter auditLogWriter;
    private final MeterRegistry meterRegistry;
    private final int hnswMaxScanTuples;

    public RetrievalService(JdbcTemplate jdbcTemplate,
                            EmbeddingClient embeddingClient,
                            AuditLogWriter auditLogWriter,
                            MeterRegistry meterRegistry,
                            @Value("${aiwarden.retrieval.hnsw.max-scan-tuples:20000}") int hnswMaxScanTuples) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.auditLogWriter = auditLogWriter;
        this.meterRegistry = meterRegistry;
        this.hnswMaxScanTuples = hnswMaxScanTuples;
    }

    /**
     * 可见集下推检索。
     *
     * @param visible 检索前算出的可见集（{@code VisibilitySetCalculator}）；空集 → 拒绝
     * @param query   查询文本
     * @param topK    Top-K（可空，默认 {@value #DEFAULT_TOP_K}，上限 {@value #MAX_TOP_K}）
     */
    @Transactional
    public List<RetrievalHit> search(VisibilitySet visible, String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("查询内容不能为空");
        }
        // 租户一致性（ADR-008「检索入口不可绕过可见集」的运行时保证）：可见集是值对象，
        // 任何调用方都能 new 一个别的租户进来——ArchUnit 只能守护签名含 VisibilitySet，
        // 守护不了它的来源。此处与 TenantContext 交叉校验，缺一不可：可见集必须由
        // VisibilitySetCalculator 在**当前租户上下文**下算出（缺失即拒绝，不回落）。
        long currentTenantId = TenantContext.requireTenantIdAsLong();
        if (visible.tenantId() != currentTenantId) {
            auditLogWriter.append(currentTenantId, PrincipalContext.requireUserId(),
                    "RETRIEVAL_DENIED", "*", "DENIED",
                    "可见集租户与当前租户上下文不一致：检索拒绝（不可由入参改写租户边界）");
            throw new SecurityException("可见集与租户上下文不一致：检索拒绝（ADR-008）");
        }
        if (visible.isEmpty()) {
            auditLogWriter.append(visible.tenantId(), PrincipalContext.requireUserId(),
                    "RETRIEVAL_DENIED", "*", "DENIED", "可见集为空：检索在计算阶段即拒绝（FR-PERM-01）");
            throw new SecurityException("可见集为空：检索拒绝（FR-PERM-01；不进入向量查询）");
        }
        int limit = topK == null ? DEFAULT_TOP_K : Math.clamp(topK, 1, MAX_TOP_K);
        String queryLiteral = PgVectorLiteral.of(embeddingClient.embed(query));

        // 查询级 GUC（事务本地）：绕回继续扫描直到凑满 K 或达上限（ADR-007 实测：默认 off 时
        // 1% 选择性下只返回 1/10 条，且无报错、无日志）
        applyHnswGucs();

        Object[] args = {
                queryLiteral, String.valueOf(visible.tenantId()),
                RetrievalSql.toTextArrayLiteral(visible.kbIds()),
                RetrievalSql.toTextArrayLiteral(visible.allowDocIds()),
                RetrievalSql.toTextArrayLiteral(visible.denyDocIds()),
                limit
        };
        List<RetrievalHit> hits = jdbcTemplate.query(RetrievalSql.PUSHDOWN, rowMapper(), args);

        if (hits.size() < limit && visibleCount(args) > hits.size()) {
            meterRegistry.counter("aiwarden_retrieval_exact_fallback_total").increment();
            hits = exactFallback(args);
        }
        return hits;
    }

    /** 查询级 GUC：iterative_scan=strict_order + max_scan_tuples 上限（事务本地，随事务结束还原）。 */
    private void applyHnswGucs() {
        jdbcTemplate.queryForObject(
                "SELECT set_config('hnsw.iterative_scan', 'strict_order', true)", String.class);
        jdbcTemplate.queryForObject(
                "SELECT set_config('hnsw.max_scan_tuples', ?, true)", String.class,
                String.valueOf(hnswMaxScanTuples));
    }

    /** 回退判定：返回 < K 时先精确计数——只有「HNSW 少给」才是塌陷；可见总数本来就 < K 不回退。 */
    private long visibleCount(Object[] filterArgs) {
        Long count = jdbcTemplate.queryForObject(RetrievalSql.COUNT_VISIBLE, Long.class,
                filterArgs[1], filterArgs[2], filterArgs[3], filterArgs[4]);
        return count == null ? 0L : count;
    }

    /**
     * 精确回退：HNSW 返回数 < 可见总数时，在过滤后做精确距离排序（不静默接受不足 K）。
     *
     * <p>「精确」由三个事务本地 GUC 共同保证：<b>重开顺序扫描</b>（若调用环境 / 外层事务
     * 设置过 {@code enable_seqscan=off}，不重开会让回退被迫再走索引——回退等于没回退）、
     * 禁用索引 / 位图扫描。使规划器对同一 SQL 走「顺序扫描 → 过滤 → 排序」的精确路径——
     * HNSW 是近似索引，回退路径必须与它拉开差异。代价是一次全量距离计算，仅在近似塌陷时发生
     * （有计数器可观测，治理税指标之一）。
     *
     * <p><b>GUC 必须在 finally 里复位</b>：{@code set_config(..., true)} 是<b>事务本地</b>，
     * 不会随本方法返回而消失。若不复位，调用方在同一事务内再检索一次时
     * {@code enable_indexscan=off} 仍在生效 —— 下推路径<b>静默不再被使用</b>
     * （结果仍正确，只是机制失效、治理税暴涨），正是本项目反复踩到的
     * 「不报错、只是静默不生效」形态。
     */
    private List<RetrievalHit> exactFallback(Object[] args) {
        jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'on', true)", String.class);
        jdbcTemplate.queryForObject("SELECT set_config('enable_indexscan', 'off', true)", String.class);
        jdbcTemplate.queryForObject("SELECT set_config('enable_bitmapscan', 'off', true)", String.class);
        try {
            return jdbcTemplate.query(RetrievalSql.PUSHDOWN, rowMapper(), args);
        } finally {
            jdbcTemplate.queryForObject("SELECT set_config('enable_indexscan', 'on', true)", String.class);
            jdbcTemplate.queryForObject("SELECT set_config('enable_bitmapscan', 'on', true)", String.class);
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
        }
    }

    private RowMapper<RetrievalHit> rowMapper() {
        return (rs, rowNum) -> new RetrievalHit(rs.getLong("chunk_id"), rs.getLong("doc_id"),
                rs.getString("content"), rs.getDouble("distance"));
    }
}
