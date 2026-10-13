package com.solace.sm.cp.api.governance;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The control plane's half of <b>undeclare</b> ({@code OR-121}, {@code OPS-Q2} U-C; plan 012 T4): an instance's
 * governance node is <i>retired</i>, never deleted ({@link InstanceGovernance} is explicit that the control plane
 * never deletes a node). A retired instance
 * <ul>
 *   <li>frees its quota slot, so the organization may declare another;</li>
 *   <li>is "not found" for every authorization and resource lookup, so each instance-scoped operation and each
 *       dashboard of it answers {@code 404};</li>
 *   <li>grants nothing through a binding whose scope <i>is</i> that instance ({@code OPS-Q4} (a)): a principal
 *       bound only there sees neither its environment nor its solution.</li>
 * </ul>
 * It never touches the workload ({@code BE-D49}): this port reaches {@code sm_control} and nothing else.
 *
 * <p>It is a separate port from {@link InstanceGovernance} on purpose. {@link #retired} is a <i>read</i> the data
 * plane's lazy sweep may call; {@link #deregister} is the one <i>write</i>, and the sweep must never call it (the
 * sweep acts on {@code sm_control} only by reading it). Two methods on one narrow port lets an architecture rule name
 * the write method and forbid the sweep from calling it.
 *
 * <p>⛔ Every method takes the organization explicitly and binds it in its own query ({@code I5}); the gateway passes
 * {@code Session.organizationId()}, never a request field.
 */
public interface InstanceRetirementGovernance {

    /**
     * Retires the instance's node. One control-plane transaction, audited {@code INSTANCE_UNDECLARED} with
     * {@code actor} as the actor. <b>Idempotent:</b> an instance already retired is not written again (no second audit
     * row, the original {@code retired_by} stays) and this answers {@code false}.
     *
     * @return {@code true} when this call retired the node, {@code false} when it was already retired
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException the instance is not this organization's
     */
    boolean deregister(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID instanceId);

    /**
     * Which of {@code instanceIds} are retired in this organization - the answer the data plane's lazy sweep needs to
     * decide whether the control-plane step of an undeclare is done. Bounded: more than {@link #MAX_BATCH} ids is an
     * {@code IllegalArgumentException}. An id that is not this organization's is simply absent from the answer.
     */
    Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds);

    /** The largest batch {@link #retired} answers; the same bound as {@link InstanceGovernance#MAX_BATCH}. */
    int MAX_BATCH = InstanceGovernance.MAX_BATCH;
}
