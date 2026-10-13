package com.solace.sm.arch;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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

    // ---- Plan 015 T2.2 (BE-D69, HF21): the launcher alone composes cp<->dp, through exactly three named adapters. ----

    private static final String ENROLLMENT_ADAPTER = "com.solace.sm.launcher.ControlPlaneEnrollmentCertificateIssuer";
    private static final String LEASE_ADAPTER = "com.solace.sm.launcher.ControlPlaneLeaseIssuer";
    private static final String REGISTRATION_ADAPTER = "com.solace.sm.launcher.ControlPlaneRegistrationSource";
    private static final String ADAPTER_CONFIGURATION = "com.solace.sm.launcher.AgentGatewayControlPlaneAdapterConfiguration";

    /** The three adapters and the configuration that publishes them, including their nested and anonymous classes. */
    private static final DescribedPredicate<JavaClass> NAMED_ADAPTER_CLASSES =
            DescribedPredicate.describe("one of the three named control-plane adapters or their configuration",
                    c -> {
                        String name = c.getName();
                        return java.util.stream.Stream.of(ENROLLMENT_ADAPTER, LEASE_ADAPTER, REGISTRATION_ADAPTER,
                                ADAPTER_CONFIGURATION).anyMatch(n -> name.equals(n) || name.startsWith(n + "$"));
                    });

    // A port the control plane answers has ONE concrete implementation in the whole tree: its named launcher adapter. A
    // fourth class implementing it, anywhere, is a second cross-plane seam.
    @ArchTest
    static final ArchRule onlyTheNamedAdapterImplementsTheEnrollmentIssuerPort =
            classes().that().areAssignableTo(EnrollmentCertificateIssuer.class).and().areNotInterfaces()
                    .should().haveFullyQualifiedName(ENROLLMENT_ADAPTER);

    @ArchTest
    static final ArchRule onlyTheNamedAdapterImplementsTheLeaseIssuerPort =
            classes().that().areAssignableTo(LeaseIssuer.class).and().areNotInterfaces()
                    .should().haveFullyQualifiedName(LEASE_ADAPTER);

    @ArchTest
    static final ArchRule onlyTheNamedAdapterImplementsTheRegistrationSourcePort =
            classes().that().areAssignableTo(AgentRegistrationSource.class).and().areNotInterfaces()
                    .should().haveFullyQualifiedName(REGISTRATION_ADAPTER);

    // The launcher may adapt exactly the cp-api agent seam, and only inside those classes.
    @ArchTest
    static final ArchRule onlyTheNamedAdaptersTouchTheCpAgentApi =
            noClasses().that().resideInAPackage("com.solace.sm.launcher..").and(
                            DescribedPredicate.not(NAMED_ADAPTER_CLASSES))
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.cp.api.agent..");

    // The launcher composes cp-api with dp-api in those four classes ONLY: any other launcher class that touches both planes'
    // api packages is a fifth place where the planes meet.
    @ArchTest
    static final ArchRule noOtherLauncherClassTouchesBothPlanesApis =
            classes().that().resideInAPackage("com.solace.sm.launcher..").and(DescribedPredicate.not(NAMED_ADAPTER_CLASSES))
                    .should(new ArchCondition<JavaClass>("not depend on both com.solace.sm.cp.api.. and com.solace.sm.dp.api..") {
                        @Override
                        public void check(JavaClass item, ConditionEvents events) {
                            boolean cp = item.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.solace.sm.cp.api"));
                            boolean dp = item.getDirectDependenciesFromSelf().stream()
                                    .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.solace.sm.dp.api"));
                            if (cp && dp) {
                                events.add(SimpleConditionEvent.violated(item,
                                        item.getName() + " depends on both cp.api.. and dp.api.."));
                            }
                        }
                    });

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

    @Test
    void aPlantedFourthAdapterTurnsTheLeaseIssuerRuleRed() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheNamedAdapterImplementsTheLeaseIssuerPort.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadFourthCpAdapter"), failure::getMessage);
    }

    @Test
    void aPlantedLauncherClassReachingForTheCpAgentApiTurnsItsRuleRed() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> onlyTheNamedAdaptersTouchTheCpAgentApi.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherCpAgentClass"), failure::getMessage);
    }

    @Test
    void aPlantedLauncherClassComposingBothPlanesApisTurnsItsRuleRed() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> noOtherLauncherClassTouchesBothPlanesApis.check(withPlantedViolations()));
        assertTrue(failure.getMessage().contains("BadLauncherBothPlanesClass"), failure::getMessage);
    }

    @Test
    void theRealAdaptersAreInTheImportAndPassTheirRules() {
        JavaClasses production = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
        for (String name : new String[] {ENROLLMENT_ADAPTER, LEASE_ADAPTER, REGISTRATION_ADAPTER, ADAPTER_CONFIGURATION}) {
            assertTrue(production.contain(name), name + " must exist, or the rules above prove nothing about it");
        }
        onlyTheNamedAdapterImplementsTheEnrollmentIssuerPort.check(production);
        onlyTheNamedAdapterImplementsTheLeaseIssuerPort.check(production);
        onlyTheNamedAdapterImplementsTheRegistrationSourcePort.check(production);
        onlyTheNamedAdaptersTouchTheCpAgentApi.check(production);
        noOtherLauncherClassTouchesBothPlanesApis.check(production);
    }

}
