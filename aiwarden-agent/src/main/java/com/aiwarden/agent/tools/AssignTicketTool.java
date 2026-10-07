package com.aiwarden.agent.tools;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.core.spi.ToolExecutionException;
import com.aiwarden.core.spi.ToolExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指派工单工具（不可补偿）：把工单指派给受理组。
 *
 * <p><b>失败注入约定（演示替身）</b>：{@code assignee} 为空或 "unavailable" 时抛
 * {@link ToolExecutionException}——模拟「外部协作系统故障」。它服务于 P3 补偿链路演示：
 * 建单成功后指派失败 → 失败调用触发补偿计划 → 逆序回滚建单（取消工单）。
 *
 * <p>输入：{@code {"orderRef": "ORDER-1001", "assignee": "group-a"}}。
 */
@Component
public class AssignTicketTool implements ToolExecutor {

    public static final String NAME = "assign_ticket";

    /** 失败注入哨兵值（演示替身：外部协作系统不可用）。 */
    static final String UNAVAILABLE = "unavailable";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AssignTicketTool(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean compensable() {
        return false;
    }

    @Override
    public String execute(String inputJson) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        JsonNode input = objectMapper.readTree(inputJson);
        String orderRef = input.path("orderRef").asString(null);
        String assignee = input.path("assignee").asString(null);
        if (orderRef == null || orderRef.isBlank()) {
            throw new IllegalArgumentException("assign_ticket 输入缺少 orderRef");
        }
        if (assignee == null || assignee.isBlank() || UNAVAILABLE.equals(assignee)) {
            throw new ToolExecutionException("受理组不可用：外部协作系统故障（演示替身注入）");
        }

        int updated = jdbcTemplate.update("""
                UPDATE t_ticket SET assignee = ?, updated_at = now()
                WHERE tenant_id = ? AND idem_key = ? AND status = 'OPEN'
                """, assignee, tenantId, orderRef);
        if (updated == 0) {
            throw new ToolExecutionException("工单不可指派：不存在或已取消（orderRef=" + orderRef + "）");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderRef", orderRef);
        result.put("assignee", assignee);
        result.put("assigned", true);
        return objectMapper.writeValueAsString(result);
    }
}
