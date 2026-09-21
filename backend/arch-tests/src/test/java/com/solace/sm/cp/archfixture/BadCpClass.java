package com.solace.sm.cp.archfixture;

import com.solace.sm.dp.DpCoreMarker;

/**
 * Planted {@code I7} violation: a control-plane class reaching into the data plane.
 *
 * <p>This class exists ONLY so {@code PlaneBoundaryTest.cpNeverImportsDpCatchesAPlantedViolation}
 * can prove the rule fires — the repo's guard-self-test discipline: a rule that cannot fire is
 * decoration. It is test-scope in {@code arch-tests}, so it ships in no artifact, and the real
 * {@code @ArchTest} run excludes {@code ..archfixture..} via
 * {@code PlaneBoundaryTest.ExcludeArchFixtures}.
 *
 * <p><b>Do not copy this pattern into production code.</b>
 */
public final class BadCpClass {

    private BadCpClass() {
    }

    /** The forbidden edge: {@code com.solace.sm.cp..} depending on {@code com.solace.sm.dp..}. */
    public static DpCoreMarker forbiddenCrossPlaneReference() {
        return null;
    }
}
