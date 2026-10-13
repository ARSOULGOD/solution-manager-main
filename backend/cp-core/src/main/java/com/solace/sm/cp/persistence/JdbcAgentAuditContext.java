package com.solace.sm.cp.persistence;

import com.solace.sm.cp.agent.AgentAuditContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The agent-actor form of {@link JdbcAuditContext}: the same transaction-local settings plus
 * {@code sm.audit.actor_agent}, with {@code sm.audit.actor} sent empty so the audit function sees exactly one kind of
 * actor ({@code change_audit_actor_one_kind}). Must run on the SAME connection and transaction as the writes it attributes.
 */
public final class JdbcAgentAuditContext implements AgentAuditContext {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentAuditContext(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void setAgent(UUID agentId, String auditOperation, UUID correlationId, String clientAddress) {
        jdbc.queryForMap("""
                SELECT set_config('sm.audit.actor', '', true),
                       set_config('sm.audit.actor_agent', :agent, true),
                       set_config('sm.audit.actor_system', '', true),
                       set_config('sm.audit.operation', :operation, true),
                       set_config('sm.audit.correlation_id', :correlationId, true),
                       set_config('sm.audit.client_address', :clientAddress, true)
                """, new MapSqlParameterSource()
                .addValue("agent", agentId == null ? "" : agentId.toString())
                .addValue("operation", auditOperation == null ? "" : auditOperation)
                .addValue("correlationId", correlationId == null ? "" : correlationId.toString())
                .addValue("clientAddress", clientAddress == null ? "" : clientAddress));
    }
}
