package com.solace.sm.cp.agent;

import java.util.UUID;

/**
 * Sets the transaction-local {@code OR-25} context for a write the AGENT makes (plan 015 T1.3, {@code BE-D70}): the
 * actor is {@code sm.audit.actor_agent} and the principal is cleared, so the audit function sees exactly an agent
 * actor. The correlation id and the client address are the gateway-minted ones, never the agent's. Same contract as
 * {@link com.solace.sm.cp.identity.AuditContext}: call INSIDE the transaction of the writes it attributes, before them.
 */
public interface AgentAuditContext {
    void setAgent(UUID agentId, String auditOperation, UUID correlationId, String clientAddress);
}
