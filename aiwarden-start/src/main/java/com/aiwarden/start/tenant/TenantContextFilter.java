package com.aiwarden.start.tenant;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * HTTP 边界（FR-TEN-02 + ADR-008）：从请求头建立租户上下文与主体上下文，请求结束清理。
 *
 * <p>过滤器不在这里拒绝缺租户 / 缺主体的请求——由需要它们的入口调用
 * {@link TenantContext#requireTenantId()} / {@link PrincipalContext#requireUserId()} 拒绝
 * （不回落默认租户、不回落匿名主体），统一由对应 ExceptionHandler 转 400。
 *
 * <p><b>本过滤器是「认证层输出的模拟」</b>：M2 的租户与主体均来自请求头。接入 API Key 后，
 * 两者来源切换为「密钥解析」，传播机制不变——<b>但届时顺序必须同时调整</b>：上下文来自密钥，
 * 必须先完成鉴权才能建立，不能再排在 {@link Ordered#HIGHEST_PRECEDENCE}（否则读到的是尚未鉴权的密钥）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(TenantContext.TENANT_ID_HEADER);
        String userId = request.getHeader(PrincipalContext.USER_ID_HEADER);
        String orgId = request.getHeader(PrincipalContext.ORG_ID_HEADER);
        try {
            if (StringUtils.hasText(tenantId)) {
                TenantContext.setTenantId(tenantId);
            }
            if (StringUtils.hasText(userId)) {
                PrincipalContext.setPrincipal(userId, orgId);
            }
            filterChain.doFilter(request, response);
        } finally {
            PrincipalContext.clear();
            TenantContext.clear();
        }
    }
}
