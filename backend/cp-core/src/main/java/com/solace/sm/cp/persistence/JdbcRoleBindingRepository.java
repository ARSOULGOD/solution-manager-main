package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.identity.RoleBindingRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter — distinct role names a principal is bound to, at any scope. */
public final class JdbcRoleBindingRepository implements RoleBindingRepository {

    private static final String SELECT = """
            SELECT DISTINCT r.name
              FROM sm_control.role_binding rb
              JOIN sm_control.role r ON r.role_id = rb.role_id
             WHERE rb.principal_id = :principalId
             ORDER BY r.name
            """;

    // `sm_control.scope.kind = 'install'` is the one, uniquely-constrained install-scope row
    // (`scope_one_install`, V001__scope.sql) — the queryable definition of "install scope" this
    // repository joins against, rather than any scope carrying the given role name.
    private static final String SELECT_AT_INSTALL_SCOPE = """
            SELECT EXISTS (
                SELECT 1
                  FROM sm_control.role_binding rb
                  JOIN sm_control.role r ON r.role_id = rb.role_id
                  JOIN sm_control.scope s ON s.scope_id = rb.scope_id
                 WHERE rb.principal_id = :principalId
                   AND r.name = :roleName
                   AND s.kind = 'install'
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRoleBindingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<String> boundRoleNames(PrincipalRef principal) {
        Objects.requireNonNull(principal, "principal");
        // ORDER BY r.name above already makes the query deterministic; Administrator is still
        // pulled to the front here regardless of its alphabetic position, matching this method's
        // existing "Administrator first, if present" contract.
        Set<String> names = new LinkedHashSet<>(
                jdbc.queryForList(SELECT, Map.of("principalId", principal.id()), String.class));
        List<String> ordered = new ArrayList<>();
        if (names.remove("Administrator")) {
            ordered.add("Administrator");
        }
        ordered.addAll(names);
        return List.copyOf(ordered);
    }

    @Override
    public boolean boundToRoleAtInstallScope(PrincipalRef principal, String roleName) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(roleName, "roleName");
        Boolean result = jdbc.queryForObject(SELECT_AT_INSTALL_SCOPE,
                Map.of("principalId", principal.id(), "roleName", roleName), Boolean.class);
        return Boolean.TRUE.equals(result);
    }
}
