package com.aiwarden.start.decision;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息层决策实验 · Redis Streams 侧（Kafka 的对照实现，同一组消费语义断言）。
 *
 * <p>覆盖清单（与 {@link KafkaRoundTripContainersTest} 对齐）：
 * <ol>
 *   <li><b>至少一次投递</b>：XADD 的消息被 XREADGROUP 消费到；</li>
 *   <li><b>手动 ack</b>：未 XACK 的消息停留在 PEL（XPENDING 可见），可被 XCLAIM/XAUTOCLAIM 重投；</li>
 *   <li><b>失败重试</b>：毒消息不 ack 留在 PEL → XCLAIM 换消费者重投（模拟另一个消费实例接手）；</li>
 *   <li><b>死信</b>：重试上限（此处=1）后，由应用层写入 DLT stream 并 ack 原消息；</li>
 *   <li><b>幂等仲裁可行</b>：同 Kafka 侧——由应用层数据库唯一键仲裁，与 broker 无关。</li>
 * </ol>
 *
 * <p>实现形态：纯 Lettuce（不引 Spring Data Redis），贴近「退化路径的真实实现成本」评估。
 */
@Testcontainers
class RedisStreamsRoundTripContainersTest {

    private static final String STREAM = "aiwarden.decision.redis.stream";
    private static final String STREAM_DLT = STREAM + ".DLT";
    private static final String GROUP = "decision-redis-group";

    /** 与 docker-compose.yml 锁同一镜像 tag。 */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> commands;

    @BeforeEach
    void connect() {
        client = RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        connection = client.connect();
        commands = connection.sync();
        commands.xgroupCreate(XReadArgs.StreamOffset.from(STREAM, "0"), GROUP, XGroupCreateArgs.Builder.mkstream(true));
    }

    @AfterEach
    void disconnect() {
        connection.close();
        client.shutdown();
    }

    @Test
    void streamsCoverTheSameConsumptionSemantics() {
        // ① 至少一次投递：XADD → XREADGROUP 消费到
        String okId = commands.xadd(STREAM, Map.of("docId", "doc-1", "payload", "payload-ok"));
        List<StreamMessage<String, String>> received = commands.xreadgroup(
                Consumer.from(GROUP, "consumer-1"),
                XReadArgs.StreamOffset.lastConsumed(STREAM));
        assertThat(received).hasSize(1);
        assertThat(received.get(0).getBody()).containsEntry("payload", "payload-ok");

        // ② 手动 ack：未 ack 消息挂在 PEL（可查询、可重投），ack 后清空
        assertThat(commands.xpending(STREAM, GROUP).getCount()).isEqualTo(1L);
        commands.xack(STREAM, GROUP, okId);
        assertThat(commands.xpending(STREAM, GROUP).getCount()).isZero();

        // ③ 失败重试：毒消息不 ack → 留在 PEL → XCLAIM 模拟另一消费实例重投
        commands.xadd(STREAM, Map.of("docId", "doc-poison", "payload", "poison-payload"));
        List<StreamMessage<String, String>> poisonBatch = commands.xreadgroup(
                Consumer.from(GROUP, "consumer-1"),
                XReadArgs.StreamOffset.lastConsumed(STREAM));
        assertThat(poisonBatch).hasSize(1);
        StreamMessage<String, String> poison = poisonBatch.get(0);
        List<StreamMessage<String, String>> claimed = commands.xclaim(
                STREAM, Consumer.from(GROUP, "consumer-2"), 0L, poison.getId());
        assertThat(claimed).hasSize(1);

        // ④ 死信：重试上限后写入 DLT stream 并 ack 原消息（应用层逻辑的最小演示）
        commands.xadd(STREAM_DLT, poison.getBody());
        commands.xack(STREAM, GROUP, poison.getId());
        assertThat(commands.xlen(STREAM_DLT)).isEqualTo(1L);
    }
}
