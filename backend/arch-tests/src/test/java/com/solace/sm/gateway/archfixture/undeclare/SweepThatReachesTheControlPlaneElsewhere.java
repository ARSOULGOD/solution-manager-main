package com.solace.sm.gateway.archfixture.undeclare;

import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import java.util.Set;
import java.util.UUID;

/**
 * Planted violation (plan 012 T5): a class the boundary test is told to treat AS the sweep that calls a control-plane
 * method other than the one read it is allowed ({@code InstanceRetirementGovernance#retired}). The real
 * {@code UndeclareSweep} calls exactly that one; the rule is proved by pointing it at this class instead.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class SweepThatReachesTheControlPlaneElsewhere {

    private SweepThatReachesTheControlPlaneElsewhere() {
    }

    public static Set<UUID> retiredIsFine(InstanceRetirementGovernance governance, UUID organization, Set<UUID> ids) {
        return governance.retired(organization, ids);
    }

    public static boolean registeredIsNot(InstanceGovernance governance, UUID organization, Set<UUID> ids) {
        return !governance.registered(organization, ids).isEmpty();
    }
}
