package com.aiwarden.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户上下文核心语义（FR-TEN-02）：作用域还原（含嵌套）、缺失即拒绝、空值参数错误。
 */
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void requireTenantId_withoutContext_throwsInsteadOfFallingBackToDefault() {
        assertThatThrownBy(TenantContext::requireTenantId)
                .isInstanceOf(MissingTenantContextException.class)
                .hasMessageContaining("拒绝执行");
    }

    @Test
    void setTenantId_rejectsNullAndBlank() {
        assertThatThrownBy(() -> TenantContext.setTenantId(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantContext.setTenantId("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runWithTenant_restoresPreviousValueAfterScope() {
        TenantContext.setTenantId("tenant-outer");

        TenantContext.runWithTenant("tenant-inner",
                () -> assertThat(TenantContext.requireTenantId()).isEqualTo("tenant-inner"));

        assertThat(TenantContext.requireTenantId()).isEqualTo("tenant-outer");
    }

    @Test
    void runWithTenant_clearsContextAfterScope_whenNoTenantWasSetBefore() {
        TenantContext.runWithTenant("tenant-a",
                () -> assertThat(TenantContext.requireTenantId()).isEqualTo("tenant-a"));

        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void callWithTenant_returnsValueAndRestoresState() throws Exception {
        String seen = TenantContext.callWithTenant("tenant-b", TenantContext::requireTenantId);

        assertThat(seen).isEqualTo("tenant-b");
        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void snapshot_capturesCurrentTenantForLaterApply() {
        TenantContext.setTenantId("tenant-c");

        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        assertThat(snapshot.tenantId()).contains("tenant-c");
    }
}
