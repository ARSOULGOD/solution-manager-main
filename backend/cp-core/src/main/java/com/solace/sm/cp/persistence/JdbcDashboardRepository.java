package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.dashboard.InvalidDashboardException;
import com.solace.sm.cp.dashboard.DashboardRepository;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_control.dashboard} over the runtime credential (plan 011 P11-7.1). Every statement binds its parameters
 * and names {@code organization_id} ({@code I5}); the dashboard id is derived from the instance id here, never taken
 * from a caller.
 *
 * <p>The runtime role holds {@code INSERT} and {@code UPDATE (document, revision, updated_by, updated_at)} only
 * (V014): nothing here can re-point a dashboard at another instance or organization, and nothing deletes one.
 */
public final class JdbcDashboardRepository implements DashboardRepository {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDashboardRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    static String dashboardId(UUID instanceId) {
        return "db_" + instanceId;
    }

    @Override
    public Optional<Stored> find(UUID organizationId, UUID instanceId) {
        return jdbc.query("SELECT document::text AS document, revision FROM sm_control.dashboard"
                        + " WHERE organization_id = :org AND dashboard_id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", dashboardId(instanceId)),
                (rs, row) -> new Stored(rs.getString("document"), rs.getLong("revision"))).stream().findFirst();
    }

    @Override
    public boolean insert(UUID organizationId, UUID instanceId, String documentJson, UUID updatedBy,
            Instant updatedAt) {
        try {
            return jdbc.update("INSERT INTO sm_control.dashboard (organization_id, dashboard_id, scope_kind,"
                            + " instance_id, document, revision, updated_by, updated_at)"
                            + " VALUES (:org, :id, 'instance', :instance, CAST(:document AS jsonb), 1, :by, :at)"
                            + " ON CONFLICT (organization_id, dashboard_id) DO NOTHING",
                    new MapSqlParameterSource().addValue("org", organizationId)
                            .addValue("id", dashboardId(instanceId)).addValue("instance", instanceId)
                            .addValue("document", documentJson).addValue("by", updatedBy)
                            .addValue("at", Timestamp.from(updatedAt))) == 1;
        } catch (DataIntegrityViolationException refused) {
            String sqlState = sqlStateOf(refused);
            if (FOREIGN_KEY_VIOLATION.equals(sqlState) || UNIQUE_VIOLATION.equals(sqlState)) {
                // The instance is not this organization's: exactly as absent as a nonexistent one (I5). The unique
                // violation is the same fact reached by the other index: the ON CONFLICT above arbitrates only this
                // organization's own dashboard, so a duplicate on instance_id means ANOTHER organization already
                // holds a dashboard for this instance id. Answering it as "stale" would confirm that it exists.
                throw new ResourceNotFoundException(ResourceKind.INSTANCE);
            }
            if (CHECK_VIOLATION.equals(sqlState)) {
                throw tooLarge();
            }
            throw refused;
        }
    }

    @Override
    public boolean update(UUID organizationId, UUID instanceId, long expectedRevision, String documentJson,
            UUID updatedBy, Instant updatedAt) {
        try {
            return jdbc.update("UPDATE sm_control.dashboard SET document = CAST(:document AS jsonb),"
                            + " revision = revision + 1, updated_by = :by, updated_at = :at"
                            + " WHERE organization_id = :org AND dashboard_id = :id AND revision = :expected",
                    new MapSqlParameterSource().addValue("org", organizationId)
                            .addValue("id", dashboardId(instanceId)).addValue("expected", expectedRevision)
                            .addValue("document", documentJson).addValue("by", updatedBy)
                            .addValue("at", Timestamp.from(updatedAt))) == 1;
        } catch (DataIntegrityViolationException refused) {
            if (CHECK_VIOLATION.equals(sqlStateOf(refused))) {
                throw tooLarge();
            }
            throw refused;
        }
    }

    private static InvalidDashboardException tooLarge() {
        return new InvalidDashboardException(InvalidDashboardException.Kind.INVALID,
                "The dashboard document is too large.");
    }

    private static String sqlStateOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
