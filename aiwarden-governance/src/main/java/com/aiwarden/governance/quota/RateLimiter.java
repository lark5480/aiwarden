package com.aiwarden.governance.quota;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 租户限流（FR-COST-07 / ADR-010 决策 6）：Redis ZSET **滑动窗口**，与配额共用 Redis 管道。
 *
 * <p>维度由调用方给定（本项目落地为「租户 + 工具」双维度——无模型路由时对「租户 + 模型」
 * 维度的替身）；超限时调用方以 {@code 429 + Retry-After} 拒绝，{@code Retry-After} 取
 * 窗口长度（保守估计）。
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;
    private final long windowSeconds;
    private final long maxRequests;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${aiwarden.rate-limit.window-seconds:60}") long windowSeconds,
                       @Value("${aiwarden.rate-limit.max-requests:1000}") long maxRequests) {
        this.redis = redis;
        this.windowSeconds = windowSeconds;
        this.maxRequests = maxRequests;
        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/rate_limit.lua")));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    /** 判定结果（allowed=false 即超限——调用方决定 429 语义）。 */
    public record Outcome(boolean allowed, long count) {
    }

    /** 尝试获取一个窗口配额（原子：清理过期 + 计数 + 写入）。 */
    public Outcome tryAcquire(long tenantId, String dimension) {
        long nowMillis = System.currentTimeMillis();
        List<?> result = redis.execute(script,
                List.of("aiwarden:rate:" + tenantId + ":" + dimension),
                String.valueOf(nowMillis), String.valueOf(windowSeconds * 1000),
                String.valueOf(maxRequests), UUID.randomUUID().toString());
        if (result == null || result.size() < 2) {
            throw new IllegalStateException("限流脚本返回异常：" + result);
        }
        long status = ((Number) result.get(0)).longValue();
        return new Outcome(status == 0, ((Number) result.get(1)).longValue());
    }

    /** 窗口长度（秒）——Retry-After 的保守取值。 */
    public long windowSeconds() {
        return windowSeconds;
    }
}
