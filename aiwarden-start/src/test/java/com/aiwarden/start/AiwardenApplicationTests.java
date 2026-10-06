package com.aiwarden.start;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 启动冒烟测试：验证 M0 工具链的关键组合能同时装载并完成上下文启动——
 * Spring Boot 4.1.1 底座 + LangChain4j 双坐标（核心件 {@code 1.21.0} 与
 * {@code spring-boot4-starter:1.21.0-beta31}）+ 全模块装配面。
 *
 * <p>本测试失败即意味着 M0「依赖共存」前置条件不成立，业务代码不应在其上继续堆叠。
 */
@SpringBootTest
class AiwardenApplicationTests {

    @Test
    void contextLoads() {
    }
}
