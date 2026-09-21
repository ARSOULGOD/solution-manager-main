package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AuthorizationIT {

    private NamedParameterJdbcTemplate jdbc;
    private ScopeEvaluatingAuthorizationService authorization;
    private UUID organization;
    private UUID otherOrganization;
    private UUID unit;
    private UUID development;
    private UUID production;
    private UUID solution;
    private UUID instance;
    private PrincipalRef principal;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("006");
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.role_binding");
        organization = UUID.randomUUID();
        otherOrganization = UUID.randomUUID();
        unit = UUID.randomUUID();
        development = UUID.randomUUID();
        production = UUID.randomUUID();
        solution = UUID.randomUUID();
        instance = UUID.randomUUID();
        seedTree();
        principal = new PrincipalRef(seedPrincipal(organization));
        authorization = new ScopeEvaluatingAuthorizationService(
                new JdbcPrincipalOrganizationResolver(jdbc),
                new JdbcAuthorizationGrantRepository(jdbc),
                new ScopePathResolver(new JdbcResourceScopeDirectory(jdbc)));
    }

    @Test
    void aUnitScopedGrantReachesAChildEnvironment() {
        grant("Operator", ScopePath.subOrganization(organization, unit));

        assertThatNoException().isThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.ENVIRONMENT, development)));
    }

    @Test
    void anEnvironmentScopedGrantDoesNotReachASiblingEnvironment() {
        grant("Operator", ScopePath.environment(organization, unit, development));
        grant("Viewer", ScopePath.environment(organization, unit, production));

        assertThatThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.ENVIRONMENT, production)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void runtimeRoleCanEvaluateTheSeededInstallGrant() {
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("009");
        ScopeEvaluatingAuthorizationService runtimeAuthorization = new ScopeEvaluatingAuthorizationService(
                new JdbcPrincipalOrganizationResolver(runtime),
                new JdbcAuthorizationGrantRepository(runtime),
                new ScopePathResolver(new JdbcResourceScopeDirectory(runtime)));

        assertThatNoException().isThrownBy(() -> runtimeAuthorization.require(
                new PrincipalRef(UUID.fromString("00000000-0000-4000-8000-000000000012")),
                Permission.CONFIG_APPLY,
                resource(ResourceKind.ORGANIZATION, UUID.fromString("00000000-0000-4000-8000-000000000011"))));
    }

    @Test
    void aCrossOrganizationResourceIsRefusedBeforeBindingsAreConsulted() {
        UUID otherUnit = UUID.randomUUID();
        UUID otherEnvironment = UUID.randomUUID();
        seedEnvironment(otherOrganization, otherUnit, otherEnvironment);
        AtomicInteger grantLookups = new AtomicInteger();
        authorization = new ScopeEvaluatingAuthorizationService(
                new JdbcPrincipalOrganizationResolver(jdbc),
                ignored -> {
                    grantLookups.incrementAndGet();
                    return java.util.List.of();
                },
                new ScopePathResolver(new JdbcResourceScopeDirectory(jdbc)));

        assertThatThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.ENVIRONMENT, otherEnvironment)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(grantLookups).hasValue(0);
    }

    @Test
    void anInstallScopedBindingReachesEveryResource() {
        grant("Administrator", ScopePath.INSTALL);

        assertThatNoException().isThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.INSTANCE, instance)));
    }

    @Test
    void aScopeIsVisibleInEitherDirectionNotOnlyDownward() {
        ScopePath granted = ScopePath.environment(organization, unit, development);

        assertThat(ScopeVisibility.isVisible(granted, ScopePath.subOrganization(organization, unit))).isTrue();
        assertThat(ScopeVisibility.isVisible(granted, ScopePath.environment(organization, unit, production))).isFalse();
    }

    @Test
    void visibilityIsNotReachability() {
        grant("Operator", ScopePath.environment(organization, unit, development));

        assertThatThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.SUB_ORGANIZATION, unit)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void existenceAndPermissionAreDiscriminated() {
        grant("Viewer", ScopePath.environment(organization, unit, development));

        assertThatThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.ENVIRONMENT, development)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authorization.require(principal, Permission.CONFIG_APPLY,
                resource(ResourceKind.ENVIRONMENT, UUID.randomUUID())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void everyResourceKindIsBackedOrFailsClosedWithoutAnInventedOwningPlaneLookup() {
        ScopePathResolver resolver = new ScopePathResolver(new JdbcResourceScopeDirectory(jdbc));
        UUID administratorRole = jdbc.queryForObject(
                "SELECT role_id FROM sm_control.role WHERE name = 'Administrator'", Map.of(), UUID.class);

        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.ORGANIZATION, organization)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.SUB_ORGANIZATION, unit)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.ENVIRONMENT, development)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.SOLUTION, solution)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.INSTANCE, instance)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.ROLE_DEFINITION, administratorRole)));
        assertThatNoException().isThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.PRINCIPAL, principal.id())));

        for (ResourceKind unbacked : new ResourceKind[]{
                ResourceKind.PLACEMENT, ResourceKind.DEPLOYMENT_TARGET, ResourceKind.SOLUTION_PACKAGE,
                ResourceKind.AUDIT_RECORD}) {
            assertThatThrownBy(() -> resolver.scopePathOf(resource(unbacked, UUID.randomUUID())))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void aDuplicateChildIdUnderAnotherOrganizationIsRefusedRatherThanResolvedArbitrarily() {
        UUID otherUnit = UUID.randomUUID();
        seedEnvironment(otherOrganization, otherUnit, development);
        ScopePathResolver resolver = new ScopePathResolver(new JdbcResourceScopeDirectory(jdbc));

        assertThatThrownBy(() -> resolver.scopePathOf(resource(ResourceKind.ENVIRONMENT, development)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void seedTree() {
        seedEnvironment(organization, unit, development);
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id) "
                        + "VALUES (:organization, :unit, :environment)", params(organization, unit, production));
        jdbc.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id, solution_id) "
                        + "VALUES (:organization, :unit, :environment, :solution)",
                params(organization, unit, development).addValue("solution", solution));
        jdbc.update("INSERT INTO sm_control.instance "
                        + "(organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:organization, :unit, :environment, :solution, :instance)",
                params(organization, unit, development).addValue("solution", solution).addValue("instance", instance));
    }

    private void seedEnvironment(UUID organizationId, UUID unitId, UUID environmentId) {
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:organization)",
                Map.of("organization", organizationId));
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) "
                        + "VALUES (:organization, :unit)", params(organizationId, unitId, null));
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id) "
                        + "VALUES (:organization, :unit, :environment)", params(organizationId, unitId, environmentId));
    }

    private UUID seedPrincipal(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username) "
                        + "VALUES (:id, :organization, 'idp', :username)",
                new MapSqlParameterSource().addValue("id", id).addValue("organization", organizationId)
                        .addValue("username", "authorization-" + id + "@example.test"));
        return id;
    }

    private void grant(String role, ScopePath path) {
        UUID scopeId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope "
                        + "(scope_id, kind, organization_id, organization_unit_id, environment_id, solution_id, instance_id) "
                        + "VALUES (:scopeId, :kind, :organization, :unit, :environment, :solution, :instance)",
                scopeParameters(scopeId, path));
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) "
                        + "VALUES (:bindingId, :principalId, "
                        + "(SELECT role_id FROM sm_control.role WHERE name = :role), :scopeId)",
                new MapSqlParameterSource().addValue("bindingId", UUID.randomUUID()).addValue("principalId", principal.id())
                        .addValue("role", role).addValue("scopeId", scopeId));
    }

    private ResourceRef resource(ResourceKind kind, UUID id) {
        return ResourceRef.of(kind, id, new TenantId(UUID.randomUUID()));
    }

    private static MapSqlParameterSource params(UUID organization, UUID unit, UUID environment) {
        return new MapSqlParameterSource().addValue("organization", organization)
                .addValue("unit", unit).addValue("environment", environment);
    }

    private static MapSqlParameterSource scopeParameters(UUID scopeId, ScopePath path) {
        return new MapSqlParameterSource().addValue("scopeId", scopeId).addValue("kind", switch (path.depth()) {
            case 0 -> "install";
            case 1 -> "organization";
            case 2 -> "organization_unit";
            case 3 -> "environment";
            case 4 -> "solution";
            case 5 -> "instance";
            default -> throw new IllegalArgumentException("unsupported scope depth");
        }).addValue("organization", path.organization()).addValue("unit", path.subOrganization())
                .addValue("environment", path.environment()).addValue("solution", path.solution())
                .addValue("instance", path.instance());
    }
}
