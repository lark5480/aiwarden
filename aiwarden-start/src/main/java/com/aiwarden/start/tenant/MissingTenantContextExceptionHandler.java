package com.aiwarden.start.tenant;

import com.aiwarden.common.tenant.MissingTenantContextException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 租户上下文缺失 → 400（FR-TEN-02：拒绝执行，不回落默认租户）。
 */
@RestControllerAdvice
public class MissingTenantContextExceptionHandler {

    @ExceptionHandler(MissingTenantContextException.class)
    public ProblemDetail onMissingTenantContext(MissingTenantContextException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
