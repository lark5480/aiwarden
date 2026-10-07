package com.aiwarden.start.tenant;

import com.aiwarden.common.principal.MissingPrincipalContextException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 主体上下文缺失 → 400（ADR-008：拒绝执行，不回落匿名主体）。
 */
@RestControllerAdvice
public class MissingPrincipalContextExceptionHandler {

    @ExceptionHandler(MissingPrincipalContextException.class)
    public ProblemDetail onMissingPrincipalContext(MissingPrincipalContextException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
