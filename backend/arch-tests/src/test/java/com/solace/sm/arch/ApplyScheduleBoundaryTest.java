package com.solace.sm.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.properties.HasParameterTypes.Predicates.rawParameterTypes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.schedule.ScheduleRows;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The scheduled-apply feature's ONE cross-organization read (plan 012 T7; {@code OPS-Q10} (c), {@code OR-122} (2)).
 *
 * <p>Shape (c) puts the firing loop in the gateway, so the data plane gains no background caller of the tenant list
 * ({@code MonitoringBoundaryTest}'s four named callers of {@code tenantsWithActiveInstances} do not change) - but it does gain
 * ONE new enumeration: {@code ApplySchedulePort#tenantsWithDueSchedules}, answering the organizations that have a due
 * schedule. It has exactly one caller, the gateway's {@code ScheduleFirer}; anything else that wants a tenant list is a new
 * enumeration and needs an owner ruling, so it is a red build here and not a convenient call.
 *
 * <p>Three rules. The first two because the SELECT is reached by two names. (1) The PORT method has one caller - and "the port"
 * is the interface AND anything that implements it ({@code assignableTo}), so a call made through the public
 * {@code ApplyScheduleServiceImpl} is the same call (W3 review W3B-2). (2) The PERSISTENCE method
 * ({@code ScheduleRows#dueTenants}, deliberately named differently so the two rules read two names) has one caller, the
 * service that implements the port: a second route to the same SELECT would walk around rule (1). The SQL itself
 * ({@code SELECT DISTINCT tenant_id}) is on {@code TenantEnumerationBoundaryTest}'s allow-list by the repository's name.
 * (3) The three methods that MOVE a schedule - {@code admit}, {@code refuse}, {@code miss} - have one caller, the firer:
 * {@code admit} runs an ordinary apply under the SUBMITTER's name WITHOUT authorizing it, because the re-{@code require} that
 * {@code OR-122} (2) mandates lives in the firer. A "fire now" route or any other caller would skip re-authorization (a
 * deactivated submitter's schedule would run, W4), so it is a red build here.
 *
 *
 * <p>A fourth rule (W5 review W5A-3) guards the schedule's pre-minted id ({@code OR-125} (5)). The id is derived in the data
 * plane precisely so that a caller cannot attach an existing apply to a request by supplying its id; but the reserved-id
 * overloads of {@code ApplyService.submitApply} and {@code rollbackApply} sit on the public {@code dp-api} port, where a
 * gateway route could pass one a body-supplied UUID. Only {@code com.solace.sm.dp.schedule} may call them - through the port
 * or through {@code ApplyServiceImpl}.
 *
 * <p>Proved by a planted second caller of each ({@code SecondScheduleEnumerator}, {@code ReadsDueTenantsPastTheService},
 * {@code AdmitsWithoutReAuthorizing}, and {@code CallsThePortThroughTheImplementation}, which reaches the same methods
 * through the concrete class) AND by the real call sites: each is found calling it, so "only this may" is not vacuous.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class ApplyScheduleBoundaryTest {

    private static final String FIRER = "com.solace.sm.gateway.http.ScheduleFirer";
    private static final String SERVICE = "com.solace.sm.dp.schedule.ApplyScheduleServiceImpl";
    private static final String PLANTED_PORT_CALLER =
            "com.solace.sm.gateway.archfixture.schedule.SecondScheduleEnumerator";
    private static final String PLANTED_ROW_CALLER =
            "com.solace.sm.dp.archfixture.schedule.ReadsDueTenantsPastTheService";
    private static final String PLANTED_ADMITTER =
            "com.solace.sm.gateway.archfixture.schedule.AdmitsWithoutReAuthorizing";
    private static final String PLANTED_THROUGH_IMPL =
            "com.solace.sm.dp.archfixture.schedule.CallsThePortThroughTheImplementation";

    @ArchTest
    static final ArchRule onlyTheFirerEnumeratesOrganizationsWithDueSchedules =
            noClasses().that(DescribedPredicate.not(outermostClassIs(FIRER)))
                    .should().callMethodWhere(target(name("tenantsWithDueSchedules"))
                            .and(target(owner(assignableTo(ApplySchedulePort.class)))))
                    .because("a second caller of the schedule tenant list is a new tenant enumeration (OR-122 (2))");

    @ArchTest
    static final ArchRule onlyTheFirerMovesASchedule =
            noClasses().that(DescribedPredicate.not(outermostClassIs(FIRER)))
                    .should().callMethodWhere(target(name("admit").or(name("refuse")).or(name("miss")))
                            .and(target(owner(assignableTo(ApplySchedulePort.class)))))
                    .because("admit runs an ordinary apply under the submitter's name WITHOUT authorizing it: the re-require "
                            + "OR-122 (2) mandates lives only in the firer (W3 review W3B-2)");

    @ArchTest
    static final ArchRule onlyTheServiceReadsTheDueTenantsRow =
            noClasses().that(DescribedPredicate.not(outermostClassIs(SERVICE)))
                    .should().callMethodWhere(target(name("dueTenants"))
                            .and(target(owner(equivalentTo(ScheduleRows.class)))))
                    .because("the persistence read has one route: the port method the gateway's firer calls (OR-122 (2))");

    private static final String SCHEDULE_PACKAGE = "com.solace.sm.dp.schedule";
    private static final String PLANTED_RESERVER =
            "com.solace.sm.gateway.archfixture.schedule.AttachesAnExistingApplyByReservedId";
    private static final String PLANTED_RESERVER_THROUGH_IMPL =
            "com.solace.sm.dp.archfixture.schedule.ReservesAnApplyIdThroughTheImplementation";

    @ArchTest
    static final ArchRule onlyTheSchedulePackageCallsTheReservedIdOverloads =
            noClasses().that().resideOutsideOfPackage(SCHEDULE_PACKAGE)
                    .should().callMethodWhere(target(owner(assignableTo(ApplyService.class)))
                            .and(target(name("submitApply").and(rawParameterTypes(UUID.class, UUID.class,
                                    SubmitApplyCommand.class, UUID.class))
                                    .or(name("rollbackApply").and(rawParameterTypes(UUID.class, UUID.class,
                                            RollbackCommand.class, UUID.class))))))
                    .because("the reserved id is derived in the data plane so a caller cannot attach an existing apply by "
                            + "supplying its id; the overloads are public on the port, so the convention is a build rule "
                            + "(OR-125 (5), W5 review W5A-3)");

    /** A class and everything nested in it (lambdas' synthetic classes, anonymous classes, records). */
    private static DescribedPredicate<JavaClass> outermostClassIs(String expected) {
        return new DescribedPredicate<>("have outermost class " + expected) {
            @Override
            public boolean test(JavaClass c) {
                return outermost(c).equals(expected);
            }
        };
    }

    private static String outermost(JavaClass c) {
        JavaClass outer = c;
        while (outer.getEnclosingClass().isPresent()) {
            outer = outer.getEnclosingClass().get();
        }
        return outer.getName();
    }

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static Set<String> callersOf(Class<?> owner, String method) {
        JavaClasses production = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
        Set<String> callers = new TreeSet<>();
        production.stream().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(owner))
                .filter(call -> call.getTarget().getName().equals(method))
                .forEach(call -> callers.add(outermost(c))));
        return callers;
    }

    @Test
    void aPlantedSecondPortCallerIsReportedAndTheFirerIsNot() {
        String report = String.join("\n", onlyTheFirerEnumeratesOrganizationsWithDueSchedules
                .evaluate(withPlantedViolations()).getFailureReport().getDetails());
        assertTrue(report.contains(PLANTED_PORT_CALLER), "the planted second caller must be reported. Was: " + report);
        assertFalse(report.contains(FIRER), "the firer is the one named caller and must not be reported. Was: " + report);
    }

    @Test
    void aPlantedCallerThatAdmitsWithoutReAuthorizingIsReportedAndTheFirerIsNot() {
        String report = String.join("\n", onlyTheFirerMovesASchedule.evaluate(withPlantedViolations())
                .getFailureReport().getDetails());
        assertTrue(report.contains(PLANTED_ADMITTER), "the planted 'fire now' route must be reported. Was: " + report);
        assertFalse(report.contains(FIRER), "the firer is the one named caller and must not be reported. Was: " + report);
    }

    @Test
    void aPlantedCallThroughTheConcreteImplementationIsReportedByBothPortRules() {
        String enumeration = String.join("\n", onlyTheFirerEnumeratesOrganizationsWithDueSchedules
                .evaluate(withPlantedViolations()).getFailureReport().getDetails());
        String moves = String.join("\n", onlyTheFirerMovesASchedule.evaluate(withPlantedViolations())
                .getFailureReport().getDetails());
        assertTrue(enumeration.contains(PLANTED_THROUGH_IMPL),
                "a call made through ApplyScheduleServiceImpl is the same enumeration. Was: " + enumeration);
        assertTrue(moves.contains(PLANTED_THROUGH_IMPL),
                "a call made through ApplyScheduleServiceImpl is the same admission. Was: " + moves);
    }

    @Test
    void aPlantedSecondRowCallerIsReportedAndTheServiceIsNot() {
        String report = String.join("\n", onlyTheServiceReadsTheDueTenantsRow.evaluate(withPlantedViolations())
                .getFailureReport().getDetails());
        assertTrue(report.contains(PLANTED_ROW_CALLER), "the planted bypass must be reported. Was: " + report);
        assertFalse(report.contains(SERVICE), "the service is the one named caller and must not be reported. Was: " + report);
    }

    @Test
    void aPlantedReservedIdCallerOutsideTheSchedulePackageIsReportedThroughThePortAndThroughTheImplementation() {
        String report = String.join("\n", onlyTheSchedulePackageCallsTheReservedIdOverloads
                .evaluate(withPlantedViolations()).getFailureReport().getDetails());
        assertTrue(report.contains(PLANTED_RESERVER), "a gateway route passing a client id must be reported. Was: " + report);
        assertTrue(report.contains(PLANTED_RESERVER_THROUGH_IMPL),
                "a call through ApplyServiceImpl is the same call. Was: " + report);
        assertTrue(report.contains("submitApply") && report.contains("rollbackApply"),
                "both overloads are guarded. Was: " + report);
        assertFalse(report.contains(SCHEDULE_PACKAGE + "."),
                "the schedule package is the one named caller and must not be reported. Was: " + report);
    }

    @Test
    void theReservedIdOverloadsReallyHaveTheScheduleServiceAsTheirOnlyCallerSoTheRuleAllowsSomething() {
        JavaClasses production = new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
        Set<String> callers = new TreeSet<>();
        production.stream().forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTargetOwner().isAssignableTo(ApplyService.class))
                .filter(call -> call.getTarget().getName().equals("submitApply")
                        || call.getTarget().getName().equals("rollbackApply"))
                .filter(call -> call.getTarget().getRawParameterTypes().size() == 4)
                .forEach(call -> callers.add(outermost(c))));
        assertEquals(Set.of(SERVICE), callers,
                "the production caller of the reserved-id overloads moved; review it against OR-125 (5) / W5A-3");
    }

    @Test
    void theOneNamedCallerOfEachReallyCallsItSoTheRulesAllowSomething() {
        assertEquals(Set.of(FIRER), callersOf(ApplySchedulePort.class, "tenantsWithDueSchedules"),
                "the production caller of the schedule tenant list moved; review it against OR-122 (2)");
        assertEquals(Set.of(SERVICE), callersOf(ScheduleRows.class, "dueTenants"),
                "the production caller of the due-tenants row moved; review it against OR-122 (2)");
        for (String mover : List.of("admit", "refuse", "miss")) {
            assertEquals(Set.of(FIRER), callersOf(ApplySchedulePort.class, mover),
                    "the production caller of " + mover + " moved; it skips the firer's re-authorization (W4)");
        }
    }
}
