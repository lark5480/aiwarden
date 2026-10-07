package com.aiwarden.start.tenant;

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
 * HTTP 边界（FR-TEN-02）：从请求头读取租户标识建立 {@link TenantContext}，请求结束清理。
 *
 * <p>过滤器不在这里拒绝缺租户的请求——由需要租户的入口调用
 * {@link TenantContext#requireTenantId()} 拒绝（不回落默认租户），
 * 统一由 {@link MissingTenantContextExceptionHandler} 转 400。
 *
 * <p>M2 接入 API Key 后，租户来源由「请求头」切换为「密钥解析」，传播机制不变——
 * <b>但本过滤器的顺序必须同时调整</b>：届时租户来自密钥，必须先完成鉴权才能建立上下文，
 * 不能再排在 {@link Ordered#HIGHEST_PRECEDENCE}（否则读到的是尚未鉴权的密钥）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String tenantId = request.getHeader(TenantContext.TENANT_ID_HEADER);
        try {
            if (StringUtils.hasText(tenantId)) {
                TenantContext.setTenantId(tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
