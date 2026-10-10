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
     * 检索片段行（由编排组装：{@code [n] doc=<id> chunk=<id>}）。
     * 计数用于产出 {@code [n]} 内联引用标记；两处同仓库同版本，约定与 {@link #NO_CONTEXT_MARKER} 同级。
     */
    private static final Pattern SNIPPET_LINE =
            Pattern.compile("^\\[(\\d+)] doc=\\d+ chunk=\\d+", Pattern.MULTILINE);

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
     * 正常回答：**按 Prompt 里注入的检索片段编号产出 {@code [n]} 内联引用标记**（FR-APP-02）。
     *
     * <p>为什么替身也要产出标记：编排的 system prompt 明确要求「引用以 [序号] 标注」，
     * 而 C 端会把 {@code [n]} 渲染成可点击的引用上标（`MarkdownBlock` 的 citationCount 机制）。
     * 替身若只回一句概述，这条链路虽已实现却永远不被激活——**演示时「引用溯源」只剩侧栏、正文里点不到**，
     * 与 FR-APP-02「每条引用可点开并定位」不符。故替身按**与片段一一对应**的编号产出标记，
     * 与真实模型遵循同一句 system prompt 的行为保持一致（真实模型接入后自然替换）。
     */
    private static String answerWithCitationMarkers(ModelChatRequest request, String message) {
        int hits = countSnippets(request.systemPrompt());
        StringBuilder markers = new StringBuilder();
        for (int i = 1; i <= hits; i++) {
            markers.append('[').append(i).append(']');
        }
        return "根据知识库检索结果回答：关于「" + truncate(message, 40) + "」，已找到 "
                + hits + " 条相关依据" + markers + "，详见本次回答的引用列表。";
    }

    /** 数 Prompt 里注入的检索片段条数（编排组装格式：{@code [n] doc=… chunk=…}）。 */
    private static int countSnippets(String systemPrompt) {
        if (systemPrompt == null) {
            return 0;
        }
        Matcher matcher = SNIPPET_LINE.matcher(systemPrompt);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
