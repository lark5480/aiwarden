package com.aiwarden.governance.messaging;

import com.aiwarden.common.tenant.TenantContextCarrier;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Kafka 消息头适配：{@link TenantContextCarrier} 的 Kafka 实现（M0 载体接口的正式接入点）。
 *
 * <p>生产端（OutboxRelay）与消费端（DocumentEventConsumer）共用本适配：
 * {@code org.apache.kafka.common.header.Headers} 在生产/消费两侧同类型。
 */
public class KafkaHeadersCarrier implements TenantContextCarrier {

    private final Headers headers;

    public KafkaHeadersCarrier(Headers headers) {
        this.headers = headers;
    }

    @Override
    public Optional<String> getHeader(String name) {
        Header header = headers.lastHeader(name);
        return header == null
                ? Optional.empty()
                : Optional.of(new String(header.value(), StandardCharsets.UTF_8));
    }

    @Override
    public void putHeader(String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
