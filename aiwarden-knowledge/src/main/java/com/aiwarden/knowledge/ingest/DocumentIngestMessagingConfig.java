package com.aiwarden.knowledge.ingest;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * 文档事件消费的错误处理策略（FR-ING-04）：
 * 失败按指数退避重试 ≤3 次（1s/2s/4s），耗尽后把原消息发布到 {@code <topic>.DLT} 死信 topic。
 *
 * <p>注意：这里的重试与 ledger 的 FAILED→重抢占配合——每次重试都能重新获得处理权；
 * 重试耗尽进 DLT 后 ledger 停留在 FAILED（对账任务可见，进入不一致清单的处理路径）。
 */
@Configuration
public class DocumentIngestMessagingConfig {

    @Bean
    CommonErrorHandler documentIngestErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1));
        return new DefaultErrorHandler(recoverer, new ExponentialBackOffWithMaxRetries(3));
    }
}
