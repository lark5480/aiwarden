package com.aiwarden.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 虚拟线程边界（FR-TEN-02 / FR-OBS-03）：虚拟线程是不可复用的一次性线程，
 * ThreadLocal 不跨线程自动继承——传播只能靠显式 capture（{@code snapshot()}）→ apply。
 *
 * <p>本测试同时冻结「不引入 TransmittableThreadLocal」的结论（ADR-003）：
 * TTL 面向池化线程复用的装饰问题，在一次性虚拟线程上没有适用场景。
 */
class TenantContextVirtualThreadPropagationTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void cleanup() {
        executor.close();
        TenantContext.clear();
    }

    @Test
    void explicitSnapshot_isVisibleInsideSpawnedVirtualThread() throws Exception {
        TenantContext.setTenantId("tenant-vt");
        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        String seen = executor.submit(() -> snapshot.callWith(
                () -> TenantContext.requireTenantId() + "|virtual=" + Thread.currentThread().isVirtual())).get();

        assertThat(seen).isEqualTo("tenant-vt|virtual=true");
    }

    @Test
    void withoutExplicitApply_virtualThreadDoesNotSeeParentTenant() {
        TenantContext.setTenantId("tenant-vt");

        assertThatThrownBy(() -> executor.submit(TenantContext::requireTenantId).get())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(MissingTenantContextException.class);
    }
}
