/**
 * AIWarden 治理层（核心差异化）：Outbox · 幂等消费 · 补偿 · 对账 · 可见集与 filter 下推 · 配额预算 · 成本归因（PRD §7.3）。
 *
 * <p>依赖约束：不得直接 import {@code dev.langchain4j.*}，AI 能力一律经 aiwarden-core 的 SPI 访问。
 */
package com.aiwarden.governance;
