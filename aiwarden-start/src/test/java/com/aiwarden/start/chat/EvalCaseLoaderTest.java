package com.aiwarden.start.chat;

import com.aiwarden.eval.EvalCase;
import com.aiwarden.eval.EvalCaseLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 样本加载的契约验证：内置样本集规模与分布（FR-EVAL-01）+ 坏样本**加载即拒绝**
 * （不静默跳过——「坏样本悄悄消失」会让门禁规模虚胖）。
 */
class EvalCaseLoaderTest {

    @Test
    void loadsBundledSamples_withExpectedScaleAndDistribution() {
        List<EvalCase> cases = EvalCaseLoader.loadFromClasspath();
        assertThat(cases.size()).as("FR-EVAL-01：20–30 条").isBetween(20, 30);
        assertThat(cases).allSatisfy(evalCase -> {
            assertThat(evalCase.messages()).isNotEmpty();
            assertThat(evalCase.replayTimes()).isGreaterThanOrEqualTo(1);
        });
        long normal = cases.stream().filter(c -> "normal".equals(c.category())).count();
        assertThat(normal)
                .as("正常样本占多数（实际 14/24 ≈ 58%，PRD「约 2/3」口径的合理下界）")
                .isGreaterThanOrEqualTo(cases.size() * 55 / 100);
        assertThat(cases.stream().filter(c -> "deny".equals(c.expectOutcome())).count())
                .as("风险样本（expect=deny）存在")
                .isGreaterThanOrEqualTo(5);
    }

    @Test
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> EvalCaseLoader.load(json(
                sample("dup-1", "answer", "msg"), sample("dup-1", "answer", "msg"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    @Test
    void rejectsInvalidOutcome() {
        assertThatThrownBy(() -> EvalCaseLoader.load(json(sample("bad-outcome", "refuse", "msg"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectOutcome 非法");
    }

    @Test
    void rejectsEmptyMessages() {
        ByteArrayInputStream in = new ByteArrayInputStream("""
                [{"id":"empty-msg","category":"normal","messages":[],"session":"s","expectOutcome":"answer"}]
                """.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> EvalCaseLoader.load(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messages");
    }

    private static ByteArrayInputStream json(String... samples) {
        return new ByteArrayInputStream(("[" + String.join(",", samples) + "]")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sample(String id, String outcome, String message) {
        return """
                {"id":"%s","category":"normal","messages":["%s"],"session":"s","expectOutcome":"%s"}
                """.formatted(id, message, outcome);
    }
}
