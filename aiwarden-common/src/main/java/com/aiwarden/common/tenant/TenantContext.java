package com.aiwarden.common.tenant;

import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * 租户上下文：请求 / 消息 / 任务处理期间的当前租户标识载体（FR-TEN-02）。
 *
 * <p><b>传播模型</b>：显式 capture → apply，不做隐式继承。四类边界统一走本类：
 * <ul>
 *   <li><b>HTTP</b>：{@code TenantContextFilter}（aiwarden-start）从请求头建立上下文，请求结束清理；</li>
 *   <li><b>虚拟线程</b>：虚拟线程是不可复用的一次性线程，无跨线程自动继承语义，
 *       用 {@link #snapshot()} 在父线程 capture、在子线程 apply；</li>
 *   <li><b>Kafka 消费</b>：租户经消息头跨进程传递——生产端 {@link #writeToCarrier}，消费端 {@link #runWithCarrier}；</li>
 *   <li><b>定时任务</b>：池化调度线程按任务粒度用 {@link #runWithTenant} 包裹，作用域结束自动还原。</li>
 * </ul>
 *
 * <p><b>为什么不用 TransmittableThreadLocal（FR-OBS-03 结论）</b>：TTL 解决的是「池化线程复用导致
 * 装饰丢失」的问题，而虚拟线程是不可复用的一次性线程，TTL 的 decorate 语义不适用。两类线程形态
 * 统一走显式 capture/apply，一条规则覆盖、语义简单且可测——决策与单测证据见 DECISIONS.md ADR-003。
 *
 * <p><b>缺失即拒绝</b>：上下文缺失时 {@link #requireTenantId()} 抛
 * {@link MissingTenantContextException}，<b>不回落默认租户</b>（FR-TEN-02）。
 */
public final class TenantContext {

    /** 跨边界传递租户标识的统一头名（HTTP 请求头与 Kafka 消息头共用）。 */
    public static final String TENANT_ID_HEADER = "X-Aiwarden-Tenant-Id";

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    /** 设置当前线程租户标识；空值即参数错误（不静默）。 */
    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId 不能为空");
        }
        CURRENT_TENANT.set(tenantId);
    }

    /** 当前租户标识（可能为空）。 */
    public static Optional<String> tenantId() {
        return Optional.ofNullable(CURRENT_TENANT.get());
    }

    /** 当前租户标识；缺失时拒绝执行（不回落默认租户，FR-TEN-02）。 */
    public static String requireTenantId() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null) {
            throw new MissingTenantContextException(
                    "租户上下文缺失：拒绝执行（不回落默认租户，FR-TEN-02）");
        }
        return tenantId;
    }

    /** 当前租户标识（数值形式，业务表 tenant_id 使用）；缺失或非数值同样拒绝执行。 */
    public static long requireTenantIdAsLong() {
        String tenantId = requireTenantId();
        try {
            return Long.parseLong(tenantId);
        } catch (NumberFormatException e) {
            throw new MissingTenantContextException("租户标识不是合法数值，拒绝执行：" + tenantId);
        }
    }

    /** 清理当前线程租户标识；请求 / 任务结束必须调用，避免池化线程串租户。 */
    public static void clear() {
        CURRENT_TENANT.remove();
    }

    /** 在指定租户作用域内执行；退出时还原进入前状态（支持嵌套）。 */
    public static void runWithTenant(String tenantId, Runnable action) {
        Optional<String> previous = tenantId();
        setTenantId(tenantId);
        try {
            action.run();
        } finally {
            apply(previous);
        }
    }

    /** {@link #runWithTenant} 的带回值版本。 */
    public static <T> T callWithTenant(String tenantId, Callable<T> action) throws Exception {
        Optional<String> previous = tenantId();
        setTenantId(tenantId);
        try {
            return action.call();
        } finally {
            apply(previous);
        }
    }

    /** capture：快照当前线程租户标识，供目标线程（虚拟线程 / 执行器）apply。 */
    public static Snapshot snapshot() {
        return new Snapshot(tenantId());
    }

    /** 生产端：把当前租户写入载体（如 Kafka 消息头）；上下文缺失即拒绝。 */
    public static void writeToCarrier(TenantContextCarrier carrier) {
        carrier.putHeader(TENANT_ID_HEADER, requireTenantId());
    }

    /** 消费端：从载体读取租户并建立上下文执行；消息缺租户头即拒绝（FR-TEN-02）。 */
    public static void runWithCarrier(TenantContextCarrier carrier, Runnable action) {
        runWithTenant(requireTenantIdFrom(carrier), action);
    }

    /** {@link #runWithCarrier} 的带回值版本。 */
    public static <T> T callWithCarrier(TenantContextCarrier carrier, Callable<T> action) throws Exception {
        return callWithTenant(requireTenantIdFrom(carrier), action);
    }

    private static String requireTenantIdFrom(TenantContextCarrier carrier) {
        return carrier.getHeader(TENANT_ID_HEADER)
                .filter(id -> !id.isBlank())
                .orElseThrow(() -> new MissingTenantContextException(
                        "消息缺少租户头 " + TENANT_ID_HEADER + "：拒绝执行（FR-TEN-02）"));
    }

    private static void apply(Optional<String> tenantId) {
        if (tenantId.isPresent()) {
            CURRENT_TENANT.set(tenantId.get());
        } else {
            CURRENT_TENANT.remove();
        }
    }

    /**
     * 租户上下文快照：capture（当前线程）→ apply（任意目标线程）。
     *
     * <p>典型用法（虚拟线程 / 执行器）：
     * <pre>{@code
     * var snapshot = TenantContext.snapshot();
     * executor.submit(() -> snapshot.runWith(() -> doWork()));
     * }</pre>
     *
     * <p><b>注意</b>：快照为空（capture 时无租户）时不建立上下文——目标线程内调用
     * {@link #requireTenantId()} 仍会拒绝，符合「缺失即拒绝」语义。
     */
    public record Snapshot(Optional<String> tenantId) {

        /** 在快照对应的租户作用域内执行；退出时还原目标线程进入前状态（支持嵌套）。 */
        public void runWith(Runnable action) {
            Optional<String> previous = TenantContext.tenantId();
            TenantContext.apply(tenantId);
            try {
                action.run();
            } finally {
                TenantContext.apply(previous);
            }
        }

        /** {@link #runWith(Runnable)} 的带回值版本。 */
        public <T> T callWith(Callable<T> action) throws Exception {
            Optional<String> previous = TenantContext.tenantId();
            TenantContext.apply(tenantId);
            try {
                return action.call();
            } finally {
                TenantContext.apply(previous);
            }
        }
    }
}
