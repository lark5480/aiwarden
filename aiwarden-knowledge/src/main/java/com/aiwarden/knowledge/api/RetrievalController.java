package com.aiwarden.knowledge.api;

import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.RetrievalSearchRequest;
import com.aiwarden.contract.knowledge.RetrievalSearchResponse;
import com.aiwarden.governance.visibility.VisibilitySet;
import com.aiwarden.governance.visibility.VisibilitySetCalculator;
import com.aiwarden.knowledge.retrieval.RetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口（M2/P2）：可见集在检索前计算并下推；空集 → 403（FR-PERM-01，越权拒绝留痕）。
 *
 * <p>主体来自 {@code PrincipalContext}（M2 过渡：请求头；ADR-008），缺失即 400（不回落匿名主体）。
 */
@RestController
@RequestMapping("/api/v1/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;
    private final VisibilitySetCalculator visibilitySetCalculator;

    public RetrievalController(RetrievalService retrievalService,
                               VisibilitySetCalculator visibilitySetCalculator) {
        this.retrievalService = retrievalService;
        this.visibilitySetCalculator = visibilitySetCalculator;
    }

    @PostMapping("/search")
    public RetrievalSearchResponse search(@RequestBody RetrievalSearchRequest request) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        long userId = PrincipalContext.requireUserIdAsLong();
        Long orgId = PrincipalContext.orgIdAsLong();
        VisibilitySet visible = visibilitySetCalculator.calculate(tenantId, userId, orgId, request.kbId());
        return new RetrievalSearchResponse(
                retrievalService.search(visible, request.query(), request.topK()));
    }
}
