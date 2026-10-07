package com.aiwarden.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kafka 消费边界（FR-TEN-02）：租户随消息头跨进程传递——
 * 生产端盖戳（{@code writeToCarrier}）、消费端恢复（{@code runWithCarrier}），消息缺租户头即拒绝。
 *
 * <p>真实 Kafka 接入在 M1；本测试用内存载体先冻结传播机制与拒绝语义，
 * M1 只需为 ProducerRecord / ConsumerRecord 实现 {@link TenantContextCarrier} 适配。
 */
class TenantContextKafkaBoundaryTest {

    private final InMemoryCarrier carrier = new InMemoryCarrier();

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void producer_stampsTenantHeader_whenContextPresent() {
        TenantContext.setTenantId("tenant-k");

        TenantContext.writeToCarrier(carrier);

        assertThat(carrier.getHeader(TenantContext.TENANT_ID_HEADER)).contains("tenant-k");
    }

    @Test
    void producer_withoutContext_isRejected() {
        assertThatThrownBy(() -> TenantContext.writeToCarrier(carrier))
                .isInstanceOf(MissingTenantContextException.class);
    }

    @Test
    void consumer_withTenantHeader_seesContextInsideHandler_andLeavesNoTraceAfter() {
        carrier.putHeader(TenantContext.TENANT_ID_HEADER, "tenant-k");

        TenantContext.runWithCarrier(carrier,
                () -> assertThat(TenantContext.requireTenantId()).isEqualTo("tenant-k"));

        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void consumer_withoutTenantHeader_isRejected() {
        assertThatThrownBy(() -> TenantContext.runWithCarrier(carrier, () -> {
        }))
                .isInstanceOf(MissingTenantContextException.class)
                .hasMessageContaining(TenantContext.TENANT_ID_HEADER);
    }

    /** 内存载体：模拟 Kafka 消息头（head = Map）。 */
    private static final class InMemoryCarrier implements TenantContextCarrier {

        private final Map<String, String> headers = new HashMap<>();

        @Override
        public Optional<String> getHeader(String name) {
            return Optional.ofNullable(headers.get(name));
        }

        @Override
        public void putHeader(String name, String value) {
            headers.put(name, value);
        }
    }
}
