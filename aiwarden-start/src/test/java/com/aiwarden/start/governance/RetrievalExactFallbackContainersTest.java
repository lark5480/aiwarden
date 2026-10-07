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
 * M2/P2 精确回退触发验证（ADR-007「两段式回退不是备选方案，而是设计的组成部分」）。
 *
 * <p><b>为什么单独一个类 + 受控实验</b>：塌陷语义依赖 HNSW 路径真实执行。实测事实：
 * 5000 行规模下 planner 真实计划是 {@code Seq Scan + Sort}（精确路径，{@code max_scan_tuples}
 * 不参与）——若不加受控条件，本测试会因「永远不触发回退」而失败或假绿。因此：
 * ① 事务内 {@code enable_seqscan=off} + 关并行 gather（排除精确路径旁路，见
 * {@code RetrievalHnswPushdownContainersTest} 类注释的完整实测清单）；
 * ② 表规模见 HIDDEN_CHUNKS（受控条件下需足以让 planner 选 HNSW）+ seed 阶段移除 btree 表达式索引；
 * ③ 类级配置把 {@code aiwarden.retrieval.hnsw.max-scan-tuples} 压到 1，模拟绕回上限耗尽。
 *
 * <p><b>自证链（防「测试通过却什么都没证明」，AGENTS.md 教训）</b>：
 * <ol>
 *   <li>先 EXPLAIN 断言受控路径下确实走 HNSW 索引（否则塌陷根本不会发生）；</li>
 *   <li>再断言塌陷回退被触发（回退计数器增量 ≥ 1）且结果补齐到 K——回退不是死代码。
 *       该断言依赖 {@code exactFallback} 重开 {@code enable_seqscan}（否则回退查询仍被禁顺序扫描，
 *       补齐不了 K）——回退路径的「精确」保证被本测试钉住。</li>
 * </ol>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        // 模拟 iterative_scan 绕回上限耗尽：HNSW 路径只能返回 ≤1 条 → 触发精确回退
        "aiwarden.retrieval.hnsw.max-scan-tuples=1"
})
class RetrievalExactFallbackContainersTest {

    private static final long TENANT = 800L;
    private static final long USER = 8001L;
    private static final String QUERY = "exact fallback probe";
    /**
     * 塌陷构造：可见 10 行 + 不可见 HIDDEN_CHUNKS 行——HNSW 首次 {@code ef_search} 候选几乎全被 filter 挡住，
     * 「凑满 K」必须依赖 iterative 绕回；再叠加 {@code max-scan-tuples=1} 限制绕回额度，塌陷必然发生。
     * （教训：若数据全可见，首次候选即凑满 10 条，max_scan_tuples 根本用不上，塌陷永远不触发。）
     */
    private static final int VISIBLE_CHUNKS = 10;
    private static final int HIDDEN_CHUNKS = 4_990;

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

    private long kbId;
    private long docId;

    @BeforeAll
    void seed() {
        kbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (?, 'fallback-visible-kb') RETURNING id
                """, Long.class, TENANT);
        long hiddenKbId = jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, org_id, name)
                VALUES (?, 20, 'fallback-hidden-kb') RETURNING id
                """, Long.class, TENANT);
        docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, 'fallback-doc', 'bulk seed', 1, 'INDEXED') RETURNING id
                """, Long.class, TENANT, kbId);
        long hiddenDocId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, 'fallback-hidden-doc', 'bulk seed', 1, 'INDEXED') RETURNING id
                """, Long.class, TENANT, hiddenKbId);

        // 插入顺序决定证据力（实测）：HNSW 图入口点＝最早插入的向量——不可见行必须先插，
        // 否则入口点落在可见团，默认候选即可凑满 K，max_scan_tuples 永远不参与、塌陷不触发（假绿）。
        insertChunks(hiddenDocId, hiddenKbId, 20L, HIDDEN_CHUNKS, "fallback hidden chunk");
        insertChunks(docId, kbId, null, VISIBLE_CHUNKS, "fallback visible chunk");

        // 向量加扰动（前 3 维随行号变化）：全 tie 向量是 HNSW 病态输入（图结构等距退化），
        // 见 RetrievalHnswPushdownContainersTest 类注释的实测记录。
        //
        // ⚠️ **先 DROP 向量索引再灌数据，最后重建**（2026-10-07 实测优化）：
        // 若保留 V2 建好的 HNSW 索引，bulk insert 会对**每一行**都做一次图维护（插入-选邻居-连边）。
        // 本机实测（50000 行）：`vectorInsert=7.9s` 但 **`hnswBuild=97.9s`**，批量构建占本类总耗时 71%；
        // 改成「drop → 插入 → 一次 CREATE INDEX」后总耗时 334s → 145s。得到**结构与逐行插入等价**的索引，
        // 断言力不变。注意顺序——`idx_t_vector_tenant`（btree 表达式索引）的移除仍必须发生在**插入之后**
        // （走索引的 insert 更慢，且它不影响结果），见下方「受控实验前提」。
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_t_vector_embedding_hnsw");
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
        jdbcTemplate.execute("CREATE INDEX idx_t_vector_embedding_hnsw "
                + "ON t_vector USING hnsw (embedding vector_cosine_ops)");
        jdbcTemplate.execute("ANALYZE t_vector");
        // 受控实验前提：移除 btree 表达式索引与主键索引（本类容器专用），否则受控下 planner 走精确路径
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_t_vector_tenant");
        jdbcTemplate.execute("ALTER TABLE t_vector DROP CONSTRAINT IF EXISTS t_vector_pkey");
    }

    private void insertChunks(long docId, long kbId, Long orgId, int count, String contentPrefix) {
        jdbcTemplate.update("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                SELECT ?, ?, 1, gs - 1, ? || ' ' || gs,
                       jsonb_strip_nulls(jsonb_build_object(
                           'tenantId', ?, 'kbId', ?, 'docId', ?, 'version', 1, 'orgId', CAST(? AS BIGINT)))
                FROM generate_series(1, ?) gs
                """, TENANT, docId, contentPrefix, TENANT, kbId, docId, orgId, count);
    }

    @Test
    void exactFallback_triggersOnHnswCollapse_andFillsToK() {
        VisibilitySet visible = visibilitySetCalculator.calculate(TENANT, USER, null, null);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // 前提自证：受控 HNSW 路径下确实走 HNSW（否则 max_scan_tuples 无意义，塌陷断言会假绿）；
        // 裸值传入，引号由 inlineArgs 统一负责（避免双重转义）
        String plan = tx.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            jdbcTemplate.queryForObject(
                    "SELECT set_config('max_parallel_workers_per_gather', '0', true)", String.class);
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
                .as("受控 HNSW 路径下必须使用向量索引。实际计划：%n%s", plan)
                .contains("Index Scan using idx_t_vector_embedding_hnsw");

        double fallbackBefore = fallbackCounter().count();

        // 受控 HNSW + max_scan_tuples=1：HNSW 只能给 ≤1 条 → 塌陷 → 精确回退补齐。
        // 检索入口与租户上下文交叉校验（ADR-008 运行时保证）——直调服务必须自带上下文。
        List<RetrievalHit> hits = withTenant(() -> tx.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            jdbcTemplate.queryForObject(
                    "SELECT set_config('max_parallel_workers_per_gather', '0', true)", String.class);
            return retrievalService.search(visible, QUERY, 10);
        }));

        assertThat(hits).as("塌陷后被精确回退补齐到 K").hasSize(10);
        assertThat(hits).allMatch(hit -> hit.docId() == docId);
        assertThat(fallbackCounter().count() - fallbackBefore)
                .as("回退计数器递增：本断言证明回退路径真实执行（不是死代码）")
                .isGreaterThanOrEqualTo(1.0);
        // 回退路径的「精确」保证：关掉索引扫描后，唯一可用的执行方式是顺序扫描 + 过滤 + 排序。
        // 断言 GUC 真的生效（而不是靠 planner 恰好选对）——ADR-007 修订段第 4 条要求的自证。
        String fallbackPlan = withTenant(() -> tx.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'on', true)", String.class);
            jdbcTemplate.queryForObject("SELECT set_config('enable_indexscan', 'off', true)", String.class);
            jdbcTemplate.queryForObject("SELECT set_config('enable_bitmapscan', 'off', true)", String.class);
            String inlined = inlineArgs(RetrievalSql.PUSHDOWN,
                    PgVectorLiteral.of(embeddingClient.embed(QUERY)),
                    String.valueOf(visible.tenantId()),
                    RetrievalSql.toTextArrayLiteral(visible.kbIds()),
                    RetrievalSql.toTextArrayLiteral(visible.allowDocIds()),
                    RetrievalSql.toTextArrayLiteral(visible.denyDocIds()),
                    10);
            return jdbcTemplate.queryForList("EXPLAIN " + inlined, String.class).stream()
                    .collect(Collectors.joining("\n"));
        }));
        assertThat(fallbackPlan)
                .as("回退路径必须真的走顺序扫描（索引扫描被禁 ⇒ 不得出现 HNSW 索引）。实际计划：%n%s", fallbackPlan)
                .contains("Seq Scan")
                .doesNotContain("idx_t_vector_embedding_hnsw");

        // 回退把 enable_indexscan / enable_bitmapscan 设为 off（事务本地 GUC，不会自动消失）。
        // 必须在同一事务内复位，否则外层事务里后续检索会静默不再走下推路径（结果仍对，机制失效）。
        String[] gucsAfterFallback = withTenant(() -> tx.execute(status -> {
            jdbcTemplate.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            jdbcTemplate.queryForObject(
                    "SELECT set_config('max_parallel_workers_per_gather', '0', true)", String.class);
            retrievalService.search(visible, QUERY, 10);  // 触发回退
            return new String[]{
                    jdbcTemplate.queryForObject("SELECT current_setting('enable_indexscan')", String.class),
                    jdbcTemplate.queryForObject("SELECT current_setting('enable_bitmapscan')", String.class),
                    jdbcTemplate.queryForObject("SELECT current_setting('enable_seqscan')", String.class)
            };
        }));
        assertThat(gucsAfterFallback[0])
                .as("回退后 enable_indexscan 必须复位为 on（否则同一事务内后续检索静默退化）")
                .isEqualTo("on");
        assertThat(gucsAfterFallback[1]).as("回退后 enable_bitmapscan 必须复位为 on").isEqualTo("on");
        assertThat(gucsAfterFallback[2])
                .as("enable_seqscan 应回到调用方设定（本用例受控路径为 off）")
                .isEqualTo("off");
    }

    private Counter fallbackCounter() {
        return meterRegistry.counter("aiwarden_retrieval_exact_fallback_total");
    }

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

    private static String inlineArgs(String sql, Object... args) {
        String result = sql;
        for (Object arg : args) {
            String literal = (arg instanceof Number number) ? number.toString() : "'" + arg + "'";
            result = result.replaceFirst("\\?", Matcher.quoteReplacement(literal));
        }
        return result;
    }
}
