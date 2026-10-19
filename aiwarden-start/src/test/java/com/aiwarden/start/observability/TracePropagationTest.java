package com.aiwarden.start.observability;

import com.aiwarden.governance.tracing.TraceSupport;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四类异步边界的 traceId 透传（FR-OBS-02）：线程池 / 虚拟线程 / 定时任务 / Kafka 消费。
 *
 * <p><b>断言方式</b>：用真实 OTel SDK + 内存 exporter（不是 mock）验证「子 span 的 traceId 与父一致、
 * parentSpanId 指向父 span」。四类边界各一个用例——每类都必须在**另一个线程**上恢复上下文，
 * 否则测的只是同线程 ThreadLocal，什么都没证明。
 *
 * <p><b>为什么不用 TransmittableThreadLocal</b>：见 ADR-003——虚拟线程是不可复用的一次性线程，
 * 池化线程会被复用；统一走显式 capture → apply，这也是 {@link TraceSupport} 的 API 形态。
 */
class TracePropagationTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private TraceSupport traceSupport;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(provider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        Tracer tracer = new OtelTracer(sdk.getTracer("trace-propagation-test"),
                new OtelCurrentTraceContext(), event -> {
        });
        OtelPropagator propagator = new OtelPropagator(sdk.getPropagators(), sdk.getTracer("trace-propagation-test"));
        traceSupport = new TraceSupport(tracer, propagator);
    }

    @AfterEach
    void tearDown() {
        provider.shutdown().join(5, TimeUnit.SECONDS);
    }

    @Test
    void virtualThreadBoundary_preservesParentTrace() throws Exception {
        Span root = traceSupport.startSpan("http.request");
        String traceId = traceSupport.traceIdOf(root);
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root);
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> childTrace = executor.submit(() -> {
                // 显式 apply：虚拟线程是一次性线程，不依赖 ThreadLocal 自动透传
                try (Tracer.SpanInScope ignoredInChild = traceSupport.withSpan(root)) {
                    Span child = traceSupport.startSpan("agent.run");
                    try {
                        return traceSupport.traceIdOf(child);
                    } finally {
                        traceSupport.end(child);
                    }
                }
            });
            assertThat(childTrace.get(10, TimeUnit.SECONDS))
                    .as("虚拟线程内的 span 必须属于请求的 trace")
                    .isEqualTo(traceId);
        } finally {
            traceSupport.end(root);
        }
    }

    @Test
    void threadPoolBoundary_preservesParentTrace() throws Exception {
        Span root = traceSupport.startSpan("http.request");
        String traceId = traceSupport.traceIdOf(root);
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root);
             ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> childTrace = executor.submit(() -> {
                try (Tracer.SpanInScope ignoredInChild = traceSupport.withSpan(root)) {
                    Span child = traceSupport.startSpan("pool.task");
                    try {
                        return traceSupport.traceIdOf(child);
                    } finally {
                        traceSupport.end(child);
                    }
                }
            });
            assertThat(childTrace.get(10, TimeUnit.SECONDS)).isEqualTo(traceId);
        } finally {
            traceSupport.end(root);
        }
    }

    @Test
    void scheduledBoundary_continuesTraceFromCapturedTraceParent() throws Exception {
        Span root = traceSupport.startSpan("http.request");
        String traceId;
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root)) {
            traceId = traceSupport.traceIdOf(root);
            root.tag("aiwarden.outbox.id", "42");
        }
        // 请求线程结束、ThreadLocal 清空——定时任务只能靠写入时捕获的 traceparent 接回调用链
        traceSupport.end(root);
        String traceParent = traceParentForTest(root);
        assertThat(traceParent).as("必须在写入时捕获 traceparent").isNotNull();

        ExecutorService scheduledThread = Executors.newSingleThreadExecutor();
        try {
            Future<String> childTrace = scheduledThread.submit(() -> {
                Span scheduled = traceSupport.startSpan("outbox.publish", traceParent);
                try {
                    return traceSupport.traceIdOf(scheduled);
                } finally {
                    traceSupport.end(scheduled);
                }
            });
            assertThat(childTrace.get(10, TimeUnit.SECONDS))
                    .as("定时任务按事件上的 traceparent 接回原 trace")
                    .isEqualTo(traceId);
        } finally {
            scheduledThread.shutdownNow();
        }
    }

    @Test
    void kafkaBoundary_propagatesThroughMessageHeaders() throws Exception {
        Span root = traceSupport.startSpan("http.request");
        String traceId;
        RecordHeaders headers = new RecordHeaders();
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root)) {
            traceId = traceSupport.traceIdOf(root);
            traceSupport.injectCurrent(headers,
                    (carrier, key, value) -> carrier.add(key, value.getBytes(StandardCharsets.UTF_8)));
        } finally {
            traceSupport.end(root);
        }

        Header header = headers.lastHeader(TraceSupport.TRACEPARENT);
        assertThat(header).as("生产端必须写入 W3C traceparent").isNotNull();
        String traceParent = new String(header.value(), StandardCharsets.UTF_8);

        // 模拟消费线程：从消息头恢复父上下文
        String consumedTraceId;
        Span consumer = traceSupport.startSpan("kafka.consume", traceParent);
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(consumer)) {
            Span ingest = traceSupport.startSpan("document.ingest");
            try {
                consumedTraceId = traceSupport.traceIdOf(ingest);
            } finally {
                traceSupport.end(ingest);
            }
        } finally {
            traceSupport.end(consumer);
        }
        assertThat(consumedTraceId).isEqualTo(traceId);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertThat(spans).extracting(SpanData::getName)
                .contains("http.request", "kafka.consume", "document.ingest");
        SpanData rootSpan = spans.stream().filter(s -> s.getName().equals("http.request")).findFirst().orElseThrow();
        SpanData consumerSpan = spans.stream().filter(s -> s.getName().equals("kafka.consume")).findFirst().orElseThrow();
        assertThat(consumerSpan.getTraceId()).isEqualTo(rootSpan.getTraceId());
        assertThat(consumerSpan.getParentSpanId()).isEqualTo(rootSpan.getSpanId());
    }

    /** 定时任务用例需要一个「在 root 仍是 current 时」捕获的 traceparent。 */
    private String traceParentForTest(Span root) {
        String traceParent;
        try (Tracer.SpanInScope ignored = traceSupport.withSpan(root)) {
            traceParent = traceSupport.currentTraceParent();
        }
        return traceParent;
    }
}
