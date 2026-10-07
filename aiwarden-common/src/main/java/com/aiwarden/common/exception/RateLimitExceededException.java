package com.aiwarden.common.exception;

/**
 * 租户限流超限（FR-COST-07 / ADR-010 决策 6）：HTTP 429 + {@code Retry-After} 头。
 *
 * <p>与 {@link QuotaExceededException} 的语义区分：本异常防的是**频率**（滑动窗口），
 * 配额超限防的是**账期总额**；两者都 429，但原因与恢复方式不同（等待窗口 / 提额或等新账期）。
 */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Retry-After 秒数（窗口剩余时间的保守估计）。 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
