package com.aiwarden.governance.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Trace 边界工具（M4 / FR-OBS）：显式 capture → apply，不依赖线程可复用性（ADR-003 的同一方法论）。
 *
 * <p><b>为什么需要它</b>：Micrometer 的 {@code Tracer}/{@code Propagator} 只在 start 装配根存在；
 * 治理层与 agent 层不能假设 tracing 一定装配（单测 / 无 tracing 的部署），因此统一从这里取——
 * 实现缺失时全部退化为 NOOP，业务链路不受影响（「可观测性不是正确性的前置条件」）。
 *
 * <p><b>四类异步边界</b>（FR-OBS-02）的落点：
 * <ul>
 *   <li>虚拟线程 / 线程池：调用方 capture 当前 {@link Span}，任务内 {@link #withSpan(Span)} 显式 apply；</li>
 *   <li>Kafka 消费：生产端把 W3C {@code traceparent} 写进消息头，消费端 {@link #startSpan(String, String)} 建子 span；</li>
 *   <li>定时任务：outbox 在<b>写入时</b>捕获 traceparent（发送晚于产生它的请求），
 *       定时发布器按事件恢复父子关系——不是靠「定时线程里恰好还有 ThreadLocal」。</li>
 * </ul>
 */
@Component
public class TraceSupport {

    /** W3C Trace Context 头（Micrometer / OTel 默认传播字段）。 */
    public static final String TRACEPARENT = "traceparent";

    private static final Propagator.Setter<Map<String, String>> MAP_SETTER = Map::put;
    private static final Propagator.Getter<Map<String, String>> MAP_GETTER = Map::get;

    private final Tracer tracer;
    private final Propagator propagator;

    /** 直接注入（单测 / 手工装配；生产用下面的 ObjectProvider 构造器）。 */
    public TraceSupport(Tracer tracer, Propagator propagator) {
        this.tracer = tracer == null ? Tracer.NOOP : tracer;
        this.propagator = propagator == null ? Propagator.NOOP : propagator;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TraceSupport(ObjectProvider<Tracer> tracerProvider, ObjectProvider<Propagator> propagatorProvider) {
        this(tracerProvider.getIfAvailable(() -> Tracer.NOOP),
                propagatorProvider.getIfAvailable(() -> Propagator.NOOP));
    }

    /** 当前 traceId（无 tracing / 无当前 span 时为 null）。 */
    public String currentTraceId() {
        TraceContext context = currentContext();
        return context == null ? null : context.traceId();
    }

    /**
     * 当前 W3C traceparent（无 tracing / 无当前 span 时为 null）。
     *
     * <p>必须在使用点<b>当场</b>调用：`@Scheduled` / Kafka 发送线程与产生业务写入的请求线程不是同一个。
     */
    public String currentTraceParent() {
        TraceContext context = currentContext();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, MAP_SETTER);
        return carrier.get(TRACEPARENT);
    }

    /** 新建 root span。 */
    public Span startSpan(String name) {
        return tracer.nextSpan().name(name).start();
    }

    /**
     * 以 W3C traceparent 为父新建 span（跨 Kafka / 定时任务恢复调用链）。
     * traceparent 为空或不可解析时退化为 root span——「坏头不阻断业务」。
     */
    public Span startSpan(String name, String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return startSpan(name);
        }
        try {
            return propagator.extract(Map.of(TRACEPARENT, traceParent), MAP_GETTER).name(name).start();
        } catch (RuntimeException e) {
            return startSpan(name);
        }
    }

    /** 把当前上下文注入任意载体（Kafka headers / 自定义消息头）；无 tracing 时为 no-op。 */
    public <C> void injectCurrent(C carrier, Propagator.Setter<C> setter) {
        TraceContext context = currentContext();
        if (context != null) {
            propagator.inject(context, carrier, setter);
        }
    }

    /** 显式 apply：在当前线程把 span 设为 current（必须 close，恢复上一个上下文）。 */
    public Tracer.SpanInScope withSpan(Span span) {
        return tracer.withSpan(span);
    }

    /** span.name 不可为 null；NOOP span 也可安全 end。 */
    public void end(Span span) {
        if (span != null) {
            span.end();
        }
    }

    /** NOOP span 的 context 没有 traceId；返回 null 而不是空串，落库语义清晰。 */
    public String traceIdOf(Span span) {
        if (span == null || span.isNoop()) {
            return null;
        }
        return span.context().traceId();
    }

    private TraceContext currentContext() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null || context.traceId() == null) {
            return null;
        }
        return context;
    }
}
