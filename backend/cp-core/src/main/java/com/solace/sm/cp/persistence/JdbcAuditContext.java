package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.AuditContext;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Sets the {@code OR-19} audit context as PostgreSQL transaction-local settings
 * ({@code set_config(..., true)}), which {@code sm_control.record_change_audit()} reads back with
 * {@code current_setting(..., true)}.
 *
 * <p>A {@code null} value is sent as an empty string rather than SQL {@code NULL}: {@code
 * set_config}'s handling of a {@code NULL} second argument for a custom (non-built-in) setting is
 * unspecified, while an empty string is unambiguous and the trigger already normalizes blank to
 * {@code NULL} with {@code NULLIF(..., '')}.
 *
 * <p>Must be called on the SAME connection/transaction as the writes it attributes. Every {@code
 * NamedParameterJdbcTemplate} built over the same {@code DataSource} shares that connection
 * automatically once a Spring-managed transaction (e.g. {@code smControlTransactions}) is active.
 */
public final class JdbcAuditContext implements AuditContext {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditContext(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void set(UUID actorPrincipalId, String auditOperation, UUID correlationId, String clientAddress) {
        jdbc.queryForMap("""
                SELECT set_config('sm.audit.actor', :actor, true),
                       set_config('sm.audit.actor_system', '', true),
                       set_config('sm.audit.operation', :operation, true),
                       set_config('sm.audit.correlation_id', :correlationId, true),
                       set_config('sm.audit.client_address', :clientAddress, true)
                """, new MapSqlParameterSource()
                .addValue("actor", actorPrincipalId == null ? "" : actorPrincipalId.toString())
                .addValue("operation", auditOperation == null ? "" : auditOperation)
                .addValue("correlationId", correlationId == null ? "" : correlationId.toString())
                .addValue("clientAddress", clientAddress == null ? "" : clientAddress));
    }
}
