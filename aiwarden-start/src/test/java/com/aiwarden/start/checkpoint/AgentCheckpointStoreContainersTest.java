package com.aiwarden.start.checkpoint;

import com.aiwarden.agent.checkpoint.AgentCheckpointStore;
import com.aiwarden.common.exception.ConflictException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本 CAS 与租约的存储层验证（FR-CKPT-03）。
 *
 * <p>不启 Spring 上下文：直接对真实 PostgreSQL 建 {@link AgentCheckpointStore}，
 * 让断言只落在「条件更新是否真的阻止了并发推进」这一件事上——不冒装配问题掩盖语义的风险。
 */
@Testcontainers
class AgentCheckpointStoreContainersTest {

    private static final long TENANT = 910L;
    private static final long USER = 91001L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private static AgentCheckpointStore store;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AgentCheckpointStore(jdbc, 60);
    }

    @Test
    void completeStep_advancesVersionAndPersistsSnapshot() {
        long runId = store.createRun(TENANT, USER, null, "store-ok", "{\"message\":\"hi\"}",
                "test-owner", "trace-1", null);

        AgentCheckpointStore.StepRow step = store.completeStep(TENANT, runId, 0, 1, "init",
                "{\"in\":1}", "{\"out\":1}", "ok", 5, "trace-1");

        assertThat(step.version()).isEqualTo(1);
        AgentCheckpointStore.RunRow run = store.findById(TENANT, runId).orElseThrow();
        assertThat(run.version()).isEqualTo(1);
        assertThat(run.lastStepNo()).isEqualTo(1);
        assertThat(store.findSteps(TENANT, runId)).hasSize(1);
    }

    @Test
    void staleVersion_isRejected_andDoesNotPersistStep() {
        long runId = store.createRun(TENANT, USER, null, "store-stale", "{}",
                "test-owner", "trace-2", null);
        store.completeStep(TENANT, runId, 0, 1, "init", "{}", "{}", "", 1, "trace-2");

        // 另一个（过期）实例仍拿着 version=0：必须被拒绝，而不是覆盖已推进的状态
        assertThatThrownBy(() -> store.completeStep(TENANT, runId, 0, 2, "visibility",
                "{}", "{}", "", 1, "trace-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("版本冲突");

        assertThat(store.findById(TENANT, runId).orElseThrow().version()).isEqualTo(1);
        assertThat(store.findSteps(TENANT, runId)).hasSize(1);
    }

    @Test
    void lease_isExclusiveUntilExpiry_sameOwnerMayReenter() {
        long runId = store.createRun(TENANT, USER, null, "store-lease", "{}",
                "owner-a", "trace-3", null);

        assertThat(store.tryClaimLease(TENANT, runId, "owner-b"))
                .as("租约未过期时其他实例不得接管")
                .isFalse();
        assertThat(store.tryClaimLease(TENANT, runId, "owner-a"))
                .as("同一实例可重入（重复 resume 不会自我阻塞）")
                .isTrue();
    }

    @Test
    void finishRun_requiresCurrentVersion_andMarksTerminal() {
        long runId = store.createRun(TENANT, USER, null, "store-finish", "{}",
                "owner-a", "trace-4", null);
        store.completeStep(TENANT, runId, 0, 1, "init", "{}", "{}", "", 1, "trace-4");

        assertThatThrownBy(() -> store.finishRun(TENANT, runId, 0, "answer", null))
                .isInstanceOf(ConflictException.class);
        store.finishRun(TENANT, runId, 1, "answer", null);

        AgentCheckpointStore.RunRow run = store.findById(TENANT, runId).orElseThrow();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.outcome()).isEqualTo("answer");
        assertThat(run.version()).isEqualTo(2);
    }
}
