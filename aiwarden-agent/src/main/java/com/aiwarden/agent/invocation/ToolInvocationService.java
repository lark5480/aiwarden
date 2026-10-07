package com.aiwarden.agent.invocation;

import com.aiwarden.agent.compensation.CompensationService;
import com.aiwarden.agent.tools.ToolApprovalPolicy;
import com.aiwarden.agent.tools.ToolRegistry;
import com.aiwarden.agent.tools.ToolWhitelist;
import com.aiwarden.common.exception.ConflictException;
import com.aiwarden.common.exception.RateLimitExceededException;
import com.aiwarden.common.principal.PrincipalContext;
import com.aiwarden.common.tenant.TenantContext;
import com.aiwarden.contract.agent.ToolInvokeRequest;
import com.aiwarden.contract.agent.ToolInvokeResponse;
import com.aiwarden.core.event.MeteringEventTypes;
import com.aiwarden.core.spi.ToolExecutionException;
import com.aiwarden.core.spi.ToolExecutor;
import com.aiwarden.governance.audit.AuditLogWriter;
import com.aiwarden.governance.metering.CallMeteringPayload;
import com.aiwarden.governance.outbox.OutboxWriter;
import com.aiwarden.governance.quota.QuotaGuard;
import com.aiwarden.governance.quota.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具调用入口（P3）：幂等键仲裁（FR-TOOL-01）+ 重放仲裁矩阵（ADR-009 决策 2）+
 * 可见面校验（FR-PERM-03）+ 计量与补偿计划挂接——治理管道全在此，工具实现只写业务副作用。
 *
 * <p><b>重放语义</b>：SUCCEEDED / FAILED 重放返回首见终态（不重执行）；PROCESSING 未超租约 409；
 * 超租约 CAS 重抢后执行（「不确定可重试，确定失败不重试」）。
 */
@Service
public class ToolInvocationService {

    /**
     * 幂等键成分上限（FR-TOOL-01）：键 = businessKey + ':' + sessionId + ':' + 16 hex，
     * 三者上限之和 64 + 64 + 16 + 2 = 146，远小于 V7 的列宽 256——留出余量，
     * 使「调整上限」不会再撞列宽。
     */
    static final int MAX_BUSINESS_KEY_LENGTH = 64;
    static final int MAX_SESSION_ID_LENGTH = 64;
    static final int MAX_STEP_NO = 100_000;

    private final ToolRegistry toolRegistry;
    private final ToolWhitelist toolWhitelist;
    private final ToolApprovalPolicy approvalPolicy;
    private final ToolInvocationStore store;
    private final CompensationService compensationService;
    private final AuditLogWriter auditLogWriter;
    private final OutboxWriter outboxWriter;
    private final RateLimiter rateLimiter;
    private final QuotaGuard quotaGuard;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;
    private final long processingLeaseSeconds;

    public ToolInvocationService(ToolRegistry toolRegistry,
                                 ToolWhitelist toolWhitelist,
                                 ToolApprovalPolicy approvalPolicy,
                                 ToolInvocationStore store,
                                 CompensationService compensationService,
                                 AuditLogWriter auditLogWriter,
                                 OutboxWriter outboxWriter,
                                 RateLimiter rateLimiter,
                                 QuotaGuard quotaGuard,
                                 MeterRegistry meterRegistry,
                                 ObjectMapper objectMapper,
                                 @Value("${aiwarden.agent.tool-invocation.processing-lease-seconds:300}")
                                 long processingLeaseSeconds) {
        this.toolRegistry = toolRegistry;
        this.toolWhitelist = toolWhitelist;
        this.approvalPolicy = approvalPolicy;
        this.store = store;
        this.compensationService = compensationService;
        this.auditLogWriter = auditLogWriter;
        this.outboxWriter = outboxWriter;
        this.rateLimiter = rateLimiter;
        this.quotaGuard = quotaGuard;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.processingLeaseSeconds = processingLeaseSeconds;
    }

    @Transactional
    public ToolInvokeResponse invoke(String toolName, ToolInvokeRequest request) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        String actor = PrincipalContext.requireUserId();
        String businessKey = requireText(request.businessKey(), "businessKey");
        String sessionId = requireText(request.sessionId(), "sessionId");
        if (request.stepNo() == null) {
            throw new IllegalArgumentException("stepNo 不能为空");
        }
        if (request.input() == null || request.input().isEmpty()) {
            throw new IllegalArgumentException("input 不能为空");
        }
        // 幂等键长度必须**在入口处**受控（越界 400，而不是撞 DB 列宽后 500）：
        // 键 = businessKey + ':' + sessionId + ':' + 16 hex，三处上限之和远小于列宽（V7 放宽到 256）。
        requireLength(businessKey, MAX_BUSINESS_KEY_LENGTH, "businessKey");
        requireLength(sessionId, MAX_SESSION_ID_LENGTH, "sessionId");
        int stepNo = requireStepNo(request.stepNo());

        ToolExecutor tool = requireVisible(toolName, tenantId, actor);
        // 租户+工具双维度限流（FR-COST-07）：先于一切重活（超限 429 + Retry-After）
        RateLimiter.Outcome rate = rateLimiter.tryAcquire(tenantId, tool.name());
        if (!rate.allowed()) {
            meterRegistry.counter("aiwarden_rate_limit_reject_total").increment();
            throw new RateLimitExceededException(
                    "租户请求频率超限（租户+工具维度）：请稍后重试", rateLimiter.windowSeconds());
        }
        String inputJson = canonicalJson(request.input());
        String idemKey = ToolInvocationKeys.of(businessKey, sessionId, stepNo, toolName, inputJson);

        Long claimedId = store.tryClaim(tenantId, idemKey, sessionId, stepNo, toolName, inputJson);
        if (claimedId == null) {
            ToolInvocationStore.InvocationRow existing = store.findByKey(tenantId, idemKey)
                    .orElseThrow(() -> new IllegalStateException("幂等键竞态：记录缺失 idemKey=" + idemKey));
            if ("SUCCEEDED".equals(existing.status()) || "FAILED".equals(existing.status())
                    || "PENDING_APPROVAL".equals(existing.status()) || "REJECTED".equals(existing.status())) {
                return replay(existing);  // 终态重放不消耗配额（ADR-010 决策 3）
            }
            if (!store.tryReclaim(tenantId, idemKey, processingLeaseSeconds)) {
                throw new ConflictException("工具调用执行中（未超租约）：请稍后重放（idemKey=" + idemKey + "）");
            }
            return reserveAndExecute(tenantId, existing.id(), tool, inputJson, sessionId, stepNo, idemKey);
        }
        return reserveAndExecute(tenantId, claimedId, tool, inputJson, sessionId, stepNo, idemKey);
    }

    /** 新执行 / 重抢路径：预扣减 → 审批分流 / 执行（重抢时 reserve 幂等命中，不重复扣，ADR-010）。 */
    private ToolInvokeResponse reserveAndExecute(long tenantId, long invocationId, ToolExecutor tool,
                                                 String inputJson, String sessionId, int stepNo,
                                                 String idemKey) {
        boolean quotaActive = quotaGuard.reserveForToolInvocation(
                tenantId, idemKey, quotaGuard.toolTokenCost());
        return executeOrSuspend(tenantId, invocationId, tool, inputJson, sessionId, stepNo, idemKey, quotaActive);
    }

    /** 可见面内的工具（列表与调用共用同一判定：不可调用 = 不可见，FR-PERM-03）。 */
    public List<String> visibleTools() {
        return toolRegistry.registeredNames().stream().filter(toolWhitelist::visible).toList();
    }

    /**
     * 二态审批分流（FR-TOOL-03）：需确认的工具在 claim 后挂起（PENDING_APPROVAL）；
     * 挂起上下文 = 已落库的输入快照——批准后照常执行，不丢上下文。
     *
     * <p><b>配额语义</b>（ADR-010 决策 3）：挂起时**保留预扣**（资源已预留）；驳回时释放，
     * 批准时 reserve 幂等命中不重复扣。
     */
    private ToolInvokeResponse executeOrSuspend(long tenantId, long invocationId, ToolExecutor tool,
                                                String inputJson, String sessionId, int stepNo,
                                                String idemKey, boolean quotaActive) {
        if (approvalPolicy.requiresApproval(tool.name())) {
            store.markPendingApproval(invocationId);
            return new ToolInvokeResponse(invocationId, tool.name(), "PENDING_APPROVAL",
                    null, null, idemKey, false);
        }
        return execute(tenantId, invocationId, tool, inputJson, sessionId, stepNo, idemKey, quotaActive);
    }

    /** 批准：CAS 挂起态 → 执行（从输入快照恢复上下文）。 */
    @Transactional
    public ToolInvokeResponse approve(long invocationId) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        String actor = PrincipalContext.requireUserId();
        ToolInvocationStore.InvocationRow row = store.requireById(tenantId, invocationId);
        if (!store.tryApprove(invocationId)) {
            throw new ConflictException("调用不处于待审批状态，无法批准：" + row.status());
        }
        // 批准时刻复核可见面（装配期可能变更），并留痕
        ToolExecutor tool = requireVisible(row.tool(), tenantId, actor);
        auditLogWriter.append(tenantId, actor, "TOOL_APPROVED", String.valueOf(invocationId),
                "APPROVED", row.tool());
        // 挂起时预扣已保留：reserve 幂等命中不重复扣；挂起后才配预算也能生效（ADR-010 决策 3）
        boolean quotaActive = quotaGuard.reserveForToolInvocation(
                tenantId, row.idemKey(), quotaGuard.toolTokenCost());
        return execute(tenantId, invocationId, tool, row.input(), row.sessionId(), row.stepNo(),
                row.idemKey(), quotaActive);
    }

    /** 驳回：CAS 挂起态 → REJECTED 终态（不触发补偿——驳回是「不执行」，不是「执行失败」）。 */
    @Transactional
    public ToolInvokeResponse reject(long invocationId) {
        long tenantId = TenantContext.requireTenantIdAsLong();
        String actor = PrincipalContext.requireUserId();
        ToolInvocationStore.InvocationRow row = store.requireById(tenantId, invocationId);
        if (!store.tryReject(invocationId)) {
            throw new ConflictException("调用不处于待审批状态，无法驳回：" + row.status());
        }
        // 归还挂起期间保留的预扣（无预扣时安全 no-op，ADR-010 决策 3）
        quotaGuard.releaseToolInvocation(tenantId, row.idemKey());
        auditLogWriter.append(tenantId, actor, "TOOL_REJECTED", String.valueOf(invocationId),
                "REJECTED", row.tool());
        return new ToolInvokeResponse(invocationId, row.tool(), "REJECTED",
                null, "人工驳回", row.idemKey(), false);
    }

    private ToolInvokeResponse execute(long tenantId, long invocationId, ToolExecutor tool,
                                       String inputJson, String sessionId, int stepNo,
                                       String idemKey, boolean quotaActive) {
        long startNanos = System.nanoTime();
        try {
            String resultJson = tool.execute(inputJson);
            store.markSucceeded(invocationId, resultJson);
            if (quotaActive) {
                // 成功结算：差额校正为实际当量（演示当量固定，差额为 0）
                quotaGuard.settleToolInvocation(tenantId, idemKey, quotaGuard.toolTokenCost());
            }
            appendMetering(tenantId, invocationId, tool.name(), sessionId, stepNo, startNanos,
                    quotaActive ? quotaGuard.toolTokenCost() : 0);
            return new ToolInvokeResponse(invocationId, tool.name(), "SUCCEEDED",
                    parseMap(resultJson), null, idemKey, false);
        } catch (ToolExecutionException e) {
            store.markFailed(invocationId, e.getMessage());
            if (quotaActive) {
                // 业务失败不占用配额：释放预扣
                quotaGuard.releaseToolInvocation(tenantId, idemKey);
            }
            appendMetering(tenantId, invocationId, tool.name(), sessionId, stepNo, startNanos, 0);
            // 失败即生成补偿计划（对同会话更早的成功可补偿调用，逆序回滚的候选集）
            compensationService.planFor(tenantId, sessionId, invocationId);
            return new ToolInvokeResponse(invocationId, tool.name(), "FAILED",
                    null, e.getMessage(), idemKey, false);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 参数 / 状态非法：工具**没有**产生副作用（是执行前的校验失败），不生成补偿计划。
            // 但预扣必须归还——否则这条预留 key 会变成「免扣券」：claim 行随事务回滚后，
            // 同一逻辑调用重试时会命中幂等分支（allowed 且不扣减）→ 该次真实调用永远不计费。
            if (quotaActive) {
                quotaGuard.releaseToolInvocation(tenantId, idemKey);
            }
            throw e;
        } catch (RuntimeException e) {
            // 非受检异常（含数据访问异常、工具内部 NPE 等）：不能假定「没有副作用」——
            // 工具可能在外部协作系统写入成功后才抛错（ToolExecutor 契约要求它包成
            // ToolExecutionException，此处是兜底）。落失败账 + 归还预扣 + 生成补偿计划，
            // 使「不确定」走补偿链路而不是静默重放。
            store.markFailed(invocationId, "未包装异常：" + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (quotaActive) {
                quotaGuard.releaseToolInvocation(tenantId, idemKey);
            }
            appendMetering(tenantId, invocationId, tool.name(), sessionId, stepNo, startNanos, 0);
            compensationService.planFor(tenantId, sessionId, invocationId);
            throw e;
        }
    }

    private ToolInvokeResponse replay(ToolInvocationStore.InvocationRow row) {
        return new ToolInvokeResponse(row.id(), row.tool(), row.status(),
                row.result() == null ? null : parseMap(row.result()),
                row.error(), row.idemKey(), true);
    }

    private ToolExecutor requireVisible(String toolName, long tenantId, String actor) {
        Optional<ToolExecutor> found = toolRegistry.find(toolName);
        if (found.isEmpty() || !toolWhitelist.visible(toolName)) {
            String detail = found.isEmpty() ? "未注册工具" : "工具不在可见面（白名单）内";
            auditLogWriter.append(tenantId, actor, "TOOL_DENIED", toolName, "DENIED", detail);
            throw new SecurityException("工具不可见：拒绝调用（FR-PERM-03；不区分未注册与未授权）");
        }
        return found.get();
    }

    private void appendMetering(long tenantId, long invocationId, String toolName, String sessionId,
                                int stepNo, long startNanos, long promptTokens) {
        int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
        // 明细口径必须与配额扣减当量一一对应（BillingReconciler 的对账前提）：
        // 溢出截断会让「明细 token < 扣减当量」而无人察觉，故显式拒绝而不是静默取低 32 位。
        int meteredTokens = Math.toIntExact(promptTokens);
        outboxWriter.append(tenantId, invocationId, MeteringEventTypes.CALL_RECORDED,
                objectMapper.writeValueAsString(new CallMeteringPayload(
                        toolName, null, sessionId, stepNo, latencyMs, meteredTokens, 0)));
    }

    private String canonicalJson(Map<String, Object> input) {
        // 输入按 key 排序序列化：同输入同指纹（幂等键稳定性的前提，ADR-009 决策 1）。
        // ⚠️ 指纹的**正确性前提是数字精度未被吞掉**——共享 ObjectMapper 已开
        // USE_BIG_DECIMAL_FOR_FLOATS（见 JacksonPrecisionConfiguration）：默认配置下
        // 高精度小数会被 Double 化，两个不同输入会得到同一指纹 → 静默重放首次结果。
        return objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(input);
    }

    private Map<String, Object> parseMap(String json) {
        return objectMapper.readValue(json, new TypeReference<>() {
        });
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /** 幂等键成分的长度上限（越界 400；列宽由 V7 兜底，语义由入口负责）。 */
    private static void requireLength(String value, int max, String field) {
        if (value.length() > max) {
            throw new IllegalArgumentException(
                    "%s 长度超限：%d > %d（幂等键由该字段构成，超限必须显式拒绝而不是截断）"
                            .formatted(field, value.length(), max));
        }
    }

    /** 步骤号范围校验：负值会让「步骤指纹」语义失效（同一输入的多次执行被折叠）。 */
    private static int requireStepNo(int stepNo) {
        if (stepNo < 0 || stepNo > MAX_STEP_NO) {
            throw new IllegalArgumentException("stepNo 越界：0 ≤ stepNo ≤ " + MAX_STEP_NO + "，实际 " + stepNo);
        }
        return stepNo;
    }
}
