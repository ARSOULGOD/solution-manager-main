package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.hierarchy.HierarchyWriteRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link HierarchyWriteRepository} over {@code sm_control} as the runtime credential. The role holds {@code INSERT}
 * on {@code environment} and on exactly {@code (organization_id, organization_unit_id, name)} of
 * {@code organization_unit} (a sub-organization is top-level only), and {@code UPDATE (name)} on the three node
 * tables, and nothing else here (V018): no node is ever deleted, and no organization is created over the API.
 *
 * <p>Every statement binds its parameters and names {@code organization_id} ({@code I5}). The sibling-name check is
 * the three unique indexes of {@code V015} ({@code OR-123}), never a SELECT: a {@code 23505} on one of them becomes
 * {@link HierarchyWriteRepository.NameTakenException}, and on any other constraint it is rethrown untouched.
 */
public final class JdbcHierarchyWriteRepository implements HierarchyWriteRepository {

    /** The three indexes of V015 whose violation means "a sibling already has this name". */
    static final Set<String> NAME_INDEXES =
            Set.of("organization_name_ci_uq", "organization_unit_name_ci_uq", "environment_name_ci_uq");

    private static final RowMapper<ScopeNode> NODE =
            (rs, row) -> new ScopeNode(rs.getObject("id", UUID.class), rs.getString("name"));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcHierarchyWriteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insertSubOrganization(UUID organizationId, UUID subOrganizationId, String name) {
        write(() -> jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name)"
                        + " VALUES (:org, :unit, :name)",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("name", name)));
    }

    @Override
    public void insertEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId, String name) {
        write(() -> jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                        + " environment_id, name) VALUES (:org, :unit, :env, :name)",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("env", environmentId).addValue("name", name)));
    }

    @Override
    public boolean renameOrganization(UUID organizationId, String name) {
        return write(() -> jdbc.update("UPDATE sm_control.organization SET name = :name"
                        + " WHERE organization_id = :org AND name IS DISTINCT FROM :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name))) == 1;
    }

    @Override
    public boolean renameSubOrganization(UUID organizationId, UUID subOrganizationId, String name) {
        return write(() -> jdbc.update("UPDATE sm_control.organization_unit SET name = :name"
                        + " WHERE organization_id = :org AND organization_unit_id = :unit AND parent_unit_id IS NULL"
                        + " AND name IS DISTINCT FROM :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("name", name))) == 1;
    }

    @Override
    public boolean renameEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId, String name) {
        return write(() -> jdbc.update("UPDATE sm_control.environment SET name = :name"
                        + " WHERE organization_id = :org AND organization_unit_id = :unit AND environment_id = :env"
                        + " AND name IS DISTINCT FROM :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("env", environmentId).addValue("name", name))) == 1;
    }

    @Override
    public Optional<ScopeNode> findOrganization(UUID organizationId) {
        return jdbc.query("SELECT organization_id AS id, name FROM sm_control.organization"
                        + " WHERE organization_id = :org",
                new MapSqlParameterSource().addValue("org", organizationId), NODE).stream().findFirst();
    }

    @Override
    public Optional<ScopeNode> findSubOrganization(UUID organizationId, UUID subOrganizationId) {
        return jdbc.query("SELECT organization_unit_id AS id, name FROM sm_control.organization_unit"
                        + " WHERE organization_id = :org AND organization_unit_id = :unit AND parent_unit_id IS NULL",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId), NODE)
                .stream().findFirst();
    }

    @Override
    public Optional<ScopeNode> findEnvironment(UUID organizationId, UUID subOrganizationId, UUID environmentId) {
        return jdbc.query("SELECT environment_id AS id, name FROM sm_control.environment"
                        + " WHERE organization_id = :org AND organization_unit_id = :unit AND environment_id = :env",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("env", environmentId), NODE).stream().findFirst();
    }

    @Override
    public Optional<ScopeNode> findSubOrganizationByName(UUID organizationId, String name) {
        return jdbc.query("SELECT organization_unit_id AS id, name FROM sm_control.organization_unit"
                        + " WHERE organization_id = :org AND parent_unit_id IS NULL AND name = :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name), NODE)
                .stream().findFirst();
    }

    @Override
    public Optional<ScopeNode> findEnvironmentByName(UUID organizationId, UUID subOrganizationId, String name) {
        return jdbc.query("SELECT environment_id AS id, name FROM sm_control.environment"
                        + " WHERE organization_id = :org AND organization_unit_id = :unit AND name = :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("unit", subOrganizationId)
                        .addValue("name", name), NODE).stream().findFirst();
    }

    private static int write(java.util.function.IntSupplier statement) {
        try {
            return statement.getAsInt();
        } catch (DuplicateKeyException duplicate) {
            String index = constraintOf(duplicate);
            if (index != null && NAME_INDEXES.contains(index)) {
                // Deliberately NOT chained: the driver's text carries the key (see NameTakenException).
                throw new NameTakenException(index);
            }
            throw duplicate;
        }
    }

    private static String constraintOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                return message == null ? null : message.getConstraint();
            }
        }
        return null;
    }
}
