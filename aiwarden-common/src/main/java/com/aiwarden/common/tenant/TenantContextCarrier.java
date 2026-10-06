package com.aiwarden.common.tenant;

import java.util.Optional;

/**
 * 租户上下文载体：跨进程边界（如 Kafka 消息头）读写键值头的抽象。
 *
 * <p>M1 接入 Kafka 时由 ProducerRecord / ConsumerRecord 头适配实现；
 * 在此之前用内存实现冻结传播机制与拒绝语义（见 {@code TenantContextKafkaBoundaryTest}）。
 */
public interface TenantContextCarrier {

    /** 读取头值；不存在时返回空。 */
    Optional<String> getHeader(String name);

    /** 写入头值（同名覆盖）。 */
    void putHeader(String name, String value);
}
