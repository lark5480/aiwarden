package com.aiwarden.common.exception;

/**
 * 配额超限（P4a / ADR-010 决策 4）：预扣减被拒 → HTTP 429。
 *
 * <p>拒绝不产生残留：调用方（工具调用入口）在事务内抛出本异常 → 回滚（幂等仲裁行同步消失），
 * 配额恢复后同一幂等键可重试；留痕走独立事务的审计（QUOTA_EXCEEDED）。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
