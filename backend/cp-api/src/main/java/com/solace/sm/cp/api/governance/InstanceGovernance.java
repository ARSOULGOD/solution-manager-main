package com.solace.sm.cp.api.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The control plane's half of a declare ({@code OR-27}, step 4 of the two-phase protocol): the governance node
 * for an instance the data plane has already reserved.
 *
 * <p>⛔ Every method takes the organization explicitly and binds it in its own query ({@code I5}); the gateway
 * passes {@code Session.organizationId()}, never a request field. The control plane never deletes a governance
 * node, so there is no inverse of {@link #register}: undeclare <i>retires</i> the node, through
 * {@link InstanceRetirementGovernance} (plan 012 T4).
 */
public interface InstanceGovernance {

    /**
     * Step 4. One CP transaction, audited {@code INSTANCE_DECLARED}: creates the environment's Solution node on the
     * first declare of that catalog entry in it ({@code OR-41}), the instance node, and their scope rows. The
     * quota ({@code OR-40}) is enforced here, under {@code FOR UPDATE} of the entitlement row, so two racing
     * declares cannot both take the last slot.
     *
     * @throws com.solace.sm.cp.api.catalog.EntitlementRefusedException the organization holds no entitlement for
     *                                                                  the entry's solution
     * @throws QuotaExceededException                                   the entitlement's instance limit is reached
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException     the environment or the catalog entry is not
     *                                                                  this organization's
     */
    GovernanceRegistration register(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
            UUID environmentId, UUID catalogSolutionId, UUID instanceId);

    /**
     * Which of {@code instanceIds} the control plane has registered for this organization - the answer the data
     * plane's declare sweep needs. Bounded: more than {@link #MAX_BATCH} ids is an {@code IllegalArgumentException}.
     */
    Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds);

    /** Pre-check only (step 2): the binding check is inside {@link #register}. {@code false} when not entitled. */
    boolean withinQuota(UUID organizationId, String solution);

    /** The largest batch {@link #registered} answers. */
    int MAX_BATCH = 500;
}
