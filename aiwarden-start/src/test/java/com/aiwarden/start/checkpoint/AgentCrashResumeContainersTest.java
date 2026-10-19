package com.aiwarden.start.checkpoint;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.governance.metering.MeteringEventConsumer;
import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * P5 验收：`kill -9` 崩溃 → 续跑 → 副作用不重复（FR-CKPT-02/04）。
 *
 * <p><b>测试形态</b>：被测应用跑在<b>独立子进程</b>里，父进程用 {@code Process.waitFor} 观察真实退出码。
 * 崩溃注入点由子进程配置 {@code aiwarden.agent.checkpoint.crash-before-checkpoint} 决定——
 * 节点动作完成后、检查点提交前调用 {@code Runtime.halt(137)}（与 kill -9 同为「无关闭钩子、无 flush」）。
 * 在同一个 JVM 里 mock 异常无法覆盖这个窗口：事务提交与检查点提交的先后关系才是 P5 的核心。
 *
 * <p>场景：
 * <ol>
 *   <li>崩在 <b>tool 之后、检查点之前</b>（副作用已发生）：续跑重做 tool，但幂等键命中首见结果
 *       → {@code replayed=true}，工单数仍为 1；</li>
 *   <li>崩在 <b>cost 动作之后、检查点之前</b>（tool 检查点已提交）：续跑从 cost 继续，
 *       tool 步骤直接复用检查点快照（{@code replayed=false}，版本号不变），不重新执行副作用；</li>
 *   <li>崩在 <b>metering 之后、检查点之前</b>：续跑重做计量，但同键事件经消费端唯一键仲裁，
 *       只落一条模型明细；</li>
 *   <li>租约未过期：另一个实例续跑必须 409，而不是并行执行副作用。</li>
 * </ol>
 *
 * <p>另有断连（ABORTED）后续跑用例。
 */
@Testcontainers
class AgentCrashResumeContainersTest {

    private static final long TENANT = 810L;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private static JdbcTemplate jdbc;
    private static MeteringEventConsumer meteringConsumer;

    @BeforeAll
    static void setUp() {
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        meteringConsumer = new MeteringEventConsumer(JsonMapper.builder().build(), jdbc,
                new TraceSupport(Tracer.NOOP, Propagator.NOOP), 0.002, 0.006);
    }

    @Test
    void crashAfterToolSideEffect_resumeReplaysIdempotentlyWithoutSecondTicket() throws Exception {
        String session = "crash-tool";
        Child chat = start("chat", session, "tool", "ORDER-9701", 1);
        assertThat(await(chat, 180))
                .as("崩溃子进程应以 137 退出（Runtime.halt；日志如下）\n" + read(chat.log()))
                .isEqualTo(137);

        // 崩溃实例的租约 1s：等它过期，模拟「原实例已死，接管者出现」
        Thread.sleep(2_000);

        Child resume = start("resume", session, "-", "ORDER-9701", 1);
        assertThat(await(resume, 180))
                .as("续跑子进程应正常退出（日志如下）\n" + read(resume.log()))
                .isZero();
        assertThat(read(resume.log()))
                .as("续跑重做 tool 步骤，但命中首见结果")
                .contains("RESULT outcome=answer")
                .contains("replayed=true");

        assertRun(session, "SUCCEEDED", 10, 11);
        assertThat(count("SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?",
                TENANT, "ORDER-9701"))
                .as("崩溃在副作用之后：续跑不得产生第二张工单")
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                """, TENANT, session))
                .as("同一幂等键只允许一条调用账本")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT detail FROM t_agent_step
                WHERE tenant_id = ? AND session_id = (SELECT max(id) FROM t_agent_session WHERE session_key = ?)
                  AND step_no = 7
                """, String.class, TENANT, session))
                .as("tool 步骤在续跑中重做并命中重放（replayed=true）")
                .contains("replayed=true");
        assertTraceContinuity(session);
    }

    @Test
    void crashAfterToolCheckpoint_resumesFromNextStepWithoutReexecutingTool() throws Exception {
        String session = "crash-cost";
        Child chat = start("chat", session, "cost", "ORDER-9702", 1);
        assertThat(await(chat, 180))
                .as("崩溃子进程应以 137 退出（日志如下）\n" + read(chat.log()))
                .isEqualTo(137);

        assertThat(count("""
                SELECT count(*) FROM t_agent_step
                WHERE tenant_id = ? AND session_id = (SELECT max(id) FROM t_agent_session WHERE session_key = ?)
                """, TENANT, session))
                .as("崩溃点在 cost：tool 及其之前的 7 个检查点应已提交")
                .isEqualTo(7);

        Thread.sleep(2_000);
        Child resume = start("resume", session, "-", "ORDER-9702", 1);
        assertThat(await(resume, 180))
                .as("续跑子进程应正常退出（日志如下）\n" + read(resume.log()))
                .isZero();
        assertThat(read(resume.log()))
                .as("tool 步骤直接复用检查点快照，不应重放执行")
                .contains("RESULT outcome=answer")
                .contains("replayed=false");

        assertRun(session, "SUCCEEDED", 10, 11);
        assertThat(count("SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?",
                TENANT, "ORDER-9702")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                """, TENANT, session)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT version FROM t_agent_step
                WHERE tenant_id = ? AND session_id = (SELECT max(id) FROM t_agent_session WHERE session_key = ?)
                  AND step_no = 7
                """, Integer.class, TENANT, session))
                .as("tool 步骤版本保持 7（若被重做，版本会变成 8）")
                .isEqualTo(7);
        assertTraceContinuity(session);
    }

    @Test
    void interruptedRun_canBeResumed_andSideEffectHappensExactlyOnce() throws Exception {
        String session = "crash-aborted";
        Child abort = start("abort", session, "-", "ORDER-9704", 1);
        assertThat(await(abort, 180))
                .as("模拟断连的子进程应正常退出（日志如下）\n" + read(abort.log()))
                .isZero();
        assertThat(read(abort.log())).contains("RESULT outcome=aborted");
        assertThat(runRow(session).get("status"))
                .as("断连后运行应为 ABORTED，且可续跑")
                .isEqualTo("ABORTED");
        assertThat(count("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                """, TENANT, session))
                .as("断连取消后工具绝不执行")
                .isZero();
        assertThat(count("SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?",
                TENANT, "ORDER-9704")).isZero();

        Child resume = start("resume", session, "-", "ORDER-9704", 1);
        assertThat(await(resume, 180))
                .as("中断后的续跑应成功（日志如下）\n" + read(resume.log()))
                .isZero();
        assertThat(read(resume.log())).contains("RESULT outcome=answer");
        // 中断时 abortRun 也推进了一次版本（6 步 = 6 → ABORTED 7），续跑 4 步 → 11，finish → 12
        assertRun(session, "SUCCEEDED", 10, 12);
        assertThat(count("SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?",
                TENANT, "ORDER-9704")).isEqualTo(1);
    }

    @Test
    void resumeBeforeLeaseExpiry_isRejectedInsteadOfRunningConcurrently() throws Exception {
        String session = "crash-lease";
        Child chat = start("chat", session, "tool", "ORDER-9703", 60);
        assertThat(await(chat, 180)).isEqualTo(137);

        Child resume = start("resume", session, "-", "ORDER-9703", 60);
        assertThat(await(resume, 180))
                .as("租约未过期时续跑必须失败退出（日志如下）\n" + read(resume.log()))
                .isNotZero();
        assertThat(read(resume.log()))
                .as("失败原因应是租约冲突，而不是其他错误")
                .contains("租约未过期");

        Map<String, Object> run = runRow(session);
        assertThat(run.get("status")).as("被拒绝的续跑不得改变运行状态").isEqualTo("RUNNING");
        assertThat(count("SELECT count(*) FROM t_ticket WHERE tenant_id = ? AND idem_key = ?",
                TENANT, "ORDER-9703")).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM t_tool_invocation
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                """, TENANT, session)).isEqualTo(1);
    }

    @Test
    void crashBeforeMeteringCheckpoint_dedupsMeteringOnResume() throws Exception {
        String session = "crash-metering";
        Child chat = start("chat", session, "metering", "ORDER-9705", 1);
        assertThat(await(chat, 180))
                .as("崩溃子进程应以 137 退出（日志如下）\n" + read(chat.log()))
                .isEqualTo(137);
        assertThat(count("""
                SELECT count(*) FROM t_agent_step
                WHERE tenant_id = ? AND session_id = (SELECT max(id) FROM t_agent_session WHERE session_key = ?)
                """, TENANT, session))
                .as("崩溃点在 metering：模型及其之前的 5 个检查点应已提交")
                .isEqualTo(5);

        Thread.sleep(2_000);
        Child resume = start("resume", session, "-", "ORDER-9705", 1);
        assertThat(await(resume, 180))
                .as("续跑子进程应正常退出（日志如下）\n" + read(resume.log()))
                .isZero();
        assertRun(session, "SUCCEEDED", 10, 11);

        List<String> payloads = jdbc.queryForList("""
                SELECT payload::text FROM t_outbox_event
                WHERE tenant_id = ? AND type = ? AND payload->>'sessionId' = ?
                ORDER BY id
                """, String.class, TENANT, MeteringEventTypes.CALL_RECORDED, session);
        assertThat(payloads)
                .as("崩溃前写一条模型计量、续跑再写一条；工具调用另有一条")
                .hasSize(3);
        assertThat(count("""
                SELECT count(DISTINCT payload->>'dedupKey') FROM t_outbox_event
                WHERE tenant_id = ? AND type = ? AND payload->>'sessionId' = ?
                  AND payload->>'tool' IS NULL
                """, TENANT, MeteringEventTypes.CALL_RECORDED, session))
                .as("崩溃前后两条模型计量必须使用同一个幂等键")
                .isEqualTo(1);

        // 模拟 Kafka 至少一次投递：两条同键模型事件都经真实消费逻辑落库
        TenantContext.runWithTenant(String.valueOf(TENANT),
                () -> payloads.forEach(meteringConsumer::consume));

        assertThat(count("""
                SELECT count(*) FROM t_llm_call_log
                WHERE tenant_id = ? AND session_id = ? AND tool IS NULL
                """, TENANT, session))
                .as("同一幂等键的两条模型计量只允许落一条明细")
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM t_llm_call_log
                WHERE tenant_id = ? AND session_id = ? AND tool = 'create_ticket'
                """, TENANT, session))
                .as("工具维度仍正常落一条")
                .isEqualTo(1);
        assertTraceContinuity(session);
    }

    // ---- helpers ----

    private void assertRun(String session, String status, int lastStepNo, int version) {
        Map<String, Object> run = runRow(session);
        assertThat(run.get("status")).isEqualTo(status);
        assertThat(((Number) run.get("last_step_no")).intValue()).isEqualTo(lastStepNo);
        assertThat(((Number) run.get("version")).intValue()).isEqualTo(version);
        assertThat((String) run.get("trace_id")).as("运行应带 traceId").isNotBlank();
    }

    /** 一次调用的所有步骤共享同一 traceId（含崩溃前后两个进程）——按 traceId 可还原完整调用链。 */
    private void assertTraceContinuity(String session) {
        Map<String, Object> trace = jdbc.queryForMap("""
                SELECT count(*) AS steps, count(DISTINCT trace_id) AS traces, min(trace_id) AS trace_id
                FROM t_agent_step
                WHERE tenant_id = ? AND session_id = (SELECT max(id) FROM t_agent_session WHERE session_key = ?)
                """, TENANT, session);
        assertThat(((Number) trace.get("steps")).intValue()).isEqualTo(10);
        assertThat(((Number) trace.get("traces")).intValue())
                .as("崩溃前后两个进程的步骤必须在同一条 trace 上")
                .isEqualTo(1);
        String traceId = (String) trace.get("trace_id");
        assertThat(traceId).isNotBlank();
        assertThat(count("""
                SELECT count(*) FROM t_outbox_event
                WHERE tenant_id = ? AND trace_parent LIKE ?
                """, TENANT, "%" + traceId + "%"))
                .as("outbox 事件在写入时捕获 traceparent，计量事件的 trace 与运行一致")
                .isPositive();
    }

    private Map<String, Object> runRow(String session) {
        return jdbc.queryForMap("""
                SELECT status, last_step_no, version, trace_id
                FROM t_agent_session WHERE tenant_id = ? AND session_key = ?
                ORDER BY id DESC LIMIT 1
                """, TENANT, session);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Child start(String mode, String session, String crashNode,
                        String orderRef, int leaseSeconds) throws IOException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("surefire.test.class.path",
                System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-cp");
        command.add(classpath);
        // 子进程输出重定向到文件：显式 UTF-8，否则 Windows 下按控制台代码页写、父进程读不回来
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add("-Daiwarden.test.db.url=" + postgres.getJdbcUrl());
        command.add("-Daiwarden.test.db.user=" + postgres.getUsername());
        command.add("-Daiwarden.test.db.password=" + postgres.getPassword());
        command.add("-Daiwarden.test.redis.host=" + redis.getHost());
        command.add("-Daiwarden.test.redis.port=" + redis.getMappedPort(6379));
        command.add("-Daiwarden.test.lease-seconds=" + leaseSeconds);
        command.add(CrashChatMain.class.getName());
        command.add(mode);
        command.add(session);
        command.add(crashNode);
        command.add(orderRef);

        File log = new File("target", "agent-" + mode + "-" + session + ".log");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(new File(System.getProperty("user.dir")))
                .redirectErrorStream(true)
                .redirectOutput(log);
        return new Child(builder.start(), log.toPath());
    }

    private int await(Child child, int seconds) throws InterruptedException {
        if (!child.process().waitFor(seconds, TimeUnit.SECONDS)) {
            child.process().destroyForcibly();
            fail("子进程超时未退出：" + child.log());
        }
        return child.process().exitValue();
    }

    private String read(Path log) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<无法读取日志 " + log + "：" + e.getMessage() + ">";
        }
    }

    private record Child(Process process, Path log) {
    }
}
