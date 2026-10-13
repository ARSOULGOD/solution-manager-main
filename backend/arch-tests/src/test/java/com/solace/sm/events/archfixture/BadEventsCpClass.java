package com.solace.sm.events.archfixture;

import com.solace.sm.cp.api.CpMarker;

/**
 * Planted {@code I7} violation: the plane-neutral {@code mesh-events} module taking a control-plane
 * dependency.
 *
 * <p>This is the conduit case the plane rules alone do not cover. {@code dependOnClassesThat()}
 * checks DIRECT dependencies only, so {@code dpNeverImportsCp} would stay silent on
 * {@code dp-core → mesh-events → cp-api} — and Maven stays silent too, because a compile-scope
 * dependency of {@code mesh-events} lands transitively on {@code dp-core}'s classpath. The only
 * thing that closes it is holding {@code mesh-events} plane-neutral: it depends on neither plane,
 * which is precisely what makes it safe for either plane to consume.
 *
 * <p>Note the reference is to {@code cp-api}, not {@code cp-core} — even the service-interface
 * module is off-limits to the shared module.
 *
 * <p><b>Do not copy this pattern into production code.</b> Task 9 fills {@code mesh-events}.
 */
public final class BadEventsCpClass {

    private BadEventsCpClass() {
    }

    /** The forbidden edge: {@code com.solace.sm.events..} depending on {@code com.solace.sm.cp..}. */
    public static CpMarker forbiddenControlPlaneReference() {
        return null;
    }
}
