package com.solace.sm.gateway.archfixture;

import com.solace.sm.cp.CpCoreMarker;
import com.solace.sm.dp.DpCoreMarker;

/**
 * Planted {@code I7} violation: the gateway reaching past the {@code -api} modules into plane
 * internals ({@code cp-core} / {@code dp-core}) instead of their service interfaces.
 *
 * <p>Exists ONLY so {@code PlaneBoundaryTest.gatewayUsesApisOnlyCatchesAPlantedViolation} can prove
 * that rule fires. Note the rule must NOT fire on {@code com.solace.sm.cp.api..} /
 * {@code com.solace.sm.dp.api..} references — {@link LegalGatewayClass} guards that half.
 *
 * <p><b>Do not copy this pattern into production code.</b>
 */
public final class BadGatewayClass {

    private BadGatewayClass() {
    }

    /** Forbidden: reaching a control-plane internal rather than {@code cp-api}. */
    public static CpCoreMarker forbiddenCpInternalReference() {
        return null;
    }

    /** Forbidden: reaching a data-plane internal rather than {@code dp-api}. */
    public static DpCoreMarker forbiddenDpInternalReference() {
        return null;
    }
}
