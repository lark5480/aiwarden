package com.aiwarden.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AIWarden 启动入口（装配根）。
 *
 * <p>扫描基准包 {@code com.aiwarden}：模块化单体下，各模块组件都在同一容器内装配（PRD §7.3）。
 * <p>已接入：Flyway 迁移、租户上下文过滤、Kafka 消费、调度（Outbox 发布器等定时任务）。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.aiwarden")
public class AiwardenApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiwardenApplication.class, args);
    }
}
