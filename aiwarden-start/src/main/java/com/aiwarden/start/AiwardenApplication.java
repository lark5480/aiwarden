package com.aiwarden.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AIWarden 启动入口（装配根）。
 *
 * <p>扫描基准包 {@code com.aiwarden}：模块化单体下，各模块组件都在同一容器内装配（PRD §7.3）。
 * <p>随 M0 推进将陆续接入：Flyway 迁移、租户上下文过滤器/拦截器等。
 */
@SpringBootApplication(scanBasePackages = "com.aiwarden")
public class AiwardenApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiwardenApplication.class, args);
    }
}
