package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.AssignmentAdministration;
import com.solace.sm.cp.api.identity.AssignmentConflictException;
import com.solace.sm.cp.api.identity.AssignmentInvalidException;
import com.solace.sm.cp.api.identity.AssignmentView;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.RoleView;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import com.solace.sm.cp.authz.ScopePathResolver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/**
 * {@link AssignmentAdministrationService} with every collaborator a fake (plan 013 T3.1): where each operation is
 * authorized, that the Administrator role is neither granted nor unbound, that a grant names real, correctly ordered
 * nodes before anything is written, and that a list shows only what the caller's {@code role.view} reaches. The
 * database half, with the real authorization service over real grants, is
 * {@code AssignmentAdministrationServiceIT}.
 */
class AssignmentAdministrationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.50", UUID.randomUUID());

    private final List<String> calls = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private final List<Permission> verbs = new ArrayList<>();
    private RuntimeException refuse;
    private final FakeAssignments assignments = new FakeAssignments(calls);
    private final FakePrincipals principals = new FakePrincipals(calls);
    private final Map<ResourceRef, ScopePath> directory = new HashMap<>();
    private final List<AuthorizationGrant> actorGrants = new ArrayList<>();
    private final List<String> reachChecks = new ArrayList<>();
    private final UUID minted = UUID.randomUUID();

    private final AuthorizationService authorization = (principal, permission, resource) -> {
        calls.add("authorize " + permission.id() + " " + resource.kind());
        verbs.add(permission);
        required.add(resource);
        if (refuse != null) {
            throw refuse;
        }
    };
    private final ReachAuthorization reach = new ReachAuthorization() {
        @Override
        public void requireHeldAnywhere(PrincipalRef principal, Permission permission) {
            calls.add("heldAnywhere " + permission.id());
            reachChecks.add(permission.id());
            if (refuse != null) {
                throw refuse;
            }
        }

        @Override
        public Set<UUID> authorizedSubset(PrincipalRef principal, Permission permission, ResourceKind kind,
                Collection<UUID> ids) {
            throw new UnsupportedOperationException();
        }
    };
    private final AuthorizationGrantRepository grants = principal -> List.copyOf(actorGrants);
    private final AuditContext auditContext = (actor, operation, correlation, client) ->
            calls.add("audit " + operation + "@" + client + "/" + actor);
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls.add("begin");
            T result = action.doInTransaction(null);
            calls.add("commit");
            return result;
        }
    };
    private final ScopePathResolver paths = new ScopePathResolver(resource -> Optional.ofNullable(directory.get(resource)));
    private final AssignmentAdministration service = new AssignmentAdministrationService(assignments, principals,
            authorization, reach, grants, paths, auditContext, transactions, () -> minted);

    // ---------------------------------------------------------------- roles

    @Test
    void listingRolesNeedsRoleViewAtTheOrganizationAndReturnsTheRepositorysRoles() {
        assignments.roles.add(new RoleView(BuiltInRoles.VIEWER, "Viewer", List.of("solution.view")));

        assertThat(service.listRoles(ACTOR, ORG)).extracting(RoleView::name).containsExactly("Viewer");

        assertThat(verbs).containsExactly(Permission.ROLE_VIEW);
        assertThat(required).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(ResourceKind.ORGANIZATION);
            assertThat(r.id()).isEqualTo(ORG);
        });
    }

    @Test
    void aRefusedRoleListReadsNothing() {
        refuse = new AccessDeniedException(Permission.ROLE_VIEW);

        assertThatThrownBy(() -> service.listRoles(ACTOR, ORG)).isInstanceOf(AccessDeniedException.class);

        assertThat(calls).containsExactly("authorize role.view ORGANIZATION");
    }

    // ---------------------------------------------------------------- list: the reach filter

    @Test
    void aListIsFilteredByTheScopesTheCallersRoleViewGrantsReachDownward() {
        UUID p = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        AssignmentView atInstall = bound(p, ScopePath.INSTALL);
        AssignmentView atOrg = bound(p, ScopePath.organization(ORG));
        AssignmentView atUnit = bound(p, ScopePath.subOrganization(ORG, UNIT));
        AssignmentView atEnv = bound(p, ScopePath.environment(ORG, UNIT, ENV));
        AssignmentView atOtherUnit = bound(p, ScopePath.subOrganization(ORG, otherUnit));
        assignments.bindings.addAll(List.of(atInstall, atOrg, atUnit, atEnv, atOtherUnit));

        actorGrants.add(new AuthorizationGrant(ScopePath.subOrganization(ORG, UNIT), Set.of(Permission.ROLE_VIEW)));
        assertThat(service.list(ACTOR, ORG, Optional.empty())).containsExactlyInAnyOrder(atUnit, atEnv);

        actorGrants.clear();
        actorGrants.add(new AuthorizationGrant(ScopePath.organization(ORG), Set.of(Permission.ROLE_VIEW)));
        assertThat(service.list(ACTOR, ORG, Optional.empty())).containsExactlyInAnyOrder(atOrg, atUnit, atEnv,
                atOtherUnit);

        actorGrants.clear();
        actorGrants.add(new AuthorizationGrant(ScopePath.INSTALL, Set.of(Permission.ROLE_VIEW)));
        assertThat(service.list(ACTOR, ORG, Optional.empty())).containsExactlyInAnyOrder(atInstall, atOrg, atUnit,
                atEnv, atOtherUnit);
    }

    @Test
    void aGrantThatDoesNotHoldRoleViewOrIsInAnotherOrganizationReachesNothing() {
        UUID p = UUID.randomUUID();
        assignments.bindings.add(bound(p, ScopePath.organization(ORG)));
        actorGrants.add(new AuthorizationGrant(ScopePath.organization(ORG), Set.of(Permission.USER_VIEW)));
        actorGrants.add(new AuthorizationGrant(ScopePath.organization(UUID.randomUUID()), Set.of(Permission.ROLE_VIEW)));

        assertThat(service.list(ACTOR, ORG, Optional.empty())).isEmpty();
    }

    @Test
    void aListNeedsRoleViewHeldSomewhereInTheOrganizationAndPassesThePrincipalFilterThrough() {
        UUID p = UUID.randomUUID();
        actorGrants.add(new AuthorizationGrant(ScopePath.INSTALL, Set.of(Permission.ROLE_VIEW)));

        service.list(ACTOR, ORG, Optional.of(p));

        assertThat(reachChecks).containsExactly("role.view");
        assertThat(calls).contains("list " + ORG + " " + p);
    }

    @Test
    void aRefusedReachCheckReadsNoBinding() {
        refuse = new AccessDeniedException(Permission.ROLE_VIEW);

        assertThatThrownBy(() -> service.list(ACTOR, ORG, Optional.empty())).isInstanceOf(AccessDeniedException.class);

        assertThat(calls).containsExactly("heldAnywhere role.view");
    }

    // ---------------------------------------------------------------- grant: authorized at the granted scope

    @Test
    void aGrantIsAuthorizedAtTheDeepestNodeOfTheGrantedScopeForEachOfTheFiveDepths() {
        List<ScopePath> scopes = List.of(ScopePath.organization(ORG), ScopePath.subOrganization(ORG, UNIT),
                ScopePath.environment(ORG, UNIT, ENV), ScopePath.solution(ORG, UNIT, ENV, SOLUTION),
                ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE));
        List<ResourceKind> kinds = List.of(ResourceKind.ORGANIZATION, ResourceKind.SUB_ORGANIZATION,
                ResourceKind.ENVIRONMENT, ResourceKind.SOLUTION, ResourceKind.INSTANCE);
        List<UUID> ids = List.of(ORG, UNIT, ENV, SOLUTION, INSTANCE);
        UUID principal = principals.addActive(ORG);
        assignments.addRole(BuiltInRoles.OPERATOR, "Operator");

        for (int i = 0; i < scopes.size(); i++) {
            ScopePath scope = scopes.get(i);
            directory.put(ResourceRef.of(kinds.get(i), ids.get(i),
                    new com.solace.sm.cp.api.authz.TenantId(ORG)), scope);
            service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, scope);
        }

        assertThat(verbs).containsOnly(Permission.ROLE_ASSIGN);
        assertThat(required).extracting(ResourceRef::kind).containsExactlyElementsOf(kinds);
        assertThat(required).extracting(ResourceRef::id).containsExactlyElementsOf(ids);
    }

    @Test
    void aRefusedGrantAuthorizationReadsAndWritesNothing() {
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));
        refuse = new AccessDeniedException(Permission.ROLE_ASSIGN);

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UNIT, ENV))).isInstanceOf(AccessDeniedException.class);

        assertThat(calls).containsExactly("authorize role.assign ENVIRONMENT");
    }

    @Test
    void aScopeInAnotherOrganizationIsAbsentAndNothingIsAuthorizedOrRead() {
        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.environment(UUID.randomUUID(), UNIT, ENV))).isInstanceOf(ResourceNotFoundException.class);

        assertThat(calls).isEmpty();
    }

    @Test
    void theInstallScopeIsNeverGrantableThroughThisSurface() {
        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.INSTALL)).isInstanceOf(IllegalArgumentException.class);

        assertThat(calls).isEmpty();
    }

    @Test
    void aScopeWhoseAncestorsAreNotTheRealOnesIsAbsentAndWritesNothing() {
        // ENV really belongs to UNIT; the request claims another unit above it.
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UUID.randomUUID(), ENV)))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("environment not found"));

        assertThat(calls).containsExactly("authorize role.assign ENVIRONMENT");
        assertThat(assignments.writes).isEmpty();
    }

    // ---------------------------------------------------------------- grant: what may be granted, and to whom

    @Test
    void theAdministratorRoleIsNeverGrantedAndNothingIsReadOrWritten() {
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.ADMINISTRATOR,
                ScopePath.environment(ORG, UNIT, ENV)))
                .isInstanceOfSatisfying(AssignmentInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo(AssignmentInvalidException.Reason.ADMINISTRATOR_NOT_GRANTABLE));

        assertThat(assignments.writes).isEmpty();
        assertThat(calls).as("authorized first, then refused before any read of the role or the principal")
                .containsExactly("authorize role.assign ENVIRONMENT");
    }

    @Test
    void anUnknownRoleIsInvalidAndAPrincipalOfAnotherOrganizationOrNoneIsAbsent() {
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));
        UUID foreign = principals.addActive(UUID.randomUUID());
        assignments.addRole(BuiltInRoles.OPERATOR, "Operator");

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), UUID.randomUUID(),
                ScopePath.environment(ORG, UNIT, ENV)))
                .isInstanceOfSatisfying(AssignmentInvalidException.class,
                        e -> assertThat(e.reason()).isEqualTo(AssignmentInvalidException.Reason.UNKNOWN_ROLE));
        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, foreign, BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UNIT, ENV)))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("principal not found"));
        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UNIT, ENV))).isInstanceOf(ResourceNotFoundException.class);

        assertThat(assignments.writes).isEmpty();
    }

    @Test
    void aDeactivatedPrincipalIsTheNamedConflictAndNothingIsBound() {
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));
        assignments.addRole(BuiltInRoles.VIEWER, "Viewer");
        UUID gone = principals.add(ORG, UserView.Status.DEACTIVATED);

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, gone, BuiltInRoles.VIEWER,
                ScopePath.environment(ORG, UNIT, ENV)))
                .isInstanceOfSatisfying(AssignmentConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(AssignmentConflictException.Reason.PRINCIPAL_DEACTIVATED));

        assertThat(assignments.writes).isEmpty();
    }

    @Test
    void aGrantBindsInOneTransactionAfterTheAuditContextAndTheRowLockAndAReplayIsReportedAsOne() {
        directory.put(env(), ScopePath.environment(ORG, UNIT, ENV));
        assignments.addRole(BuiltInRoles.OPERATOR, "Operator");
        UUID principal = principals.addActive(ORG);
        ScopePath scope = ScopePath.environment(ORG, UNIT, ENV);

        AssignmentAdministration.Granted first = service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, scope);
        AssignmentAdministration.Granted second = service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, scope);

        assertThat(first.created()).isTrue();
        assertThat(first.assignment()).isEqualTo(new AssignmentView(minted, principal, BuiltInRoles.OPERATOR, scope));
        assertThat(second.created()).as("an identical grant is a replay: the same assignment").isFalse();
        assertThat(second.assignment()).isEqualTo(first.assignment());
        assertThat(calls).containsSubsequence(
                "authorize role.assign ENVIRONMENT",
                "begin",
                "audit " + AuditOperations.ROLE_BINDING_GRANTED + "@203.0.113.50/" + ACTOR.id(),
                "lock " + principal,
                "bind " + principal + " " + BuiltInRoles.OPERATOR,
                "commit");
        assertThat(assignments.writes).containsExactly("bind");
    }

    // ---------------------------------------------------------------- grant: a grantor holds what it grants (W3D-4)

    @Test
    void aGrantorLackingAVerbOfTheRoleAtTheGrantedScopeIsRefusedAndNothingIsWritten() {
        ScopePath env = ScopePath.environment(ORG, UNIT, ENV);
        directory.put(env(), env);
        assignments.roles.add(new RoleView(BuiltInRoles.OPERATOR, "Operator",
                List.of("config.apply", "solution.view")));
        UUID principal = principals.addActive(ORG);
        actorGrants.add(new AuthorizationGrant(ScopePath.organization(ORG),
                Set.of(Permission.ROLE_ASSIGN, Permission.SOLUTION_VIEW)));

        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, env))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("not permitted: config.apply"));

        assertThat(assignments.writes).isEmpty();
        assertThat(calls).as("refused before the transaction: no audit context, no lock, no bind")
                .containsExactly("authorize role.assign ENVIRONMENT", "findRole " + BuiltInRoles.OPERATOR);
    }

    @Test
    void aGrantorHoldingEveryVerbOfTheRoleAtOrAboveTheGrantedScopeMayGrantItAndOneHoldingThemOnlyBelowMayNot() {
        ScopePath unit = ScopePath.subOrganization(ORG, UNIT);
        directory.put(ResourceRef.of(ResourceKind.SUB_ORGANIZATION, UNIT, new com.solace.sm.cp.api.authz.TenantId(ORG)),
                unit);
        assignments.roles.add(new RoleView(BuiltInRoles.OPERATOR, "Operator",
                List.of("config.apply", "solution.view")));
        UUID principal = principals.addActive(ORG);
        Set<Permission> verbs = Set.of(Permission.CONFIG_APPLY, Permission.SOLUTION_VIEW);

        actorGrants.add(new AuthorizationGrant(ScopePath.organization(ORG), verbs));
        assertThat(service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, unit).created()).isTrue();

        actorGrants.clear();
        actorGrants.add(new AuthorizationGrant(ScopePath.environment(ORG, UNIT, ENV), verbs));
        assignments.addRole(BuiltInRoles.VIEWER, "Viewer");
        assertThat(service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.VIEWER, unit).created())
                .as("a role with no verbs is held by anyone").isTrue();
        assertThatThrownBy(() -> service.grant(ACTOR, ORIGIN, ORG, principal, BuiltInRoles.OPERATOR, unit))
                .as("held only at an environment below the unit: not at the unit")
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- find (address resolution for the gateway)

    @Test
    void findReadsTheBindingOfTheOrganizationAndChecksNoVerbAndWritesNothing() {
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UNIT, ENV));
        assignments.bindings.add(binding);

        assertThat(service.find(ORG, binding.assignmentId())).contains(binding);
        assertThat(service.find(ORG, UUID.randomUUID())).isEmpty();

        assertThat(verbs).as("an address resolution: no verb is evaluated").isEmpty();
        assertThat(assignments.writes).isEmpty();
        assertThat(calls).allSatisfy(call -> assertThat(call).startsWith("find "));
    }

    // ---------------------------------------------------------------- revoke

    @Test
    void aRevocationIsAuthorizedAtTheBindingsOwnScopeThenDeletesInOneAuditedTransaction() {
        UUID principal = UUID.randomUUID();
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), principal, BuiltInRoles.OPERATOR,
                ScopePath.environment(ORG, UNIT, ENV));
        assignments.bindings.add(binding);

        service.revoke(ACTOR, ORIGIN, ORG, binding.assignmentId());

        assertThat(calls).containsExactly(
                "find " + binding.assignmentId(),
                "authorize role.assign ENVIRONMENT",
                "begin",
                "audit " + AuditOperations.ROLE_BINDING_REVOKED + "@203.0.113.50/" + ACTOR.id(),
                "unbind " + binding.assignmentId(),
                "commit");
        assertThat(required.get(0).id()).isEqualTo(ENV);
        assertThat(assignments.bindings).isEmpty();
    }

    @Test
    void anInstallScopeBindingIsAuthorizedAtTheInstallScopeThroughItsRoleDefinition() {
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.INSTALL);
        assignments.bindings.add(binding);

        service.revoke(ACTOR, ORIGIN, ORG, binding.assignmentId());

        assertThat(required).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(ResourceKind.ROLE_DEFINITION);
            assertThat(r.id()).isEqualTo(BuiltInRoles.OPERATOR);
        });
    }

    @Test
    void anAdministratorBindingIsNeverRevokedWhoeverAsksAndOnlyAfterAuthorization() {
        AssignmentView admin = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), BuiltInRoles.ADMINISTRATOR,
                ScopePath.organization(ORG));
        assignments.bindings.add(admin);

        assertThatThrownBy(() -> service.revoke(ACTOR, ORIGIN, ORG, admin.assignmentId()))
                .isInstanceOfSatisfying(AssignmentConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(AssignmentConflictException.Reason.ADMINISTRATOR_BINDING));

        assertThat(assignments.bindings).containsExactly(admin);
        assertThat(assignments.writes).isEmpty();
        assertThat(calls).containsExactly("find " + admin.assignmentId(), "authorize role.assign ORGANIZATION");

        refuse = new AccessDeniedException(Permission.ROLE_ASSIGN);
        assertThatThrownBy(() -> service.revoke(ACTOR, ORIGIN, ORG, admin.assignmentId()))
                .as("an unauthorized caller is refused as such, and learns nothing of the Administrator rule")
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aBindingThatIsNotThereIsAbsentWithoutAnAuthorizationAndOneThatVanishesBeforeTheDeleteIsAbsentToo() {
        assertThatThrownBy(() -> service.revoke(ACTOR, ORIGIN, ORG, UUID.randomUUID()))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).as("W3D-5: names the assignment, not a role definition")
                                .isEqualTo("role assignment not found"));
        assertThat(calls).hasSize(1).first().asString().startsWith("find ");

        calls.clear();
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), BuiltInRoles.VIEWER,
                ScopePath.organization(ORG));
        assignments.bindings.add(binding);
        assignments.unbindReportsNothingDeleted = true;
        assertThatThrownBy(() -> service.revoke(ACTOR, ORIGIN, ORG, binding.assignmentId()))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("role assignment not found"));
    }

    @Test
    void aRefusedRevocationAuthorizationDeletesNothing() {
        AssignmentView binding = new AssignmentView(UUID.randomUUID(), UUID.randomUUID(), BuiltInRoles.OPERATOR,
                ScopePath.subOrganization(ORG, UNIT));
        assignments.bindings.add(binding);
        refuse = new AccessDeniedException(Permission.ROLE_ASSIGN);

        assertThatThrownBy(() -> service.revoke(ACTOR, ORIGIN, ORG, binding.assignmentId()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(assignments.bindings).containsExactly(binding);
        assertThat(assignments.writes).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static ResourceRef env() {
        return ResourceRef.of(ResourceKind.ENVIRONMENT, ENV, new com.solace.sm.cp.api.authz.TenantId(ORG));
    }

    private static AssignmentView bound(UUID principal, ScopePath scope) {
        return new AssignmentView(UUID.randomUUID(), principal, BuiltInRoles.VIEWER, scope);
    }

    // ================================================================ fakes

    private static final class FakeAssignments implements AssignmentRepository {

        private final List<String> calls;
        final List<RoleView> roles = new ArrayList<>();
        final List<AssignmentView> bindings = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        boolean unbindReportsNothingDeleted;

        FakeAssignments(List<String> calls) {
            this.calls = calls;
        }

        void addRole(UUID id, String name) {
            roles.add(new RoleView(id, name, List.of()));
        }

        @Override
        public List<RoleView> roles() {
            calls.add("roles");
            return List.copyOf(roles);
        }

        @Override
        public Optional<RoleView> findRole(UUID roleId) {
            calls.add("findRole " + roleId);
            return roles.stream().filter(r -> r.id().equals(roleId)).findFirst();
        }

        @Override
        public List<AssignmentView> list(UUID organizationId, Optional<UUID> principalId) {
            calls.add("list " + organizationId + principalId.map(p -> " " + p).orElse(""));
            return bindings.stream().filter(b -> principalId.map(b.principalId()::equals).orElse(true)).toList();
        }

        @Override
        public Optional<AssignmentView> find(UUID organizationId, UUID assignmentId) {
            calls.add("find " + assignmentId);
            return bindings.stream().filter(b -> b.assignmentId().equals(assignmentId)).findFirst();
        }

        @Override
        public Bound bind(UUID assignmentId, UUID principalId, UUID roleId, ScopePath scope) {
            calls.add("bind " + principalId + " " + roleId);
            Optional<AssignmentView> existing = bindings.stream().filter(b -> b.principalId().equals(principalId)
                    && b.roleId().equals(roleId) && b.scope().equals(scope)).findFirst();
            if (existing.isPresent()) {
                return new Bound(existing.get(), false);
            }
            writes.add("bind");
            AssignmentView created = new AssignmentView(assignmentId, principalId, roleId, scope);
            bindings.add(created);
            return new Bound(created, true);
        }

        @Override
        public boolean unbind(UUID organizationId, UUID assignmentId) {
            calls.add("unbind " + assignmentId);
            if (unbindReportsNothingDeleted) {
                return false;
            }
            writes.add("unbind");
            return bindings.removeIf(b -> b.assignmentId().equals(assignmentId));
        }
    }

    /** Only {@code lock} is part of this surface. */
    private static final class FakePrincipals implements PrincipalAdministrationRepository {

        private final List<String> calls;
        private final Map<UUID, UserView> byId = new HashMap<>();
        private final Map<UUID, UUID> organizationOf = new HashMap<>();

        FakePrincipals(List<String> calls) {
            this.calls = calls;
        }

        UUID addActive(UUID organization) {
            return add(organization, UserView.Status.ACTIVE);
        }

        UUID add(UUID organization, UserView.Status status) {
            UUID id = UUID.randomUUID();
            byId.put(id, new UserView(id, "u-" + id, PrincipalSource.LOCAL, status, false, Instant.EPOCH));
            organizationOf.put(id, organization);
            return id;
        }

        @Override
        public Optional<UserView> lock(UUID organizationId, UUID id) {
            calls.add("lock " + id);
            return Optional.ofNullable(byId.get(id)).filter(v -> organizationId.equals(organizationOf.get(id)));
        }

        @Override
        public UserView insertLocal(UUID id, UUID organizationId, String username, String credentialHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UserView> list(UUID organizationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserView> find(UUID organizationId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean holdsRole(UUID principalId, UUID roleId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean deactivate(UUID organizationId, UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean resetCredential(UUID organizationId, UUID id, String credentialHash) {
            throw new UnsupportedOperationException();
        }
    }
}
