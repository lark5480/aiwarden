package com.aiwarden.start.governance;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.RetrievalHit;
import com.aiwarden.contract.knowledge.RetrievalSearchRequest;
import com.aiwarden.contract.knowledge.RetrievalSearchResponse;
import com.aiwarden.core.spi.EmbeddingClient;
import com.aiwarden.knowledge.ingest.DocumentIngestStore;
import com.aiwarden.knowledge.retrieval.RetrievalSql;
import com.aiwarden.knowledge.vector.PgVectorLiteral;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2/P2 越权样本门禁（FR-PERM-04，ADR-008 验证表）：检索类 16 条样本
 * （跨租户 / 跨组织 / 无权限知识库 / 已删除各 4 条），断言拦截率 100%。
 *
 * <p><b>假阳性排除</b>：每条样本断言「不应可见的文档一首未出现」之前，本类先用
 * {@link #authorizedBaseline_hitsExactlyWhatShouldBeVisible()} 证明「相同内容的向量真实存在、
 * 且对有权主体可命中」——否则 0 命中可能只是因为数据不存在 / 嵌入失效，门禁沦为形式。
 *
 * <p><b>拦截的两种形态</b>：可见集为空（含请求限定 kbId 不在可见集内）→ 403 拒绝（FR-PERM-01）；
 * 可见集非空但目标内容不在集内 → 200 且目标文档不出现（下推过滤生效）。
 *
 * <p>工具类 4 条样本（越权工具调用）在 P3 切片补充，与本文合计 PRD 承诺的 20 条。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.flyway.enabled=true")
class RetrievalVisibilityContainersTest {

    private static final String TENANT_A = "400";
    private static final String TENANT_B = "500";

    // 主体：u1 组织10 / u2 无组织 / u5 被 DENY 公共库 / u6 被授权组织20库 / u7 被 DENY 敏感文档 /
    //      u8 被单独授权组织20文档 / u9 组织20 / uB 租户B
    private static final String U1 = "4001";
    private static final String U2 = "4002";
    private static final String U5 = "4005";
    private static final String U6 = "4006";
    private static final String U7 = "4007";
    private static final String U8 = "4008";
    private static final String U9 = "4009";
    private static final String UB = "5001";

    private static final String P_DOC = "public document on refund policy: refunds processed within seven business days";
    private static final String S_DOC = "sensitive document: internal salary review notes for leadership only";
    private static final String O10_DOC = "org ten document: quarterly maintenance plan for datacenter alpha";
    private static final String O20_DOC = "org twenty document: vendor negotiation draft for logistics beta";
    private static final String O20B_DOC = "org twenty document two: exceptional shared file for guest auditor";
    private static final String LIVE_DOC = "live document: current onboarding checklist and access matrix";
    private static final String DEL_DOC = "deleted document: obsolete pricing sheet awaiting removal";
    private static final String B_DOC = "tenant b document: independent tenant knowledge asset gamma";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private DocumentIngestStore documentIngestStore;

    @Autowired
    private Environment environment;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private String baseUrl;

    private long pubKbId;
    private long org10KbId;
    private long org20KbId;
    private long pubKb2Id;
    private long pubDocId;
    private long sensitiveDocId;
    private long org10DocId;
    private long org20DocId;
    private long org20Doc2Id;
    private long liveDocId;
    private long delDocId;
    private long bKbId;
    private long bDocId;

    @BeforeAll
    void seed() {
        baseUrl = "http://localhost:" + environment.getProperty("local.server.port");

        pubKbId = seedKnowledgeBase(400, null, "pub-kb");
        pubDocId = seedIndexedDocument(400, pubKbId, null, "pub-doc", P_DOC);
        sensitiveDocId = seedIndexedDocument(400, pubKbId, null, "sensitive-doc", S_DOC);

        org10KbId = seedKnowledgeBase(400, 10L, "org10-kb");
        org10DocId = seedIndexedDocument(400, org10KbId, 10L, "org10-doc", O10_DOC);

        org20KbId = seedKnowledgeBase(400, 20L, "org20-kb");
        org20DocId = seedIndexedDocument(400, org20KbId, 20L, "org20-doc", O20_DOC);
        org20Doc2Id = seedIndexedDocument(400, org20KbId, 20L, "org20-doc2", O20B_DOC);

        pubKb2Id = seedKnowledgeBase(400, null, "pub-kb-2");
        liveDocId = seedIndexedDocument(400, pubKb2Id, null, "live-doc", LIVE_DOC);
        delDocId = seedIndexedDocument(400, pubKb2Id, null, "del-doc", DEL_DOC);

        bKbId = seedKnowledgeBase(500, null, "b-kb");
        bDocId = seedIndexedDocument(500, bKbId, null, "b-doc", B_DOC);

        seedKbAcl(400, pubKbId, 4005, "DENY");
        seedKbAcl(400, org20KbId, 4006, "ALLOW");
        seedDocAcl(400, sensitiveDocId, 4007, "DENY");
        seedDocAcl(400, org20Doc2Id, 4008, "ALLOW");
    }

    /**
     * 越权样本（16 条）：{@code mustNotAppear} 中的文档一条都不得出现在结果里。
     *
     * <p>每类先由 {@link #authorizedBaseline_hitsExactlyWhatShouldBeVisible()} 建立
     * 「目标内容真实存在且可被有权者命中」的前提，样本断言因此是**能失败的断言**。
     */
    static Stream<Sample> unauthorizedSamples() {
        return Stream.of(
                // ---- 跨租户 4 条 ----
                new Sample("跨租户-无kb限定", "400", "4001", "10", B_DOC, null, 200, "B"),
                new Sample("跨租户-指定他租户kb", "400", "4001", "10", P_DOC, "BKb", 403, null),
                new Sample("跨租户-指定不存在kb", "400", "4001", "10", P_DOC, "MISSING", 403, null),
                new Sample("跨租户-无组织主体", "400", "4002", null, B_DOC, null, 200, "B"),
                // ---- 跨组织 4 条 ----
                new Sample("跨组织-无kb限定", "400", "4001", "10", O20_DOC, null, 200, "O20"),
                new Sample("跨组织-指定他组织kb", "400", "4001", "10", O20_DOC, "O20Kb", 403, null),
                new Sample("跨组织-无组织主体指定组织kb", "400", "4002", null, O10_DOC, "O10Kb", 403, null),
                new Sample("跨组织-无组织主体", "400", "4002", null, O20_DOC, null, 200, "O20"),
                // ---- 无权限知识库 4 条 ----
                new Sample("kbACL-DENY-无kb限定", "400", "4005", "10", P_DOC, null, 200, "P"),
                new Sample("kbACL-DENY-指定被拒kb", "400", "4005", "10", P_DOC, "PubKb", 403, null),
                new Sample("docACL-DENY-敏感文档", "400", "4007", "10", S_DOC, null, 200, "S"),
                new Sample("docACL-ALLOW边界-同库其他文档", "400", "4008", "10", O20_DOC, null, 200, "O20")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthorizedSamples")
    void unauthorizedSamplesAreIntercepted(Sample sample) throws Exception {
        HttpResponse<String> response = searchHttp(sample.tenant(), sample.user(), sample.org(),
                sample.query(), resolveKbId(sample.kbRef()));
        assertThat(response.statusCode())
                .as("样本[%s]：期望状态码", sample.name())
                .isEqualTo(sample.expectedStatus());
        if (sample.expectedStatus() == 200) {
            List<RetrievalHit> hits = parseHits(response);
            assertThat(hits)
                    .as("样本[%s]：被保护文档不得出现在命中中", sample.name())
                    .noneMatch(hit -> protectedDocId(sample.protectedDoc()) == hit.docId());
        }
    }

    /**
     * 有权基线（假阳性排除）：目标内容真实存在，且对「应当可见」的主体可命中——
     * 覆盖 org 门、kbACL ALLOW（跨组织例外）、docACL ALLOW（文档级例外）的正向路径。
     */
    @Test
    void authorizedBaseline_hitsExactlyWhatShouldBeVisible() throws Exception {
        assertHits("公共库-org10用户", "400", U1, "10", P_DOC, pubDocId);
        assertHits("org10库-org10用户", "400", U1, "10", O10_DOC, org10DocId);
        assertHits("org20库-org20用户", "400", U9, "20", O20_DOC, org20DocId);
        assertHits("org20库-kbACL跨组织ALLOW", "400", U6, "10", O20_DOC, org20DocId);
        assertHits("org20文档-docACL单独ALLOW", "400", U8, "10", O20B_DOC, org20Doc2Id);
        assertHits("B租户自有内容", "500", UB, "10", B_DOC, bDocId);
        assertHits("敏感文档向量存在(u1可见)", "400", U1, "10", S_DOC, sensitiveDocId);
        assertHits("u7链路正常(其可见库)", "400", U7, "10", O10_DOC, org10DocId);
        assertHits("活文档正常", "400", U1, "10", LIVE_DOC, liveDocId);
    }

    /**
     * 已删文档 4 条视角（越权样本的第 4 类）：删除收敛（经由产品删除路径
     * {@link DocumentIngestStore#purgeDocumentArtifacts}，非手写 SQL）后，
     * 各组织 / 有无 kb 限定 / 无组织主体下均不得命中。
     *
     * <p>先断言「删除前可命中」——这是**能失败的断言**：向量若因清理缺口残留，删除后必然被捞出。
     */
    @Test
    void deletedDocument_disappearsFromAllVisibilityAngles() throws Exception {
        assertHits("删除前可命中(前置证明)", "400", U1, "10", DEL_DOC, delDocId);

        documentIngestStore.purgeDocumentArtifacts(400, delDocId);

        assertNoHit("删除后-无kb限定", "400", U1, "10", DEL_DOC, null, delDocId);
        assertNoHit("删除后-指定可见kb", "400", U1, "10", DEL_DOC, pubKb2Id, delDocId);
        assertNoHit("删除后-无组织主体", "400", U2, null, DEL_DOC, null, delDocId);
        assertNoHit("删除后-org20主体", "400", U9, "20", DEL_DOC, null, delDocId);
    }

    /**
     * 越权留痕（FR-PERM-05）+ 不可变（ADR-008 决策 5）：拒绝写审计、
     * 数据库触发器拒 UPDATE / DELETE（「不可变留痕」的行为级证据）。
     */
    @Test
    void deniedRetrieval_isAudited_andAuditIsImmutable() throws Exception {
        // 自行触发一次越权拒绝（不依赖其他测试方法的执行顺序）
        HttpResponse<String> denied = searchHttp("400", U1, "10", P_DOC, bKbId);
        assertThat(denied.statusCode()).isEqualTo(403);

        long deniedCount = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM t_audit_log
                WHERE tenant_id = 400 AND actor = ? AND action = 'RETRIEVAL_DENIED' AND result = 'DENIED'
                """, Long.class, U1);
        assertThat(deniedCount).isGreaterThanOrEqualTo(1L);

        Long auditId = jdbcTemplate.queryForObject(
                "SELECT min(id) FROM t_audit_log WHERE tenant_id = 400", Long.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE t_audit_log SET detail = 'tampered' WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("不可变审计日志");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM t_audit_log WHERE id = ?", auditId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("不可变审计日志");
    }

    /**
     * <b>租户边界的承重性自证 + 跨租户 ACL 行的双重防线</b>。
     *
     * <p><b>为什么需要这条</b>：常规「跨租户」样本与 {@code DocumentLifecycleContainersTest
     * #crossTenantRetrieval_returnsNoHits} 其实都不是被 {@code meta->>'tenantId'} 挡住的——
     * 它们是被 OR 的<b>左支</b> {@code meta->>'kbId' = ANY(kbIds)} 挡住的：{@code kbIds} 只可能
     * 含本租户 KB，而异租户 chunk 的 kbId 永不在其中（{@code VisibilitySetCalculator} 的三处
     * ACL / KB 查询都带 {@code tenant_id = ?}）。也就是说，把
     * {@link RetrievalSql#PUSHDOWN} 里的 {@code AND v.meta->>'tenantId' = ?} 整行删掉，
     * 那批用例<b>依然全绿</b>：租户谓词至今是<b>零证据</b>的承重墙。
     *
     * <p><b>本条怎么证</b>（三段，互不依赖）：
     * <ol>
     *   <li><b>结构性证据</b>：用<b>同一条下推 SQL 去掉租户谓词</b>、且把某租户 500 文档的 docId
     *       喂进 allowDocIds —— 它<b>能命中</b>（说明该 docId 一旦进入可见集就会直接泄露）；</li>
     *   <li><b>产品路径</b>：同一条件下走真实接口 —— 0 命中，因为租户谓词与 kbId 支都拦住了它；</li>
     *   <li><b>DB 层防线</b>：那条本该造成泄露的「跨租户 ACL 行」现在<b>根本插不进去</b>
     *       （V8 的组合外键 {@code (tenant_id, doc_id) → t_document(tenant_id, id)}）——
     *       即使第 1 步的构造在可见集中成立，也再无写入路径能制造它；</li>
     *   <li>反向证明：同一主体对<b>本租户</b>内容仍正常可命中（排除「被整体拒绝」造成的假绿）。</li>
     * </ol>
     */
    @Test
    void tenantPredicateIsLoadBearing_andCrossTenantAclRowIsImpossible() throws Exception {
        // ② 产品路径：租户 400 的主体查不到租户 500 的内容
        assertNoHit("跨租户-产品路径 0 命中", "400", U2, null, B_DOC, null, bDocId);

        // ① 结构性证据：同一套下推谓词**去掉租户条件**后，某个外租户 docId 是能命中的。
        //    显式取「属于租户 500 的某一个 docId」——**不能用 `WHERE name = ?` 取**：
        //    `t_document` 只有 `(kb_id, name)` 局部唯一，别处同名文档会让子查询返回多行、
        //    allowDocIds 带上一串 id，断言就失去判别力（实测踩过：count=3 而非 1）。
        Long foreignDocId = jdbcTemplate.queryForObject("""
                SELECT doc_id FROM t_chunk
                WHERE content = ? AND meta->>'tenantId' = '500'
                ORDER BY doc_id LIMIT 1
                """, Long.class, B_DOC);
        assertThat(foreignDocId)
                .as("自证夹具前提：租户 500 的文档确实已索引（否则后面的断言无意义）")
                .isNotNull();

        String withoutTenantPredicate = RetrievalSql.PUSHDOWN.replace("v.meta->>'tenantId' = ?\n  AND ", "");
        assertThat(withoutTenantPredicate)
                .as("自证夹具必须真的移除了租户谓词（否则本用例什么都没证明）")
                .doesNotContain("v.meta->>'tenantId'");
        Long reachableWithoutTenantPredicate = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (" + withoutTenantPredicate + ") probe",
                Long.class,
                PgVectorLiteral.of(embeddingClient.embed(B_DOC)),
                // kbIds 故意留空：使 OR 的**左支恒假**，命中只能来自右支（allowDocIds）。
                // 若这里塞进本租户 KB，本租户的文档会一起通过，count 变成 ≥2，
                // 断言就不再是「外租户 docId 是否可达」的判别性证据（实测踩过：count=3）。
                RetrievalSql.toTextArrayLiteral(Set.of()),
                RetrievalSql.toTextArrayLiteral(Set.of(foreignDocId)),
                RetrievalSql.toTextArrayLiteral(Set.of()),
                10);
        assertThat(reachableWithoutTenantPredicate)
                .as("去掉租户谓词后，外租户 docId 一旦进入可见集即可命中——证明租户谓词是承重墙")
                .isEqualTo(1L);

        // ③ DB 层防线：制造该泄露所需的「跨租户 ACL 行」现在被组合外键直接拒绝
        assertThatThrownBy(() -> seedDocAcl(400, foreignDocId, 4002, "ALLOW"))
                .as("跨租户 ACL 行（tenant_id=400 指向租户 500 的文档）必须被 DB 约束拒绝")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_t_doc_acl_tenant_doc");

        // ④ 反向证明：同一主体对本租户内容仍正常可命中
        assertHits("同主体-本租户内容仍正常", "400", U2, null, P_DOC, pubDocId);
    }

    // ---- sample model & helpers ----

    /** 越权样本：kbRef 为知识库引用（PubKb / O10Kb / O20Kb / BKb / MISSING / null）。 */
    record Sample(String name, String tenant, String user, String org, String query,
                  String kbRef, int expectedStatus, String protectedDoc) {
    }

    private Long resolveKbId(String kbRef) {
        if (kbRef == null) {
            return null;
        }
        return switch (kbRef) {
            case "PubKb" -> pubKbId;
            case "O10Kb" -> org10KbId;
            case "O20Kb" -> org20KbId;
            case "BKb" -> bKbId;
            case "MISSING" -> 999_999L;
            default -> throw new IllegalArgumentException("未知 kbRef: " + kbRef);
        };
    }

    private long protectedDocId(String protectedDoc) {
        return switch (protectedDoc) {
            case "P" -> pubDocId;
            case "S" -> sensitiveDocId;
            case "O10" -> org10DocId;
            case "O20" -> org20DocId;
            case "O20B" -> org20Doc2Id;
            case "B" -> bDocId;
            default -> throw new IllegalArgumentException("未知 protectedDoc: " + protectedDoc);
        };
    }

    private void assertHits(String scene, String tenant, String user, String org,
                            String query, long expectedDocId) throws Exception {
        HttpResponse<String> response = searchHttp(tenant, user, org, query, null);
        assertThat(response.statusCode()).as("[%s] 状态码", scene).isEqualTo(200);
        assertThat(parseHits(response))
                .as("[%s] 应命中 docId=%d", scene, expectedDocId)
                .anyMatch(hit -> hit.docId() == expectedDocId);
    }

    private void assertNoHit(String scene, String tenant, String user, String org,
                             String query, Long kbId, long forbiddenDocId) throws Exception {
        HttpResponse<String> response = searchHttp(tenant, user, org, query, kbId);
        assertThat(response.statusCode()).as("[%s] 状态码", scene).isEqualTo(200);
        assertThat(parseHits(response))
                .as("[%s] 不得命中 docId=%d", scene, forbiddenDocId)
                .noneMatch(hit -> hit.docId() == forbiddenDocId);
    }

    private HttpResponse<String> searchHttp(String tenant, String user, String org, String query, Long kbId)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/retrieval/search"))
                .header(TenantContext.TENANT_ID_HEADER, tenant)
                .header(PrincipalContext.USER_ID_HEADER, user);
        if (org != null) {
            builder.header(PrincipalContext.ORG_ID_HEADER, org);
        }
        builder.header("Content-Type", "application/json");
        builder.method("POST", HttpRequest.BodyPublishers.ofString(
                objectMapper.writeValueAsString(new RetrievalSearchRequest(query, 10, kbId)),
                StandardCharsets.UTF_8));
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private List<RetrievalHit> parseHits(HttpResponse<String> response) throws Exception {
        return objectMapper.readValue(response.body(), RetrievalSearchResponse.class).hits();
    }

    // ---- seed helpers（显式 SQL，meta 字段与 DocumentIngestStore.metaJson 一致） ----

    private long seedKnowledgeBase(long tenant, Long orgId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO t_knowledge_base (tenant_id, org_id, name) VALUES (?, ?, ?) RETURNING id
                """, Long.class, tenant, orgId, name);
    }

    private long seedIndexedDocument(long tenant, long kbId, Long orgId, String name, String content) {
        Long docId = jdbcTemplate.queryForObject("""
                INSERT INTO t_document (tenant_id, kb_id, name, content, version, status)
                VALUES (?, ?, ?, ?, 1, 'INDEXED') RETURNING id
                """, Long.class, tenant, kbId, name, content);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tenantId", tenant);
        meta.put("kbId", kbId);
        meta.put("docId", docId);
        meta.put("version", 1);
        if (orgId != null) {
            meta.put("orgId", orgId);
        }
        String metaJson = objectMapper.writeValueAsString(meta);
        Long chunkId = jdbcTemplate.queryForObject("""
                INSERT INTO t_chunk (tenant_id, doc_id, version, seq, content, meta)
                VALUES (?, ?, 1, 0, ?, ?::jsonb) RETURNING id
                """, Long.class, tenant, docId, content, metaJson);
        jdbcTemplate.update("""
                INSERT INTO t_vector (chunk_id, embedding, meta) VALUES (?, ?::vector, ?::jsonb)
                """, chunkId, PgVectorLiteral.of(embeddingClient.embed(content)), metaJson);
        return docId;
    }

    private void seedKbAcl(long tenant, long kbId, long userId, String effect) {
        jdbcTemplate.update("""
                INSERT INTO t_kb_acl (tenant_id, kb_id, user_id, effect) VALUES (?, ?, ?, ?)
                """, tenant, kbId, userId, effect);
    }

    private void seedDocAcl(long tenant, long docId, long userId, String effect) {
        jdbcTemplate.update("""
                INSERT INTO t_doc_acl (tenant_id, doc_id, user_id, effect) VALUES (?, ?, ?, ?)
                """, tenant, docId, userId, effect);
    }
}
