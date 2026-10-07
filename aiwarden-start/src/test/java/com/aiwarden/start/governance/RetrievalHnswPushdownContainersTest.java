package com.aiwarden.start.governance;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import com.aiwarden.knowledge.retrieval.RetrievalSql;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2/P2 下推机制自证（ADR-007 落实清单 #5/#6，PRD §11 风险 8 的正面回答）。
 *
 * <p><b>实测事实（2026-10，本机 PG16.15/pgvector0.8.7，决定本类的受控实验设计）</b>：
 * planner 对检索 SQL 的真实选择是 —— 5000/20000 行：{@code Seq Scan + Sort}（近邻清单全文）；
 * 50000 行：关串行序扫后绕道「并行序扫 + Gather Merge + Sort」；再关并行序扫后首选
 * <b>btree 表达式索引</b>（{@code idx_t_vector_tenant}，估算 rows=250）；移除 btree 后又转头
 * <b>主键索引</b>（{@code t_vector_pkey} 全索引扫描，估算 rows=1 的幻觉）——planner 对 JSONB
 * {@code meta} 过滤的选择性估算在 1/250/50000 间横跳，**恒倾向精确路径**；只有把两个索引
 * 都移除才稳定选中 HNSW。也就是说：中小规模下 HNSW 根本不被执行，{@code hnsw.*} 参数不参与
 * ——测试若不断言机制被触发就是「全对、但什么都没证明」（AGENTS.md 教训形态）。
 *
 * <p><b>受控实验</b>（仅测试库、仅本类容器）：seed 阶段移除 {@code idx_t_vector_tenant} 与
 * {@code t_vector_pkey}（精确路径的最后两个入口）+ 事务内 {@code enable_seqscan=off} + 关并行
 * gather——排除全部旁路后 planner 只能走 HNSW，才能观察其形态与行为。生产 SQL 不被污染；
 * JSONB 过滤选择性估算失稳与 btree 索引的利弊见 ADR-008 修订段。
 *
 * <p>断言链：
 * <ol>
 *   <li>{@link #pushdownPlan_underForcedHnswPath_usesIndexAndPushdownFilter()}：HNSW 索引被使用 +
 *       可见性谓词出现在<b>索引扫描节点的 Filter</b>（下推而非后过滤）；消费生产 SQL 常量本身，
 *       避免「断言的是测试自己的 SQL」；</li>
 *   <li>{@link #narrowVisibility_stillFillsK_withoutExactFallback()}：50000 行中仅 100 行可见
 *       （0.2%）、K=10 仍然凑满，<b>且回退计数器零增量</b>——10 条只能来自 iterative_scan 绕回。
 *       绕回额度设为可覆盖全表（确定性：绕回必然访问到全部可见行）；若 GUC 未生效（off），
 *       首次候选中可见行期望 ≈ 0，将 &lt; K 并触发回退，计数器断言即失败：能失败的断言。</li>
 * </ol>
 *
 * <p>「近似塌陷 → 精确回退」的确定性注入测试见 {@code RetrievalExactFallbackContainersTest}
 * （{@code max-scan-tuples=1} 极限注入，比依赖扫描自然耗尽更稳定）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        // 绕回额度放大到可覆盖全表（100000 > 50000）：使「凑满 K」不依赖 HNSW 随机图结构
        // 与 tie 距离下的绕回运气（默认 20000 时全量 vs 单独跑会 flaky——实测踩过）。
        // 证据力不变：若 iterative_scan GUC 未生效（off），首次 ef_search 候选中可见行期望≈0，
        // 必然 < K 并触发回退——「计数零增量」断言即失败。
        "aiwarden.retrieval.hnsw.max-scan-tuples=100000"
})
class RetrievalHnswPushdownContainersTest {

    private static final long TENANT = 700L;
    private static final long USER = 7001L;
    private static final long USER_ORG = 10L;
    private static final String QUERY = "narrow visibility probe";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private VisibilitySetCalculator visibilitySetCalculator;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long visibleKbId;
    private long visibleDocId;

    @BeforeAll
    void seed() {
        visibleKbId = insertKnowledgeBase(null, "hnsw-visible-kb");
        long hiddenKbId = insertKnowledgeBase(20L, "hnsw-hidden-kb");

        visibleDocId = insertDocument(visibleKbId, "hnsw-visible-doc");
        long hiddenDocId = insertDocument(hiddenKbId, "hnsw-hidden-doc");

        // 插入顺序决定证据力（本类实测）：pgvector HNSW 的图入口点＝最早插入的向量——
        // 若可见行先插，入口点在可见团内，默认配置（off）的首次 ef_search 候选即可凑满 K，
        // 「凑满 10」无法区分 iterative_scan 是否生效（假绿）；
        // 因此先插不可见 49900 行、后插可见 100 行——off 时候选里几乎不含可见行（实测 0 条），
        // 「凑满 K」只能由 iterative_scan 绕回来举证。
        insertChunks(hiddenDocId, hiddenKbId, 20L, 49_900, "hidden chunk");
        insertChunks(visibleDocId, visibleKbId, null, 100, "visible chunk");

        // 向量加扰动（前 3 维随行号变化）：**全 tie 向量（所有行同值）是 HNSW 的病态输入**——
        // 图结构在等距下退化，iterative 绕回无法保证覆盖全图（实测：即使额度覆盖全表仍会凑不满）；
        // 扰动后图结构健康，绕回可覆盖全图，「凑满 K」成为确定性断言。
        // 查询向量用嵌入模型正常生成，与所有行距离不同（延续 ADR-007「过滤与距离不相关」的硬场景）。
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta)
                SELECT c.id,
                       ('[' || (0.1 + (c.seq % 97) * 0.0001)::text || ',' ||
                              (0.1 + (c.doc_id % 53) * 0.0002)::text || ',' ||
                              (0.1 + (c.id % 89) * 0.0001)::text || ',' ||
                              repeat('0.1,', 1532) || '0.1]')::vector,
                       c.meta
                FROM t_chunk c WHERE c.tenant_id = ?
                """, TENANT);
        jdbcTemplate.execute("ANALYZE t_vector");

        // 受控实验前提：移除「精确路径」的全部索引入口（本类容器专用，见类注释）——
        // btree 表达式索引与主键索引在不同估算下都是 planner 首选，不排除则 HNSW 永不被观察。
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_t_vector_tenant");
        jdbcTemplate.execute("ALTER TABLE t_vector DROP CONSTRAINT IF EXISTS t_vector_pkey");
    }

    /** ① 受控 EXPLAIN 自证：HNSW 路径的形态——索引被使用 + 可见性谓词下推到扫描节点。 */
    @Test
    void pushdownPlan_underForcedHnswPath_usesIndexAndPushdownFilter() {
        VisibilitySet visible = visibilitySetCalculator.calculate(TENANT, USER, USER_ORG, null);

        String plan = forcedHnswPath(() -> {
            String inlined = inlineArgs(RetrievalSql.PUSHDOWN,
                    PgVectorLiteral.of(embeddingClient.embed(QUERY)),
                    String.valueOf(visible.tenantId()),
                    RetrievalSql.toTextArrayLiteral(visible.kbIds()),
                    RetrievalSql.toTextArrayLiteral(visible.allowDocIds()),
                    RetrievalSql.toTextArrayLiteral(visible.denyDocIds()),
                    10);
            return jdbcTemplate.queryForList("EXPLAIN " + inlined, String.class).stream()
                    .collect(Collectors.joining("\n"));
        });

        assertThat(plan)
                .as("HNSW 路径必须使用向量索引。实际计划：%n%s", plan)
                .contains("Index Scan using idx_t_vector_embedding_hnsw");
        assertThat(plan)
                .as("可见性谓词必须出现在索引扫描节点的 Filter（下推，而非回表后过滤）。实际计划：%n%s", plan)
                .contains("Filter:")
                .contains("tenantId")
                .contains("kbId");
    }

    /** ② 受控 HNSW 路径下，窄可见集（100/50000）仍凑满 K，且不依赖回退——iterative_scan 生效的证据。 */
    @Test
    void narrowVisibility_stillFillsK_withoutExactFallback() {
        double fallbackBefore = fallbackCounter().count();
        // 检索入口与租户上下文交叉校验（ADR-008 运行时保证）——直调服务必须自带上下文，
        // 与 HTTP 路径（TenantContextFilter 注入）同构。
        List<RetrievalHit> hits = withTenant(() -> {
            VisibilitySet visible = visibilitySetCalculator.calculate(TENANT, USER, USER_ORG, visibleKbId);
            return forcedHnswPath(() -> retrievalService.search(visible, QUERY, 10));
        });

        assertThat(hits).hasSize(10);
        assertThat(hits).allMatch(hit -> hit.docId() == visibleDocId);
        assertThat(fallbackCounter().count() - fallbackBefore)
                .as("回退计数器零增量：凑满的 10 条只能来自 iterative_scan 绕回（GUC 未生效时必然触发回退）")
                .isZero();
    }

    // ---- helpers ----

    /** 在测试租户上下文中取值（等价于 HTTP 侧 TenantContextFilter 的注入）。 */
    private <T> T withTenant(Supplier<T> action) {
        try {
            return TenantContext.callWithTenant(String.valueOf(TENANT), action::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 受控实验（仅本类容器）：事务内 {设 "enable_seqscan=off" + 关并行 gather}，seed 阶段已移除
     * btree / 主键索引——四个旁路缺一不可：串行序扫、并行序扫（Gather Merge）、btree（250 行估算）、
     * 主键（1 行估算）。只有全部排除，HNSW 路径才被观察。
     */
    private <T> T forcedHnswPath(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            jdbcTemplate.queryForObject(
                    "SELECT set_config('max_parallel_workers_per_gather', '0', true)", String.class);
            return action.get();
        });
    }

    private Counter fallbackCounter() {
        return meterRegistry.counter("aiwarden_retrieval_exact_fallback_total");
    }

    private static String inlineArgs(String sql, Object... args) {
        String result = sql;
        for (Object arg : args) {
            String literal = (arg instanceof Number number) ? number.toString() : "'" + arg + "'";
            result = result.replaceFirst("\\?", Matcher.quoteReplacement(literal));
        }
        return result;
    }

    private long insertKnowledgeBase(Long orgId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, org_id, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, TENANT, orgId, name);
    }

    private long insertDocument(long kbId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, ?, 'bulk seed', 1, 'INDEXED') RETURNING id
                """, Long.class, TENANT, kbId, name);
    }

    private void insertChunks(long docId, long kbId, Long orgId, int count, String contentPrefix) {
        // orgId 可空：jsonb_build_object 对 null 参数无法推断类型，显式 CAST（与生产 metaJson「非空才带」对齐，
        // jsonb_strip_nulls 负责移除 null 字段）
        jdbcTemplate.update("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                SELECT ?, ?, 1, gs - 1, ? || ' ' || gs,
                       jsonb_strip_nulls(jsonb_build_object(
                           'tenantId', ?, 'kbId', ?, 'docId', ?, 'version', 1, 'orgId', CAST(? AS BIGINT)))
                FROM generate_series(1, ?) gs
                """, TENANT, docId, contentPrefix, TENANT, kbId, docId, orgId, count);
    }
}
