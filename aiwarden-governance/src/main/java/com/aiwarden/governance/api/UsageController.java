package com.aiwarden.governance.api;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.governance.UsageRecordResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 用量明细接口（FR-COST-04）：按会话 / 步骤 / 工具 / 时间多维过滤（租户经行级隔离）；
 * 聚合由 B 端看板（M3）基于同一明细表完成。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class UsageController {

    private final JdbcTemplate jdbcTemplate;

    public UsageController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/usage")
    public List<UsageRecordResponse> usage(@RequestParam(required = false) String sessionId,
                                           @RequestParam(required = false) Integer stepNo,
                                           @RequestParam(required = false) String tool,
                                           @RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(defaultValue = "100") Integer limit) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        int size = Math.clamp(limit, 1, 500);

        StringBuilder sql = new StringBuilder("""
                SELECT id, session_id, step_no, tool, model, prompt_tokens, completion_tokens,
                       latency_ms, created_at
                FROM t_llm_call_log WHERE tenant_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (sessionId != null && !sessionId.isBlank()) {
            sql.append(" AND session_id = ?");
            params.add(sessionId);
        }
        if (stepNo != null) {
            sql.append(" AND step_no = ?");
            params.add(stepNo);
        }
        if (tool != null && !tool.isBlank()) {
            sql.append(" AND tool = ?");
            params.add(tool);
        }
        if (from != null && !from.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(OffsetDateTime.parse(from));
        }
        if (to != null && !to.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(OffsetDateTime.parse(to));
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        params.add(size);

        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new UsageRecordResponse(
                        rs.getLong("id"),
                        rs.getString("session_id"),
                        rs.getObject("step_no", Integer.class),
                        rs.getString("tool"),
                        rs.getString("model"),
                        rs.getInt("prompt_tokens"),
                        rs.getInt("completion_tokens"),
                        rs.getObject("latency_ms", Integer.class),
                        rs.getObject("created_at", OffsetDateTime.class)),
                params.toArray());
    }
}
