package com.aiwarden.start.json;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/**
 * Jackson 数字精度（FR-TOOL-01 幂等指纹的正确性前提）。
 *
 * <p><b>为什么必须开这个开关</b>：工具调用的幂等键含「输入规范 JSON」的 sha256 指纹。
 * 默认配置把 JSON 小数反序列化为 {@code Double}——精度在进入业务代码<b>之前</b>就已丢失，
 * 于是两个仅在 17 位有效数字之后不同的输入会得到<b>同一个指纹</b>，第二次调用会静默重放
 * 首次结果（不报错、不执行、无日志）。本机实测（Jackson 3.1.5，本仓库同版本）：
 *
 * <pre>
 * 默认：   {"n":12345678901234567890.12345678901234567890}
 *          {"n":12345678901234567890.12345678901234567891}   → 两者规范形式完全相同（碰撞）
 * 开启后： 两者分别保持各自的数字字面量（不碰撞）
 * </pre>
 *
 * <p><b>为什么不能用「读原始字面量」绕开</b>：实测 {@code readTree} 得到的是 {@code DoubleNode}
 * （同样已丢精度），因此治本点只能在反序列化配置上，而不是在指纹计算处。
 *
 * <p><b>为什么必须放在共享 ObjectMapper 上</b>：{@code @RequestBody} 在 Controller 边界就把
 * 请求体解析成了 {@code Map<String,Object>}；指纹计算发生在那之后。若只在指纹处新建一个
 * Mapper，拿到的仍是已被 {@code Double} 化的值——修在错的地方等于没修。
 *
 * <p><b>被丢弃的精度不再可逆，且方向是安全的</b>：MySQL/PG 读出的数字本就是 {@code BigDecimal}；
 * 此开关只影响 JSON 反序列化，不改变写侧。代价是 JSON 数字→{@code BigDecimal} 的少量分配开销
 * （治理税可观测）。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonPrecisionConfiguration {

    /**
     * JSON 小数一律以 {@code BigDecimal} 承载，保留请求体中的原始有效数字。
     *
     * <p>注意：这里针对的是 {@code Map<String,Object>} / {@code Object} 这类无类型目标的浮点绑定，
     * 显式声明为 {@code double} 的字段不受影响。
     */
    @Bean
    JsonMapperBuilderCustomizer bigDecimalForFloatsCustomizer() {
        return builder -> builder.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
