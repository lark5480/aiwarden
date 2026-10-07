package com.aiwarden.contract.knowledge;

import java.util.List;

/**
 * 检索响应（P1 验收载体：文档删除并清理收敛后，该文档不再出现在任何命中里）。
 */
public record RetrievalSearchResponse(List<RetrievalHit> hits) {
}
