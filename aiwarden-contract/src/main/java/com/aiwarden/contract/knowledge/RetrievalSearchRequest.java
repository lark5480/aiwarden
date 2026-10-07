package com.aiwarden.contract.knowledge;

/**
 * 检索请求（M1 最小版：单路向量检索，按租户过滤；混合检索与可见集四级下推在 M2）。
 */
public record RetrievalSearchRequest(String query, Integer topK) {
}
