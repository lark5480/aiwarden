/**
 * AIWarden Agent 模块：确定性 DAG 编排 + Checkpoint + 工具执行（幂等 / 补偿 / 二态审批）（PRD §7.3）。
 *
 * <p>依赖约束：不得直接 import {@code dev.langchain4j.*}，模型调用经 aiwarden-core 的 SPI 访问。
 */
package com.aiwarden.agent;
