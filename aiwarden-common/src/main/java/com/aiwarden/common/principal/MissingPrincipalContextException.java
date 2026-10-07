package com.aiwarden.common.principal;

/**
 * 主体上下文缺失（ADR-008 决策 1）：拒绝执行，<b>不回落匿名主体</b>。
 *
 * <p>HTTP 边界由 {@code MissingPrincipalContextExceptionHandler}（aiwarden-start）统一转 400；
 * 与 {@code MissingTenantContextException}（FR-TEN-02）同构——身份类上下文缺失一律拒绝，
 * 不允许「缺 userId 就当服务账号」这类静默回落。
 */
public class MissingPrincipalContextException extends RuntimeException {

    public MissingPrincipalContextException(String message) {
        super(message);
    }
}
