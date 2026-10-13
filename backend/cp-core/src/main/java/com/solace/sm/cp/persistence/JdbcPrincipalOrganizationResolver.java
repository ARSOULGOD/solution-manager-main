package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.authz.PrincipalOrganizationResolver;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC lookup of an active principal's server-side organization. */
public final class JdbcPrincipalOrganizationResolver implements PrincipalOrganizationResolver {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPrincipalOrganizationResolver(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<UUID> organizationOf(PrincipalRef principal) {
        return jdbc.query("SELECT organization_id FROM sm_control.principal "
                        + "WHERE id = :id AND status = 'active'", Map.of("id", principal.id()), resultSet ->
                resultSet.next() ? Optional.of(resultSet.getObject("organization_id", UUID.class)) : Optional.empty());
    }
}
