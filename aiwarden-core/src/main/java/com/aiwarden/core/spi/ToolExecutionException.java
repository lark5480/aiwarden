package com.aiwarden.core.spi;

/**
 * 工具执行业务失败（如外部协作系统不可用）。
 *
 * <p>语义（ADR-009 决策 2）：调用一旦落 FAILED 即为终态——**重放不重执行**，
 * 恢复走补偿链路；因此本异常只用于「确定失败」，启动期的参数错误等
 * 编程错误请用 {@link IllegalArgumentException}（调用方 400）。
 */
public class ToolExecutionException extends RuntimeException {

    public ToolExecutionException(String message) {
        super(message);
    }

    public ToolExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
