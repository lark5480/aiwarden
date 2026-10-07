package com.aiwarden.common.exception;

/**
 * 资源不存在（找不到 / 不属于当前租户 / 已删除）。
 *
 * <p>口径（FR-KB-01）：跨租户访问返回 404 而不是 403——避免探测资源存在性。
 * 由 aiwarden-start 的 ApiExceptionHandler 统一转 404 ProblemDetail。
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
