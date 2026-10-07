package com.aiwarden.agent.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具级二态审批开关（FR-TOOL-03 / ADR-009）：{@code 无需确认}（默认）/ {@code 需确认}。
 *
 * <p>开关粒度到工具、**装配期定死**（配置项 {@code aiwarden.agent.tools.require-approval}，
 * 逗号分隔），不提供运行时切换 API——「一律拒绝」态已按 PRD 裁决砍除，二态就够。
 *
 * <p>需确认的工具在被调用时挂起（{@code PENDING_APPROVAL}），等待人工批准（执行）
 * 或驳回（终态）；挂起上下文 = 已落库的输入快照，批准后照常执行、不丢上下文。
 */
@Component
public class ToolApprovalPolicy {

    private final Set<String> requireApproval;

    public ToolApprovalPolicy(@Value("${aiwarden.agent.tools.require-approval:}") String requireApprovalCsv) {
        this.requireApproval = Arrays.stream(requireApprovalCsv.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean requiresApproval(String toolName) {
        return requireApproval.contains(toolName);
    }
}
