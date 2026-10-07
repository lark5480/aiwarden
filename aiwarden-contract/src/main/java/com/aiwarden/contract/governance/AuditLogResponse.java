package com.aiwarden.contract.governance;

import java.time.OffsetDateTime;

/**
 * 审计留痕条目（FR-PERM-05）：谁在何时对哪个目标做了什么，越权与配额拒绝同样留痕。
 */
public record AuditLogResponse(long id, String actor, String action, String target,
                               String result, String detail, OffsetDateTime createdAt) {
}
