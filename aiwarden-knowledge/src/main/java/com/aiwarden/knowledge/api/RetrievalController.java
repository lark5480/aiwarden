package com.aiwarden.knowledge.api;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.RetrievalSearchRequest;
import com.aiwarden.contract.knowledge.RetrievalSearchResponse;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口（M1 最小版）：P1「删除即失效」的验收载体。
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public RetrievalSearchResponse search(@RequestBody RetrievalSearchRequest request) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        return new RetrievalSearchResponse(
                retrievalService.search(tenantId, request.query(), request.topK()));
    }
}
