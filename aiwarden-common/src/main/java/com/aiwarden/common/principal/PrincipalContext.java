package com.aiwarden.common.principal;

import java.util.Optional;

/**
 * 主体上下文：请求处理期间的当前操作者（userId + orgId），可见集计算的输入之一（ADR-008）。
 *
 * <p><b>来源（M2 过渡）</b>：请求头 {@value #USER_ID_HEADER} / {@value #ORG_ID_HEADER}——
 * 这是「认证层输出的模拟」：真实部署中由 API Key / JWT 解析产出，届时只换来源、不换传播机制，
 * 与 {@code TenantContext} 对租户的处理同构（ADR-003）。
 *
 * <p><b>缺失即拒绝</b>：{@link #requireUserId()} 在缺失时抛
 * {@link MissingPrincipalContextException}，<b>不回落匿名主体</b>——检索等入口必须先有主体，
 * 否则无法计算可见集。
 *
 * <p><b>orgId 允许为空</b>：无组织归属的用户只能看见「租户公共」（org_id 为空）的知识库。
 */
public final class PrincipalContext {

    /** 用户标识请求头（认证层输出模拟；Kafka 消息头传播在需要时再对称扩展）。 */
    public static final String USER_ID_HEADER = "X-Aiwarden-User-Id";

    /** 组织标识请求头；可缺失（缺失 = 无组织归属，仅可见公共内容）。 */
    public static final String ORG_ID_HEADER = "X-Aiwarden-Org-Id";

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private PrincipalContext() {
    }

    /** 设置当前线程主体；userId 空值即参数错误（不静默），orgId 可为空。 */
    public static void setPrincipal(String userId, String orgId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        CURRENT.set(new Principal(userId, (orgId == null || orgId.isBlank()) ? null : orgId));
    }

    /** 当前主体（可能为空）。 */
    public static Optional<Principal> principal() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** 当前用户标识；缺失时拒绝执行（不回落匿名主体）。 */
    public static String requireUserId() {
        Principal principal = CURRENT.get();
        if (principal == null) {
            throw new MissingPrincipalContextException(
                    "主体上下文缺失（" + USER_ID_HEADER + "）：拒绝执行（ADR-008；不回落匿名主体）");
        }
        return principal.userId();
    }

    /** 当前用户标识（数值形式）；缺失或非数值同样拒绝执行。 */
    public static long requireUserIdAsLong() {
        String userId = requireUserId();
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw new MissingPrincipalContextException("用户标识不是合法数值，拒绝执行：" + userId);
        }
    }

    /** 当前组织标识（数值形式，可为空——无组织归属）。 */
    public static Long orgIdAsLong() {
        String orgId = orgId();
        if (orgId == null) {
            return null;
        }
        try {
            return Long.parseLong(orgId);
        } catch (NumberFormatException e) {
            throw new MissingPrincipalContextException("组织标识不是合法数值，拒绝执行：" + orgId);
        }
    }

    /** 当前组织标识（可能为空，空 = 无组织归属）。 */
    public static String orgId() {
        Principal principal = CURRENT.get();
        return principal == null ? null : principal.orgId();
    }

    /** 清理当前线程主体；请求 / 任务结束必须调用，避免池化线程串主体。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** 主体快照（userId + orgId）。 */
    public record Principal(String userId, String orgId) {
    }
}
