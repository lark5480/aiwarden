package com.aiwarden.governance.visibility;

import java.util.Set;

/**
 * 可见集（ADR-008）：检索前算出的「当前主体可见什么」的不可变结果，是 filter 下推的唯一输入。
 *
 * <p>四级语义在 {@link VisibilitySetCalculator} 中已折叠为三组载体：
 * <ul>
 *   <li>{@code kbIds}：可见知识库集合（已含 tenant / org / kbACL 三级的计算结果）；</li>
 *   <li>{@code allowDocIds}：文档级例外授权（即使所属 KB 不可见也可命中）；</li>
 *   <li>{@code denyDocIds}：文档级屏蔽（即使所属 KB 可见也必须排除）。</li>
 * </ul>
 *
 * <p><b>空集语义</b>：{@link #isEmpty()} 为真时检索必须在计算阶段直接拒绝（FR-PERM-01），
 * 不进入向量查询——「可见集为空」与「查询无命中」是两件事，前者是越权拒绝（403），
 * 后者是正常的空结果（200）。
 */
public record VisibilitySet(long tenantId, Set<Long> kbIds, Set<Long> allowDocIds, Set<Long> denyDocIds) {

    /** 可见集为空：既没有任何可见 KB，也没有文档级例外授权。 */
    public boolean isEmpty() {
        return kbIds.isEmpty() && allowDocIds.isEmpty();
    }
}
