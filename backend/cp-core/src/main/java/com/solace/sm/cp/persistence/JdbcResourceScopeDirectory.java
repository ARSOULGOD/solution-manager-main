package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** sm_control-only resource directory; owning-plane adapters are required for the remaining kinds. */
public final class JdbcResourceScopeDirectory implements ResourceScopeDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcResourceScopeDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /**
     * The SELECT (without WHERE) and the identity column for a kind this adapter owns. {@code liveOnly} is a predicate
     * every lookup of the kind adds, or empty: a retired instance (V017, plan 012 T4) is "not found" to every
     * authorization and resource lookup, which is what makes each instance-scoped operation on it a {@code 404}.
     */
    private record Source(String select, String idColumn, boolean hasOrganization, String liveOnly) {

        Source(String select, String idColumn, boolean hasOrganization) {
            this(select, idColumn, hasOrganization, "");
        }

        /** The same SELECT, also returning the resource's own identity as {@code resource_id}. */
        String selectWithId() {
            return "SELECT " + idColumn + " AS resource_id, " + select.substring("SELECT ".length());
        }
    }

    private static Source sourceOf(ResourceKind kind) {
        return switch (kind) {
            case ORGANIZATION -> new Source("SELECT organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.organization", "organization_id", true);
            case SUB_ORGANIZATION -> new Source("SELECT organization_id, organization_unit_id, NULL::uuid AS environment_id, "
                    + "NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.organization_unit", "organization_unit_id", true);
            case ENVIRONMENT -> new Source("SELECT organization_id, organization_unit_id, environment_id "
                    + ", NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.environment", "environment_id", true);
            case SOLUTION -> new Source("SELECT organization_id, organization_unit_id, environment_id, solution_id "
                    + ", NULL::uuid AS instance_id FROM sm_control.solution", "solution_id", true);
            case INSTANCE -> new Source("SELECT organization_id, organization_unit_id, environment_id, solution_id, instance_id "
                    + "FROM sm_control.instance", "instance_id", true, " AND retired_at IS NULL");
            case ROLE_DEFINITION -> new Source("SELECT NULL::uuid AS organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.role", "role_id", false);
            case PRINCIPAL -> new Source("SELECT organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.principal", "id", true);
            // OR-28 / SM-D23: a target carries its OWN ownership path, read from its canonical scope row. The derived
            // table keeps the generic `<id> = ANY(:ids) AND organization_id = :org` shape unambiguous (both the target
            // and its scope row have an organization_id). A retired target still resolves: authorization is about
            // scope, and liveness is the caller's concern.
            case DEPLOYMENT_TARGET -> new Source("SELECT organization_id, organization_unit_id, environment_id, "
                    + "solution_id, instance_id, target_id FROM (SELECT t.target_id, t.organization_id AS organization_id, "
                    + "s.organization_unit_id, s.environment_id, s.solution_id, s.instance_id "
                    + "FROM sm_control.target t JOIN sm_control.scope s ON s.scope_id = t.ownership_scope_id) owned_target",
                    "target_id", true);
            // OR-14: a catalog entry (the id `listInstances` calls {solutionId}) is organization-scoped, never
            // install-wide and never finer than the organization.
            case SOLUTION_PACKAGE -> new Source("SELECT organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.catalog_solution", "catalog_solution_id", true);
            // BE-D66 / OR-130: a connection carries its OWN ownership path, same shape as DEPLOYMENT_TARGET above.
            case CONNECTION -> new Source("SELECT organization_id, organization_unit_id, environment_id, "
                    + "solution_id, instance_id, connection_id FROM (SELECT c.connection_id, c.organization_id AS organization_id, "
                    + "s.organization_unit_id, s.environment_id, s.solution_id, s.instance_id "
                    + "FROM sm_control.connection c JOIN sm_control.scope s ON s.scope_id = c.ownership_scope_id) owned_connection",
                    "connection_id", true);
            case PLACEMENT, AUDIT_RECORD -> null;
        };
    }

    @Override
    public Optional<ScopePath> findScopePath(ResourceRef resource) {
        Source source = sourceOf(resource.kind());
        if (source == null) {
            return Optional.empty();
        }
        return query(source.select() + " WHERE " + source.idColumn() + " = :id" + source.liveOnly(), resource.id());
    }

    /** One bind-parameter query for the whole list; the organization is in the WHERE, not in Java ({@code I5}). */
    @Override
    public Map<UUID, ScopePath> findScopePaths(ResourceKind kind, UUID organization, Collection<UUID> ids) {
        Source source = sourceOf(kind);
        // A role has no organization column: it is never in an organization's subset.
        if (source == null || !source.hasOrganization() || ids.isEmpty()) {
            return Map.of();
        }
        String sql = source.selectWithId() + " WHERE " + source.idColumn() + " = ANY(:ids) AND organization_id = :org"
                + source.liveOnly();
        Map<UUID, ScopePath> found = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource()
                        .addValue("ids", new HashSet<>(ids).toArray(UUID[]::new), Types.ARRAY)
                        .addValue("org", organization),
                resultSet -> {
                    found.put(resultSet.getObject("resource_id", UUID.class), pathOf(resultSet));
                });
        return found;
    }

    private Optional<ScopePath> query(String sql, UUID id) {
        return jdbc.query(sql + " LIMIT 2", Map.of("id", id), resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            ScopePath path = pathOf(resultSet);
            return resultSet.next() ? Optional.empty() : Optional.of(path);
        });
    }

    private static ScopePath pathOf(ResultSet resultSet) throws SQLException {
        return new ScopePath(
                resultSet.getObject("organization_id", UUID.class),
                resultSet.getObject("organization_unit_id", UUID.class),
                resultSet.getObject("environment_id", UUID.class),
                resultSet.getObject("solution_id", UUID.class),
                resultSet.getObject("instance_id", UUID.class));
    }
}
