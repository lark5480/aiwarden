package com.aiwarden.knowledge.api;

import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.knowledge.DocumentDeleteResponse;
import com.aiwarden.contract.knowledge.DocumentStatusResponse;
import com.aiwarden.contract.knowledge.DocumentUploadResponse;
import com.aiwarden.contract.knowledge.UploadDocumentRequest;
import com.aiwarden.knowledge.document.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档接口（FR-KB-02/03/04）：上传（异步摄入）/ 删除（异步清理）/ 状态查询。
 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/knowledge-bases/{kbId}/documents")
    public ResponseEntity<DocumentUploadResponse> upload(@PathVariable long kbId,
                                                         @RequestBody UploadDocumentRequest request) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        DocumentUploadResponse response = documentService.upload(tenantId, kbId, request.name(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/documents/{docId}")
    public DocumentDeleteResponse delete(@PathVariable long docId) {
        return documentService.delete(TenantContext.requireTenantIdAsLong(), docId);
    }

    @GetMapping("/documents/{docId}/status")
    public DocumentStatusResponse status(@PathVariable long docId) {
        return documentService.status(TenantContext.requireTenantIdAsLong(), docId);
    }
}
