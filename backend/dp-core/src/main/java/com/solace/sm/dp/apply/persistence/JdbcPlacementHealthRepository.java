package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.health.PlacementHealthRecord;
import com.solace.sm.dp.health.PlacementHealthRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.placement_health} over the runtime credential (plan 010 T7.2). The trigger - not this class - decides
 * whether a write is a change: it stamps {@code state_version} only when {@code state} changes, and keeps
 * {@code changed_at} when it does not. This class compares the stamp before and after, so what it reports as
 * "changed" is exactly what the database stamped, and the {@code instance.health} event it feeds carries the very
 * stamp the row now has.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}.
 */
public final class JdbcPlacementHealthRepository implements PlacementHealthRows {

    private static final String COLUMNS = "tenant_id, placement_id, instance_id, state, checked_at, changed_at,"
            + " state_version";

    private static final RowMapper<PlacementHealthRecord> MAP = JdbcPlacementHealthRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlacementHealthRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PlacementHealthRecord> find(UUID tenantId, UUID placementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.placement_health"
                        + " WHERE tenant_id = :tenant AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId), MAP)
                .stream().findFirst();
    }

    @Override
    public Recorded record(UUID tenantId, UUID instanceId, UUID placementId, HealthState state, Instant at) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("instance", instanceId).addValue("placement", placementId).addValue("state", state.wire())
                .addValue("at", Sql.at(at));
        Optional<Long> before = stamp(tenantId, placementId);
        if (before.isEmpty()) {
            try {
                Long inserted = Sql.writeReturning(() -> jdbc.queryForObject(
                        "INSERT INTO sm_data.placement_health (tenant_id, placement_id, instance_id, state,"
                                + " checked_at, changed_at) VALUES (:tenant, :placement, :instance, :state, :at, :at)"
                                + " RETURNING state_version", p, Long.class));
                return new Recorded(true, inserted);
            } catch (PgConstraintViolation raced) {
                before = stamp(tenantId, placementId);
                if (before.isEmpty()) {
                    throw raced;
                }
            }
        }
        // The change test reads the state UNDER A ROW LOCK (FOR UPDATE), inside the caller's transaction (HealthRecorder's), and the write follows
        // on the same row: a second writer that raced this one waits for the lock, reads the state the first committed, and reports "not
        // changed" (OR-140: exactly one instance.health across SM instances). Before, the version was read WITHOUT a lock, so a racing writer
        // reported a change it had not made. (A single data-modifying CTE does not do it: FOR UPDATE in a CTE skips a row the same statement updates.)
        List<String> prior = jdbc.queryForList("SELECT state FROM sm_data.placement_health WHERE tenant_id = :tenant"
                + " AND placement_id = :placement FOR UPDATE", p, String.class);
        String was = prior.isEmpty() ? null : prior.get(0);
        Long now = Sql.writeReturning(() -> jdbc.queryForObject(
                "UPDATE sm_data.placement_health SET state = :state, checked_at = :at, changed_at = :at"
                        + " WHERE tenant_id = :tenant AND placement_id = :placement RETURNING state_version",
                p, Long.class));
        return new Recorded(!state.wire().equals(was), now);
    }

    @Override
    public List<PlacementHealthRecord> liveOf(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT h.tenant_id, h.placement_id, h.instance_id, h.state, h.checked_at, h.changed_at,"
                        + " h.state_version FROM sm_data.placement_health h JOIN sm_data.placement p"
                        + " ON p.tenant_id = h.tenant_id AND p.placement_id = h.placement_id"
                        + " WHERE h.tenant_id = :tenant AND h.instance_id = :instance AND p.retired_at IS NULL"
                        + " ORDER BY p.created_at, h.placement_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId), MAP);
    }

    private Optional<Long> stamp(UUID tenantId, UUID placementId) {
        return jdbc.queryForList("SELECT state_version FROM sm_data.placement_health"
                        + " WHERE tenant_id = :tenant AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId),
                Long.class).stream().findFirst();
    }

    private static PlacementHealthRecord row(ResultSet rs, int i) throws SQLException {
        return new PlacementHealthRecord(rs.getObject("tenant_id", UUID.class),
                rs.getObject("placement_id", UUID.class), rs.getObject("instance_id", UUID.class),
                HealthState.fromWire(rs.getString("state")), Sql.instant(rs, "checked_at"),
                Sql.instant(rs, "changed_at"), rs.getLong("state_version"));
    }
}
