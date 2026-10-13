package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.SystemActors;
import com.solace.sm.cp.identity.SystemAuditContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The system-actor form of {@link JdbcAuditContext} (V022): the same transaction-local settings plus
 * {@code sm.audit.actor_system}, with {@code sm.audit.actor} and {@code sm.audit.actor_agent} sent empty so the audit function
 * sees exactly one kind of actor ({@code change_audit_actor_one_kind}). Must run on the SAME connection and transaction as the
 * writes it attributes.
 */
public final class JdbcSystemAuditContext implements SystemAuditContext {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSystemAuditContext(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void setSystem(UUID systemActorId, String auditOperation, UUID correlationId) {
        jdbc.queryForMap("""
                SELECT set_config('sm.audit.actor', '', true),
                       set_config('sm.audit.actor_agent', '', true),
                       set_config('sm.audit.actor_system', :system, true),
                       set_config('sm.audit.operation', :operation, true),
                       set_config('sm.audit.correlation_id', :correlationId, true),
                       set_config('sm.audit.client_address', :clientAddress, true)
                """, new MapSqlParameterSource()
                .addValue("system", systemActorId == null ? "" : systemActorId.toString())
                .addValue("operation", auditOperation == null ? "" : auditOperation)
                .addValue("correlationId", correlationId == null ? "" : correlationId.toString())
                .addValue("clientAddress", SystemActors.CLIENT_ADDRESS));
    }
}
