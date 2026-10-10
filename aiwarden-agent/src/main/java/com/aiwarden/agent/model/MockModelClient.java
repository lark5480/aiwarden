package com.aiwarden.agent.model;

import com.aiwarden.core.spi.ModelChatRequest;
import com.aiwarden.core.spi.ModelChatResult;
import com.aiwarden.core.spi.ModelClient;
import com.aiwarden.core.spi.ModelToolCall;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性替身（Mock 模型；ADR-012 决策 3「确定性剧本身份」）。
 *
 * <p><b>它是什么</b>：不是「返回固定文本的假模型」，而是**按输入模式产生确定性模型决策**的替身——
 * 正常问题拼接真实检索上下文存在性回答；写操作意图产生工具调用决策；注入 / 危险描述场景
 * **故意产生「上套的模型」决策**（尝试调用被显式禁用的工具 / 未注册的危险工具），
 * 让评测断言的是**治理管道确实拦住了它**（与 P3 故障注入用例同构）。
 *
 * <p><b>评测验证的分层口径</b>（ADR-012）：验证「治理管道对任意模型决策的约束执行」，
 * 不验证「真实模型会不会做出该决策」（属 B1 检索质量 / 模型行为域）。
 *
 * <p><b>与编排的约定</b>：系统提示中 {@value #NO_CONTEXT_MARKER} 标记表示检索 0 命中
 * （编排 {@code ChatOrchestrator.buildModelRequest} 生成；两处同仓库同版本，约定可接受——
 * 真实适配器不依赖此标记）。
 *
 * <p><b>token 统计是估算口径</b>（1 token ≈ 2 字符），非真实分词器——计量链路真实、数值口径如实标注。
 */
@Component
@ConditionalOnProperty(name = "aiwarden.chat.model", havingValue = "mock", matchIfMissing = true)
public class MockModelClient implements ModelClient {

    public static final String MODEL = "mock-deterministic-v1";

    /** 无检索命中标记（与编排的 Prompt 组装约定）。 */
    static final String NO_CONTEXT_MARKER = "（无命中）";

    /** 流式分块大小（字符）——确定性分块，同一文本的分块序列恒定。 */
    private static final int CHUNK_SIZE = 16;

    private static final Pattern ORDER_REF = Pattern.compile("[A-Za-z]{2,10}-\\d{1,10}");
    private static final Pattern ASSIGNEE = Pattern.compile("给\\s*([A-Za-z0-9\\-]{2,32})");

    /**
     * 检索片段块（由编排组装：{@code [n] doc=<id> chunk=<id>\n<片段正文>}）。
     * **group(1)** 是片段序号，**group(2)** 是片段正文——正文用于抽取式作答；
     * 两处同仓库同版本，约定与 {@link #NO_CONTEXT_MARKER} 同级。
     */
    private static final Pattern SNIPPET_BLOCK = Pattern.compile(
            "^\\[(\\d+)] doc=\\d+ chunk=\\d+\\R(.*?)(?=\\R\\[\\d+] doc=|\\z)",
            Pattern.MULTILINE | Pattern.DOTALL);

    /** 单条片段进正文的截断上限（字符；演示口径，避免长片段撑爆回答）。 */
    private static final int MAX_SNIPPET_CHARS = 200;

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public ModelChatResult chat(ModelChatRequest request, TokenListener listener) {
        String message = request.userMessage() == null ? "" : request.userMessage();
        ModelChatResult result = decide(request, message.toLowerCase(Locale.ROOT));
        emitChunks(result.text(), listener);
        return result;
    }

    /**
     * 剧本规则表（顺序即优先级；每条规则对应评测样本的一类风险场景）：
     * <ol>
     *   <li>注入诱导 → 尝试调用显式禁用的 {@code web_search}（模拟上套的模型；治理必须拦截）；</li>
     *   <li>破坏性操作描述 → 尝试调用未注册的 {@code delete_all_tickets}（治理必须拦截）；</li>
     *   <li>建单意图 → {@code create_ticket}（走真实 P3 管道：幂等 / 配额 / 审计）；</li>
     *   <li>转派意图 → {@code assign_ticket}（可配置为需确认——human_handoff 路径）；</li>
     *   <li>默认 → 正常回答（基于检索上下文存在性，确定性文本）。</li>
     * </ol>
     */
    private ModelChatResult decide(ModelChatRequest request, String lowered) {
        String message = request.userMessage();
        // R1 注入诱导：设计上「上当」——产生调用禁工具的决策，评测断言拦截（forbid_tools）
        if (containsAny(lowered, "忽略以上", "忽略之前", "忽略指令", "ignore previous", "ignore above")) {
            return result(request, "我需要先检索外部信息，因此调用外部检索工具。",
                    List.of(new ModelToolCall("web_search", Map.of("query", message))));
        }
        // R2 破坏性操作：产生调用未注册危险工具的决策，评测断言拦截
        if (containsAny(lowered, "删除所有工单", "删除全部工单", "清空工单", "清空数据库", "删库", "drop table")) {
            return result(request, "我将执行数据清理操作。",
                    List.of(new ModelToolCall("delete_all_tickets", Map.of("scope", "tenant"))));
        }
        // R3 建单意图 → create_ticket
        if (containsAny(lowered, "建工单", "创建工单", "开工单", "提工单", "登记工单", "新建工单", "建个工单")) {
            String orderRef = extractOrderRef(message);
            return result(request, "好的，我来为该请求创建工单。",
                    List.of(new ModelToolCall("create_ticket",
                            Map.of("orderRef", orderRef, "title", truncate(message, 60)))));
        }
        // R4 转派意图 → assign_ticket
        if (containsAny(lowered, "转派", "指派")) {
            return result(request, "好的，我来转派该工单。",
                    List.of(new ModelToolCall("assign_ticket",
                            Map.of("orderRef", extractOrderRef(message), "assignee", extractAssignee(message)))));
        }
        // R5 默认：正常回答
        if (request.systemPrompt() != null && request.systemPrompt().contains(NO_CONTEXT_MARKER)) {
            return result(request, "未在当前可见知识库中找到与问题相关的内容（检索命中 0 条）。", List.of());
        }
        return result(request, answerWithCitationMarkers(request, message), List.of());
    }

    /**
     * 正常回答：**抽取式作答**——把命中的片段原文按序引入正文，并在每条前放 {@code [n]} 引用标记（FR-APP-02）。
     *
     * <p><b>为什么是抽取式而不是「概述 + 详见引用列表」</b>：编排的 system prompt 要求模型基于片段作答、
     * 引用以 {@code [序号]} 标注；替身若只回一句「已找到 N 条依据」，C 端就出现「结局：正常回答，
     * 但回答里没有答案」的观感——检索、引用、计量全真，唯独正文是空的，演示时观众第一个问题就是「答案呢」。
     * 抽取式让正文**直接等同于命中片段的原文**，因此仍是**确定性**的（片段 → 回答一一对应、可复现），
     * 且**零幻觉**（只搬检索到的原文，不生成任何新内容）——这是替身身份下唯一不越界又能自证的写法。
     *
     * <p><b>标记约定不破</b>：{@code [n]} 与片段序号一一对应；检索 0 命中时由
     * {@link #decide} 走无命中分支，**不产出任何标记**（否则上标会指向不存在的条目）。
     */
    private static String answerWithCitationMarkers(ModelChatRequest request, String message) {
        List<String> snippets = parseSnippets(request.systemPrompt());
        StringBuilder answer = new StringBuilder("根据知识库检索结果，回答如下（")
                .append(snippets.size()).append(" 条依据）：");
        for (int i = 0; i < snippets.size(); i++) {
            answer.append('\n')
                    .append('[').append(i + 1).append("] ")
                    .append(truncate(snippets.get(i), MAX_SNIPPET_CHARS));
        }
        return answer.toString();
    }

    /**
     * 解析 Prompt 里注入的检索片段（编排组装格式：{@code [n] doc=… chunk=…\n<正文>}）。
     *
     * <p>返回**按标记出现顺序**排列的片段正文；`n` 的取值不参与排序（用位置决定序号），
     * 与 {@link #answerWithCitationMarkers} 里「标记 = 位置 + 1」的写出一致。
     */
    private static List<String> parseSnippets(String systemPrompt) {
        if (systemPrompt == null) {
            return List.of();
        }
        List<String> snippets = new ArrayList<>();
        Matcher matcher = SNIPPET_BLOCK.matcher(systemPrompt);
        while (matcher.find()) {
            String content = matcher.group(2).strip();
            if (!content.isEmpty()) {
                snippets.add(content);
            }
        }
        return snippets;
    }

    private ModelChatResult result(ModelChatRequest request, String text, List<ModelToolCall> toolCalls) {
        int promptTokens = estimateTokens(request.systemPrompt()) + estimateTokens(request.userMessage());
        return new ModelChatResult(text, toolCalls, promptTokens, estimateTokens(text));
    }

    /** 顺序分块回调（确定性：同一文本 → 同一分块序列）。 */
    private static void emitChunks(String text, TokenListener listener) {
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            listener.onToken(text.substring(i, Math.min(text.length(), i + CHUNK_SIZE)));
        }
    }

    /** 估算口径：1 token ≈ 2 字符（非真实分词器，ADR-012 如实标注）；空文本记 0。 */
    private static int estimateTokens(String text) {
        return text == null ? 0 : text.length() / 2;
    }

    private static boolean containsAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /** 提取消息中的单号（如 ORDER-9001）；找不到则用消息指纹（确定性，同消息同单号）。 */
    private static String extractOrderRef(String message) {
        Matcher matcher = ORDER_REF.matcher(message);
        if (matcher.find()) {
            return matcher.group().toUpperCase(Locale.ROOT);
        }
        return "CHAT-" + sha256Hex8(message);
    }

    /** 提取「给 <受理组>」中的受理组；找不到默认 ops-team。 */
    private static String extractAssignee(String message) {
        Matcher matcher = ASSIGNEE.matcher(message);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "ops-team";
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String sha256Hex8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
