package com.aiwarden.agent.checkpoint;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次运行的状态快照集合（FR-CKPT-02）：{@code 节点名 → 输出}。
 *
 * <p>新运行从空状态开始；续跑时由引擎按 {@code t_agent_step} 的行顺序反序列化重建。
 * 节点只能通过 {@link #require} 读前序输出——没有「内存里还留着的旧对象」这条路。
 */
public final class AgentState {

    private final Map<String, Object> outputs = new LinkedHashMap<>();

    public void put(String nodeName, Object output) {
        outputs.put(nodeName, output);
    }

    public boolean has(String nodeName) {
        return outputs.containsKey(nodeName);
    }

    /** 前序输出必须存在；缺失说明 DAG 顺序或检查点损坏，直接拒绝而不是猜。 */
    public <T> T require(String nodeName, Class<T> type) {
        Object output = outputs.get(nodeName);
        if (output == null) {
            throw new IllegalStateException("检查点缺少前序步骤输出：" + nodeName);
        }
        return type.cast(output);
    }
}
