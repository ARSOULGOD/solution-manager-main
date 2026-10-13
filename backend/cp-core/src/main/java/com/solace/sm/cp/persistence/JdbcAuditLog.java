package com.solace.sm.cp.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.audit.AuditEntry;
import com.solace.sm.cp.audit.AuditLog;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter for append-only control-plane audit records. */
public final class JdbcAuditLog implements AuditLog {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuditLog(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** This method never starts or commits a transaction. */
    @Override
    public void record(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry");
        jdbc.update("INSERT INTO sm_control.audit_log "
                        + "(at, actor_principal_id, verb, resource_kind, resource_id, tenant, outcome, detail) "
                        + "VALUES (:at, :actorId, :verb, :resourceKind, :resourceId, :tenant, :outcome, "
                        + "CAST(:detail AS jsonb))",
                new MapSqlParameterSource()
                        .addValue("at", Timestamp.from(entry.at()))
                        .addValue("actorId", entry.actor().id())
                        .addValue("verb", entry.verb().id())
                        .addValue("resourceKind", entry.resourceKind().name().toLowerCase(Locale.ROOT))
                        .addValue("resourceId", entry.resourceId())
                        .addValue("tenant", entry.tenant().value())
                        .addValue("outcome", entry.outcome().name().toLowerCase(Locale.ROOT))
                        .addValue("detail", json(entry)));
    }

    private static String json(AuditEntry entry) {
        try {
            return JSON.writeValueAsString(entry.detail());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("validated audit detail could not be serialized", exception);
        }
    }
}
