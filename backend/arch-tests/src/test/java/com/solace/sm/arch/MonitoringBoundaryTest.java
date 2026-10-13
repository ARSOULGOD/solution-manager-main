package com.solace.sm.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The monitoring work's own boundary (plan 011 P11-7.2).
 *
 * <p><b>The cross-tenant list has four named callers</b> ({@code OR-49}, {@code OR-72}, {@code OR-92} OQ-6 (a)): the health
 * poller, the fleet batch advancer, the key re-wrap command and the metrics background loop (collection, rollups and the
 * push reconcile, all fed by the loop's one call). A fifth caller is a new tenant enumeration and needs an owner ruling, so
 * it is a red build here and not a convenient call in a new class. The rule is matched on the call's TARGET (name and
 * owner), so a call through the repository's own type is caught wherever it is written.
 *
 * <p>Proved by a planted fifth caller ({@code CollectorReadsAThirdTenantList}) AND by the real call sites: the four are
 * found calling it, so "only these may" is not vacuous. The gateway's own datasource rule, which the monitoring routes
 * extend with a planted gateway fixture, is {@code ApplyPathBoundaryTest.theGatewayHoldsNoDatasource}.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class MonitoringBoundaryTest {

    private static final String REPOSITORY = TenantEnumerationRepository.class.getName();
    private static final Set<String> RULED_CALLERS = Set.of(
            "com.solace.sm.dp.health.HealthPoller",
            "com.solace.sm.dp.fleet.FleetScheduler",
            "com.solace.sm.dp.apply.persistence.KeyRotationMain",
            "com.solace.sm.dp.metrics.collect.MetricsBackgroundLoop");
    private static final String FIFTH = "com.solace.sm.dp.archfixture.monitoring.CollectorReadsAThirdTenantList";

    @ArchTest
    static final ArchRule onlyTheRuledCallersEnumerateActiveTenants =
            noClasses().that(DescribedPredicate.not(outermostClassIsOneOf(RULED_CALLERS)))
                    .should().callMethodWhere(target(name("tenantsWithActiveInstances"))
                            .and(target(owner(equivalentTo(TenantEnumerationRepository.class)))))
                    .because("a fifth caller of the cross-tenant list is a new tenant enumeration (OR-92 OQ-6 (a))");

    /** A class and everything nested in it (lambdas' synthetic classes, anonymous classes, records). */
    private static DescribedPredicate<JavaClass> outermostClassIsOneOf(Set<String> names) {
        return new DescribedPredicate<>("have outermost class one of " + names) {
            @Override
            public boolean test(JavaClass c) {
                JavaClass outer = c;
                while (outer.getEnclosingClass().isPresent()) {
                    outer = outer.getEnclosingClass().get();
                }
                return names.contains(outer.getName());
            }
        };
    }

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    @Test
    void aPlantedFifthCallerIsReportedAndNoRealClassIs() {
        String report = String.join("\n", onlyTheRuledCallersEnumerateActiveTenants.evaluate(withPlantedViolations())
                .getFailureReport().getDetails());
        assertTrue(report.contains(FIFTH), "the planted fifth caller must be reported. Was: " + report);
        for (String ruled : RULED_CALLERS) {
            assertFalse(report.contains(ruled), ruled + " is a ruled caller and must not be reported. Was: " + report);
        }
    }

    @Test
    void theFourRuledCallersReallyCallItSoTheRuleAllowsSomething() {
        JavaClasses production = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
        Set<String> callers = new TreeSet<>();
        production.stream().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().getName().equals(REPOSITORY))
                .filter(call -> call.getTarget().getName().equals("tenantsWithActiveInstances"))
                .forEach(call -> {
                    JavaClass outer = c;
                    while (outer.getEnclosingClass().isPresent()) {
                        outer = outer.getEnclosingClass().get();
                    }
                    callers.add(outer.getName());
                }));
        assertEquals(new TreeSet<>(RULED_CALLERS), callers,
                "the production call sites of the cross-tenant list moved; review them against OR-92 OQ-6 (a)");
    }
}
