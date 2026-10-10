package com.aiwarden.governance.api;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;

/**
 * B 端管理接口的身份校验入口（FR-TEN-02 / ADR-008 的「缺失即拒绝」纪律）。
 *
 * <p><b>为什么需要它</b>：2026-10-10 联调实测发现 B 端管理接口口径不一致——
 * {@code /api/v1/admin/audit} 与 {@code /usage} 只校验租户、**缺主体也返回 200**，
 * 而 {@code /api/v1/chat} 与检索入口会以 {@link com.aiwarden.common.principal.MissingPrincipalContextException} 拒绝；
 * 更严重的是 {@code /api/v1/admin/consistency/*} **连租户都没校验**（裸扫全表即返回报告）。
 * 同一套身份头「有的入口必填、有的可选」本身就是缺陷——**管理面尤其需要主体**（审计留痕的 {@code actor} 就取自主体）。
 *
 * <p><b>口径</b>：这些是**平台级只读 / 运维接口**（{@code t_reconcile_report} 与 {@code t_eval_report}
 * 均无租户维度，对账是全表扫描），因此这里**只要求身份上下文存在，不做租户维度过滤**——
 * 与 M3 身份头定位为「认证层输出的模拟」一致；真实鉴权接入时应在此处升级为角色校验（B 端管理面）。
 *
 * <p><b>缺失即拒绝</b>：两处均不回落默认值，异常由
 * {@code MissingTenantContextExceptionHandler} / {@code MissingPrincipalContextExceptionHandler}
 * 统一映射为 400。
 */
final class AdminAccess {

    private AdminAccess() {
    }

    /**
     * 校验调用方身份上下文齐备（租户 + 主体），缺失即抛异常 → 400。
     *
     * @return 调用方租户 id（需要按租户过滤的接口可直接使用；平台级接口可忽略）
     */
    static long requireIdentity() {
        long tenantId = TenantContext.requireTenantIdAsLong();
        PrincipalContext.requireUserIdAsLong();
        return tenantId;
    }
}
