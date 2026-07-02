package com.apex.agent.architecture

import com.tngtech.archunit.base.DescribedPredicate.alwaysTrue
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.junit.ArchUnitRunner
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.runner.RunWith

@RunWith(ArchUnitRunner::class)
@AnalyzeClasses(packages = ["com.apex.agent"])
class LayeredArchitectureTest {

    @ArchTest
    val layersShouldOnlyDependOnLowerLayers: ArchRule = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Presentation").definedBy("com.apex.agent.presentation..")
        .layer("Orchestration").definedBy("com.apex.agent.orchestration..")
        .layer("Domain").definedBy("com.apex.agent.domain..")
        .layer("Infrastructure").definedBy("com.apex.agent.infrastructure..")
        .layer("Data").definedBy("com.apex.agent.data..")
        .layer("Common").definedBy("com.apex.agent.common..")
        .whereLayer("Presentation").mayOnlyAccessLayers("Orchestration", "Domain", "Common")
        .whereLayer("Orchestration").mayOnlyAccessLayers("Domain", "Infrastructure", "Common")
        .whereLayer("Domain").mayOnlyAccessLayers("Common")
        .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Common")
        .whereLayer("Data").mayOnlyAccessLayers("Domain", "Infrastructure", "Common")
        .whereLayer("Common").mayNotAccessAnyLayer()
        .ignoreDependency(
            alwaysTrue(),
            JavaClass.Predicates.resideInAnyPackage(
                "java..",
                "javax..",
                "kotlin..",
                "kotlinx..",
                "android..",
                "androidx..",
                "com.google..",
                "dagger..",
                "javax.inject.."
            )
        )

    @ArchTest
    val domainShouldNotDependOnOrchestration: ArchRule = noClasses()
        .that().resideInAPackage("com.apex.agent.domain..")
        .should().dependOnClassesThat().resideInAPackage("com.apex.agent.orchestration..")

    @ArchTest
    val commonShouldNotDependOnOtherLayers: ArchRule = noClasses()
        .that().resideInAPackage("com.apex.agent.common..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.apex.agent.presentation..",
            "com.apex.agent.orchestration..",
            "com.apex.agent.domain..",
            "com.apex.agent.infrastructure..",
            "com.apex.agent.data.."
        )

    @ArchTest
    val presentationShouldNotDependOnData: ArchRule = noClasses()
        .that().resideInAPackage("com.apex.agent.presentation..")
        .should().dependOnClassesThat().resideInAPackage("com.apex.agent.data..")
}
