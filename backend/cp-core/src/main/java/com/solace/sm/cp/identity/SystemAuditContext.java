package com.solace.sm.cp.identity;

import java.util.UUID;

/**
 * Sets the transaction-local {@code OR-25} context for a write a scheduled JOB makes (plan 015 T4.6, {@code OR-140}): the actor
 * is {@code sm.audit.actor_system}, the principal and the agent are cleared, and the client address is
 * {@link SystemActors#CLIENT_ADDRESS}. Same contract as {@link AuditContext}: call INSIDE the transaction of the writes it
 * attributes, before them. The correlation id is the job pass's own, minted by the job.
 */
public interface SystemAuditContext {

    void setSystem(UUID systemActorId, String auditOperation, UUID correlationId);
}
