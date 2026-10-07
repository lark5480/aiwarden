package com.aiwarden.common.exception;

/**
 * 资源冲突（如租户内同名知识库重复创建）。
 * 由 aiwarden-start 的 ApiExceptionHandler 统一转 409 ProblemDetail。
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
