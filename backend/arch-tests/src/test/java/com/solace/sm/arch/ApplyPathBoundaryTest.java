package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * One mutating apply path, enforced (plan 010 T6.7, executing plan 007 Task 12).
 *
 * <p>{@code console-gateway -> dp-api -> durable worker -> ApplyExecutor -> RuntimeAdapter} is the only way a host
 * is mutated. A 202 is honest only because the request thread cannot open a session to a host; these rules are what
 * make that a property of the build rather than of the code as it happens to be written today.
 *
 * <p><b>Scope, stated once.</b> Every rule runs against the production tree ({@link
 * PlaneBoundaryTest.ExcludeArchFixtures}). {@code demo-launcher} was deleted by plan 011 P11-9.1, so the mutating-RuntimeAdapter
 * rule below runs unscoped: no demo package is exempt.
 *
 * <p>Each rule carries its own planted-violation fixture. A proof asserts the rule reports its own fixture
 * <i>and none of the sibling rules' fixtures</i> (so no two proofs are the same proof), and that the real classes it
 * governs are in the analysed set and are not reported (so the rule does not reject the code it governs).
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class ApplyPathBoundaryTest {

    private static final String PRODUCTION_WIRING = "com.solace.sm.dp.apply.production.ProductionApplyConfiguration";

    // INVERTED on purpose. A rule scoped to com.solace.sm.gateway.. would have passed the violation this plan
    // replaced -- the demo's controller path imported RuntimeAdapter and called startOrUpdate/materializeConfig --
    // and would go on permitting any future production module to do the same. Only the engine and the seam's own
    // package may name the mutating seam -- plus exactly two measured exceptions that
    // plan 007's text did not have (it predates both):
    //   * ProductionApplyConfiguration (plan 010 T6.2) -- the ONE class that builds the adapter bean and hands it
    //     to the ApplyExecutor. Allowed by class, not by package: a second class in dp.apply.production that
    //     names the adapter is a finding.
    //   * com.solace.sm.arch.. -- this module's own rule classes (PlaneBoundaryTest's adapter inventory names the
    //     type to enumerate its implementations). Test code of the rules, never production.
    @ArchTest
    static final ArchRule onlyProductionCodeTouchesTheMutatingRuntimeAdapter =
            noClasses().that().resideOutsideOfPackages(
                            "com.solace.sm.dp.apply.engine..",
                            "com.solace.sm.dp.apply.runtime..",
                            "com.solace.sm.arch..")
                    .and().doNotHaveFullyQualifiedName(PRODUCTION_WIRING)
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "com.solace.sm.dp.apply.runtime.RuntimeAdapter")
                    .because("the only mutating apply path is worker -> ApplyExecutor -> RuntimeAdapter");

    // The seam rule alone would still permit a controller reaching a host through a NEW transport, so the two
    // transports that exist (SSH, the Docker client) are fenced by name too. NOT "..apply.runtime.." -- the read
    // surface legitimately needs RuntimeStatus and RuntimeDiagnostics, which live there. What the HTTP layer must
    // never hold is a TRANSPORT.
    @ArchTest
    static final ArchRule noHttpLayerHoldsATransport =
            noClasses().that().resideInAnyPackage("com.solace.sm.gateway..", "com.solace.sm.launcher..")
                    .should().dependOnClassesThat().resideInAnyPackage("net.schmizz..", "com.github.dockerjava..")
                    .because("a 202 is only honest if the request thread cannot open a session to a host");

    // Five roots, matching agent-gateway/design.md section 2.6's agwHoldsNoDatasource: a single
    // javax.sql.DataSource check lets a JPA entity or a Flyway type through.
    @ArchTest
    static final ArchRule theGatewayHoldsNoDatasource =
            noClasses().that().resideInAPackage("com.solace.sm.gateway..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "javax.sql..", "jakarta.persistence..", "org.springframework.jdbc..",
                            "org.springframework.data..", "org.flywaydb..");

    @ArchTest
    static final ArchRule nothingProductionImportsTheDemoModule =
            noClasses().that().resideOutsideOfPackage("com.solace.sm.demo..")
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.demo..");

    // ---------------------------------------------------------------------------------------
    // Proof that each rule fires, and only on its own fixture.
    // ---------------------------------------------------------------------------------------

    private static final String BAD_RUNTIME_ADAPTER = "com.solace.sm.gateway.archfixture.BadGatewayRuntimeAdapterClass";
    private static final String BAD_SECOND_WIRING =
            "com.solace.sm.dp.apply.production.archfixture.BadSecondProductionWiringClass";
    private static final String BAD_GATEWAY_SSH = "com.solace.sm.gateway.archfixture.BadGatewaySshClientClass";
    private static final String BAD_LAUNCHER_DOCKER = "com.solace.sm.launcher.archfixture.BadLauncherDockerClientClass";
    private static final String BAD_GATEWAY_DATASOURCE = "com.solace.sm.gateway.archfixture.BadGatewayDataSourceClass";
    private static final String BAD_GATEWAY_JDBC_TEMPLATE =
            "com.solace.sm.gateway.archfixture.BadGatewayJdbcTemplateClass";
    private static final String BAD_GATEWAY_FLYWAY = "com.solace.sm.gateway.archfixture.BadGatewayFlywayClass";
    // Plan 011 P11-7.2 (M-Q11): the gateway now hosts the monitoring routes, so its no-datasource rule is also proved on a
    // fixture in the monitoring package, where the code that would be tempted to read the metrics store itself lives.
    private static final String BAD_GATEWAY_MONITORING =
            "com.solace.sm.gateway.archfixture.monitoring.GatewayHoldsADataSource";
    private static final String BAD_GATEWAY_DEMO = "com.solace.sm.gateway.archfixture.BadGatewayDemoClass";

    private static final Set<String> ALL_OWN_FIXTURES = Set.of(BAD_RUNTIME_ADAPTER, BAD_SECOND_WIRING, BAD_GATEWAY_SSH,
            BAD_LAUNCHER_DOCKER, BAD_GATEWAY_DATASOURCE, BAD_GATEWAY_JDBC_TEMPLATE, BAD_GATEWAY_FLYWAY,
            BAD_GATEWAY_MONITORING, BAD_GATEWAY_DEMO);

    private static final Pattern VIOLATOR = Pattern.compile(
            "^\\w+ <((?:[\\w$]+\\.)+[\\w$]+)\\.(?:<init>|<clinit>|[\\w$]+)(?:\\(|>)");

    /** Every class under {@code com.solace.sm}, fixtures INCLUDED. */
    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    /** The classes a rule reports as violators (the class owning the offending member), not message text. */
    private static Set<String> violators(ArchRule rule) {
        Set<String> out = new TreeSet<>();
        for (String detail : rule.evaluate(withPlantedViolations()).getFailureReport().getDetails()) {
            Matcher m = VIOLATOR.matcher(detail);
            if (m.find()) {
                out.add(m.group(1));
            } else {
                out.add(detail);   // a field / class-level violation: keep the raw line so nothing is lost
            }
        }
        return out;
    }

    private static boolean reported(Set<String> violators, String className) {
        return violators.stream().anyMatch(v -> v.equals(className) || v.contains(className + " ")
                || v.contains(className + "."));
    }

    private static void assertReportsOnlyItsOwnFixtures(ArchRule rule, Set<String> own) {
        Set<String> reported = violators(rule);
        for (String fixture : own) {
            assertTrue(reported(reported, fixture), rule.getDescription() + " must report its own fixture "
                    + fixture + ". Reported: " + reported);
        }
        for (String other : ALL_OWN_FIXTURES) {
            if (!own.contains(other)) {
                assertFalse(reported(reported, other), rule.getDescription() + " also reports " + other
                        + ", a sibling rule's fixture: the two proofs would not be independent. Reported: "
                        + reported);
            }
        }
    }

    private static void assertAnalysedAndNotReported(ArchRule rule, String... realClasses) {
        JavaClasses all = withPlantedViolations();
        Set<String> reported = violators(rule);
        for (String real : realClasses) {
            assertTrue(all.stream().anyMatch(c -> c.getName().equals(real)),
                    real + " must be in the analysed set, or the absence check proves nothing");
            assertFalse(reported(reported, real), rule.getDescription() + " rejects " + real
                    + ", code it is meant to govern. Reported: " + reported);
        }
    }

    @Test
    void anAdapterObtainedOutsideTheEngineIsReportedAndTheEngineIsNot() {
        // The existing launcher fixture (CompositionBoundaryTest's) holds a RuntimeAdapter too: the two rules
        // overlap on purpose, so it is allowed here alongside this rule's own fixture.
        Set<String> reported = violators(onlyProductionCodeTouchesTheMutatingRuntimeAdapter);
        assertTrue(reported(reported, BAD_RUNTIME_ADAPTER), "planted gateway class holding a RuntimeAdapter: "
                + reported);
        assertTrue(reported(reported, "com.solace.sm.launcher.archfixture.BadLauncherRuntimeAdapterClass"),
                "planted launcher class holding a RuntimeAdapter: " + reported);
        // The production-wiring allowance is by CLASS: a second wiring class in the same family is reported.
        assertTrue(reported(reported, BAD_SECOND_WIRING), "planted second wiring class: " + reported);
        for (String other : ALL_OWN_FIXTURES) {
            if (!other.equals(BAD_RUNTIME_ADAPTER) && !other.equals(BAD_SECOND_WIRING)) {
                assertFalse(reported(reported, other), other + " is another rule's fixture. " + reported);
            }
        }
        assertAnalysedAndNotReported(onlyProductionCodeTouchesTheMutatingRuntimeAdapter,
                "com.solace.sm.dp.apply.engine.ApplyExecutor",
                "com.solace.sm.dp.apply.runtime.ssh.SshDockerRuntimeAdapter",
                "com.solace.sm.dp.apply.runtime.docker.DockerAdapter",
                PRODUCTION_WIRING);
    }

    @Test
    void aTransportInTheHttpLayerIsReportedInBothPackagesAndForBothClients() {
        assertReportsOnlyItsOwnFixtures(noHttpLayerHoldsATransport, Set.of(BAD_GATEWAY_SSH, BAD_LAUNCHER_DOCKER));
        assertAnalysedAndNotReported(noHttpLayerHoldsATransport,
                "com.solace.sm.launcher.SolutionManagerConfiguration",
                "com.solace.sm.gateway.http.ApplyController");
    }

    @Test
    void aDatasourceInTheGatewayIsReportedForEachRootThatIsOnTheClasspath() {
        assertReportsOnlyItsOwnFixtures(theGatewayHoldsNoDatasource,
                Set.of(BAD_GATEWAY_DATASOURCE, BAD_GATEWAY_JDBC_TEMPLATE, BAD_GATEWAY_FLYWAY, BAD_GATEWAY_MONITORING));
        assertAnalysedAndNotReported(theGatewayHoldsNoDatasource, "com.solace.sm.gateway.http.metrics.QueryController",
                "com.solace.sm.gateway.http.metrics.ExportController", "com.solace.sm.gateway.http.metrics.DashboardController");
        assertAnalysedAndNotReported(theGatewayHoldsNoDatasource, "com.solace.sm.gateway.http.ApplyController");
    }

    @Test
    void aProductionDependencyOnTheDemoModuleIsReported() {
        Set<String> reported = violators(nothingProductionImportsTheDemoModule);
        assertTrue(reported(reported, BAD_GATEWAY_DEMO), "planted gateway class on the demo module: " + reported);
        assertTrue(reported(reported, "com.solace.sm.launcher.archfixture.BadLauncherDemoClass"),
                "planted launcher class on the demo module: " + reported);
        for (String other : ALL_OWN_FIXTURES) {
            if (!other.equals(BAD_GATEWAY_DEMO)) {
                assertFalse(reported(reported, other), other + " is another rule's fixture. " + reported);
            }
        }
        assertFalse(reported(reported, "com.solace.sm.demo.archfixture.DemoCompositionType"),
                "the demo package itself is the one thing allowed to name its own types. " + reported);
    }
}
