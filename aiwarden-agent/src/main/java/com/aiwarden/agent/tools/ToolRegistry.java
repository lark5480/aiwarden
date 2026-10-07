package com.aiwarden.agent.tools;

import com.aiwarden.core.spi.ToolExecutor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 工具注册表（装配期收集全部 {@link ToolExecutor} 实现；重名即启动失败）。
 *
 * <p>「注册」与「可见」是两件事：注册是装配事实，可见由 {@link ToolWhitelist} 决定
 * （FR-PERM-03：未在白名单内的工具不进模型请求体、不可调用）。
 */
@Component
public class ToolRegistry {

    private final Map<String, ToolExecutor> executors;

    public ToolRegistry(List<ToolExecutor> toolExecutors) {
        TreeMap<String, ToolExecutor> collected = new TreeMap<>();
        for (ToolExecutor executor : toolExecutors) {
            ToolExecutor previous = collected.put(executor.name(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "工具名装配冲突：" + executor.name() + "（" + previous.getClass().getName()
                                + " 与 " + executor.getClass().getName() + "）");
            }
        }
        this.executors = Collections.unmodifiableSortedMap(collected);
    }

    public Optional<ToolExecutor> find(String name) {
        return Optional.ofNullable(executors.get(name));
    }

    /** 已注册工具名（字典序，稳定输出）。 */
    public List<String> registeredNames() {
        return List.copyOf(executors.keySet());
    }
}
