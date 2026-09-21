package com.solace.sm.arch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Keeps the production composition root from becoming a second implementation plane. */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class CompositionBoundaryTest {

    @ArchTest
    static final ArchRule onlyTheLauncherDependsOnTheLauncher =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.launcher..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.launcher..");

    @ArchTest
    static final ArchRule launcherDoesNotTouchDataAccessOrMigrations =
            noClasses().that().resideInAPackage("com.solace.sm.launcher..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..", "org.flywaydb..");

    @ArchTest
    static final ArchRule launcherNeverImportsDemo =
            noClasses().that().resideInAPackage("com.solace.sm.launcher..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.demo..");

    @ArchTest
    static final ArchRule launcherNeverDependsOnAWorkerRuntimeOrExecutor =
            noClasses().that().resideInAPackage("com.solace.sm.launcher..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.solace.sm.dp.apply.runtime.RuntimeAdapter");

    @ArchTest
    static final ArchRule launcherNeverDependsOnAnApplyExecutor =
            noClasses().that().resideInAPackage("com.solace.sm.launcher..")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.solace.sm.dp.apply.engine.ApplyExecutor");

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    @Test
    void ownershipRuleCatchesThePlantedDependencyOnTheLauncher() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheLauncherDependsOnTheLauncher.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadDpDependsOnLauncher"), failure::getMessage);
    }

    @Test
    void dataAccessRuleCatchesThePlantedLauncherDependency() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> launcherDoesNotTouchDataAccessOrMigrations.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherJdbcClass"), failure::getMessage);
    }

    @Test
    void demoRuleCatchesThePlantedLauncherDependency() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> launcherNeverImportsDemo.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherDemoClass"), failure::getMessage);
    }

    @Test
    void runtimeRuleCatchesThePlantedLauncherDependency() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> launcherNeverDependsOnAWorkerRuntimeOrExecutor.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherRuntimeAdapterClass"), failure::getMessage);
    }

    @Test
    void executorRuleCatchesThePlantedLauncherDependency() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> launcherNeverDependsOnAnApplyExecutor.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherApplyExecutorClass"), failure::getMessage);
    }
}
