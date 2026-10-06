/**
 * AIWarden 评测模块：越权样本 + 评测样本进 {@code mvn verify} 门禁（PRD §5.9）。
 *
 * <p>依赖约束：不得直接 import {@code dev.langchain4j.*}，仅通过 SPI 与被测系统交互。
 */
package com.aiwarden.eval;
