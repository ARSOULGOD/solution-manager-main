package com.solace.sm.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.model.PlacementId;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Plan 008 S3 / {@code BE-D46}: <b>there is no enumerator of placements from apply history.</b>
 *
 * <p>An instance-wide stop used to learn the instance's placements from {@code SELECT DISTINCT placement_id FROM
 * apply_request}: every placement SM had EVER acted on. That set includes a retired placement (so the stop failed over a
 * placement nobody has any more, having stopped every live one) and omits a placement added and never acted on. The set a
 * stop addresses is the registry's live placements, through {@link LivePlacements}. This test keeps it that way, by TYPE
 * and never by counting text matches ({@code L12}):
 *
 * <ol>
 *   <li><b>no method of any class in {@code apply.persistence} returns a collection of {@link PlacementId}</b> (a
 *       {@code List}, {@code Set}, {@code Collection} or a stream of them): the shape of the deleted
 *       {@code placementsOf}. The persistence package is where {@code apply_request} is read, so a history enumerator can
 *       only be written there. {@code jdbcIsConfinedToPersistencePackages} already keeps SQL out of every other package;
 *   <li><b>every {@link LivePlacements} implementation answers from the registry</b>: it depends on nothing in
 *       {@code apply.persistence}, where the history lives;
 *   <li><b>and there is exactly one</b>, {@code RegistryLivePlacements}: a second source of "which placements" is a second
 *       definition of it.
 * </ol>
 *
 * Each rule is proved to fire by a planted fixture under an {@code archfixture} package, which the real run excludes and
 * the proof run includes, and each is proved not to over-fire by a legal counterpart.
 */
class PlacementEnumerationTest {

    private static final String PERSISTENCE = "com.solace.sm.dp.apply.persistence..";
    private static final String PLANTED_HISTORY_ENUMERATOR =
            "com.solace.sm.dp.apply.persistence.archfixture.HistoryPlacementEnumerator";
    private static final String LEGAL_RETURNS = "com.solace.sm.dp.apply.persistence.archfixture.LegalPlacementReturns";
    private static final String PLANTED_HISTORY_SOURCE = "com.solace.sm.dp.inventory.archfixture.HistoryLivePlacements";
    private static final String REGISTRY_SOURCE = "com.solace.sm.dp.inventory.RegistryLivePlacements";

    private static JavaClasses production() {
        return new ClassFileImporter().withImportOption(new PlaneBoundaryTest.ExcludeArchFixtures())
                .importPackages("com.solace.sm");
    }

    private static JavaClasses withPlanted() {
        return new ClassFileImporter().importPackages("com.solace.sm");
    }

    /** A collection (or stream) whose element type is a placement id: any raw type involved in the return type. */
    private static boolean enumeratesPlacementIds(JavaMethod method) {
        Set<JavaClass> involved = Set.copyOf(method.getReturnType().getAllInvolvedRawTypes());
        boolean placementId = involved.stream().anyMatch(t -> t.isEquivalentTo(PlacementId.class));
        boolean collection = involved.stream().anyMatch(t -> t.isAssignableTo(Collection.class)
                || t.isAssignableTo(java.util.stream.Stream.class) || t.isAssignableTo(Iterable.class)
                || t.isArray());
        return placementId && collection;
    }

    private static final ArchCondition<JavaMethod> NOT_ENUMERATE_PLACEMENT_IDS = new ArchCondition<>(
            "not return a collection of PlacementId") {
        @Override
        public void check(JavaMethod method, ConditionEvents events) {
            if (enumeratesPlacementIds(method)) {
                events.add(SimpleConditionEvent.violated(method, method.getFullName()
                        + " enumerates placements from apply persistence: " + method.getReturnType().getName()));
            }
        }
    };

    private static final ArchRule NO_HISTORY_ENUMERATOR = methods().that().areDeclaredInClassesThat()
            .resideInAPackage(PERSISTENCE).should(NOT_ENUMERATE_PLACEMENT_IDS)
            .because("BE-D46: the placements an instance has are the registry's; apply_request history includes retired ones");

    private static final ArchRule SOURCES_READ_THE_REGISTRY = classes().that().implement(LivePlacements.class)
            .should(new ArchCondition<JavaClass>("not depend on apply persistence (the history)") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    type.getDirectDependenciesFromSelf().stream()
                            .filter(d -> d.getTargetClass().getPackageName().startsWith("com.solace.sm.dp.apply.persistence"))
                            .forEach(d -> events.add(SimpleConditionEvent.violated(type, type.getName()
                                    + " implements LivePlacements and depends on " + d.getTargetClass().getName()
                                    + ": it must answer from the registry, never the apply history")));
                }
            }).because("BE-D46");

    private static Set<String> liveSources(JavaClasses classes) {
        return classes.stream().filter(c -> c.isAssignableTo(LivePlacements.class))
                .filter(c -> !c.isInterface() && !c.isAnonymousClass())
                .map(JavaClass::getName).collect(Collectors.toCollection(TreeSet::new));
    }

    // ---- the real tree ---------------------------------------------------------------------------------------

    @Test
    void noPersistenceClassEnumeratesPlacementsFromApplyHistory() {
        assertDoesNotThrow(() -> NO_HISTORY_ENUMERATOR.check(production()));
    }

    @Test
    void everyLivePlacementsImplementationAnswersFromTheRegistryAndNotTheApplyHistory() {
        assertDoesNotThrow(() -> SOURCES_READ_THE_REGISTRY.check(production()));
    }

    @Test
    void theOnlyLivePlacementsInProductionIsTheRegistrys() {
        assertEquals(Set.of(REGISTRY_SOURCE), liveSources(production()),
                "a second source of 'which placements an instance has' is a second definition of it");
    }

    @Test
    void theRuleScansSomethingTheDeletedMethodsHomeStillHasMethodsAndTheRegistryReallyHasASource() {
        JavaClasses production = production();
        assertTrue(production.stream().anyMatch(c -> c.getName().equals(
                "com.solace.sm.dp.apply.persistence.ApplyRequestRepository")
                && !c.getMethods().isEmpty()), "ApplyRequestRepository is in the scan, with methods");
        assertTrue(production.stream().anyMatch(c -> c.getName().equals(REGISTRY_SOURCE)));
        assertTrue(production.stream().filter(c -> c.getPackageName().startsWith("com.solace.sm.dp.apply.persistence"))
                .flatMap(c -> c.getMethods().stream()).count() > 100, "the persistence package is scanned, not skipped");
    }

    // ---- the proofs ------------------------------------------------------------------------------------------

    @Test
    void aPlantedHistoryEnumeratorIsReportedAndTheLegalReturnsAreNot() {
        AssertionError failure = assertThrows(AssertionError.class, () -> NO_HISTORY_ENUMERATOR.check(withPlanted()));

        assertTrue(failure.getMessage().contains(PLANTED_HISTORY_ENUMERATOR + ".placementsOf"),
                "the planted enumerator must be reported. Was: " + failure.getMessage());
        assertFalse(failure.getMessage().contains(LEGAL_RETURNS),
                "a single id, an optional id and a list of strings are not enumerations: " + failure.getMessage());
    }

    @Test
    void aPlantedHistoryBackedSourceIsReportedAndTheRegistrysIsNot() {
        AssertionError failure = assertThrows(AssertionError.class, () -> SOURCES_READ_THE_REGISTRY.check(withPlanted()));

        assertTrue(failure.getMessage().contains(PLANTED_HISTORY_SOURCE),
                "the planted source must be reported. Was: " + failure.getMessage());
        assertFalse(failure.getMessage().contains(REGISTRY_SOURCE), failure.getMessage());
        assertTrue(liveSources(withPlanted()).contains(PLANTED_HISTORY_SOURCE),
                "and the second-source rule sees it too");
    }

    @Test
    void theEnumerationPredicateSeesTheShapesItIsMeantFor() throws Exception {
        JavaClasses planted = withPlanted();
        JavaClass fixture = planted.get(PLANTED_HISTORY_ENUMERATOR);
        assertTrue(enumeratesPlacementIds(fixture.getMethod("placementsOf")));
        JavaClass legal = planted.get(LEGAL_RETURNS);
        assertFalse(enumeratesPlacementIds(legal.getMethod("one")));
        assertFalse(enumeratesPlacementIds(legal.getMethod("maybe")));
        assertFalse(enumeratesPlacementIds(legal.getMethod("somethingElse")));
    }
}
