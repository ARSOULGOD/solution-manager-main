package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.placement} over the runtime credential (plan 010 T2.5). A placement is immutable but for
 * retirement (V007's trigger), and is never stamped: it carries no {@code state_version}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}.
 */
public final class JdbcPlacementRepository implements PlacementRows {

    private static final String COLUMNS = "tenant_id, placement_id, instance_id, target_id, container_name,"
            + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
            + " health_kind, health_path, health_port, health_strength, created_at, created_by, connection_id";

    private static final RowMapper<PlacementRecord> MAP = JdbcPlacementRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlacementRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(PlacementRecord r) {
        try {
            insertRow(r);
        } catch (PgConstraintViolation e) {
            throw collision(e);
        }
    }

    /**
     * OR-29's three per-target uniqueness indexes become the collision CLASS they guard — and only the class: the
     * exception carries neither the value nor the other placement. So does the one trigger that guards the health port
     * against another placement's metrics port (plan 011 P11-4.1). Anything else is rethrown untouched.
     */
    private static RuntimeException collision(PgConstraintViolation e) {
        if (e.namesAnyOf("placement_container_once", "placement_connection_container_once")) {
            return new PlacementCollisionException(PlacementCollisionException.Kind.CONTAINER_NAME);
        }
        if (e.namesAnyOf("placement_config_dir_once", "placement_connection_config_dir_once")) {
            return new PlacementCollisionException(PlacementCollisionException.Kind.CONFIG_DIRECTORY);
        }
        if (e.namesAnyOf("placement_health_port_once", "placement_connection_health_port_once")) {
            return new PlacementCollisionException(PlacementCollisionException.Kind.HEALTH_PORT);
        }
        // P11-4.1 (M-Q14): the health port this placement probes may be a host port another live placement on the target
        // maps for its metrics. V016's trigger names it; the declare answers it with the metrics collision class.
        return JdbcMetricDefinitionRepository.collisionOf(e).<RuntimeException>map(c -> c).orElse(e);
    }

    private void insertRow(PlacementRecord r) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.placement (" + COLUMNS + ") VALUES (:tenant, :placement, :instance, :target,"
                        + " :container, :dir, :containerPath, :file, :mechanism, :retained, :hkind, :hpath, :hport,"
                        + " :hstrength, :createdAt, :createdBy, :connection)",
                new MapSqlParameterSource().addValue("tenant", r.tenantId()).addValue("placement", r.placementId())
                        .addValue("instance", r.instanceId()).addValue("target", r.targetId(), Types.OTHER)
                        .addValue("container", r.containerName()).addValue("dir", r.remoteConfigDirectory())
                        .addValue("containerPath", r.containerConfigPath()).addValue("file", r.fileName())
                        .addValue("mechanism", r.mechanism()).addValue("retained", r.retainedGenerations())
                        .addValue("hkind", r.healthKind().orElse(null), Types.VARCHAR)
                        .addValue("hpath", r.healthPath().orElse(null), Types.VARCHAR)
                        .addValue("hport", r.healthPort().orElse(null), Types.INTEGER)
                        .addValue("hstrength", r.healthStrength().map(ProbeStrength::wire).orElse(null),
                                Types.VARCHAR)
                        .addValue("createdAt", Sql.at(r.createdAt()))
                        .addValue("createdBy", r.createdBy(), Types.OTHER)
                        .addValue("connection", r.connectionId(), Types.OTHER)));
    }

    @Override
    public Optional<PlacementRecord> findLive(UUID tenantId, UUID instanceId, UUID placementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.placement WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND placement_id = :placement AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("placement", placementId), MAP).stream().findFirst();
    }

    @Override
    public Optional<PlacementRecord> find(UUID tenantId, UUID instanceId, UUID placementId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.placement WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("placement", placementId), MAP).stream().findFirst();
    }

    @Override
    public List<PlacementRecord> live(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.placement WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND retired_at IS NULL ORDER BY created_at, placement_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId), MAP);
    }

    @Override
    public int retireAll(UUID tenantId, UUID instanceId, Instant at) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.placement SET retired_at = :at"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("at", Sql.at(at)).addValue("tenant", tenantId)
                        .addValue("instance", instanceId)));
    }

    @Override
    public int retire(UUID tenantId, UUID instanceId, UUID placementId, Instant at, UUID retiredBy) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.placement SET retired_at = :at, retired_by = :by"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND placement_id = :placement"
                        + " AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("at", Sql.at(at)).addValue("by", retiredBy, Types.OTHER)
                        .addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("placement", placementId)));
    }

    @Override
    public int recordRetirement(UUID tenantId, UUID retirementId, UUID instanceId, UUID placementId, UUID requestedBy,
            Instant at, UUID correlationId) {
        // The same record undeclare writes (V023), for one placement: written already closed, because a placement is retired
        // by one statement and never attempted-and-abandoned. last_health is built here, by the database, from the
        // placement's last persisted health: ids and a state token only, so there is nowhere to put a value (I6).
        return Sql.write(() -> jdbc.update("INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id,"
                        + " placement_id, requested_by, requested_at, correlation_id, workload_disposition, last_health,"
                        + " completed_at, outcome)"
                        + " SELECT p.tenant_id, :retirement, p.instance_id, p.placement_id, :by, :at, :correlation,"
                        + " 'leave-running', jsonb_build_array(jsonb_build_object('placementId', p.placement_id,"
                        + "                                                       'lastHealth', h.state)), :at, 'retired'"
                        + " FROM sm_data.placement p"
                        + " LEFT JOIN sm_data.placement_health h"
                        + "   ON h.tenant_id = p.tenant_id AND h.placement_id = p.placement_id"
                        + " WHERE p.tenant_id = :tenant AND p.instance_id = :instance AND p.placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("retirement", retirementId)
                        .addValue("instance", instanceId).addValue("placement", placementId)
                        .addValue("by", requestedBy).addValue("at", Sql.at(at)).addValue("correlation", correlationId)));
    }

    @Override
    public boolean lockApplyScope(UUID tenantId, UUID instanceId) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("instance", instanceId);
        // The same single-arbiter upsert admission uses (ApplyScopeRepository.ensureScopeRow), then the same row lock. Written
        // as INSERT .. SELECT so that an instance the tenant does not have creates no row (the scope_has_instance foreign key
        // would otherwise refuse it with a 23503 instead of a readable "no such instance").
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id)"
                + " SELECT tenant_id, instance_id FROM sm_data.instance_state"
                + " WHERE tenant_id = :tenant AND instance_id = :instance"
                + " ON CONFLICT (tenant_id, instance_id) DO NOTHING", key));
        return !jdbc.queryForList("SELECT instance_watermark FROM sm_data.apply_scope"
                + " WHERE tenant_id = :tenant AND instance_id = :instance FOR UPDATE", key, Long.class).isEmpty();
    }

    @Override
    public boolean inFlightOn(UUID tenantId, UUID instanceId, UUID placementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')"
                        + " AND (placement_id = :placement OR placement_id = :nil))"
                        + " OR EXISTS (SELECT 1 FROM sm_data.fleet_member WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND placement_id = :placement"
                        + " AND disposition IN ('pending','admitted') AND settled IS NULL)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("placement", placementId).addValue("nil", PlacementId.INSTANCE_WIDE.value()),
                Boolean.class));
    }

    @Override
    public boolean scheduleReservesPlacement(UUID tenantId, UUID instanceId, UUID placementId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data.apply_schedule"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND placement_id = :placement"
                        + " AND state = 'PENDING')",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("placement", placementId),
                Boolean.class));
    }

    @Override
    public boolean instanceWideInFlight(UUID tenantId, UUID instanceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')"
                        + " AND placement_id = :nil)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("nil", PlacementId.INSTANCE_WIDE.value()),
                Boolean.class));
    }

    // ---- plan 013 T1.5 --------------------------------------------------------------------------------------

    /** The instances with a LIVE placement on the target: the set whose admission an edit of the target must exclude. */
    private static final String INSTANCES_ON_TARGET = "SELECT instance_id FROM sm_data.placement"
            + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL";

    @Override
    public List<UUID> lockApplyScopesOfTarget(UUID tenantId, UUID targetId) {
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId);
        // The same single-arbiter upsert admission uses, for every instance on the target at once: an instance that has
        // never had an admission has no scope row yet, and a row that does not exist cannot be locked.
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id)"
                + " SELECT DISTINCT tenant_id, instance_id FROM sm_data.placement"
                + " WHERE tenant_id = :tenant AND target_id = :target AND retired_at IS NULL"
                + " ORDER BY instance_id"       // W4C-7: rows are inserted in the order they are later locked in
                + " ON CONFLICT (tenant_id, instance_id) DO NOTHING", key));
        // ORDER BY instance_id: PostgreSQL's LockRows node sits above the sort, so the rows are locked in this order,
        // and two edits of two targets that share instances cannot lock the same two rows in opposite orders.
        return jdbc.queryForList("SELECT s.instance_id FROM sm_data.apply_scope s WHERE s.tenant_id = :tenant"
                + " AND s.instance_id IN (" + INSTANCES_ON_TARGET + ") ORDER BY s.instance_id FOR UPDATE OF s",
                key, UUID.class);
    }

    @Override
    public boolean inFlightOnTarget(UUID tenantId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request r WHERE r.tenant_id = :tenant"
                        + " AND r.state IN ('REQUESTED','RUNNING','APPLY_INDETERMINATE')"
                        + " AND EXISTS (SELECT 1 FROM sm_data.placement p WHERE p.tenant_id = r.tenant_id"
                        + " AND p.instance_id = r.instance_id AND p.target_id = :target AND p.retired_at IS NULL"
                        + " AND (r.placement_id = p.placement_id OR r.placement_id = :nil)))"
                        + " OR EXISTS (SELECT 1 FROM sm_data.fleet_member m JOIN sm_data.placement p"
                        + " ON p.tenant_id = m.tenant_id AND p.instance_id = m.instance_id"
                        + " AND p.placement_id = m.placement_id WHERE m.tenant_id = :tenant AND p.target_id = :target"
                        + " AND p.retired_at IS NULL AND m.disposition IN ('pending','admitted') AND m.settled IS NULL)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId)
                        .addValue("nil", PlacementId.INSTANCE_WIDE.value()),
                Boolean.class));
    }

    @Override
    public boolean anyLiveOnTarget(UUID tenantId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (" + INSTANCES_ON_TARGET + ")",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("target", targetId), Boolean.class));
    }

    private static PlacementRecord row(ResultSet rs, int i) throws SQLException {
        int port = rs.getInt("health_port");
        Optional<Integer> healthPort = rs.wasNull() ? Optional.empty() : Optional.of(port);
        return new PlacementRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("placement_id", UUID.class),
                rs.getObject("instance_id", UUID.class), rs.getObject("target_id", UUID.class),
                rs.getString("container_name"), rs.getString("remote_config_directory"),
                rs.getString("container_config_path"), rs.getString("file_name"), rs.getString("mechanism"),
                rs.getInt("retained_generations"), Optional.ofNullable(rs.getString("health_kind")),
                Optional.ofNullable(rs.getString("health_path")), healthPort,
                Optional.ofNullable(rs.getString("health_strength")).map(ProbeStrength::fromWire),
                Sql.instant(rs, "created_at"), rs.getObject("created_by", UUID.class),
                rs.getObject("connection_id", UUID.class));
    }
}
