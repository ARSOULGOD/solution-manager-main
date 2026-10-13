package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * JDBC reader for the role-binding permission union used by authorization.
 *
 * <p>A binding whose scope is a <b>retired</b> instance (V017, plan 012 T4, {@code OPS-Q4} (a)) grants nothing: it is
 * skipped here, once, so every reader built on the grants - authorization, the hierarchy directory, the identity
 * view - agrees. That is what makes a principal bound only on the retired instance see neither its environment nor its
 * solution, and get {@code 404} (not {@code 403}) on the environment and the solution of its path (not on a sibling instance). A binding at the environment, the solution or
 * higher is untouched. {@code instance_id} is globally unique ({@code OR-27}, V013), so matching it alone is exact.
 */
public final class JdbcAuthorizationGrantRepository implements AuthorizationGrantRepository {

    private static final String SELECT = """
            SELECT s.scope_id, s.organization_id, s.organization_unit_id, s.environment_id, s.solution_id,
                   s.instance_id, rp.permission_id
              FROM sm_control.role_binding rb
              JOIN sm_control.scope s ON s.scope_id = rb.scope_id
              JOIN sm_control.role_permission rp ON rp.role_id = rb.role_id
             WHERE rb.principal_id = :principalId
               AND NOT EXISTS (SELECT 1 FROM sm_control.instance retired
                                WHERE retired.instance_id = s.instance_id AND retired.retired_at IS NOT NULL)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAuthorizationGrantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<AuthorizationGrant> grantsFor(PrincipalRef principal) {
        Map<ScopePath, Set<Permission>> permissionsByScope = new LinkedHashMap<>();
        jdbc.query(SELECT, Map.of("principalId", principal.id()), (RowCallbackHandler) resultSet -> {
            ScopePath path = new ScopePath(
                    resultSet.getObject("organization_id", UUID.class),
                    resultSet.getObject("organization_unit_id", UUID.class),
                    resultSet.getObject("environment_id", UUID.class),
                    resultSet.getObject("solution_id", UUID.class),
                    resultSet.getObject("instance_id", UUID.class));
            permissionsByScope.computeIfAbsent(path, ignored -> EnumSet.noneOf(Permission.class))
                    .add(Permission.byId(resultSet.getString("permission_id")));
        });
        List<AuthorizationGrant> grants = new ArrayList<>();
        permissionsByScope.forEach((scope, permissions) -> grants.add(new AuthorizationGrant(scope, permissions)));
        return List.copyOf(grants);
    }
}
