package com.solace.sm.gateway.archfixture;

import com.solace.sm.cp.api.CpMarker;
import com.solace.sm.dp.api.DpMarker;

/**
 * The counterpart to {@link BadGatewayClass}: what the gateway IS allowed to do — depend on both
 * planes' {@code -api} modules. Used to prove {@code gatewayUsesApisOnly} is not merely a rule that
 * rejects everything; it must stay silent on this class while rejecting {@link BadGatewayClass}.
 */
public final class LegalGatewayClass {

    private LegalGatewayClass() {
    }

    /** Allowed: {@code cp-api}. */
    public static CpMarker allowedCpApiReference() {
        return null;
    }

    /** Allowed: {@code dp-api}. */
    public static DpMarker allowedDpApiReference() {
        return null;
    }
}
