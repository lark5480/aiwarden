package com.aiwarden.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定时任务边界（FR-TEN-02）：{@code @Scheduled} 跑在池化线程（非虚拟线程、线程会被复用），
 * 约定按任务粒度用 {@code runWithTenant} 显式包裹；作用域退出必须还原，避免复用线程串租户。
 */
class TenantContextScheduledTaskBoundaryTest {

    /** 单线程池：模拟 @Scheduled 的池化调度线程（会被下一个任务复用）。 */
    private final ExecutorService schedulerPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void cleanup() {
        schedulerPool.close();
        TenantContext.clear();
    }

    @Test
    void scopedScheduledTask_seesTenantInside_andNextTaskOnSameThreadSeesNothing() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();

        schedulerPool.submit(() -> TenantContext.runWithTenant("tenant-sched",
                () -> seen.set(TenantContext.requireTenantId()))).get();

        assertThat(seen).hasValue("tenant-sched");
        // 同一池化线程的下一个任务：不得看到上一个任务的租户（否则就是串租户）
        assertThat(schedulerPool.submit(TenantContext::tenantId).get()).isEmpty();
    }

    @Test
    void scheduledTaskWithoutScope_cannotLocateTenant() {
        assertThatThrownBy(() -> schedulerPool.submit(TenantContext::requireTenantId).get())
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(MissingTenantContextException.class);
    }
}
