package com.aiwarden.eval;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 样本加载器（数据化样本：改样本不改代码；FR-EVAL-04 的版本回归以样本为比较基准）。
 *
 * <p><b>加载即校验（不静默）</b>：id 唯一、消息非空、outcome 合法、replayTimes ≥ 1——
 * 坏样本在加载期显式拒绝，而不是在门禁里变成静默跳过。
 */
public final class EvalCaseLoader {

    private static final String DEFAULT_RESOURCE = "/eval/cases.json";
    private static final Set<String> VALID_OUTCOMES = Set.of("answer", "deny", "human_handoff");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private EvalCaseLoader() {
    }

    public static List<EvalCase> loadFromClasspath() {
        return loadFromClasspath(DEFAULT_RESOURCE);
    }

    public static List<EvalCase> loadFromClasspath(String resource) {
        try (InputStream in = EvalCaseLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("评测样本资源缺失：" + resource);
            }
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("评测样本读取失败：" + resource, e);
        }
    }

    public static List<EvalCase> load(InputStream in) {
        List<EvalCase> cases = MAPPER.readValue(in, new TypeReference<List<EvalCase>>() {
        });
        validate(cases);
        return List.copyOf(cases);
    }

    private static void validate(List<EvalCase> cases) {
        Set<String> ids = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (evalCase.id() == null || evalCase.id().isBlank()) {
                throw new IllegalArgumentException("样本 id 不能为空");
            }
            if (!ids.add(evalCase.id())) {
                throw new IllegalArgumentException("样本 id 重复：" + evalCase.id());
            }
            if (evalCase.messages() == null || evalCase.messages().isEmpty()
                    || evalCase.messages().stream().anyMatch(m -> m == null || m.isBlank())) {
                throw new IllegalArgumentException("样本 messages 不能为空：" + evalCase.id());
            }
            if (evalCase.session() == null || evalCase.session().isBlank()) {
                throw new IllegalArgumentException("样本 session 不能为空：" + evalCase.id());
            }
            if (!VALID_OUTCOMES.contains(evalCase.expectOutcome())) {
                throw new IllegalArgumentException("样本 expectOutcome 非法（%s）：%s"
                        .formatted(evalCase.expectOutcome(), evalCase.id()));
            }
            if (evalCase.replayTimes() < 1) {
                throw new IllegalArgumentException("样本 replayTimes 必须 ≥1：" + evalCase.id());
            }
        }
    }
}
