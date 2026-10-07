package com.aiwarden.governance.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.List;

/**
 * 配额账本（ADR-010 决策 1/2）：Redis + Lua 原子脚本；预扣减 → 结算（差额校正）→ 释放。
 *
 * <p><b>幂等</b>：{@code requestKey} 复用调用幂等键——同一逻辑调用的重放 / 重抢 / 审批恢复
 * 不会重复扣减（幂等 key 命中直接放行）。<b>release 无条件安全</b>：无预扣时差额为 0（no-op）。
 */
@Component
public class QuotaService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> reserveScript;
    private final DefaultRedisScript<List> settleScript;
    private final DefaultRedisScript<List> releaseScript;
    private final long reserveTtlSeconds;
    private final long settledTtlSeconds;
    private final long usageTtlSeconds;

    public QuotaService(StringRedisTemplate redis,
                        @Value("${aiwarden.quota.reserve-ttl-hours:72}") long reserveTtlHours,
                        @Value("${aiwarden.quota.usage-ttl-days:45}") long usageTtlDays) {
        this.redis = redis;
        this.reserveTtlSeconds = reserveTtlHours * 3600;
        // 结算标记必须活过预扣 key（见 quota_settle.lua 注释），故取 2× 预扣 TTL
        this.settledTtlSeconds = this.reserveTtlSeconds * 2;
        // 用量 key 是「每租户每账期」一个的常驻键：账期内必须存活，故取一个账期 +1 天
        this.usageTtlSeconds = usageTtlDays * 86_400;
        this.reserveScript = loadScript("redis/quota_reserve.lua");
        this.settleScript = loadScript("redis/quota_settle.lua");
        this.releaseScript = loadScript("redis/quota_release.lua");
    }

    /** 预扣减结果（allowed=false 即超限——调用方决定 429 语义）。 */
    public record QuotaOutcome(boolean allowed, long usedAfter, boolean idempotentHit) {
    }

    /**
     * 预扣减（原子「检查 + 占用」）。
     *
     * <p><b>幂等键必须带租户</b>：DB 侧唯一约束是 {@code (tenant_id, idem_key)}，因此两个租户
     * <b>可以</b>持有完全相同的 idemKey。若 reserve/settled 键不带 tenantId，租户 B 会命中租户 A
     * 留下的键 → 走幂等分支「允许且不扣减」→ 免费调用，同时明细仍写入 B 的账 → 对账负差异。
     * （实测复现：同 requestKey 下租户 2 的 usage 恒为空。）
     *
     * @param requestKey 幂等键（复用调用幂等键；**在租户作用域内**唯一）
     * @return 结果；超限时不产生任何扣减
     */
    public QuotaOutcome reserve(long tenantId, String period, String requestKey, long tokens, long limit) {
        List<?> result = redis.execute(reserveScript,
                List.of(usageKey(tenantId, period), reserveKey(tenantId, requestKey),
                        settledKey(tenantId, requestKey)),
                String.valueOf(tokens), String.valueOf(limit),
                String.valueOf(reserveTtlSeconds), String.valueOf(usageTtlSeconds));
        if (result == null || result.size() < 3) {
            throw new IllegalStateException("配额预扣减脚本返回异常：" + result);
        }
        return new QuotaOutcome(num(result, 0) == 0, num(result, 1), num(result, 2) == 1);
    }

    /**
     * 结算：把预扣量校正为实际用量（差额 + actual − reserved）；结算标记保证只应用一次。
     *
     * @return 本次应用的差额（0 表示已结算或无差异）
     */
    public long settle(long tenantId, String period, String requestKey, long actualTokens) {
        List<?> result = redis.execute(settleScript,
                List.of(usageKey(tenantId, period), reserveKey(tenantId, requestKey),
                        settledKey(tenantId, requestKey)),
                String.valueOf(actualTokens), String.valueOf(settledTtlSeconds));
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("配额结算脚本返回异常：" + result);
        }
        return num(result, 1);
    }

    /** 释放（归还未消耗的预扣）：扣回用量并**删除预扣键**；无预扣时为安全 no-op。 */
    public void release(long tenantId, String period, String requestKey) {
        redis.execute(releaseScript,
                List.of(usageKey(tenantId, period), reserveKey(tenantId, requestKey),
                        settledKey(tenantId, requestKey)));
    }

    /** 当前账期用量（对账与观测用）。 */
    public long currentUsage(long tenantId, String period) {
        String value = redis.opsForValue().get(usageKey(tenantId, period));
        return value == null ? 0 : Long.parseLong(value);
    }

    /** 当前账期（YYYY-MM）。 */
    public static String currentPeriod() {
        return YearMonth.now().toString();
    }

    private static String usageKey(long tenantId, String period) {
        return "aiwarden:quota:usage:" + tenantId + ":" + period;
    }

    /**
     * 预扣键 / 结算标记键**必须带租户**：DB 唯一约束是 {@code (tenant_id, idem_key)}，
     * 两租户可持有同一 idemKey；不带租户会让租户 B 命中租户 A 的键而免扣（见 reserve 注释）。
     */
    private static String reserveKey(long tenantId, String requestKey) {
        return "aiwarden:quota:reserve:" + tenantId + ":" + requestKey;
    }

    private static String settledKey(long tenantId, String requestKey) {
        return "aiwarden:quota:settled:" + tenantId + ":" + requestKey;
    }

    private static long num(List<?> result, int index) {
        Object value = result.get(index);
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> loadScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType(List.class);
        return script;
    }
}
