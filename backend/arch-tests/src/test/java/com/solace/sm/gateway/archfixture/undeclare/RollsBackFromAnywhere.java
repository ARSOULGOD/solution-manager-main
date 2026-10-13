package com.solace.sm.gateway.archfixture.undeclare;

import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import java.util.UUID;

/**
 * Planted violation (plan 012 T5): callers of the data plane's undeclare steps outside the two classes that own them. The
 * roll-back and the {@code retiring} list belong to the sweep alone (the caller, never the port, decides it is time), and
 * {@code beginRetire} to the orchestrator alone (it is the one that has passed {@code instance.undeclare}).
 * {@code UndeclareBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class RollsBackFromAnywhere {

    private RollsBackFromAnywhere() {
    }

    public static boolean rollBack(InstanceRetirementService port, UUID organization, UUID instance) {
        port.retiring(organization);
        return port.rollBackRetire(organization, instance);
    }

    public static RetirementBegun beginWithoutAuthorizing(InstanceRetirementService port, UUID organization, UUID instance,
            UUID actor, UUID correlation) {
        return port.beginRetire(organization, instance, actor, correlation, WorkloadDisposition.LEAVE_RUNNING);
    }
}
