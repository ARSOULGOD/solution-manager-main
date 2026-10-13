package com.solace.sm.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Who may move an undeclare (plan 012 T5; {@code ops-501.md} section 4.4, {@code OPS-Q2} U-C).
 *
 * <p>Undeclare writes two planes and {@code I7} forbids a distributed transaction, so the protocol (DP intent, CP release,
 * DP finish) is only sound if the callers are exactly the ones the design names:
 * <ol>
 *   <li>{@code InstanceRetirementGovernance#deregister} - the ONE control-plane write - is called by
 *       {@code UndeclareOrchestrator} alone, because only it runs under the requesting session's audit context
 *       ({@code OR-25}) after {@code instance.undeclare} passed. The lazy sweep runs inside <i>another</i> user's request and
 *       must never call it.</li>
 *   <li>{@code InstanceRetirementService#beginRetire} (the intent, taken after the verb passed) is the orchestrator's alone;
 *       {@code completeRetire} is the orchestrator's and the sweep's; {@code rollBackRetire} and {@code retiring} are the
 *       sweep's alone.</li>
 *   <li>{@code UndeclareSweep} calls no control-plane method but the one read, {@code InstanceRetirementGovernance#retired}
 *       ("the sweep adds no tenant enumeration and calls no {@code cp-api} write").</li>
 * </ol>
 * "The port" is the interface AND anything that implements it ({@code assignableTo}), so a call through the concrete class is
 * the same call. Proved by planted violations ({@code SweepThatWritesTheControlPlane}, {@code RollsBackFromAnywhere},
 * {@code SweepThatReachesTheControlPlaneElsewhere}) AND by the real call sites: each is found calling what it may, so "only
 * these may" is not vacuous. The sweep adds no tenant enumeration: that is {@code TenantEnumerationBoundaryTest} and
 * {@code MonitoringBoundaryTest}, which this task leaves unchanged.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class UndeclareBoundaryTest {

    private static final String ORCHESTRATOR = "com.solace.sm.gateway.http.UndeclareOrchestrator";
    private static final String SWEEP = "com.solace.sm.gateway.http.UndeclareSweep";
    private static final String PLANTED_WRITER = "com.solace.sm.gateway.archfixture.undeclare.SweepThatWritesTheControlPlane";
    private static final String PLANTED_MOVER = "com.solace.sm.gateway.archfixture.undeclare.RollsBackFromAnywhere";
    private static final String PLANTED_SWEEP =
            "com.solace.sm.gateway.archfixture.undeclare.SweepThatReachesTheControlPlaneElsewhere";

    @ArchTest
    static final ArchRule onlyTheOrchestratorWritesTheControlPlaneRetirement =
            noClasses().that(DescribedPredicate.not(outermostClassIs(ORCHESTRATOR)))
                    .should().callMethodWhere(target(name("deregister"))
                            .and(target(owner(assignableTo(InstanceRetirementGovernance.class)))))
                    .because("the control-plane release is audited under the requesting session; the sweep has none (OR-25)");

    @ArchTest
    static final ArchRule onlyTheOrchestratorBeginsAnUndeclare =
            noClasses().that(DescribedPredicate.not(outermostClassIs(ORCHESTRATOR)))
                    .should().callMethodWhere(target(name("beginRetire"))
                            .and(target(owner(assignableTo(InstanceRetirementService.class)))))
                    .because("the intent is recorded only after instance.undeclare passed, by the one class that checked");

    @ArchTest
    static final ArchRule onlyTheOrchestratorAndTheSweepFinishAnUndeclare =
            noClasses().that(DescribedPredicate.not(outermostClassIs(ORCHESTRATOR))
                            .and(DescribedPredicate.not(outermostClassIs(SWEEP))))
                    .should().callMethodWhere(target(name("completeRetire"))
                            .and(target(owner(assignableTo(InstanceRetirementService.class)))))
                    .because("step 5 has two callers: the DELETE that did steps 3-4, and the sweep that finds step 4 done");

    @ArchTest
    static final ArchRule onlyTheSweepRollsBackOrListsRetiringInstances =
            noClasses().that(DescribedPredicate.not(outermostClassIs(SWEEP)))
                    .should().callMethodWhere(target(name("rollBackRetire").or(name("retiring")))
                            .and(target(owner(assignableTo(InstanceRetirementService.class)))))
                    .because("the port never decides it is time; the sweep's window is the one place that does (section 4.4)");

    @ArchTest
    static final ArchRule theSweepCallsNoControlPlaneMethodButTheOneRead = sweepCallsOnlyTheRead(SWEEP);

    private static ArchRule sweepCallsOnlyTheRead(String sweepClass) {
        DescribedPredicate<JavaMethodCall> beyondTheOneRead = new DescribedPredicate<>(
                "call a cp-api method other than InstanceRetirementGovernance#retired") {
            @Override
            public boolean test(JavaMethodCall call) {
                boolean inCpApi = call.getTargetOwner().getPackageName().startsWith("com.solace.sm.cp.api");
                boolean theOneRead = call.getTarget().getName().equals("retired")
                        && call.getTargetOwner().isAssignableTo(InstanceRetirementGovernance.class);
                return inCpApi && !theOneRead;
            }
        };
        return noClasses().that(outermostClassIs(sweepClass)).should().callMethodWhere(beyondTheOneRead)
                .because("the sweep runs inside another user's request: it reads the control plane and never writes it");
    }

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

    private static String report(ArchRule rule) {
        return String.join("\n", rule.evaluate(withPlantedViolations()).getFailureReport().getDetails());
    }

    @Test
    void aPlantedSecondControlPlaneWriterIsReportedAndTheOrchestratorIsNot() {
        String report = report(onlyTheOrchestratorWritesTheControlPlaneRetirement);
        assertTrue(report.contains(PLANTED_WRITER), "the planted second writer must be reported. Was: " + report);
        assertFalse(report.contains(ORCHESTRATOR), "the orchestrator is the one named caller. Was: " + report);
    }

    @Test
    void aPlantedBeginOutsideTheOrchestratorIsReported() {
        String report = report(onlyTheOrchestratorBeginsAnUndeclare);
        assertTrue(report.contains(PLANTED_MOVER), "a begin made without the verb having passed. Was: " + report);
        assertFalse(report.contains(ORCHESTRATOR), report);
    }

    @Test
    void aPlantedRollBackAndListOutsideTheSweepAreReportedAndTheSweepIsNot() {
        String report = report(onlyTheSweepRollsBackOrListsRetiringInstances);
        assertTrue(report.contains(PLANTED_MOVER), "a roll-back / list made outside the sweep. Was: " + report);
        assertFalse(report.contains(SWEEP), "the sweep is the one named caller. Was: " + report);
        assertTrue(report.contains("rollBackRetire") && report.contains("retiring"),
                "both methods are held to the sweep. Was: " + report);
    }

    @Test
    void theSweepRuleIsProvedByPointingItAtAClassThatCallsMoreThanTheRead() {
        String report = report(sweepCallsOnlyTheRead(PLANTED_SWEEP));
        assertTrue(report.contains("registered"), "a second control-plane method is reported. Was: " + report);
        assertFalse(report.contains("retired("), "the one allowed read is not. Was: " + report);
        // and a planted class calling deregister is caught by the same rule when it is the sweep
        String writer = report(sweepCallsOnlyTheRead(PLANTED_WRITER));
        assertTrue(writer.contains("deregister"), "Was: " + writer);
    }

    @Test
    void theNamedCallersReallyCallSoTheRulesAllowSomething() {
        assertEquals(Set.of(ORCHESTRATOR), callersOf(InstanceRetirementGovernance.class, "deregister"));
        assertEquals(Set.of(ORCHESTRATOR), callersOf(InstanceRetirementService.class, "beginRetire"));
        assertEquals(Set.of(ORCHESTRATOR, SWEEP), callersOf(InstanceRetirementService.class, "completeRetire"));
        assertEquals(Set.of(SWEEP), callersOf(InstanceRetirementService.class, "rollBackRetire"));
        assertEquals(Set.of(SWEEP), callersOf(InstanceRetirementService.class, "retiring"));
        assertEquals(Set.of(SWEEP), callersOf(InstanceRetirementGovernance.class, "retired"),
                "the control-plane read the sweep needs has one caller");
    }
}
