package com.aiwarden.agent.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具可见面白名单（FR-PERM-03 / ADR-009 决策 5）：装配期从配置定死，会话级可见面。
 *
 * <p>两道门：① 配置黑名单 {@code aiwarden.agent.tools.allowed} 之外的不可见；
 * ② 代码级 {@link #EXCLUDED}（显式禁 {@code web_search} / {@code web_fetch}）——
 * 即使配置误把它们加进 allowed，也不可见（显式禁不可被配置绕过）。
 */
@Component
public class ToolWhitelist {

    /** 显式禁名单（FR-PERM-03 原文；装配期定死，配置无法解开）。 */
    static final Set<String> EXCLUDED = Set.of("web_search", "web_fetch");

    private final Set<String> allowed;

    public ToolWhitelist(@Value("${aiwarden.agent.tools.allowed:}") String allowedCsv) {
        this.allowed = Arrays.stream(allowedCsv.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 工具是否在可见面内（调用与列表共用同一判定，保证「不可调用 = 不可见」）。 */
    public boolean visible(String toolName) {
        return !EXCLUDED.contains(toolName) && allowed.contains(toolName);
    }
}
