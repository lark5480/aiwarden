package com.aiwarden.common.tenant;

/**
 * 租户上下文缺失（FR-TEN-02）：拒绝执行，<b>不回落默认租户</b>。
 *
 * <p>HTTP 边界由 {@code MissingTenantContextExceptionHandler}（aiwarden-start）统一转 400；
 * Kafka 消费 / 定时任务边界在入口处直接抛出，由对应消费者 / 任务框架进入告警与重试策略。
 */
public class MissingTenantContextException extends RuntimeException {

    public MissingTenantContextException(String message) {
        super(message);
    }
}
