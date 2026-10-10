package com.aiwarden.core.spi;

import java.util.Map;

/**
 * 可见面工具规格（模型请求体用；与 MCP 暴露的 schema 是两个视角——
 * 此处不含 businessKey / sessionId：幂等键由治理层计算，模型不该看到也不该填）。
 */
public record ModelToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
