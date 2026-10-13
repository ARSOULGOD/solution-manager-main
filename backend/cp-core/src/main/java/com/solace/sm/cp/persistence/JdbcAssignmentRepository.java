package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.identity.AssignmentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link AssignmentRepository} over {@code sm_control} as the runtime credential (plan 013 T3.1). The role holds
 * {@code SELECT} on {@code role}, {@code role_permission} and {@code role_binding}, and {@code INSERT, DELETE} on
 * {@code role_binding} (V018: the only {@code DELETE} it holds). It can write no role and no role's verbs: the built-in
 * roles are changed only by a migration.
 *
 * <p>Every binding statement reaches the organization through the <b>principal</b> it binds ({@code I5}) and excludes a
 * binding whose scope is a retired instance (V017: it grants nothing, so it is not shown, and a revoke finds nothing).
 * Every statement binds its parameters; none interpolates a value.
 */
public final class JdbcAssignmentRepository implements AssignmentRepository {

    private static final String BINDING_SELECT = """
            SELECT rb.binding_id, rb.principal_id, rb.role_id, s.organization_id, s.organization_unit_id,
                   s.environment_id, s.solution_id, s.instance_id
              FROM sm_control.role_binding rb
              JOIN sm_control.principal p ON p.id = rb.principal_id
              JOIN sm_control.scope s ON s.scope_id = rb.scope_id
             WHERE p.organization_id = :org
               AND NOT EXISTS (SELECT 1 FROM sm_control.instance retired
                                WHERE retired.instance_id = s.instance_id AND retired.retired_at IS NOT NULL)
            """;
    private static final String ORDER = " ORDER BY rb.principal_id, rb.role_id, rb.binding_id";

    private static final RowMapper<AssignmentView> BINDING = (rs, row) -> new AssignmentView(
            rs.getObject("binding_id", UUID.class),
            rs.getObject("principal_id", UUID.class),
            rs.getObject("role_id", UUID.class),
            new ScopePath(rs.getObject("organization_id", UUID.class),
                    rs.getObject("organization_unit_id", UUID.class),
                    rs.getObject("environment_id", UUID.class),
                    rs.getObject("solution_id", UUID.class),
                    rs.getObject("instance_id", UUID.class)));

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcScopeStore scopes;

    public JdbcAssignmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.scopes = new JdbcScopeStore(jdbc);
    }

    @Override
    public List<RoleView> roles() {
        return roles("", new MapSqlParameterSource());
    }

    @Override
    public Optional<RoleView> findRole(UUID roleId) {
        return roles(" WHERE r.role_id = :role", new MapSqlParameterSource().addValue("role", roleId)).stream()
                .findFirst();
    }

    private List<RoleView> roles(String where, MapSqlParameterSource parameters) {
        Map<UUID, String> names = new LinkedHashMap<>();
        Map<UUID, List<String>> verbs = new LinkedHashMap<>();
        jdbc.query("SELECT r.role_id, r.name, rp.permission_id FROM sm_control.role r"
                        + " LEFT JOIN sm_control.role_permission rp ON rp.role_id = r.role_id" + where
                        + " ORDER BY r.name COLLATE \"C\", r.role_id, rp.permission_id",
                parameters, resultSet -> {
                    UUID id = resultSet.getObject("role_id", UUID.class);
                    names.putIfAbsent(id, resultSet.getString("name"));
                    List<String> held = verbs.computeIfAbsent(id, ignored -> new ArrayList<>());
                    String permission = resultSet.getString("permission_id");
                    if (permission != null) {
                        held.add(permission);
                    }
                });
        List<RoleView> roles = new ArrayList<>();
        names.forEach((id, name) -> roles.add(new RoleView(id, name, verbs.get(id))));
        return List.copyOf(roles);
    }

    @Override
    public List<AssignmentView> list(UUID organizationId, Optional<UUID> principalId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("org", organizationId);
        if (principalId.isPresent()) {
            return jdbc.query(BINDING_SELECT + " AND rb.principal_id = :principal" + ORDER,
                    parameters.addValue("principal", principalId.get()), BINDING);
        }
        return jdbc.query(BINDING_SELECT + ORDER, parameters, BINDING);
    }

    @Override
    public Optional<AssignmentView> find(UUID organizationId, UUID assignmentId) {
        return jdbc.query(BINDING_SELECT + " AND rb.binding_id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", assignmentId), BINDING)
                .stream().findFirst();
    }

    @Override
    public Bound bind(UUID assignmentId, UUID principalId, UUID roleId, ScopePath scope) {
        ScopeId scopeId = scopes.canonicalize(scope);
        int inserted = jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                        + " VALUES (:id, :principal, :role, :scope) ON CONFLICT ON CONSTRAINT role_binding_unique"
                        + " DO NOTHING",
                new MapSqlParameterSource().addValue("id", assignmentId).addValue("principal", principalId)
                        .addValue("role", roleId).addValue("scope", scopeId.value()));
        if (inserted == 1) {
            return new Bound(new AssignmentView(assignmentId, principalId, roleId, scope), true);
        }
        UUID existing = jdbc.queryForObject("SELECT binding_id FROM sm_control.role_binding"
                        + " WHERE principal_id = :principal AND role_id = :role AND scope_id = :scope",
                new MapSqlParameterSource().addValue("principal", principalId).addValue("role", roleId)
                        .addValue("scope", scopeId.value()), UUID.class);
        return new Bound(new AssignmentView(existing, principalId, roleId, scope), false);
    }

    @Override
    public boolean unbind(UUID organizationId, UUID assignmentId) {
        return jdbc.update("DELETE FROM sm_control.role_binding rb USING sm_control.principal p"
                        + " WHERE rb.binding_id = :id AND p.id = rb.principal_id AND p.organization_id = :org",
                new MapSqlParameterSource().addValue("id", assignmentId).addValue("org", organizationId)) == 1;
    }
}
