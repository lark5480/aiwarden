package com.aiwarden.knowledge.api;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.CreateKnowledgeBaseRequest;
import com.aiwarden.contract.knowledge.KnowledgeBaseResponse;
import com.aiwarden.knowledge.document.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库接口（FR-KB-01）：租户上下文由 TenantContextFilter 建立；缺失时由统一异常处理转 400。
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(@RequestBody CreateKnowledgeBaseRequest request) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        KnowledgeBaseResponse created = knowledgeBaseService.create(tenantId, request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<KnowledgeBaseResponse> list() {
        return knowledgeBaseService.list(TenantContext.requireTenantIdAsLong());
    }
}
