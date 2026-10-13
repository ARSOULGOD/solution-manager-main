package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.ConfigSecretServiceImpl;
import com.solace.sm.dp.inventory.InventoryService;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.1 (M-Q5, M-Q14, M-Q15): the metrics declaration and the per-generation active list are carried by
 * the CANONICAL constructors, and no production path may reach for the older, shorter one.
 *
 * <p>Plan 010's tests build {@code DeclareInstanceCommand}, {@code StageCommand} and three services with the arity they
 * had before this plan; each type keeps that arity as a secondary constructor so those tests stay byte-unchanged. The
 * hazard is a production path using one by mistake: a declare would silently declare no metrics, a stage would record an
 * empty active list (which the collector reads as "nothing is collectable"), and a service built without its metrics
 * repository would drop what it was handed. Nothing would fail; a metric would simply never arrive. So:
 *
 * <ol>
 *   <li>no {@code src/main} class calls any constructor of those five types but the canonical one, and
 *   <li>every class that inserts a generation also writes that generation's active list (there are two such classes, and
 *       a third that forgot is the defect this catches), and
 *   <li>the data plane holds no predicate evaluator: the control plane's is the only one ({@code UI-D2}).
 * </ol>
 *
 * Each rule is proved to fire by a planted fixture under an {@code archfixture} package, which the real run excludes
 * (the {@code ExcludeArchFixtures} option) and the proof run includes.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class MetricsDeclarationConstructorTest {

    private static final String FIXTURE = "com.solace.sm.gateway.archfixture.monitoring.DeclaresWithoutMetrics";
    private static final String MINT_FIXTURE = "com.solace.sm.dp.config.archfixture.MintsAGenerationWithoutAList";
    private static final String EVALUATOR_FIXTURE = "com.solace.sm.dp.archfixture.monitoring.DpPredicateEvaluator";

    /** The five types and the parameter count of the constructor production code must use: their canonical one. */
    private static final Map<String, Integer> CANONICAL = Map.of(
            DeclareInstanceCommand.class.getName(), DeclareInstanceCommand.class.getRecordComponents().length,
            StageCommand.class.getName(), StageCommand.class.getRecordComponents().length,
            InventoryService.class.getName(), longestConstructor(InventoryService.class),
            ConfigRecordServiceImpl.class.getName(), longestConstructor(ConfigRecordServiceImpl.class),
            ConfigSecretServiceImpl.class.getName(), longestConstructor(ConfigSecretServiceImpl.class));

    private static int longestConstructor(Class<?> type) {
        return Arrays.stream(type.getConstructors()).mapToInt(Constructor::getParameterCount).max().orElseThrow();
    }

    private static final DescribedPredicate<JavaConstructorCall> A_SECONDARY_CONSTRUCTOR =
            new DescribedPredicate<>("a constructor of DeclareInstanceCommand, StageCommand, InventoryService, "
                    + "ConfigRecordServiceImpl or ConfigSecretServiceImpl other than its canonical one") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    Integer canonical = CANONICAL.get(call.getTargetOwner().getName());
                    // exempt ONLY constructor delegation: one of the type's own constructors calling its private one
                    // (InventoryService's resolverMayBeAbsent arity, W1 review C-6). A method of the same class (a
                    // factory, a static helper) reaching for a shorter arity is still a production path to refuse.
                    boolean delegation = call.getOrigin() instanceof JavaConstructor
                            && call.getOriginOwner().equals(call.getTargetOwner())
                            && call.getTarget().resolveMember()
                                    .map(c -> c.getModifiers().contains(JavaModifier.PRIVATE)).orElse(false);
                    return canonical != null && call.getTarget().getRawParameterTypes().size() != canonical
                            && !delegation;
                }
            };

    @ArchTest
    static final ArchRule noProductionClassCallsASecondaryConstructor = noClasses()
            .should().callConstructorWhere(A_SECONDARY_CONSTRUCTOR)
            .because("a declare or a stage built without its metrics declaration silently declares no metrics (M-Q5, M-Q15)");

    private static final DescribedPredicate<JavaClass> INSERTS_A_GENERATION = new DescribedPredicate<>(
            "inserts a config generation (ConfigGenerationRows.insert)") {
        @Override
        public boolean test(JavaClass type) {
            return type.getMethodCallsFromSelf().stream().anyMatch(MetricsDeclarationConstructorTest::insertsAGeneration);
        }
    };

    private static boolean insertsAGeneration(JavaMethodCall call) {
        return call.getTargetOwner().getName().equals("com.solace.sm.dp.config.ConfigGenerationRows")
                && call.getName().equals("insert");
    }

    private static final DescribedPredicate<JavaClass> WRITES_THE_ACTIVE_LIST = new DescribedPredicate<>(
            "writes the generation's active metric list (MetricDeclarationRows.insertActiveMetrics)") {
        @Override
        public boolean test(JavaClass type) {
            return type.getMethodCallsFromSelf().stream()
                    .anyMatch(c -> c.getTargetOwner().getName().equals("com.solace.sm.dp.metrics.declare.MetricDeclarationRows")
                            && c.getName().equals("insertActiveMetrics"));
        }
    };

    private static final ArchCondition<JavaClass> WRITES_ITS_LIST = new ArchCondition<>(
            "write the generation's active metric list (MetricDeclarationRows.insertActiveMetrics)") {
        @Override
        public void check(JavaClass type, ConditionEvents events) {
            if (!WRITES_THE_ACTIVE_LIST.test(type)) {
                events.add(SimpleConditionEvent.violated(type, type.getName()
                        + " inserts a config generation and never writes its active metric list"));
            }
        }
    };

    @ArchTest
    static final ArchRule everyGenerationIsBornWithItsList = classes().that(INSERTS_A_GENERATION)
            .should(WRITES_ITS_LIST)
            .because("a generation without a recorded list reads, to the collector, as 'nothing is collectable' (M-Q15)");

    @ArchTest
    static final ArchRule theDataPlaneEvaluatesNoPredicate = noClasses()
            .that().resideInAPackage("com.solace.sm.dp..")
            .should().haveSimpleNameEndingWith("Evaluator")
            .orShould().dependOnClassesThat().haveSimpleNameEndingWith("Evaluator")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("com.solace.sm.cp.api.designer.MetricApplicability")
            .because("the control plane's evaluator is the only one (UI-D2); the data plane stores the list it is handed");

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
    void theCanonicalConstructorIsTheLongestOfEachOfTheFiveTypesAndNeverTheOneTheOlderTestsUse() {
        // 12 since plan 014: it also carries the connection binding and the deployment mode; plan 011's ten-argument form
        // is now a secondary constructor too, refused to production like plan 010's nine-argument one.
        // 13 since the instance role (UAT e786913d, sm_data V042): the twelve-argument form is secondary too. UAT added the
        // component without moving this count; the OR-147 reconciliation does (the test failed on UAT's own head).
        assertEquals(13, CANONICAL.get(DeclareInstanceCommand.class.getName()));
        assertEquals(12, CANONICAL.get(StageCommand.class.getName()));
        // 8 since plan 013 T1.1: the service also takes the SecretResolver that opens a stored key for the replay compare.
        // 10 since plan 008 S3: it also takes the LadderRecorder and the ApplyEventEmitter a placement change settles the
        // configuration ladder through (BE-D47, OQ-8.10 (a)).
        // 11 since plan 013 T1.4: it also takes the HostKeyScanService the HTTP registration's pre-check asks.
        assertEquals(11, CANONICAL.get(InventoryService.class.getName()));
        assertEquals(7, CANONICAL.get(ConfigRecordServiceImpl.class.getName()));
        assertEquals(7, CANONICAL.get(ConfigSecretServiceImpl.class.getName()));
    }

    @Test
    void aProductionClassCallingEachSecondaryConstructorIsReportedForEveryOneOfTheFiveTypes() {
        Set<String> reported = violators(noProductionClassCallsASecondaryConstructor);

        for (String type : CANONICAL.keySet()) {
            String simple = type.substring(type.lastIndexOf('.') + 1);
            assertTrue(reported.stream().anyMatch(v -> v.contains(FIXTURE) && v.contains(simple + ".<init>")),
                    "the planted call to " + simple + "'s secondary constructor must be reported. Reported: " + reported);
        }
    }

    @Test
    void theCanonicalCallsOfTheRealCompositionRootsAreNotReported() {
        JavaClasses all = withPlantedViolations();
        for (String root : new String[] {"com.solace.sm.dp.inventory.InventoryConfiguration",
                "com.solace.sm.dp.config.ConfigRecordConfiguration", "com.solace.sm.dp.config.ConfigSecretConfiguration",
                "com.solace.sm.gateway.http.DeclareOrchestrator", "com.solace.sm.gateway.http.DesignerOrchestrator",
                "com.solace.sm.gateway.http.AcceptRuntimeOrchestrator",
                "com.solace.sm.gateway.http.ConfigSecretOrchestrator"}) {
            assertTrue(all.stream().anyMatch(c -> c.getName().equals(root)),
                    root + " must be in the analysed set, or the absence check proves nothing");
        }
        Set<String> reported = violators(noProductionClassCallsASecondaryConstructor);
        assertFalse(reported.stream().anyMatch(v -> !v.contains(FIXTURE)),
                "a production class calls a secondary constructor: " + reported);
        // ... and they DO call the canonical ones, so the allowance is not an accident of the rule matching nothing
        assertTrue(callsConstructor(all, "com.solace.sm.gateway.http.DeclareOrchestrator", DeclareInstanceCommand.class));
        assertTrue(callsConstructor(all, "com.solace.sm.gateway.http.DesignerOrchestrator", StageCommand.class));
        assertTrue(callsConstructor(all, "com.solace.sm.dp.inventory.InventoryConfiguration", InventoryService.class));
        assertTrue(callsConstructor(all, "com.solace.sm.dp.config.ConfigRecordConfiguration", ConfigRecordServiceImpl.class));
        assertTrue(callsConstructor(all, "com.solace.sm.dp.config.ConfigSecretConfiguration", ConfigSecretServiceImpl.class));
    }

    private static boolean callsConstructor(JavaClasses all, String caller, Class<?> target) {
        return all.get(caller).getConstructorCallsFromSelf().stream()
                .filter(c -> c.getTargetOwner().isEquivalentTo(target))
                .anyMatch(c -> c.getTarget().getRawParameterTypes().size() == CANONICAL.get(target.getName()));
    }

    @Test
    void aClassThatInsertsAGenerationAndForgetsTheListIsReportedAndTheTwoRealMintsAreNot() {
        Set<String> reported = violators(everyGenerationIsBornWithItsList);

        assertTrue(reported.stream().anyMatch(v -> v.contains(MINT_FIXTURE)),
                "the planted mint without a list must be reported. Reported: " + reported);
        JavaClasses all = withPlantedViolations();
        for (String real : new String[] {"com.solace.sm.dp.config.ConfigRecordServiceImpl",
                "com.solace.sm.dp.config.ConfigSecretServiceImpl"}) {
            assertTrue(all.get(real).getMethodCallsFromSelf().stream()
                    .anyMatch(MetricsDeclarationConstructorTest::insertsAGeneration),
                    real + " must be one of the classes the rule governs, or its absence from the report proves nothing");
            assertFalse(reported.stream().anyMatch(v -> v.contains(real)), real + " was reported: " + reported);
        }
    }

    @Test
    void anEvaluatorInTheDataPlaneIsReportedAndNothingRealIs() {
        Set<String> reported = violators(theDataPlaneEvaluatesNoPredicate);

        assertTrue(reported.stream().anyMatch(v -> v.contains(EVALUATOR_FIXTURE)),
                "the planted data-plane evaluator must be reported. Reported: " + reported);
        assertFalse(reported.stream().anyMatch(v -> !v.contains(EVALUATOR_FIXTURE)),
                "a real data-plane class is an evaluator or depends on one: " + reported);
    }
}
