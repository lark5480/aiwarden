package com.aiwarden.start.checkpoint;

import com.aiwarden.agent.checkpoint.AgentDagEngine;
import com.aiwarden.agent.orchestration.ChatOrchestrator;
import com.aiwarden.agent.orchestration.ChatStreamSink;
import com.aiwarden.agent.orchestration.OrchestrationResult;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.chat.ChatAskRequest;
import com.aiwarden.contract.chat.ChatEvents;
import com.aiwarden.start.AiwardenApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 断点续跑的**独立进程**驱动（测试用，不是产品入口）。
 *
 * <p>为什么必须是独立进程：`kill -9` 的语义是「进程突然消失，没有关闭钩子、没有 flush」。
 * 在测试 JVM 里模拟只能证明「异常路径」，证明不了「崩溃路径」；而崩溃窗口恰恰是
 * 「副作用已发生、检查点未提交」的唯一来源。测试父进程用 {@code Process.waitFor}
 * 观察退出码，再启动第二个进程续跑——这才是 P5 的验收形态。
 *
 * <p>用法：{@code CrashChatMain <chat|resume> <sessionKey> <crashNode|->}；
 * 数据库 / Redis 地址来自 {@code -Daiwarden.test.*}。
 */
public final class CrashChatMain {

    private static final String TENANT = "810";
    private static final String USER = "81001";

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        String sessionKey = args[1];
        String crashNode = args.length > 2 ? args[2] : "-";
        String orderRef = args.length > 3 ? args[3] : "ORDER-9701";
        String crashBefore = "-".equals(crashNode) ? "" : crashNode;
        String message = "帮我建工单 " + orderRef + "，客户投诉退款慢";

        // 必须用命令行参数（最高优先级）：application.yml 显式写了 spring.datasource.url，
        // builder.properties() 只是「默认值」，会被 yml 覆盖——这正是本测试第一版踩到的坑。
        String[] springArgs = {
                "--spring.datasource.url=" + System.getProperty("aiwarden.test.db.url"),
                "--spring.datasource.username=" + System.getProperty("aiwarden.test.db.user"),
                "--spring.datasource.password=" + System.getProperty("aiwarden.test.db.password"),
                "--spring.data.redis.host=" + System.getProperty("aiwarden.test.redis.host"),
                "--spring.data.redis.port=" + System.getProperty("aiwarden.test.redis.port"),
                "--spring.flyway.enabled=true",
                "--spring.kafka.listener.auto-startup=false",
                "--spring.main.web-application-type=none",
                "--aiwarden.outbox.relay.enabled=false",
                "--aiwarden.reconcile.enabled=false",
                "--aiwarden.agent.tools.allowed=create_ticket,assign_ticket",
                "--aiwarden.agent.checkpoint.lease-seconds="
                        + System.getProperty("aiwarden.test.lease-seconds", "60"),
                "--aiwarden.agent.checkpoint.crash-before-checkpoint=" + crashBefore,
                "--management.tracing.export.otlp.enabled=false",
                "--management.otlp.metrics.export.enabled=false",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"
        };
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AiwardenApplication.class)
                .web(WebApplicationType.NONE)
                .run(springArgs);

        try {
            ensureKnowledgeBase(context.getBean(JdbcTemplate.class));
            String outcome = TenantContext.callWithTenant(TENANT, () -> {
                PrincipalContext.setPrincipal(USER, null);
                try {
                    OrchestrationResult result;
                    if ("resume".equals(mode)) {
                        result = context.getBean(AgentDagEngine.class)
                                .resume(sessionKey, ChatStreamSink.NOOP, new AtomicBoolean(false));
                    } else if ("abort".equals(mode)) {
                        result = runAborted(context.getBean(ChatOrchestrator.class), message, sessionKey);
                    } else {
                        result = context.getBean(ChatOrchestrator.class).chat(
                                new ChatAskRequest(message, sessionKey, null, null),
                                ChatStreamSink.NOOP, new AtomicBoolean(false));
                    }
                    System.out.println("RESULT outcome=" + result.outcome()
                            + " tools=" + result.tools()
                            + " tokens=" + result.promptTokens() + "/" + result.completionTokens());
                    return result.outcome();
                } finally {
                    PrincipalContext.clear();
                }
            });
            System.out.println("DONE outcome=" + outcome);
            context.close();
            System.exit(0);
        } catch (Exception e) {
            System.out.println("FAILED " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace(System.out);
            context.close();
            System.exit(2);
        }
    }

    private static void ensureKnowledgeBase(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO t_knowledge_base (tenant_id, name) VALUES (?, ?)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """, Long.parseLong(TENANT), "crash-resume-kb");
    }

    /** 模拟「人工中断 / 超时」：首个 token 后置位取消标志，编排停在工具之前，运行状态 ABORTED。 */
    private static OrchestrationResult runAborted(ChatOrchestrator orchestrator, String message,
                                                  String sessionKey) {
        AtomicBoolean aborted = new AtomicBoolean(false);
        return orchestrator.chat(new ChatAskRequest(message, sessionKey, null, null),
                new AbortOnFirstTokenSink(aborted), aborted);
    }

    private static final class AbortOnFirstTokenSink implements ChatStreamSink {

        private final AtomicBoolean aborted;
        private boolean seen;

        private AbortOnFirstTokenSink(AtomicBoolean aborted) {
            this.aborted = aborted;
        }

        @Override
        public void token(ChatEvents.Token event) {
            if (!seen) {
                seen = true;
                aborted.set(true);
            }
        }

        @Override
        public void step(ChatEvents.Step event) {
        }

        @Override
        public void citation(ChatEvents.Citation event) {
        }

        @Override
        public void tool(ChatEvents.Tool event) {
        }

        @Override
        public void outcome(ChatEvents.Outcome event) {
        }

        @Override
        public void cost(ChatEvents.Cost event) {
        }

        @Override
        public void done(ChatEvents.Done event) {
        }
    }
}
