package com.aiwarden.knowledge.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 简单定长切分（M1 演示替身）：每 {@value #CHUNK_SIZE} 字符一块、无重叠。
 *
 * <p>不做语义切分 / 重叠策略——检索质量不在承诺范围（B1 边界）；
 * FR-ING-02 的 DAG 引擎与更优切分策略为后续独立切片，节点替换不影响本类契约。
 */
@Component
public class SimpleTextChunker {

    static final int CHUNK_SIZE = 256;

    /** 切块：按定长顺序切分；空白内容产出 0 块（文档仍可进入 INDEXED 终态）。 */
    public List<String> chunk(String content) {
        String text = content == null ? "" : content.strip();
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += CHUNK_SIZE) {
            chunks.add(text.substring(start, Math.min(text.length(), start + CHUNK_SIZE)));
        }
        return chunks;
    }
}
