package com.aiwarden.start.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway 迁移的真实 PostgreSQL 验证（Testcontainers，pgvector 镜像）。
 *
 * <p>与普通测试的分工（AGENTS.md §4）：常规单测不依赖外部服务（根 pom 里全局关闭 Flyway）；
 * 本类显式开启 Flyway 并把数据源指向临时容器，验证「应用启动 → 自动迁移 → 表结构就位」完整链路。
 *
 * <p>需要本机 / CI 具备 Docker；镜像与 docker-compose.yml 锁同一 tag。
 */
@Testcontainers
@SpringBootTest(properties = "spring.flyway.enabled=true")
class FlywayMigrationContainersTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migrationCreatesTenantTable() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 't_tenant'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void migrationIsRecordedInHistory() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void migrationCreatesM1TablesAndEnablesVectorExtension() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "t_tenant", "t_knowledge_base", "t_document", "t_chunk", "t_vector",
                "t_outbox_event", "t_ingest_ledger", "t_reconcile_report", "t_llm_call_log");

        Integer vectorExtension = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(vectorExtension).isEqualTo(1);

        // V3（ADR-007 下推地基）：tenantId 表达式索引存在
        Integer tenantIndex = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_t_vector_tenant'", Integer.class);
        assertThat(tenantIndex).isEqualTo(1);
    }
}
