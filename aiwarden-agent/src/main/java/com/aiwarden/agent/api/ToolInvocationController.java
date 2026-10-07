package com.aiwarden.agent.api;

import com.aiwarden.agent.invocation.ToolInvocationService;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import com.aiwarden.contract.agent.ToolVisibilityResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用与可见面接口（FR-TOOL-01 / FR-PERM-03）。
 *
 * <p>调用契约（ADR-009）：
 * <ul>
 *   <li>200 + {@code replayed=false}：首次执行（SUCCEEDED / FAILED 为业务结果）；</li>
 *   <li>200 + {@code replayed=true}：重放，复用首见终态（SUCCEEDED 结果 / FAILED 首见错误）；</li>
 *   <li>403：工具不在可见面（含未注册——不区分，不泄露存在性）且留审计；</li>
 *   <li>409：同幂等键调用仍在执行中（未超租约），请稍后重放。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/agent")
public class ToolInvocationController {

    private final ToolInvocationService toolInvocationService;

    public ToolInvocationController(ToolInvocationService toolInvocationService) {
        this.toolInvocationService = toolInvocationService;
    }

    @PostMapping("/tools/{tool}/invoke")
    public ToolInvokeResponse invoke(@PathVariable String tool, @RequestBody ToolInvokeRequest request) {
        return toolInvocationService.invoke(tool, request);
    }

    /** 可见面列表：只含白名单内工具（「未在白名单内的工具不进模型请求体」的读侧）。 */
    @GetMapping("/tools")
    public ToolVisibilityResponse tools() {
        TenantContext.requireTenantIdAsLong();
        return new ToolVisibilityResponse(toolInvocationService.visibleTools());
    }

    /** 批准需确认的挂起调用（FR-TOOL-03）：从输入快照恢复执行，不丢上下文。 */
    @PostMapping("/tool-invocations/{id}/approve")
    public ToolInvokeResponse approve(@PathVariable long id) {
        return toolInvocationService.approve(id);
    }

    /** 驳回需确认的挂起调用：REJECTED 终态（不执行、不触发补偿）。 */
    @PostMapping("/tool-invocations/{id}/reject")
    public ToolInvokeResponse reject(@PathVariable long id) {
        return toolInvocationService.reject(id);
    }
}
