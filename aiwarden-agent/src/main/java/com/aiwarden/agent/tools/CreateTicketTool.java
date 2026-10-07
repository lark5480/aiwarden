package com.aiwarden.agent.tools;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.spi.ToolExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 建工单工具（P3 主演示场景的副作用对象；PRD §10：工单是零资金风险的演示替身）。
 *
 * <p><b>双重幂等</b>（ADR-009 决策 3）：调用层幂等键仲裁是第一道防线；
 * 本工具自身以 {@code (tenant_id, orderRef)} 唯一约束为最终防线——同一来源单号
 * 在任何会话、任何重放下都只建一张单（{@code ON CONFLICT DO NOTHING} 后读现有）。
 *
 * <p>输入：{@code {"orderRef": "ORDER-1001", "title": "..."}}；
 * 补偿 = 取消工单（幂等：仅 OPEN → CANCELLED）。
 */
@Component
public class CreateTicketTool implements ToolExecutor {

    public static final String NAME = "create_ticket";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CreateTicketTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean compensable() {
        return true;
    }

    @Override
    public String execute(String inputJson) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        JsonNode input = objectMapper.readTree(inputJson);
        String orderRef = input.path("orderRef").asString(null);
        String title = input.path("title").asString(null);
        if (orderRef == null || orderRef.isBlank()) {
            throw new IllegalArgumentException("create_ticket 输入缺少 orderRef（来源单号）");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("create_ticket 输入缺少 title");
        }

        jdbcTemplate.update("""
                INSERT INTO t_ticket (tenant_id, idem_key, title) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, idem_key) DO NOTHING
                """, tenantId, orderRef, title);
        Map<String, Object> ticket = jdbcTemplate.queryForMap("""
                SELECT id, status FROM t_ticket WHERE tenant_id = ? AND idem_key = ?
                """, tenantId, orderRef);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticket.get("id"));
        result.put("orderRef", orderRef);
        result.put("status", ticket.get("status"));
        return objectMapper.writeValueAsString(result);
    }

    @Override
    public int compensate(String inputJson, String resultJson) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        String orderRef = objectMapper.readTree(inputJson).path("orderRef").asString(null);
        if (orderRef == null || orderRef.isBlank()) {
            throw new IllegalArgumentException("create_ticket 补偿缺少 orderRef");
        }
        // 幂等：仅 OPEN → CANCELLED；重复补偿为 no-op。
        // 返回**受影响行数**：0 行表示「本就没有可撤销的效果」——执行器据此记为 NO_OP，
        // 不把它当成补偿成功（否则会出现审计 SUCCEEDED 而数据库毫无变化）。
        return jdbcTemplate.update("""
                UPDATE t_ticket SET status = 'CANCELLED', updated_at = now()
                WHERE tenant_id = ? AND idem_key = ? AND status = 'OPEN'
                """, tenantId, orderRef);
    }
}
