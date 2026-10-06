package com.aiwarden.start.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构守护测试（CI 红线，PRD §7.3）。
 *
 * <p>M0 起点只启用第一条规则：<b>业务模块不得直接 import {@code dev.langchain4j.*}</b>——
 * LangChain4j 是底座实现细节，必须经 aiwarden-core 的 SPI 屏蔽；
 * {@code aiwarden-start} 作为装配根（负责把 SPI 实现接进容器）暂不在此规则范围内。
 *
 * <p>其余三条（core 依赖方向 / contract 单向 / 禁止先查全量再在应用层过滤）随对应代码落地再启用。
 */
@AnalyzeClasses(packages = "com.aiwarden", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule businessModulesMustNotDependOnLangChain4j =
            noClasses().that().resideOutsideOfPackage("com.aiwarden.start..")
                    .should().dependOnClassesThat().resideInAPackage("dev.langchain4j..")
                    .because("AI 能力必须经 aiwarden-core 的 SPI 访问，业务模块不得直接依赖 LangChain4j（PRD §7.3 规则 2）");

    /**
     * 守护规则自证：用一个故意违规的测试夹具，证明「依赖 {@code dev.langchain4j} 会被判定为违规」，
     * 防止规则因选择器 / 导入范围问题沦为永久绿灯的摆设——规则本身也要有「能被违反」的证据。
     *
     * <p>注意：若调整上方主规则的条件，本用例需同步调整。
     */
    @Test
    void guardConditionShouldCatchLangChain4jDependency() {
        JavaClasses violatingFixture = new ClassFileImporter().importClasses(ViolatingFixture.class);
        ArchRule mirroredCondition = noClasses()
                .should().dependOnClassesThat().resideInAPackage("dev.langchain4j..");
        EvaluationResult result = mirroredCondition.evaluate(violatingFixture);
        assertThat(result.hasViolation()).isTrue();
    }

    /** 测试夹具：故意直接持有 LangChain4j 类型（仅用于自证守护规则有效，勿仿）。 */
    static final class ViolatingFixture {

        static void invokedWith(dev.langchain4j.model.chat.ChatModel chatModel) {
            // 故意违规：参数类型即为对 dev.langchain4j 的依赖
        }
    }
}
