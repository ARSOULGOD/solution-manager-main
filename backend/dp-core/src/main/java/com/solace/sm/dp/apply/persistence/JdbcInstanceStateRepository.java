package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeploymentMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceNameTakenException;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
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
 * {@code sm_data.instance_state} over the runtime credential (plan 010 T2.5). In this package because JDBC is
 * confined here; the inventory service in {@code dp.inventory} reaches it through {@link InstanceRows}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}. {@code state_version} is never
 * written (the runtime role has no column grant on it, V007): the stamp trigger sets it on every write, so a
 * statement that changes nothing observable must not be issued — {@link #activate} and {@link #abandon} are
 * conditional on the source lifecycle for exactly that reason.
 */
public final class JdbcInstanceStateRepository implements InstanceRows {

    private static final String COLUMNS = "tenant_id, instance_id, environment_id, catalog_solution_id, solution,"
            + " declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at, state_version,"
            + " deployment_mode, role";

    private static final RowMapper<InstanceRecord> MAP = JdbcInstanceStateRepository::row;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInstanceStateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * OR-88's partial unique index becomes the dp-api exception the gateway can name (it may not import
     * {@link PgConstraintViolation}); any other refusal is rethrown untouched.
     */
    @Override
    public void insert(InstanceRecord r) {
        try {
            insertRow(r);
        } catch (PgConstraintViolation e) {
            if (e.namesAnyOf("instance_name_once")) {
                throw new InstanceNameTakenException();
            }
            throw e;
        }
    }

    private void insertRow(InstanceRecord r) {
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by,"
                        + " created_at, deployment_mode, role) VALUES (:tenant, :instance, :env, :catalog, :solution,"
                        + " :version, :name, :lifecycle, :acquisition, :createdBy, :createdAt, :deployment, :role)",
                new MapSqlParameterSource().addValue("tenant", r.tenantId()).addValue("instance", r.instanceId())
                        .addValue("env", r.environmentId()).addValue("catalog", r.catalogSolutionId())
                        .addValue("solution", r.solution()).addValue("version", r.declaredPackageVersion())
                        .addValue("name", r.name()).addValue("lifecycle", r.lifecycle().wire())
                        .addValue("acquisition", r.acquisition().wire()).addValue("createdBy", r.createdBy())
                        .addValue("createdAt", Sql.at(r.createdAt()))
                        .addValue("deployment", r.deploymentMode().wire()).addValue("role", r.role())));
    }

    @Override
    public Optional<InstanceRecord> find(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.instance_state"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId), MAP)
                .stream().findFirst();
    }

    @Override
    public List<InstanceRecord> list(UUID tenantId, InstanceLifecycle lifecycle, Optional<UUID> catalogSolutionId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("lifecycle", lifecycle.wire());
        String narrow = "";
        if (catalogSolutionId.isPresent()) {
            narrow = " AND catalog_solution_id = :catalog";
            params.addValue("catalog", catalogSolutionId.get());
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.instance_state WHERE tenant_id = :tenant"
                + " AND lifecycle = :lifecycle" + narrow + " ORDER BY name, instance_id", params, MAP);
    }

    @Override
    public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff) {
        return jdbc.queryForList("SELECT instance_id FROM sm_data.instance_state WHERE tenant_id = :tenant"
                        + " AND lifecycle = 'pending' AND created_at < :cutoff ORDER BY created_at, instance_id",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("cutoff", Sql.at(cutoff)),
                UUID.class);
    }

    @Override
    public List<UUID> pendingIds(UUID tenantId, int limit) {
        return jdbc.queryForList("SELECT instance_id FROM sm_data.instance_state WHERE tenant_id = :tenant"
                        + " AND lifecycle = 'pending' ORDER BY created_at, instance_id LIMIT :limit",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("limit", limit), UUID.class);
    }

    @Override
    public List<UUID> pendingCreatedBefore(UUID tenantId, Instant cutoff, int limit) {
        return jdbc.queryForList("SELECT instance_id FROM sm_data.instance_state WHERE tenant_id = :tenant"
                        + " AND lifecycle = 'pending' AND created_at < :cutoff"
                        + " ORDER BY created_at, instance_id LIMIT :limit",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("cutoff", Sql.at(cutoff))
                        .addValue("limit", limit), UUID.class);
    }

    @Override
    public int activate(UUID tenantId, UUID instanceId) {
        return transition(tenantId, instanceId, "active");
    }

    @Override
    public int abandon(UUID tenantId, UUID instanceId) {
        return transition(tenantId, instanceId, "abandoned");
    }

    @Override
    public Optional<InstanceRecord> lockForPlacementChange(UUID tenantId, UUID instanceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_data.instance_state"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance FOR NO KEY UPDATE",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId), MAP)
                .stream().findFirst();
    }

    @Override
    public int touch(UUID tenantId, UUID instanceId) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.instance_state SET lifecycle = lifecycle"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND lifecycle = 'active'",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)));
    }

    private int transition(UUID tenantId, UUID instanceId, String to) {
        return Sql.write(() -> jdbc.update("UPDATE sm_data.instance_state SET lifecycle = :to"
                        + " WHERE tenant_id = :tenant AND instance_id = :instance AND lifecycle = 'pending'",
                new MapSqlParameterSource().addValue("to", to).addValue("tenant", tenantId)
                        .addValue("instance", instanceId)));
    }

    private static InstanceRecord row(ResultSet rs, int i) throws SQLException {
        return new InstanceRecord(rs.getObject("tenant_id", UUID.class), rs.getObject("instance_id", UUID.class),
                rs.getObject("environment_id", UUID.class), rs.getObject("catalog_solution_id", UUID.class),
                rs.getString("solution"), rs.getString("declared_package_version"), rs.getString("name"),
                InstanceLifecycle.fromWire(rs.getString("lifecycle")),
                AcquisitionMode.fromWire(rs.getString("acquisition_mode")), rs.getObject("created_by", UUID.class),
                Sql.instant(rs, "created_at"), rs.getLong("state_version"),
                DeploymentMode.fromWire(rs.getString("deployment_mode")), rs.getString("role"));
    }
}
