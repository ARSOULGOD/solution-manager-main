package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.authz.ResourceScopeDirectory;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** sm_control-only resource directory; owning-plane adapters are required for the remaining kinds. */
public final class JdbcResourceScopeDirectory implements ResourceScopeDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcResourceScopeDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<ScopePath> findScopePath(ResourceRef resource) {
        return switch (resource.kind()) {
            case ORGANIZATION -> query("SELECT organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.organization WHERE organization_id = :id", resource.id());
            case SUB_ORGANIZATION -> query("SELECT organization_id, organization_unit_id, NULL::uuid AS environment_id, "
                    + "NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.organization_unit WHERE organization_unit_id = :id", resource.id());
            case ENVIRONMENT -> query("SELECT organization_id, organization_unit_id, environment_id "
                    + ", NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.environment WHERE environment_id = :id", resource.id());
            case SOLUTION -> query("SELECT organization_id, organization_unit_id, environment_id, solution_id "
                    + ", NULL::uuid AS instance_id FROM sm_control.solution WHERE solution_id = :id", resource.id());
            case INSTANCE -> query("SELECT organization_id, organization_unit_id, environment_id, solution_id, instance_id "
                    + "FROM sm_control.instance WHERE instance_id = :id", resource.id());
            case ROLE_DEFINITION -> query("SELECT NULL::uuid AS organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.role WHERE role_id = :id", resource.id());
            case PRINCIPAL -> query("SELECT organization_id, NULL::uuid AS organization_unit_id, "
                    + "NULL::uuid AS environment_id, NULL::uuid AS solution_id, NULL::uuid AS instance_id "
                    + "FROM sm_control.principal WHERE id = :id", resource.id());
            case PLACEMENT, DEPLOYMENT_TARGET, SOLUTION_PACKAGE, AUDIT_RECORD -> Optional.empty();
        };
    }

    private Optional<ScopePath> query(String sql, UUID id) {
        return jdbc.query(sql + " LIMIT 2", Map.of("id", id), resultSet -> {
            if (!resultSet.next()) {
                return Optional.empty();
            }
            ScopePath path = new ScopePath(
                    resultSet.getObject("organization_id", UUID.class),
                    resultSet.getObject("organization_unit_id", UUID.class),
                    resultSet.getObject("environment_id", UUID.class),
                    resultSet.getObject("solution_id", UUID.class),
                    resultSet.getObject("instance_id", UUID.class));
            return resultSet.next() ? Optional.empty() : Optional.of(path);
        });
    }
}
