package com.aiwarden.knowledge.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定长切分规则：块数、顺序、内容无损（拼接还原 = strip 后原文）。
 */
class SimpleTextChunkerTest {

    private final SimpleTextChunker chunker = new SimpleTextChunker();

    @Test
    void splitsByFixedSize_inOrder() {
        String content = "0123456789".repeat(60);   // 600 字符 → 256 + 256 + 88 = 3 块

        List<String> chunks = chunker.chunk(content);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0)).hasSize(SimpleTextChunker.CHUNK_SIZE);
        assertThat(chunks.get(1)).hasSize(SimpleTextChunker.CHUNK_SIZE);
        assertThat(chunks.get(2)).hasSize(88);
        assertThat(String.join("", chunks)).isEqualTo(content);
    }

    @Test
    void exactMultiple_producesExactChunkCount() {
        String content = "x".repeat(SimpleTextChunker.CHUNK_SIZE * 2);

        assertThat(chunker.chunk(content)).hasSize(2);
    }

    @Test
    void blankContent_producesNoChunks() {
        assertThat(chunker.chunk("   ")).isEmpty();
        assertThat(chunker.chunk(null)).isEmpty();
    }

    @Test
    void chunk_doesNotModifyContent() {
        String content = "  " + "中文内容。".repeat(30) + "  ";

        List<String> chunks = chunker.chunk(content);

        assertThat(String.join("", chunks)).isEqualTo(content.strip());
    }
}
