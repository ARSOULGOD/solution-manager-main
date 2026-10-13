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
import org.junit.jupiter.api.Test;

/**
 * D-ACCEPT's {@code V-4}, as a property of the build (plan 010 T10.4, {@code BE-D41}).
 *
 * <p>The code that reads a host's file back is allowed ONE way to a stored secret: the compare-only dp-api port
 * ({@code SecretCompare}), which answers "same" or "differs" and returns nothing. It must not hold the secret store's
 * read or write side, because a read-back that can fetch a secret can return it, and one that can store can adopt a value
 * the operator never entered through SM ({@code I6}).
 *
 * <p>The ONE class in that package tree that names the secret package is {@code RuntimeReadBackConfiguration}, which builds
 * the comparator from the resolver; it is allowed by class, so a second wiring class in the family is a finding.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class ReadBackSecretBoundaryTest {

    private static final String WIRING = "com.solace.sm.dp.apply.runtime.readback.RuntimeReadBackConfiguration";
    private static final String FIXTURE = "com.solace.sm.dp.apply.runtime.readback.archfixture.BadReadBackSecretReaderClass";

    @ArchTest
    static final ArchRule readBackNeverHoldsASecretReader =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.runtime.readback..")
                    .and().doNotHaveFullyQualifiedName(WIRING)
                    .should().dependOnClassesThat().resideInAPackage("com.solace.sm.dp.secret..")
                    .because("a read-back secret may be compared (SecretCompare) and dropped, never fetched, returned or stored");

    private static JavaClasses withPlantedViolations() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    private static Set<String> violators(ArchRule rule) {
        Set<String> out = new TreeSet<>();
        rule.evaluate(withPlantedViolations()).getFailureReport().getDetails().forEach(out::add);
        return out;
    }

    @Test
    void aReadBackClassHoldingTheSecretStoreIsReportedAndTheRealOnesAreNot() {
        Set<String> reported = violators(readBackNeverHoldsASecretReader);
        assertTrue(reported.stream().anyMatch(v -> v.contains(FIXTURE)),
                "the planted class holding a SecretResolver must be reported. Reported: " + reported);

        JavaClasses all = withPlantedViolations();
        for (String real : new String[] {"com.solace.sm.dp.apply.runtime.readback.RuntimeReadBackServiceImpl",
                "com.solace.sm.dp.apply.runtime.readback.ReadBackMapper",
                "com.solace.sm.dp.apply.runtime.readback.ConfigFileParser", WIRING}) {
            assertTrue(all.stream().anyMatch(c -> c.getName().equals(real)),
                    real + " must be in the analysed set, or the absence check proves nothing");
            assertFalse(reported.stream().anyMatch(v -> v.contains(real)),
                    "the rule rejects " + real + ", code it governs. Reported: " + reported);
        }
    }

    @Test
    void theWiringAllowanceIsByClassNotByPackage() {
        // The one wiring class names the secret package, and is allowed only because it is named above.
        JavaClasses all = withPlantedViolations();
        assertTrue(all.stream().filter(c -> c.getName().equals(WIRING))
                .anyMatch(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("com.solace.sm.dp.secret"))),
                "if the wiring class stopped naming the secret package the allowance would be dead weight");
    }
}
