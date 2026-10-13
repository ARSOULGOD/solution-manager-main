package com.solace.sm.gateway.archfixture.undeclare;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import java.util.UUID;

/**
 * Planted violation (plan 012 T5, {@code ops-501.md} section 4.4): a second caller of
 * {@code InstanceRetirementGovernance#deregister}, the one control-plane write of an undeclare. It must be made inside the
 * requesting session's audit context (only {@code UndeclareOrchestrator} has one); anything else - above all the lazy sweep,
 * which runs inside some other user's request - would be audited under the wrong session or refused (OR-25).
 * {@code UndeclareBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SweepThatWritesTheControlPlane {

    private SweepThatWritesTheControlPlane() {
    }

    public static boolean secondWriter(InstanceRetirementGovernance governance, PrincipalRef actor, RequestOrigin origin,
            UUID organization, UUID instance) {
        return governance.deregister(actor, origin, organization, instance);
    }
}
