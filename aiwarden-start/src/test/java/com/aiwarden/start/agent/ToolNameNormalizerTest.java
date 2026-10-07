package com.aiwarden.start.agent;

import com.aiwarden.agent.tools.ToolNameNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M2 切片④ 工具名归一化单测（FR-TOOL-05）：非法字符换下划线 + 撞名加哈希后缀 + 稳定可重复。
 */
class ToolNameNormalizerTest {

    @Test
    void illegalCharacters_replacedWithUnderscore() {
        assertThat(ToolNameNormalizer.normalize("web.search")).isEqualTo("web_search");
        assertThat(ToolNameNormalizer.normalize("search??")).isEqualTo("search__");
        assertThat(ToolNameNormalizer.normalize("create_ticket")).isEqualTo("create_ticket");
        assertThat(ToolNameNormalizer.normalize("v1.2工具")).isEqualTo("v1_2__");
    }

    @Test
    void collision_getsStableHashSuffix_fromRawName() {
        // 撞名构造：`.` 与空格都归一为下划线（连字符是合法字符，不参与撞名）
        Map<String, String> mapping = ToolNameNormalizer.normalizeAll(List.of("a.b", "a b", "plain_tool"));

        // 不撞名的不变
        assertThat(mapping.get("plain_tool")).isEqualTo("plain_tool");
        // 撞名组：都以下划线 + 8 位哈希区分，且两个结果不同
        assertThat(mapping.get("a.b")).startsWith("a_b_").hasSize("a_b_".length() + 8);
        assertThat(mapping.get("a b")).startsWith("a_b_").hasSize("a_b_".length() + 8);
        assertThat(mapping.get("a.b")).isNotEqualTo(mapping.get("a b"));

        // 稳定可重复：同输入同输出（装配期与执行期看到一致的映射）
        assertThat(ToolNameNormalizer.normalizeAll(List.of("a.b", "a b", "plain_tool"))).isEqualTo(mapping);
    }

    @Test
    void overlongName_truncatedWithHash_andDistinct() {
        String prefix = "x".repeat(80);
        Map<String, String> mapping = ToolNameNormalizer.normalizeAll(List.of(prefix + "A", prefix + "B"));

        assertThat(mapping.values()).allSatisfy(name ->
                assertThat(name).hasSizeLessThanOrEqualTo(64));
        assertThat(mapping.get(prefix + "A")).isNotEqualTo(mapping.get(prefix + "B"));
    }

    @Test
    void blankName_rejected() {
        assertThatThrownBy(() -> ToolNameNormalizer.normalize(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
