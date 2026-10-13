package com.solace.sm.cp.identity;

import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.audit;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.count;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.principalWith;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.ref;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentConflictException;
import com.solace.sm.cp.api.identity.AssignmentInvalidException;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.authz.GrantReachAuthorization;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.identity.IdentityAdministrationFixtures.Instances;
import com.solace.sm.cp.persistence.JdbcAssignmentRepository;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalAdministrationRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalOrganizationResolver;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.target.TargetFixtures;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 013 T3.1: {@link AssignmentAdministrationService} against a real {@code sm_control} schema at the head (V018),
 * running <b>as {@code sm_cp_runtime}</b>, with the real authorization and reach services over real grants. The
 * decisive cases are the escalation ones: a principal who may assign at a sub-organization cannot assign at the
 * organization, and the Administrator role is neither granted nor unbound by anyone.
 */
class AssignmentAdministrationServiceIT {

    private final NamedParameterJdbcTemplate runtime = TargetFixtures.runtime();
    private final NamedParameterJdbcTemplate migrator = TargetFixtures.migrator();

    private AssignmentAdministration service;
    private ScopeEvaluatingAuthorizationService authorization;
    private JdbcAuthorizationGrantRepository grants;
    private Tree tree;

    @org.junit.jupiter.api.AfterEach
    void dropCustomRoles() {
        IdentityAdministrationFixtures.dropCustomRoles();
    }

    @BeforeEach
    void setUp() {
        ScopePathResolver paths = new ScopePathResolver(new JdbcResourceScopeDirectory(runtime));
        JdbcPrincipalOrganizationResolver organizations = new JdbcPrincipalOrganizationResolver(runtime);
        grants = new JdbcAuthorizationGrantRepository(runtime);
        authorization = new ScopeEvaluatingAuthorizationService(organizations, grants, paths);
        service = new AssignmentAdministrationService(new JdbcAssignmentRepository(runtime),
                new JdbcPrincipalAdministrationRepository(runtime), authorization,
                new GrantReachAuthorization(organizations, grants, paths), grants, paths,
                new JdbcAuditContext(runtime),
                new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource())),
                UUID::randomUUID);
        tree = TargetFixtures.seedTree();
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.70", UUID.randomUUID());
    }

    private PrincipalRef orgAdmin() {
        return ref(principalWith(tree, "Administrator", tree.organizationScope()));
    }

    private UUID user() {
        return TargetFixtures.localPrincipal(tree.organization(), "assignee-" + UUID.randomUUID(), "unused-credential",
                false);
    }

    private long bindings(UUID principal) {
        return count("SELECT count(*) FROM sm_control.role_binding WHERE principal_id = :p", Map.of("p", principal));
    }

    private long scopeRows() {
        return count("SELECT count(*) FROM sm_control.scope WHERE organization_id = :o",
                Map.of("o", tree.organization()));
    }

    private static UUID roleId(String name) {
        return switch (name) {
            case "Operator" -> BuiltInRoles.OPERATOR;
            case "Viewer" -> BuiltInRoles.VIEWER;
            default -> BuiltInRoles.ADMINISTRATOR;
        };
    }

    // ---------------------------------------------------------------- roles

    @Test
    void theBuiltInRoleIdsTheCodeNamesAreTheOnesTheMigrationSeeded() {
        Map<String, UUID> seeded = new java.util.HashMap<>();
        migrator.getJdbcTemplate().query("SELECT name, role_id FROM sm_control.role",
                rs -> {
                    seeded.put(rs.getString("name"), rs.getObject("role_id", UUID.class));
                });

        assertThat(seeded).containsEntry("Administrator", BuiltInRoles.ADMINISTRATOR)
                .containsEntry("Viewer", BuiltInRoles.VIEWER).containsEntry("Operator", BuiltInRoles.OPERATOR);
    }

    @Test
    void theRolesAreTheThreeBuiltInsWithTheirVerbsAndAdministratorHoldsEveryCatalogVerb() {
        PrincipalRef admin = orgAdmin();

        List<RoleView> roles = service.listRoles(admin, tree.organization());

        assertThat(roles).extracting(RoleView::name).containsExactly("Administrator", "Operator", "Viewer");
        assertThat(roles).filteredOn(r -> r.name().equals("Administrator")).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(BuiltInRoles.ADMINISTRATOR);
            assertThat(r.permissions()).containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(Permission.values()).map(Permission::id).toList());
            assertThat(r.permissions()).isSorted();
        });
        assertThat(roles).filteredOn(r -> r.name().equals("Viewer")).singleElement()
                .satisfies(r -> assertThat(r.permissions()).contains("solution.view").doesNotContain("role.assign"));
    }

    @Test
    void listingRolesNeedsRoleViewAtTheOrganization() {
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        PrincipalRef viewer = ref(principalWith(tree, "Viewer", tree.organizationScope()));

        assertThatThrownBy(() -> service.listRoles(unitAdmin, tree.organization()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.listRoles(viewer, tree.organization()))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- grant

    @Test
    void aGrantBindsTheRoleAtACanonicalScopeAuditsItAndTakesEffectOnTheNextAuthorization() {
        PrincipalRef admin = orgAdmin();
        UUID principal = user();
        ScopePath env = tree.environmentScope();
        ResourceRef envResource = ResourceRef.of(ResourceKind.ENVIRONMENT, tree.environment(),
                new TenantId(tree.organization()));
        assertThatThrownBy(() -> authorization.require(ref(principal), Permission.CONFIG_APPLY, envResource))
                .isInstanceOf(ResourceNotFoundException.class);
        RequestOrigin origin = origin();

        AssignmentAdministration.Granted granted =
                service.grant(admin, origin, tree.organization(), principal, BuiltInRoles.OPERATOR, env);

        assertThat(granted.created()).isTrue();
        assertThat(granted.assignment().principalId()).isEqualTo(principal);
        assertThat(granted.assignment().roleId()).isEqualTo(BuiltInRoles.OPERATOR);
        assertThat(granted.assignment().scope()).isEqualTo(env);
        Map<String, Object> row = migrator.queryForMap("SELECT rb.role_id, s.kind, s.environment_id FROM"
                        + " sm_control.role_binding rb JOIN sm_control.scope s ON s.scope_id = rb.scope_id"
                        + " WHERE rb.binding_id = :id", Map.of("id", granted.assignment().assignmentId()));
        assertThat(row).containsEntry("kind", "environment").containsEntry("environment_id", tree.environment());
        authorization.require(ref(principal), Permission.CONFIG_APPLY, envResource);
        assertThat(grants.grantsFor(ref(principal))).singleElement()
                .satisfies(g -> assertThat(g.scope()).isEqualTo(env));
        List<Map<String, Object>> audits = audit(origin.correlationId());
        assertThat(audits).extracting(a -> a.get("table_name")).contains("role_binding");
        assertThat(audits).allSatisfy(a -> assertThat(a).containsEntry("audit_operation", "ROLE_BINDING_GRANTED")
                .containsEntry("actor_principal_id", admin.id()).containsEntry("client_address", "203.0.113.70"));
    }

    @Test
    void anIdenticalGrantIsAReplayTheSameAssignmentNothingNewWrittenNoAuditRow() {
        PrincipalRef admin = orgAdmin();
        UUID principal = user();
        AssignmentAdministration.Granted first = service.grant(admin, origin(), tree.organization(), principal,
                BuiltInRoles.VIEWER, tree.unitScope());
        long scopes = scopeRows();
        RequestOrigin second = origin();

        AssignmentAdministration.Granted again = service.grant(admin, second, tree.organization(), principal,
                BuiltInRoles.VIEWER, tree.unitScope());

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.assignment().assignmentId()).isEqualTo(first.assignment().assignmentId());
        assertThat(bindings(principal)).isEqualTo(1);
        assertThat(scopeRows()).isEqualTo(scopes);
        assertThat(audit(second.correlationId())).isEmpty();
    }

    @Test
    void aSolutionAndAnInstanceAreGrantableAndAlreadyOwnedScopeRowsAreReusedNotDuplicated() {
        PrincipalRef admin = orgAdmin();
        Instances instances = IdentityAdministrationFixtures.seedInstances(tree);
        UUID a = user();
        UUID b = user();

        service.grant(admin, origin(), tree.organization(), a, BuiltInRoles.VIEWER, instances.solutionPath(tree));
        service.grant(admin, origin(), tree.organization(), a, BuiltInRoles.VIEWER, instances.livePath(tree));
        long scopes = scopeRows();
        service.grant(admin, origin(), tree.organization(), b, BuiltInRoles.VIEWER, instances.livePath(tree));

        assertThat(bindings(a)).isEqualTo(2);
        assertThat(scopeRows()).as("the second principal reuses the canonical scope row").isEqualTo(scopes);
    }

    @Test
    void aPrincipalWhoMayAssignAtASubOrganizationCannotAssignAtTheOrganizationNorAtASiblingSubOrganization() {
        UUID siblingUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), siblingUnit);
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        UUID principal = user();
        long scopes = scopeRows();

        assertThatThrownBy(() -> service.grant(unitAdmin, origin(), tree.organization(), principal,
                BuiltInRoles.OPERATOR, tree.organizationScope()))
                .as("escalation: the organization is above its binding").isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.grant(unitAdmin, origin(), tree.organization(), principal,
                BuiltInRoles.OPERATOR, ScopePath.subOrganization(tree.organization(), siblingUnit)))
                .as("a sibling is outside every scope it holds: absent").isInstanceOf(ResourceNotFoundException.class);

        assertThat(bindings(principal)).isZero();
        assertThat(scopeRows()).as("a refused grant mints no scope row").isEqualTo(scopes);

        service.grant(unitAdmin, origin(), tree.organization(), principal, BuiltInRoles.OPERATOR, tree.unitScope());
        service.grant(unitAdmin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.environmentScope());
        assertThat(bindings(principal)).as("inside its own unit it may").isEqualTo(2);
    }

    @Test
    void anInstallBoundAdministratorMayAssignAtAnyScopeOfTheSessionsOrganization() {
        UUID installAdmin = TargetFixtures.localPrincipal(tree.organization(), "inst-" + UUID.randomUUID(),
                "unused-credential", false);
        IdentityAdministrationFixtures.bindAtInstall(installAdmin, "Administrator");
        UUID principal = user();

        service.grant(ref(installAdmin), origin(), tree.organization(), principal, BuiltInRoles.OPERATOR,
                tree.organizationScope());

        assertThat(bindings(principal)).isEqualTo(1);
    }

    @Test
    void theAdministratorRoleIsNeverGrantedNotEvenByTheInstallAdministrator() {
        PrincipalRef admin = orgAdmin();
        UUID installAdmin = TargetFixtures.localPrincipal(tree.organization(), "inst-" + UUID.randomUUID(),
                "unused-credential", false);
        IdentityAdministrationFixtures.bindAtInstall(installAdmin, "Administrator");
        UUID principal = user();
        long scopes = scopeRows();

        for (PrincipalRef caller : List.of(admin, ref(installAdmin))) {
            assertThatThrownBy(() -> service.grant(caller, origin(), tree.organization(), principal,
                    BuiltInRoles.ADMINISTRATOR, tree.environmentScope()))
                    .isInstanceOfSatisfying(AssignmentInvalidException.class, e -> assertThat(e.reason())
                            .isEqualTo(AssignmentInvalidException.Reason.ADMINISTRATOR_NOT_GRANTABLE));
        }

        assertThat(bindings(principal)).isZero();
        assertThat(scopeRows()).isEqualTo(scopes);
    }

    @Test
    void aRequestedScopeWhoseAncestorsAreNotTheRealOnesIsAbsentAndNoScopeRowOrFkViolationFollows() {
        PrincipalRef admin = orgAdmin();
        UUID otherUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), otherUnit);
        UUID principal = user();
        long scopes = scopeRows();

        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), principal,
                BuiltInRoles.OPERATOR, ScopePath.environment(tree.organization(), otherUnit, tree.environment())))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(bindings(principal)).isZero();
        assertThat(scopeRows()).isEqualTo(scopes);
    }

    @Test
    void aScopeInAnotherOrganizationIsAbsentAndTheInstallScopeIsNotGrantable() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        UUID principal = user();

        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                other.environmentScope())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                ScopePath.INSTALL)).isInstanceOf(IllegalArgumentException.class);
        assertThat(bindings(principal)).isZero();
    }

    @Test
    void aPrincipalOfAnotherOrganizationAnUnknownRoleAndADeactivatedPrincipalAreRefusedAndNothingIsBound() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        UUID foreign = TargetFixtures.localPrincipal(other.organization(), "f-" + UUID.randomUUID(), "unused-credential",
                false);
        UUID gone = user();
        migrator.update("UPDATE sm_control.principal SET status = 'deactivated', deactivated_at = now() WHERE id = :id",
                Map.of("id", gone));
        UUID live = user();

        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), foreign, BuiltInRoles.VIEWER,
                tree.environmentScope())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), live, UUID.randomUUID(),
                tree.environmentScope())).isInstanceOfSatisfying(AssignmentInvalidException.class,
                e -> assertThat(e.reason()).isEqualTo(AssignmentInvalidException.Reason.UNKNOWN_ROLE));
        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), gone, BuiltInRoles.VIEWER,
                tree.environmentScope())).isInstanceOfSatisfying(AssignmentConflictException.class,
                e -> assertThat(e.reason()).isEqualTo(AssignmentConflictException.Reason.PRINCIPAL_DEACTIVATED));

        assertThat(bindings(foreign) + bindings(gone) + bindings(live)).isZero();
    }

    @Test
    void aGrantorCannotGrantARoleWhoseVerbsItDoesNotHoldAtTheGrantedScope() {
        // W3D-4, the stand-in for D-REG OQ-7 (c): holds role.assign (and role.view), none of the Operator's verbs.
        String infra = IdentityAdministrationFixtures.customRole(Permission.ROLE_ASSIGN, Permission.ROLE_VIEW);
        PrincipalRef infraAdmin = ref(principalWith(tree, infra, tree.organizationScope()));
        UUID principal = user();
        long scopes = scopeRows();

        assertThatThrownBy(() -> service.grant(infraAdmin, origin(), tree.organization(), principal,
                BuiltInRoles.OPERATOR, tree.environmentScope())).isInstanceOf(AccessDeniedException.class);

        assertThat(bindings(principal)).isZero();
        assertThat(scopeRows()).as("a refused grant mints no scope row").isEqualTo(scopes);

        UUID infraRole = migrator.queryForObject("SELECT role_id FROM sm_control.role WHERE name = :n",
                Map.of("n", infra), UUID.class);
        assertThat(service.grant(infraAdmin, origin(), tree.organization(), principal, infraRole,
                tree.environmentScope()).created()).as("it holds every verb of its own role").isTrue();
    }

    @Test
    void aPrincipalWithoutRoleAssignAtTheGrantedScopeIsRefused() {
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));
        UUID principal = user();

        assertThatThrownBy(() -> service.grant(operator, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.environmentScope())).isInstanceOf(AccessDeniedException.class);
        assertThat(bindings(principal)).isZero();
    }

    // ---------------------------------------------------------------- revoke

    @Test
    void aRevocationDeletesTheBindingAuditsItAndTheNextAuthorizationLosesTheGrant() {
        PrincipalRef admin = orgAdmin();
        UUID principal = user();
        ResourceRef envResource = ResourceRef.of(ResourceKind.ENVIRONMENT, tree.environment(),
                new TenantId(tree.organization()));
        AssignmentView granted = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.OPERATOR,
                tree.environmentScope()).assignment();
        authorization.require(ref(principal), Permission.CONFIG_APPLY, envResource);
        RequestOrigin origin = origin();

        service.revoke(admin, origin, tree.organization(), granted.assignmentId());

        assertThat(bindings(principal)).isZero();
        assertThatThrownBy(() -> authorization.require(ref(principal), Permission.CONFIG_APPLY, envResource))
                .isInstanceOf(ResourceNotFoundException.class);
        List<Map<String, Object>> audits = audit(origin.correlationId());
        assertThat(audits).singleElement().satisfies(a -> assertThat(a).containsEntry("table_name", "role_binding")
                .containsEntry("operation", "DELETE").containsEntry("audit_operation", "ROLE_BINDING_REVOKED")
                .containsEntry("actor_principal_id", admin.id()));
        assertThatThrownBy(() -> service.revoke(admin, origin(), tree.organization(), granted.assignmentId()))
                .as("a second revoke finds nothing").isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aPrincipalCannotRevokeAboveItsOwnScopeAndCannotSeeASiblingsBinding() {
        PrincipalRef admin = orgAdmin();
        UUID siblingUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), siblingUnit);
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        UUID principal = user();
        AssignmentView atOrg = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.organizationScope()).assignment();
        AssignmentView atSibling = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                ScopePath.subOrganization(tree.organization(), siblingUnit)).assignment();
        AssignmentView atEnv = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.environmentScope()).assignment();

        assertThatThrownBy(() -> service.revoke(unitAdmin, origin(), tree.organization(), atOrg.assignmentId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.revoke(unitAdmin, origin(), tree.organization(), atSibling.assignmentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bindings(principal)).isEqualTo(3);

        service.revoke(unitAdmin, origin(), tree.organization(), atEnv.assignmentId());
        assertThat(bindings(principal)).isEqualTo(2);
    }

    @Test
    void anAdministratorBindingIsNeverRevokedByAnyoneAndTheUnauthorizedAreRefusedAsSuch() {
        PrincipalRef admin = orgAdmin();
        UUID otherAdmin = principalWith(tree, "Administrator", tree.organizationScope());
        UUID bindingId = migrator.queryForObject("SELECT binding_id FROM sm_control.role_binding WHERE principal_id = :p",
                Map.of("p", otherAdmin), UUID.class);
        UUID installAdmin = TargetFixtures.localPrincipal(tree.organization(), "inst-" + UUID.randomUUID(),
                "unused-credential", false);
        IdentityAdministrationFixtures.bindAtInstall(installAdmin, "Administrator");
        UUID installBindingId = migrator.queryForObject(
                "SELECT binding_id FROM sm_control.role_binding WHERE principal_id = :p", Map.of("p", installAdmin),
                UUID.class);
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));

        assertThatThrownBy(() -> service.revoke(admin, origin(), tree.organization(), bindingId))
                .isInstanceOfSatisfying(AssignmentConflictException.class, e -> assertThat(e.reason())
                        .isEqualTo(AssignmentConflictException.Reason.ADMINISTRATOR_BINDING));
        assertThatThrownBy(() -> service.revoke(ref(installAdmin), origin(), tree.organization(), installBindingId))
                .as("not even the install administrator unbinds itself: the last one cannot be removed")
                .isInstanceOf(AssignmentConflictException.class);
        assertThatThrownBy(() -> service.revoke(operator, origin(), tree.organization(), bindingId))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(bindings(otherAdmin)).isEqualTo(1);
        assertThat(bindings(installAdmin)).isEqualTo(1);
    }

    @Test
    void aBindingOfAnotherOrganizationsPrincipalOrNoneAtAllIsAbsent() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        PrincipalRef otherAdmin = ref(principalWith(other, "Administrator", other.organizationScope()));
        UUID foreignUser = TargetFixtures.localPrincipal(other.organization(), "f-" + UUID.randomUUID(),
                "unused-credential", false);
        AssignmentView foreign = service.grant(otherAdmin, origin(), other.organization(), foreignUser,
                BuiltInRoles.VIEWER, other.organizationScope()).assignment();

        assertThatThrownBy(() -> service.revoke(admin, origin(), tree.organization(), foreign.assignmentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.revoke(admin, origin(), tree.organization(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bindings(foreignUser)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- list

    @Test
    void aListShowsOnlyWhatTheCallersRoleViewReachesAndTheInstallBindingOnlyToAnInstallGrant() {
        PrincipalRef admin = orgAdmin();
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        UUID installAdmin = TargetFixtures.localPrincipal(tree.organization(), "inst-" + UUID.randomUUID(),
                "unused-credential", false);
        IdentityAdministrationFixtures.bindAtInstall(installAdmin, "Administrator");
        UUID siblingUnit = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id,"
                + " organization_unit_id, name) VALUES (?, ?, 'fixture-' || gen_random_uuid()::text)",
                tree.organization(), siblingUnit);
        UUID principal = user();
        AssignmentView atOrg = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.organizationScope()).assignment();
        AssignmentView atUnit = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                tree.unitScope()).assignment();
        AssignmentView atEnv = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.OPERATOR,
                tree.environmentScope()).assignment();
        AssignmentView atSibling = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                ScopePath.subOrganization(tree.organization(), siblingUnit)).assignment();

        assertThat(service.list(unitAdmin, tree.organization(), Optional.of(principal))).containsExactlyInAnyOrder(
                atUnit, atEnv);
        assertThat(service.list(admin, tree.organization(), Optional.of(principal))).containsExactlyInAnyOrder(atOrg,
                atUnit, atEnv, atSibling);
        List<AssignmentView> all = service.list(ref(installAdmin), tree.organization(), Optional.empty());
        assertThat(all).contains(atOrg, atUnit, atEnv, atSibling);
        assertThat(all).anySatisfy(a -> {
            assertThat(a.principalId()).isEqualTo(installAdmin);
            assertThat(a.scope()).as("the install-scope binding").isEqualTo(ScopePath.INSTALL);
            assertThat(a.roleId()).isEqualTo(BuiltInRoles.ADMINISTRATOR);
        });
        assertThat(service.list(admin, tree.organization(), Optional.empty()))
                .as("an organization-bound caller does not reach the install-scope binding")
                .noneMatch(a -> a.scope().depth() == 0);
    }

    @Test
    void aListOfAPrincipalOutsideTheOrganizationIsEmptyAndAListNeedsRoleView() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        PrincipalRef otherAdmin = ref(principalWith(other, "Administrator", other.organizationScope()));
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));

        assertThat(service.list(admin, tree.organization(), Optional.of(otherAdmin.id()))).isEmpty();
        assertThatThrownBy(() -> service.list(operator, tree.organization(), Optional.empty()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(admin, tree.organization(), Optional.empty()))
                .noneMatch(a -> a.principalId().equals(otherAdmin.id()));
    }

    @Test
    void aBindingAtARetiredInstanceIsNeitherListedNorRevocableBecauseItGrantsNothing() {
        PrincipalRef admin = orgAdmin();
        Instances instances = IdentityAdministrationFixtures.seedInstances(tree);
        UUID principal = user();
        AssignmentView atLive = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                instances.livePath(tree)).assignment();
        AssignmentView atRetiring = service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.VIEWER,
                instances.retiringPath(tree)).assignment();
        assertThat(service.list(admin, tree.organization(), Optional.of(principal))).containsExactlyInAnyOrder(atLive,
                atRetiring);

        IdentityAdministrationFixtures.retire(tree, instances.retiring());

        assertThat(service.list(admin, tree.organization(), Optional.of(principal))).containsExactly(atLive);
        assertThatThrownBy(() -> service.revoke(admin, origin(), tree.organization(), atRetiring.assignmentId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.grant(admin, origin(), tree.organization(), principal, BuiltInRoles.OPERATOR,
                instances.retiringPath(tree))).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- the schema's own limits stay

    @Test
    void theRuntimeRoleStillCannotWriteARoleOrItsVerbsSoTheBuiltInsChangeOnlyByMigration() {
        assertThatThrownBy(() -> runtime.update("INSERT INTO sm_control.role_permission (role_id, permission_id)"
                + " VALUES (:r, 'role.assign')", Map.of("r", BuiltInRoles.VIEWER)))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> runtime.update("DELETE FROM sm_control.role WHERE role_id = :r",
                Map.of("r", BuiltInRoles.VIEWER))).hasStackTraceContaining("permission denied");
    }
}
