package com.solace.sm.cp.persistence;

import com.solace.sm.cp.governance.GovernanceRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link GovernanceRepository} over {@code sm_control}, served with the {@code sm_cp_runtime} credential. Every
 * statement binds its parameters ({@code I5}) and names its organization in its own {@code WHERE}.
 *
 * <p>{@code sm_cp_runtime} holds a column-scoped {@code UPDATE} on {@code entitlement} (V013), and PostgreSQL
 * accepts {@code SELECT ... FOR UPDATE} on the strength of an {@code UPDATE} privilege on any one column of the
 * table - so {@link #lockEntitlement} needs no further grant.
 */
public final class JdbcGovernanceRepository implements GovernanceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGovernanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<String> solutionNameOf(UUID organizationId, UUID catalogSolutionId) {
        return jdbc.query("""
                SELECT solution FROM sm_control.catalog_solution
                 WHERE organization_id = :organization AND catalog_solution_id = :id
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", catalogSolutionId), (rs, row) -> rs.getString("solution")).stream().findFirst();
    }

    @Override
    public Optional<UUID> organizationUnitOf(UUID organizationId, UUID environmentId) {
        return jdbc.query("""
                SELECT organization_unit_id FROM sm_control.environment
                 WHERE organization_id = :organization AND environment_id = :id
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("id", environmentId), (rs, row) -> rs.getObject("organization_unit_id", UUID.class))
                .stream().findFirst();
    }

    @Override
    public OptionalInt lockEntitlement(UUID organizationId, String solution) {
        return maxInstancesOf("""
                SELECT max_instances FROM sm_control.entitlement
                 WHERE organization_id = :organization AND solution = :solution
                   FOR UPDATE
                """, organizationId, solution);
    }

    @Override
    public OptionalInt maxInstances(UUID organizationId, String solution) {
        return maxInstancesOf("""
                SELECT max_instances FROM sm_control.entitlement
                 WHERE organization_id = :organization AND solution = :solution
                """, organizationId, solution);
    }

    private OptionalInt maxInstancesOf(String sql, UUID organizationId, String solution) {
        return jdbc.query(sql, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution), (rs, row) -> rs.getInt("max_instances")).stream()
                .mapToInt(Integer::intValue).findFirst();
    }

    @Override
    public long countInstances(UUID organizationId, String solution) {
        Long count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM sm_control.instance i
                  JOIN sm_control.solution s
                    ON s.organization_id = i.organization_id AND s.organization_unit_id = i.organization_unit_id
                   AND s.environment_id = i.environment_id AND s.solution_id = i.solution_id
                  JOIN sm_control.catalog_solution c
                    ON c.organization_id = s.organization_id AND c.catalog_solution_id = s.catalog_solution_id
                 WHERE i.organization_id = :organization AND c.solution = :solution
                   AND i.retired_at IS NULL
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("solution", solution), Long.class);
        return count == null ? 0L : count;
    }

    @Override
    public Optional<UUID> findSolutionNode(UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID catalogSolutionId) {
        return jdbc.query("""
                SELECT solution_id FROM sm_control.solution
                 WHERE organization_id = :organization AND organization_unit_id = :unit
                   AND environment_id = :environment AND catalog_solution_id = :entry
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("unit", organizationUnitId).addValue("environment", environmentId)
                .addValue("entry", catalogSolutionId), (rs, row) -> rs.getObject("solution_id", UUID.class))
                .stream().findFirst();
    }

    @Override
    public boolean insertSolutionNode(UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID solutionId, UUID catalogSolutionId) {
        return jdbc.update("""
                INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id, solution_id,
                       catalog_solution_id)
                VALUES (:organization, :unit, :environment, :solution, :entry)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("unit", organizationUnitId).addValue("environment", environmentId)
                .addValue("solution", solutionId).addValue("entry", catalogSolutionId)) == 1;
    }

    @Override
    public void insertInstance(UUID organizationId, UUID organizationUnitId, UUID environmentId, UUID solutionId,
            UUID instanceId) {
        // No ON CONFLICT: a duplicate instance id is a defect (the data plane mints it) and must refuse loudly -
        // instance_id_unique (V013) makes the id globally unique, the resource directory depends on it.
        jdbc.update("""
                INSERT INTO sm_control.instance (organization_id, organization_unit_id, environment_id, solution_id,
                       instance_id)
                VALUES (:organization, :unit, :environment, :solution, :instance)
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("unit", organizationUnitId).addValue("environment", environmentId)
                .addValue("solution", solutionId).addValue("instance", instanceId));
    }

    @Override
    public void insertSolutionScope(UUID scopeId, UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID solutionId) {
        insertScope("solution", scopeId, organizationId, organizationUnitId, environmentId, solutionId, null);
    }

    @Override
    public void insertInstanceScope(UUID scopeId, UUID organizationId, UUID organizationUnitId, UUID environmentId,
            UUID solutionId, UUID instanceId) {
        insertScope("instance", scopeId, organizationId, organizationUnitId, environmentId, solutionId, instanceId);
    }

    private void insertScope(String kind, UUID scopeId, UUID organizationId, UUID organizationUnitId,
            UUID environmentId, UUID solutionId, UUID instanceId) {
        // The same canonical row ScopeStore.canonicalize would mint on first use; ON CONFLICT DO NOTHING makes
        // this a no-op when a grant already canonicalized the scope (the unique indexes name the node).
        jdbc.update("""
                INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id, environment_id,
                       solution_id, instance_id)
                VALUES (:scope, :kind, :organization, :unit, :environment, :solution, :instance)
                ON CONFLICT DO NOTHING
                """, new MapSqlParameterSource().addValue("scope", scopeId).addValue("kind", kind)
                .addValue("organization", organizationId).addValue("unit", organizationUnitId)
                .addValue("environment", environmentId).addValue("solution", solutionId)
                .addValue("instance", instanceId));
    }

    @Override
    public Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds) {
        if (instanceIds.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.query("""
                SELECT instance_id FROM sm_control.instance
                 WHERE organization_id = :organization AND instance_id IN (:ids)
                """, new MapSqlParameterSource().addValue("organization", organizationId)
                .addValue("ids", instanceIds), (rs, row) -> rs.getObject("instance_id", UUID.class)));
    }
}
