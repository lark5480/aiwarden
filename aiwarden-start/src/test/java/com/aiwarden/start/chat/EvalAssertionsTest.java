package com.aiwarden.start.chat;

import com.aiwarden.eval.EvalAssertions;
import com.aiwarden.eval.EvalCase;
import com.aiwarden.eval.EvalCaseResult;
import com.aiwarden.eval.RunOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断言引擎自证（AGENTS §4 推论「验证本身也要自证」）：每个硬断言都必须有**能被违反**的证据——
 * 否则门禁可能因条件写错而沦为永久绿灯（本项目已在 ArchUnit 选择器上踩过同款坑）。
 */
class EvalAssertionsTest {

    private static final Map<String, Long> DOC_IDS = Map.of("refund-doc", 101L, "travel-doc", 102L);

    private static EvalCase sample(String expectOutcome, List<String> forbidTools,
                                   List<String> ticketRefs, List<String> expectedDocs,
                                   Integer maxTickets, Integer minCitations, Integer replayTimes) {
        return new EvalCase("T-1", "normal", List.of("msg"), "s1", null, expectOutcome,
                forbidTools, ticketRefs, expectedDocs, maxTickets, minCitations, replayTimes);
    }

    private static RunOutcome run(String outcome, List<Long> docIds, boolean replayed) {
        return new RunOutcome(outcome, "reason", docIds, replayed, List.of(),
                12, new BigDecimal("0.001"));
    }

    private static EvalCaseResult evaluate(EvalCase evalCase, List<RunOutcome> runs,
                                           long tickets, long invocations, long denyAudits) {
        return EvalAssertions.evaluate(evalCase, runs,
                ref -> tickets, tool -> invocations, () -> denyAudits, DOC_IDS::get);
    }

    @Test
    void passingCase_yieldsNoFailures() {
        EvalCase evalCase = sample("answer", List.of(), List.of("ORDER-1"), List.of("refund-doc"),
                1, 1, 1);
        EvalCaseResult result = evaluate(evalCase, List.of(run("answer", List.of(101L), false)), 1, 0, 0);
        assertThat(result.passed()).isTrue();
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void outcomeMismatch_fails() {
        EvalCase evalCase = sample("deny", List.of(), List.of(), List.of(), 0, 0, 1);
        EvalCaseResult result = evaluate(evalCase, List.of(run("answer", List.of(), false)), 0, 0, 1);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("outcome"));
    }

    @Test
    void denyWithoutAuditTrail_fails() {
        EvalCase evalCase = sample("deny", List.of(), List.of(), List.of(), 0, 0, 1);
        EvalCaseResult result = evaluate(evalCase, List.of(run("deny", List.of(), false)), 0, 0, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("审计留痕"));
    }

    @Test
    void forbidToolViolation_fails() {
        EvalCase evalCase = sample("answer", List.of("web_search"), List.of(), List.of(), 0, 0, 1);
        EvalCaseResult result = evaluate(evalCase, List.of(run("answer", List.of(), false)), 0, 3, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("forbid_tools"));
    }

    @Test
    void ticketOverLimit_fails_andCountsExcess() {
        EvalCase evalCase = sample("answer", List.of(), List.of("ORDER-1"), List.of(), 1, 0, 1);
        EvalCaseResult result = evaluate(evalCase, List.of(run("answer", List.of(), false)), 3, 0, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("max_tickets"));
        assertThat(result.excessTickets()).as("超出 max_tickets 的额外工单数").isEqualTo(2);
    }

    @Test
    void missingExpectedDoc_fails() {
        EvalCase evalCase = sample("answer", List.of(), List.of(), List.of("refund-doc"), 0, 0, 1);
        EvalCaseResult result = evaluate(evalCase,
                List.of(run("answer", List.of(999L), false)), 0, 0, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("引用缺失") && f.contains("refund-doc"));
    }

    @Test
    void citationBelowMinimum_fails() {
        EvalCase evalCase = sample("answer", List.of(), List.of(), List.of(), 0, 2, 1);
        EvalCaseResult result = evaluate(evalCase,
                List.of(run("answer", List.of(101L), false)), 0, 0, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("引用数不足"));
    }

    @Test
    void replayWithoutIdempotencyHit_fails() {
        EvalCase evalCase = sample("answer", List.of(), List.of("ORDER-1"), List.of(), 1, 0, 2);
        EvalCaseResult result = evaluate(evalCase,
                List.of(run("answer", List.of(), false), run("answer", List.of(), false)), 1, 0, 0);
        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(f -> f.contains("重放未命中幂等"));
    }

    @Test
    void replayWithIdempotencyHit_passes() {
        EvalCase evalCase = sample("answer", List.of(), List.of("ORDER-1"), List.of(), 1, 0, 2);
        EvalCaseResult result = evaluate(evalCase,
                List.of(run("answer", List.of(), false), run("answer", List.of(), true)), 1, 0, 0);
        assertThat(result.passed()).isTrue();
    }
}
