/**
 * AIWarden 领域核心：领域模型 + SPI（ModelClient / VectorStore / ToolExecutor / TenantResolver）。
 *
 * <p>SPI 是治理边界的正式定义：治理动作必须挂在 SPI 切面上，不侵入业务模块（PRD §7.3）。
 *
 * <p>依赖约束：
 * <ul>
 *   <li>不得依赖 governance / knowledge / agent（ArchUnit 规则 1，领域纯净）；</li>
 *   <li>不得直接 import {@code dev.langchain4j.*}（ArchUnit 规则 2）。</li>
 * </ul>
 */
package com.aiwarden.core;
