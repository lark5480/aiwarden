package com.aiwarden.agent.api;

import com.aiwarden.agent.compensation.CompensationService;
import com.aiwarden.contract.agent.CompensationRunRequest;
import com.aiwarden.contract.agent.CompensationRunResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 补偿执行接口（FR-TOOL-02）：显式触发指定会话的 PENDING 补偿计划（逆序回滚）。
 */
@RestController
@RequestMapping("/api/v1/agent")
public class CompensationController {

    private final CompensationService compensationService;

    public CompensationController(CompensationService compensationService) {
        this.compensationService = compensationService;
    }

    @PostMapping("/compensations/run")
    public CompensationRunResponse run(@RequestBody CompensationRunRequest request) {
        return compensationService.run(request.sessionId());
    }
}
