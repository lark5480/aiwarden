package com.aiwarden.start.api;

import com.aiwarden.common.exception.ConflictException;
import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.common.exception.QuotaExceededException;
import com.aiwarden.common.exception.RateLimitExceededException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * API 统一异常映射：404（不存在 / 跨租户不可见，FR-KB-01）、403（可见集为空即拒绝 / 工具不可见，FR-PERM-01/03）、
 * 409（冲突）、400（参数错误）、429（配额超限 / 租户限流，P4a）。与 M0 的 MissingTenantContextExceptionHandler 并存。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail onNotFound(NotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /**
     * 可见集为空（含请求指定 kbId 不在可见集内）→ 403：检索是「搜索」语义，
     * 无权与不存在同为 403，不可区分（不泄露存在性，ADR-008 依据 3）；
     * 资源读取（GET KB / 文档状态）仍按 FR-KB-01 走 404。
     */
    @ExceptionHandler(SecurityException.class)
    public ProblemDetail onSecurityException(SecurityException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail onConflict(ConflictException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    /** 配额超限 → 429（P4a / ADR-010；审计 QUOTA_EXCEEDED 已在拒绝点独立落库）。 */
    @ExceptionHandler(QuotaExceededException.class)
    public ProblemDetail onQuotaExceeded(QuotaExceededException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    /** 租户限流超限 → 429 + Retry-After（FR-COST-07）。 */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> onRateLimitExceeded(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.retryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
