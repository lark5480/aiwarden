package com.aiwarden.start.tenant;

import com.aiwarden.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP 边界（FR-TEN-02）：真实 Tomcat（随机端口 + 虚拟线程）——
 * 请求头携带租户 → {@link TenantContextFilter} 建立上下文 → 控制器（虚拟线程）可见；
 * 缺头访问需要租户的接口 → 400（拒绝执行，不回落默认租户）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantContextHttpBoundaryTest {

    @Autowired
    private Environment environment;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void requestWithTenantHeader_propagatesTenantToControllerOnVirtualThread() throws Exception {
        HttpResponse<String> response = send(TenantContext.TENANT_ID_HEADER, "tenant-http");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("tenant-http|virtual=true");
    }

    @Test
    void requestWithoutTenantHeader_isRejectedWith400() throws Exception {
        HttpResponse<String> response = send(null, null);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> send(String headerName, String headerValue) throws Exception {
        int port = Integer.parseInt(environment.getProperty("local.server.port"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/test/tenant/echo"));
        if (headerName != null) {
            builder.header(headerName, headerValue);
        }
        return httpClient.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class TenantEchoConfig {

        @Bean
        TenantEchoController tenantEchoController() {
            return new TenantEchoController();
        }
    }

    /**
     * 测试专用回声接口。
     * <p>{@link TestComponent} 使其被组件扫描排除（避免与其他测试上下文重复注册），
     * 仅通过上方 {@code @Bean} 显式装配。
     */
    @RestController
    @TestComponent
    static class TenantEchoController {

        @GetMapping("/test/tenant/echo")
        String echo() {
            return TenantContext.requireTenantId() + "|virtual=" + Thread.currentThread().isVirtual();
        }
    }
}
