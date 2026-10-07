package com.aiwarden.governance.visibility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 可见集计算（ADR-008 决策 3，FR-PERM-01）：检索前把四级权限折叠为 filter 可下推的载体。
 *
 * <p><b>算法</b>：
 * <ol>
 *   <li>可见 KB = {@code (org 门：org_id IS NULL OR org_id = :orgId) − DENY ∪ ALLOW}——
 *       ALLOW / DENY 来自 {@code t_kb_acl}（用户粒度）；</li>
 *   <li>请求指定 {@code kbId} 时与可见 KB 求交集（跨租户 / 不存在 / 无权一律落空集，
 *       不可区分——不泄露存在性）；</li>
 *   <li>文档例外 = {@code t_doc_acl} 的 ALLOW / DENY 两个集合。</li>
 * </ol>
 *
 * <p><b>边界纪律</b>：本类只返回集合（可为空集），<b>不在这里抛拒绝</b>——「空集即拒绝」的语义
 * 由检索入口（{@code RetrievalService}）执行，保证任何调用方都无法绕过；本类自身可被
 * 「列出可见知识库」等只读场景复用（那里空集不是异常）。
 *
 * <p>全部为索引点查（{@code idx_t_kb_acl_user} / {@code idx_t_doc_acl_user} / 主键），
 * 无全量扫描、无应用层后过滤。
 */
@Component
public class VisibilitySetCalculator {

    private final JdbcTemplate jdbcTemplate;

    public VisibilitySetCalculator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 计算可见集。
     *
     * @param tenantId      租户（硬边界，来自 TenantContext）
     * @param userId        主体用户
     * @param orgId         主体组织；可为空（空 = 仅公共 KB）
     * @param requestedKbId 请求限定的知识库；可为空（空 = 全部可见 KB）
     */
    public VisibilitySet calculate(long tenantId, long userId, Long orgId, Long requestedKbId) {
        Set<Long> kbIds = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT kb.id FROM t_knowledge_base kb
                WHERE kb.tenant_id = ?
                  AND (kb.org_id IS NULL OR kb.org_id = CAST(? AS BIGINT))
                  AND NOT EXISTS (SELECT 1 FROM t_kb_acl a
                                  WHERE a.kb_id = kb.id AND a.user_id = ?
                                    AND a.tenant_id = kb.tenant_id AND a.effect = 'DENY')
                UNION
                SELECT a.kb_id FROM t_kb_acl a
                JOIN t_knowledge_base kb ON kb.id = a.kb_id
                WHERE a.tenant_id = ? AND a.user_id = ? AND a.effect = 'ALLOW' AND kb.tenant_id = ?
                """, Long.class, tenantId, orgId, userId, tenantId, userId, tenantId));

        if (requestedKbId != null) {
            kbIds.retainAll(Set.of(requestedKbId));
        }

        Set<Long> allowDocIds = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT doc_id FROM t_doc_acl
                WHERE tenant_id = ? AND user_id = ? AND effect = 'ALLOW'
                """, Long.class, tenantId, userId));
        Set<Long> denyDocIds = new HashSet<>(jdbcTemplate.queryForList("""
                SELECT doc_id FROM t_doc_acl
                WHERE tenant_id = ? AND user_id = ? AND effect = 'DENY'
                """, Long.class, tenantId, userId));

        return new VisibilitySet(tenantId, kbIds, allowDocIds, denyDocIds);
    }
}
