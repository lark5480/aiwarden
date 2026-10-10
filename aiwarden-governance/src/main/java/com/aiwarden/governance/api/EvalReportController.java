package com.aiwarden.governance.api;

import com.aiwarden.common.exception.NotFoundException;
import com.aiwarden.contract.governance.EvalReportResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 评测报告读取（FR-ADM-05 / ADR-012 决策 9）：返回最近一次评测门禁运行的结论表。
 *
 * <p><b>数据来源</b>：{@code t_eval_report}（V9 迁移）——由全栈门禁驱动
 * （{@code ChatEvalGateContainersTest}，随 `mvn verify` 执行）写入；本接口只读。
 * 表为**平台级数据**（一次构建一次运行，无租户维度），入口仅要求身份上下文存在
 * （与其它 B 端接口一致的纪律级别）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class EvalReportController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvalReportController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/eval/report")
    public EvalReportResponse latest() {
        AdminAccess.requireIdentity();
        List<EvalReportRow> rows = jdbcTemplate.query("""
                SELECT run_at, total, passed, deny_total, deny_blocked,
                       duplicate_tickets, p95_latency_ms, avg_cost, detail
                FROM t_eval_report
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> new EvalReportRow(
                rs.getTimestamp("run_at").toInstant(),
                rs.getInt("total"),
                rs.getInt("passed"),
                rs.getInt("deny_total"),
                rs.getInt("deny_blocked"),
                rs.getInt("duplicate_tickets"),
                rs.getLong("p95_latency_ms"),
                rs.getBigDecimal("avg_cost"),
                rs.getString("detail")));
        if (rows.isEmpty()) {
            throw new NotFoundException("暂无评测报告（评测门禁尚未运行——随 mvn verify 执行）");
        }
        EvalReportRow row = rows.get(0);
        List<Map<String, Object>> detail = objectMapper.readValue(row.detail(),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        return new EvalReportResponse(row.runAt(), row.total(), row.passed(), row.denyTotal(),
                row.denyBlocked(), row.duplicateTickets(), row.p95LatencyMs(), row.avgCost(), detail);
    }

    /** 读取行（内部模型）。 */
    private record EvalReportRow(java.time.Instant runAt, int total, int passed, int denyTotal,
                                 int denyBlocked, int duplicateTickets, long p95LatencyMs,
                                 BigDecimal avgCost, String detail) {
    }
}
