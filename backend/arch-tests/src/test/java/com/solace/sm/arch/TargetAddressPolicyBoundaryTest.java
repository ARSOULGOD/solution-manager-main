package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.inventory.StrictTargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.2 (D-REG section 7.3, R7): the address rule has no off switch.
 *
 * <p>{@code TargetAddressPolicy} is the seam the data plane's connectors check a target's host through. Production
 * wires {@link StrictTargetAddressPolicy} and nothing else; the permissive implementation an integration test needs
 * (Testcontainers and stub servers live on loopback) exists only in test sources. So the rule is that <b>no main-source
 * class other than the strict policy implements the interface</b>. A permissive class in production code, switched by a
 * property or a profile, is exactly the misconfiguration this makes impossible to ship.
 *
 * <p>A lambda is invisible to this rule, so the interface is deliberately not a functional interface (it has two
 * abstract methods); the anonymous-class form is covered by a second planted fixture.
 *
 * <p>The rule runs against the production tree ({@link PlaneBoundaryTest.ExcludeArchFixtures}); test classes of
 * {@code dp-core} are not on this module's classpath. The proof run includes the planted fixtures.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class TargetAddressPolicyBoundaryTest {

    private static final String CLASS_FIXTURE = "com.solace.sm.dp.inventory.archfixture.BadPermissiveTargetAddressPolicy";
    private static final String ANONYMOUS_FIXTURE = "com.solace.sm.dp.inventory.archfixture.BadAnonymousTargetAddressPolicy";

    @ArchTest
    static final ArchRule onlyTheStrictPolicyImplementsTheTargetAddressPolicy = classes()
            .that().implement(TargetAddressPolicy.class)
            .should().haveFullyQualifiedName(StrictTargetAddressPolicy.class.getName())
            .because("no production setting may relax the address rule; a permissive policy lives in test sources only");

    // ---- the proofs ----------------------------------------------------------------------------------------

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static Set<String> violators(ArchRule rule) {
        Set<String> out = new TreeSet<>();
        rule.evaluate(withPlantedViolations()).getFailureReport().getDetails().forEach(out::add);
        return out;
    }

    @Test
    void aMainSourcePolicyThatAcceptsAnythingIsReported() {
        Set<String> reported = violators(onlyTheStrictPolicyImplementsTheTargetAddressPolicy);

        assertTrue(reported.stream().anyMatch(v -> v.contains(CLASS_FIXTURE)),
                "the planted permissive policy must be reported. Reported: " + reported);
    }

    @Test
    void anAnonymousPolicyInAMainSourceBeanMethodIsReportedToo() {
        Set<String> reported = violators(onlyTheStrictPolicyImplementsTheTargetAddressPolicy);

        assertTrue(reported.stream().anyMatch(v -> v.contains(ANONYMOUS_FIXTURE)),
                "the planted anonymous policy must be reported. Reported: " + reported);
    }

    @Test
    void theStrictPolicyIsInTheAnalysedSetAndIsTheOnlyRealImplementationAndTheInterfaceItselfIsNotReported() {
        JavaClasses all = withPlantedViolations();
        assertTrue(all.stream().anyMatch(c -> c.getName().equals(StrictTargetAddressPolicy.class.getName())),
                "the strict policy must be in the analysed set, or its absence from the report proves nothing");
        assertTrue(all.get(StrictTargetAddressPolicy.class.getName()).isAssignableTo(TargetAddressPolicy.class),
                "the strict policy must be one of the classes the rule governs");

        Set<String> reported = violators(onlyTheStrictPolicyImplementsTheTargetAddressPolicy);

        assertFalse(reported.stream().anyMatch(v -> !v.contains("archfixture")),
                "a real class is reported (the strict policy, or the interface itself): " + reported);
    }
}
