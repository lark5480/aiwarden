package com.aiwarden.start.decision;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.util.backoff.FixedBackOff;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息层决策实验 · Kafka 侧（PRD §11 风险 7：Kafka → Redis Streams 第 4 周末决策点提前执行）。
 *
 * <p>判定标准（实验前写死，见对话记录 / 裁决报告）：语义清单全绿 + 反馈循环 ≤ 90s → 保留 Kafka。
 * 本类用 Testcontainers 真 Kafka（与 docker-compose.yml 同镜像 tag）验证消费语义清单：
 * <ol>
 *   <li><b>至少一次投递</b>：发送的消息必须到达消费者；</li>
 *   <li><b>手动 ack</b>：ack-mode=manual，处理成功后才显式确认（失败消息因此会被重投——即 ① 的体现）；</li>
 *   <li><b>失败重试</b>：毒消息按 FixedBackOff 重试 1 次；</li>
 *   <li><b>死信</b>：重试耗尽后原消息发布到显式指定的 DLT topic；</li>
 *   <li><b>幂等仲裁可行</b>：由应用层数据库唯一键仲裁（与 broker 无关，此处仅验证 ①③ 为其前提——
 *       至少一次 + 手动 ack 是「唯一键仲裁」能工作的充分条件）；</li>
 * </ol>
 *
 * <p>计时：本类运行墙上时间与容器启动耗时记录在裁决报告中（反馈循环量化）。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.kafka.listener.ack-mode=manual",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.auto-startup=true"
})
class KafkaRoundTripContainersTest {

    /** 语义清单 ①②：正常链路。 */
    private static final String TOPIC_OK = "aiwarden.decision.kafka.ok";

    /** 语义清单 ③④：毒消息链路与死信。 */
    private static final String TOPIC_POISON = "aiwarden.decision.kafka.poison";
    private static final String TOPIC_POISON_DLT = TOPIC_POISON + ".DLT";

    /** 与 docker-compose.yml 锁同一镜像 tag。 */
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    static CountDownLatch okReceived;
    static AtomicReference<String> okPayload;
    static AtomicInteger poisonAttempts;
    static AtomicReference<String> dltPayload;
    static CountDownLatch dltReceived;

    @BeforeEach
    void resetState() {
        okReceived = new CountDownLatch(1);
        okPayload = new AtomicReference<>();
        poisonAttempts = new AtomicInteger();
        dltPayload = new AtomicReference<>();
        dltReceived = new CountDownLatch(1);
    }

    @Test
    void message_reachesConsumer_andCanBeManuallyAcked() throws Exception {
        kafkaTemplate.send(TOPIC_OK, "doc-1", "payload-ok").get(30, TimeUnit.SECONDS);

        assertThat(okReceived.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(okPayload.get()).isEqualTo("payload-ok");
    }

    @Test
    void poisonMessage_isRetriedOnce_thenLandsInDeadLetterTopic() throws Exception {
        kafkaTemplate.send(TOPIC_POISON, "doc-poison", "poison-payload").get(30, TimeUnit.SECONDS);

        assertThat(dltReceived.await(60, TimeUnit.SECONDS)).isTrue();
        assertThat(dltPayload.get()).isEqualTo("poison-payload");
        // 1 次初次消费 + 1 次按策略重试 = 2 次尝试，然后进 DLT
        assertThat(poisonAttempts.get()).isEqualTo(2);
    }

    @TestConfiguration
    static class KafkaDecisionConfig {

        @Bean
        NewTopic okTopic() {
            return new NewTopic(TOPIC_OK, 1, (short) 1);
        }

        @Bean
        NewTopic poisonTopic() {
            return new NewTopic(TOPIC_POISON, 1, (short) 1);
        }

        @Bean
        NewTopic poisonDltTopic() {
            return new NewTopic(TOPIC_POISON_DLT, 1, (short) 1);
        }

        /**
         * 语义清单 ③④：重试 1 次（FixedBackOff），耗尽后发布到显式指定的 DLT。
         *
         * <p>注意 {@code @Primary}：产品代码（knowledge 的 DocumentIngestMessagingConfig）也注册了
         * CommonErrorHandler；全上下文测试中多个候选不唯一时，Kafka 工厂会静默回退默认错误处理器
         * （无 DLT 行为），必须显式指定本测试的策略生效。
         */
        @Bean
        @Primary
        CommonErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                    (record, exception) -> new TopicPartition(TOPIC_POISON_DLT, -1));
            return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 1L));
        }

        @Bean
        TestConsumer testConsumer() {
            return new TestConsumer();
        }
    }

    static class TestConsumer {

        @KafkaListener(topics = TOPIC_OK, groupId = "decision-kafka-ok")
        void onOk(String payload, Acknowledgment ack) {
            okPayload.set(payload);
            ack.acknowledge();
            okReceived.countDown();
        }

        @KafkaListener(topics = TOPIC_POISON, groupId = "decision-kafka-poison")
        void onPoison(String payload, Acknowledgment ack) {
            poisonAttempts.incrementAndGet();
            throw new IllegalStateException("毒消息按设计必失败：" + payload);
        }

        @KafkaListener(topics = TOPIC_POISON_DLT, groupId = "decision-kafka-dlt")
        void onDeadLetter(String payload, Acknowledgment ack) {
            dltPayload.set(payload);
            ack.acknowledge();
            dltReceived.countDown();
        }
    }
}
