package com.aiwarden.agent.tools;

import com.aiwarden.core.spi.ToolExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 导出数据工具（内部工具）：**默认不在白名单内**——注册事实与可见面分离的样本。
 *
 * <p>它服务于 FR-PERM-03 的越权样本：「注册但未授权」的工具调用必须被拒（403 + 审计），
 * 且不出现在可见面列表里（不进模型请求体）。
 */
@Component
public class ExportDataTool implements ToolExecutor {

    public static final String NAME = "export_data";

    private final ObjectMapper objectMapper;

    public ExportDataTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean compensable() {
        return false;
    }

    @Override
    public String execute(String inputJson) {
        // 永远不可见（不在白名单），此实现仅为注册事实的占位
        return objectMapper.writeValueAsString(Map.of("exported", 0));
    }
}
