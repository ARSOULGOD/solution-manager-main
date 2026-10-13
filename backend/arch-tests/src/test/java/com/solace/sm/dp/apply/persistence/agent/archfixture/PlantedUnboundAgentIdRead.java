package com.solace.sm.dp.apply.persistence.agent.archfixture;

/**
 * Planted violation: a second statement that derives an organization from an agent id alone, outside {@code AgentAuthority}
 * (rule {@code onlyAgentAuthorityDerivesATenantFromAnAgentId}).
 */
public final class PlantedUnboundAgentIdRead {
    public static final String SQL = "SELECT i.tenant_id FROM sm_data.agent_session s JOIN sm_data.agent_identity i"
            + " ON i.tenant_id = s.tenant_id AND i.agent_id = s.agent_id WHERE s.agent_id = :agent";
}
