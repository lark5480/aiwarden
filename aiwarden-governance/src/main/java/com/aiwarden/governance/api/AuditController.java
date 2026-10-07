package com.aiwarden.governance.api;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.governance.AuditLogResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计检索接口（FR-PERM-05）：谁在何时对哪个目标做了什么——越权尝试（TOOL_DENIED /
 * RETRIEVAL_DENIED）与配额拒绝（QUOTA_EXCEEDED）同样留痕，可按动作 / 主体过滤。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AuditController {

    private final JdbcTemplate jdbcTemplate;

    public AuditController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/audit")
    public List<AuditLogResponse> audit(@RequestParam(required = false) String action,
                                        @RequestParam(required = false) String actor,
                                        @RequestParam(defaultValue = "50") Integer limit) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        int size = Math.clamp(limit, 1, 200);

        StringBuilder sql = new StringBuilder("""
                SELECT id, actor, action, target, result, detail, created_at
                FROM t_audit_log WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = ?");
            params.add(actor);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(size);

        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new AuditLogResponse(
                        rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("target"), rs.getString("result"), rs.getString("detail"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                params.toArray());
    }
}
