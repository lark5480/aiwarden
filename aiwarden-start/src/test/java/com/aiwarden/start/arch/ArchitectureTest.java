package com.aiwarden.start.arch;

import com.aiwarden.knowledge.retrieval.RetrievalService;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构守护测试（CI 红线，PRD §7.3）。
 *
 * <p>已启用四条规则：
 * <ol>
 *   <li><b>core 领域纯净</b>：不得依赖 governance / knowledge / agent；</li>
 *   <li><b>业务模块不得直接 import {@code dev.langchain4j.*}</b>——LangChain4j 是底座实现细节，
 *       必须经 aiwarden-core 的 SPI 屏蔽；{@code aiwarden-start} 作为装配根暂不在此规则范围内；</li>
 *   <li><b>契约单向</b>：contract 不得依赖任何业务模块；</li>
 *   <li><b>禁止「先查全量再在应用层过滤权限」</b>（M2/P2 随代码落地启用）：两层守护——
 *       ④a 静态：{@code *Service / *Store / *Calculator} 不得声明「全量查询」方法签名
 *       （findAll / listAll / getAll / selectAll / queryAll 开头的集合返回方法）；
 *       ④b 签名：检索入口（{@code RetrievalService} 的公开 search）必须接收可见集（{@code VisibilitySet}），
 *       任何绕过可见集计算的检索入口都是违规——两条均有「能被违反」的自证夹具。</li>
 * </ol>
 */
@AnalyzeClasses(packages = "com.aiwarden", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule coreMustNotDependOnBusinessModules =
            noClasses().that().resideInAPackage("com.aiwarden.core..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.aiwarden.governance..", "com.aiwarden.knowledge..", "com.aiwarden.agent..")
                    .because("aiwarden-core 是 SPI 与领域模型的基础层，业务模块只能依赖它、不能反向（PRD §7.3 规则 1）");

    @ArchTest
    static final ArchRule contractMustNotDependOnBusinessModules =
            noClasses().that().resideInAPackage("com.aiwarden.contract..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("com.aiwarden.governance..", "com.aiwarden.knowledge..", "com.aiwarden.agent..")
                    .because("对外契约必须单向独立，业务模块不得反向渗入契约层（PRD §7.3 规则 3）");

    @ArchTest
    static final ArchRule businessModulesMustNotDependOnLangChain4j =
            noClasses().that().resideOutsideOfPackage("com.aiwarden.start..")
                    .should().dependOnClassesThat().resideInAPackage("dev.langchain4j..")
                    .because("AI 能力必须经 aiwarden-core 的 SPI 访问，业务模块不得直接依赖 LangChain4j（PRD §7.3 规则 2）");

    /**
     * 规则 ④a：禁止「全量查询签名」——「先查全量再在应用层过滤权限」的典型入口形态。
     * 权限过滤只允许发生在 SQL（filter 下推）中，不允许出现无过滤的集合查询方法。
     */
    @Test
    void servicesMustNotExposeUnfilteredFullScanMethods() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.aiwarden");
        assertThat(fullScanViolations(classes))
                .as("禁止先查全量再过滤：*Service/*Store/*Calculator 不得声明全量查询方法（PRD §7.3 规则 4）")
                .isEmpty();
    }

    /** 规则 ④a 自证：违规形态（listAllXxx 返回集合）必须被判定为违规。 */
    @Test
    void fullScanGuardShouldCatchViolatingFixture() {
        JavaClasses fixture = new ClassFileImporter().importClasses(ViolatingListAllService.class);
        assertThat(fullScanViolations(fixture)).isNotEmpty();
    }

    /**
     * 规则 ④b：检索入口必须接收可见集——{@code RetrievalService} 的全部公开 {@code search}
     * 方法都不得绕过 {@code VisibilitySet}（ADR-008：任何调用方都不可绕过可见集计算）。
     */
    @Test
    void retrievalSearchMustRequireVisibilitySet() {
        JavaClass service = new ClassFileImporter().importClasses(RetrievalService.class)
                .get(RetrievalService.class);
        assertThat(service.getMethods().stream().anyMatch(m -> m.getName().equals("search")))
                .as("RetrievalService 应存在 search 方法（守护对象存在性）")
                .isTrue();
        assertThat(unguardedSearchMethods(service))
                .as("公开检索入口必须含 VisibilitySet 参数，不得绕过可见集（ADR-008）")
                .isEmpty();
    }

    /** 规则 ④b 自证：不含可见集参数的公开 search 方法必须被判定为违规。 */
    @Test
    void visibilityGuardShouldCatchUnguardedSignature() {
        JavaClass fixture = new ClassFileImporter().importClasses(UnguardedRetrievalFixture.class)
                .get(UnguardedRetrievalFixture.class);
        assertThat(unguardedSearchMethods(fixture)).isNotEmpty();
    }

    private static List<String> fullScanViolations(JavaClasses classes) {
        return classes.stream()
                .filter(ArchitectureTest::isServiceLikeClass)
                .flatMap(clazz -> clazz.getMethods().stream()
                        .filter(method -> method.getName().matches("(findAll|listAll|getAll|selectAll|queryAll).*"))
                        .filter(method -> returnsCollectionLike(method.getRawReturnType()))
                        .map(method -> method.getFullName()))
                .toList();
    }

    private static List<String> unguardedSearchMethods(JavaClass clazz) {
        return clazz.getMethods().stream()
                .filter(method -> method.getName().equals("search"))
                .filter(method -> method.getModifiers().contains(JavaModifier.PUBLIC))
                .filter(method -> method.getRawParameterTypes().stream()
                        .noneMatch(type -> type.getName().equals("com.aiwarden.governance.visibility.VisibilitySet")))
                .map(JavaMethod::getFullName)
                .toList();
    }

    private static boolean isServiceLikeClass(JavaClass clazz) {
        String name = clazz.getSimpleName();
        return name.endsWith("Service") || name.endsWith("Store")
                || name.endsWith("Calculator") || name.endsWith("Repository");
    }

    private static boolean returnsCollectionLike(JavaClass rawReturnType) {
        return rawReturnType.isAssignableTo(Collection.class)
                || rawReturnType.isAssignableTo(Stream.class)
                || rawReturnType.isArray();
    }

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

    /** 测试夹具：故意声明「全量查询」方法签名（仅用于自证 ④a 守护有效，勿仿）。 */
    static final class ViolatingListAllService {

        @SuppressWarnings("unused")
        List<String> listAllSecrets() {
            // 故意违规：无过滤全量查询形态（「先查全量再过滤」的入口）
            return List.of();
        }
    }

    /** 测试夹具：故意声明不含可见集的公开检索入口（仅用于自证 ④b 守护有效，勿仿）。 */
    static final class UnguardedRetrievalFixture {

        @SuppressWarnings("unused")
        public void search(long tenantId) {
            // 故意违规：公开 search 不接收 VisibilitySet
        }
    }
}
