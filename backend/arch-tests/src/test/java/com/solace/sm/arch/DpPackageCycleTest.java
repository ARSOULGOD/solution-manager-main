package com.solace.sm.arch;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 W4 note (e): {@code dp.apply.runtime..} (the seam and its adapters) does not depend on {@code dp.config..} (the ladder), because {@code dp.config} depends on {@code dp.apply.runtime}. The one place it did,
 * {@code AgentRuntimeAdapter}'s import of {@code ConfigLadder.START_TOLERANCE}, now reads {@code dp.apply.model.StartTolerance}.
 * Nothing forbade the cycle before; this does. The older {@code readback -> OverlayKeys} edge is frozen by name below. A planted class that imports the ladder from the agent package turns it red.
 */
@AnalyzeClasses(packages = "com.solace.sm", importOptions = PlaneBoundaryTest.ExcludeArchFixtures.class)
class DpPackageCycleTest {

    /**
     * The one frozen debt: {@code runtime.readback.RuntimeReadBackConfiguration} calls {@code config.OverlayKeys#ownsBaseline}, an edge
     * older than W4 and outside its brief. It is ignored BY NAME (the dependency target, not the whole class), so a new edge to any other
     * {@code dp.config} class from anywhere in {@code dp.apply.runtime..} is a red build, and the debt can only shrink.
     */
    @ArchTest
    static final ArchRule theRuntimeSeamNeverDependsOnTheLadder =
            noClasses().that().resideInAPackage("com.solace.sm.dp.apply.runtime..")
                    .should().dependOnClassesThat(resideInAPackage("com.solace.sm.dp.config..")
                            .and(DescribedPredicate.not(JavaClass.Predicates.simpleName("OverlayKeys"))))
                    .because("dp.config depends on dp.apply.runtime; the reverse edge is a package cycle (plan 015 W4 note (e)); "
                            + "OverlayKeys is the one frozen pre-existing edge");

    @Test
    void aPlantedRuntimeClassThatImportsTheLadderTurnsTheRuleRed() {
        AssertionError failure = assertThrows(AssertionError.class, () -> theRuntimeSeamNeverDependsOnTheLadder
                .check(new ClassFileImporter().importPackages("com.solace.sm")));
        assertTrue(failure.getMessage().contains("ImportsTheLadder"), failure::getMessage);
    }
}
