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

/** JDBC reader for the role-binding permission union used by authorization. */
public final class JdbcAuthorizationGrantRepository implements AuthorizationGrantRepository {

    private static final String SELECT = """
            SELECT s.scope_id, s.organization_id, s.organization_unit_id, s.environment_id, s.solution_id,
                   s.instance_id, rp.permission_id
              FROM sm_control.role_binding rb
              JOIN sm_control.scope s ON s.scope_id = rb.scope_id
              JOIN sm_control.role_permission rp ON rp.role_id = rb.role_id
             WHERE rb.principal_id = :principalId
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
