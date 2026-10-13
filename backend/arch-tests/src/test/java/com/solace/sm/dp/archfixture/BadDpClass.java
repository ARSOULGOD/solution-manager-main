package com.solace.sm.dp.archfixture;

import com.solace.sm.cp.CpCoreMarker;

/**
 * Planted {@code I7} violation: a data-plane class reaching into the control plane.
 *
 * <p>Exists ONLY so {@code PlaneBoundaryTest.dpNeverImportsCpCatchesAPlantedViolation} can prove
 * that rule fires too. Excluded from the real {@code @ArchTest} run.
 *
 * <p><b>Do not copy this pattern into production code.</b>
 */
public final class BadDpClass {

    private BadDpClass() {
    }

    /** The forbidden edge: {@code com.solace.sm.dp..} depending on {@code com.solace.sm.cp..}. */
    public static CpCoreMarker forbiddenCrossPlaneReference() {
        return null;
    }
}
