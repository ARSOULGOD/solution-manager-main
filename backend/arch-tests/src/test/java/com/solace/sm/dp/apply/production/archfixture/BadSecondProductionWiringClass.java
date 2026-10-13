package com.solace.sm.dp.apply.production.archfixture;

import com.solace.sm.dp.apply.runtime.RuntimeAdapter;

/**
 * Planted violation: a SECOND wiring class naming the mutating adapter. {@code ProductionApplyConfiguration} is
 * allowed to, by class; a neighbour is not. Proves the allowance in
 * {@code ApplyPathBoundaryTest.onlyProductionCodeTouchesTheMutatingRuntimeAdapter} is not a package-wide hole.
 */
public final class BadSecondProductionWiringClass {

    RuntimeAdapter adapter() {
        return null;
    }
}
