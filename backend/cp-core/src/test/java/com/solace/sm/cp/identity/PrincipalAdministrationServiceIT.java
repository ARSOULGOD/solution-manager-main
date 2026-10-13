package com.solace.sm.cp.identity;

import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.audit;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.count;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.principalWith;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.ref;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.session;
import static com.solace.sm.cp.identity.IdentityAdministrationFixtures.sessionRevoked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.identity.PrincipalConflictException;
import com.solace.sm.cp.api.identity.PrincipalConflictException.Reason;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcAuthorizationGrantRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalAdministrationRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import com.solace.sm.cp.target.TargetFixtures;
import com.solace.sm.cp.target.TargetFixtures.Tree;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 013 T3.1: {@link PrincipalAdministrationService} against a real {@code sm_control} schema at the head (V018),
 * running <b>as {@code sm_cp_runtime}</b> (the OR-25 audit rule only fires for that role), with the real
 * {@code ScopeEvaluatingAuthorizationService} over real grants: every principal is bound by a built-in role at a scope,
 * so the tests prove the verb and the scope, not a fake.
 */
class PrincipalAdministrationServiceIT {

    private static final String CREDENTIAL = "an-initial-credential";
    private static final String NEW_CREDENTIAL = "a-replacement-credential";

    private final NamedParameterJdbcTemplate runtime = TargetFixtures.runtime();
    private final NamedParameterJdbcTemplate migrator = TargetFixtures.migrator();
    private final CredentialHasher hasher = new CredentialHasher();

    private PrincipalAdministrationService service;
    private Tree tree;

    @org.junit.jupiter.api.AfterEach
    void dropCustomRoles() {
        IdentityAdministrationFixtures.dropCustomRoles();
    }

    @BeforeEach
    void setUp() {
        service = new PrincipalAdministrationService(new JdbcPrincipalAdministrationRepository(runtime),
                new JdbcSessionRepository(runtime), hasher, IdentityAdministrationFixtures.authorization(runtime),
                new JdbcAuthorizationGrantRepository(runtime), new JdbcAuditContext(runtime),
                new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource())),
                Clock.systemUTC(), UUID::randomUUID);
        tree = TargetFixtures.seedTree();
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.60", UUID.randomUUID());
    }

    private PrincipalRef orgAdmin() {
        return ref(principalWith(tree, "Administrator", tree.organizationScope()));
    }

    private UUID plainUser() {
        return TargetFixtures.localPrincipal(tree.organization(), "plain-" + UUID.randomUUID(), CREDENTIAL, false);
    }

    private static String username() {
        return "user-" + UUID.randomUUID();
    }

    private Map<String, Object> row(UUID id) {
        return migrator.queryForMap("SELECT * FROM sm_control.principal WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- create

    @Test
    void aCreatedUserIsLocalMustRotateInTheCallersOrganizationArgon2idHashedAndAuditedWithoutTheHash() {
        PrincipalRef admin = orgAdmin();
        RequestOrigin origin = origin();
        String username = username();

        UserView created = service.create(admin, origin, tree.organization(), username, CREDENTIAL.toCharArray());

        assertThat(created.username()).isEqualTo(username);
        assertThat(created.source()).isEqualTo(PrincipalSource.LOCAL);
        assertThat(created.status()).isEqualTo(UserView.Status.ACTIVE);
        assertThat(created.mustRotate()).isTrue();
        Map<String, Object> row = row(created.id());
        assertThat(row.get("organization_id")).isEqualTo(tree.organization());
        assertThat(row.get("must_rotate")).isEqualTo(true);
        String hash = (String) row.get("credential_hash");
        assertThat(hash).startsWith("{argon2id}").doesNotContain(CREDENTIAL);
        assertThat(hasher.verify(CREDENTIAL.toCharArray(), hash)).isTrue();

        List<Map<String, Object>> audits = audit(origin.correlationId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).containsEntry("table_name", "principal")
                .containsEntry("audit_operation", "PRINCIPAL_CREATED")
                .containsEntry("actor_principal_id", admin.id()).containsEntry("client_address", "203.0.113.60");
        assertThat(audits.get(0).get("new_values").toString())
                .as("the audit row copies the row but never the hash (OR-19's secret-column rule)")
                .doesNotContain(hash).doesNotContain("credential_hash").doesNotContain(CREDENTIAL);
        assertThat(audits.get(0).get("row_key").toString()).contains(created.id().toString());
    }

    /**
     * OR-125 (4), closing W3D-1: a username is unique per organization and compared without regard to case. A repeat inside
     * the organization (even in another case) is a 409; the same name in ANOTHER organization is simply created, so a 409
     * never reveals that another organization has the name.
     */
    @Test
    void aUsernameIsUniquePerOrganizationWithoutRegardToCaseAndAnotherOrganizationsNameNeverConflicts() {
        PrincipalRef admin = orgAdmin();
        String username = username();
        service.create(admin, origin(), tree.organization(), username, CREDENTIAL.toCharArray());
        long before = count("SELECT count(*) FROM sm_control.principal", Map.of());

        assertThatThrownBy(() -> service.create(admin, origin(), tree.organization(), username,
                CREDENTIAL.toCharArray()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.USERNAME_TAKEN));
        assertThatThrownBy(() -> service.create(admin, origin(), tree.organization(), username.toUpperCase(),
                CREDENTIAL.toCharArray()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.USERNAME_TAKEN));
        assertThat(count("SELECT count(*) FROM sm_control.principal", Map.of())).as("the refusals wrote nothing")
                .isEqualTo(before);

        Tree other = TargetFixtures.seedTree();
        PrincipalRef otherAdmin = ref(principalWith(other, "Administrator", other.organizationScope()));
        long beforeTheirs = count("SELECT count(*) FROM sm_control.principal", Map.of());
        UserView theirs = service.create(otherAdmin, origin(), other.organization(), username,
                CREDENTIAL.toCharArray());

        assertThat(theirs.username()).isEqualTo(username);
        assertThat(row(theirs.id()).get("organization_id")).isEqualTo(other.organization());
        assertThat(count("SELECT count(*) FROM sm_control.principal", Map.of())).isEqualTo(beforeTheirs + 1);
    }

    @Test
    void onlyAPrincipalHoldingUserCreateAtTheOrganizationCreatesAndTheRefusalWritesNothing() {
        long before = count("SELECT count(*) FROM sm_control.principal WHERE organization_id = :o",
                Map.of("o", tree.organization()));
        PrincipalRef viewer = ref(principalWith(tree, "Viewer", tree.organizationScope()));
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        PrincipalRef envAdmin = ref(principalWith(tree, "Administrator", tree.environmentScope()));
        long afterFixtures = before + 4;

        for (PrincipalRef refused : List.of(viewer, operator, unitAdmin, envAdmin)) {
            assertThatThrownBy(() -> service.create(refused, origin(), tree.organization(), username(),
                    CREDENTIAL.toCharArray())).isInstanceOf(AccessDeniedException.class);
        }

        assertThat(count("SELECT count(*) FROM sm_control.principal WHERE organization_id = :o",
                Map.of("o", tree.organization()))).isEqualTo(afterFixtures);
    }

    @Test
    void anAdministratorOfAnotherOrganizationCannotCreateInOursNorListOurs() {
        Tree other = TargetFixtures.seedTree();
        PrincipalRef otherAdmin = ref(principalWith(other, "Administrator", other.organizationScope()));

        assertThatThrownBy(() -> service.create(otherAdmin, origin(), tree.organization(), username(),
                CREDENTIAL.toCharArray())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.list(otherAdmin, tree.organization()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------------------------------------------------------- list

    @Test
    void aListIsTheOrganizationsOwnPrincipalsDeactivatedOnesIncludedAndNeverACredential() {
        PrincipalRef admin = orgAdmin();
        UUID alice = plainUser();
        UUID bob = plainUser();
        service.deactivate(admin, origin(), tree.organization(), bob);
        Tree other = TargetFixtures.seedTree();
        UUID stranger = TargetFixtures.localPrincipal(other.organization(), username(), CREDENTIAL, false);

        List<UserView> users = service.list(admin, tree.organization());

        assertThat(users).extracting(UserView::id).contains(admin.id(), alice, bob).doesNotContain(stranger);
        assertThat(users).filteredOn(u -> u.id().equals(bob)).singleElement()
                .satisfies(u -> assertThat(u.status()).isEqualTo(UserView.Status.DEACTIVATED));
        assertThat(users).extracting(UserView::username).as("C collation: locale-independent").isSorted();
        assertThat(users.toString()).doesNotContain("argon2id");
    }

    @Test
    void aListNeedsUserViewAtTheOrganization() {
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));

        assertThatThrownBy(() -> service.list(unitAdmin, tree.organization())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.list(operator, tree.organization())).isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- deactivate

    @Test
    void aDeactivationEndsTheUsersSessionsInTheSameTransactionAndTheNextRequestIsRefused() {
        PrincipalRef admin = orgAdmin();
        UUID target = plainUser();
        UUID bystander = plainUser();
        String targetSession = session(target);
        String targetSecondSession = session(target);
        String bystanderSession = session(bystander);
        JdbcSessionRepository sessions = new JdbcSessionRepository(runtime);
        assertThat(sessions.resolveAndTouch(targetSession, Instant.now(), Duration.ofMinutes(30))).isPresent();
        RequestOrigin origin = origin();

        UserView view = service.deactivate(admin, origin, tree.organization(), target);

        assertThat(view.status()).isEqualTo(UserView.Status.DEACTIVATED);
        Map<String, Object> row = row(target);
        assertThat(row.get("status")).isEqualTo("deactivated");
        assertThat(row.get("deactivated_at")).isNotNull();
        assertThat(sessionRevoked(targetSession)).isTrue();
        assertThat(sessionRevoked(targetSecondSession)).isTrue();
        assertThat(sessionRevoked(bystanderSession)).as("another principal's session is untouched").isFalse();
        assertThat(sessions.resolveAndTouch(targetSession, Instant.now(), Duration.ofMinutes(30)))
                .as("a deactivated user's existing session is refused on the next request").isEmpty();
        assertThat(sessions.resolveAndTouch(bystanderSession, Instant.now(), Duration.ofMinutes(30))).isPresent();
        List<Map<String, Object>> audits = audit(origin.correlationId());
        assertThat(audits).extracting(a -> a.get("table_name") + ":" + a.get("audit_operation"))
                .contains("principal:PRINCIPAL_DEACTIVATED", "session:PRINCIPAL_DEACTIVATED");
        assertThat(audits).allSatisfy(a -> assertThat(a).containsEntry("actor_principal_id", admin.id())
                .containsEntry("client_address", "203.0.113.60"));
        assertThat(audits.stream().filter(a -> a.get("table_name").equals("session"))).hasSize(2);
    }

    @Test
    void aSecondDeactivationIsIdempotentAndWritesNothingAndNoAuditRow() {
        PrincipalRef admin = orgAdmin();
        UUID target = plainUser();
        service.deactivate(admin, origin(), tree.organization(), target);
        Object firstAt = row(target).get("deactivated_at");
        RequestOrigin second = origin();

        UserView again = service.deactivate(admin, second, tree.organization(), target);

        assertThat(again.status()).isEqualTo(UserView.Status.DEACTIVATED);
        assertThat(row(target).get("deactivated_at")).isEqualTo(firstAt);
        assertThat(audit(second.correlationId())).isEmpty();
    }

    @Test
    void theCallerItselfAndEveryHolderOfAnAdministratorBindingAtAnyScopeAreRefusedAndNothingChanges() {
        PrincipalRef admin = orgAdmin();
        UUID otherOrgAdmin = principalWith(tree, "Administrator", tree.organizationScope());
        UUID unitAdmin = principalWith(tree, "Administrator", tree.unitScope());
        UUID installAdmin = TargetFixtures.localPrincipal(tree.organization(), "install-" + UUID.randomUUID(),
                CREDENTIAL, false);
        IdentityAdministrationFixtures.bindAtInstall(installAdmin, "Administrator");
        String adminSession = session(admin.id());
        String otherSession = session(otherOrgAdmin);

        assertThatThrownBy(() -> service.deactivate(admin, origin(), tree.organization(), admin.id()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CALLER_ITSELF));
        for (UUID protectedPrincipal : List.of(otherOrgAdmin, unitAdmin, installAdmin)) {
            assertThatThrownBy(() -> service.deactivate(admin, origin(), tree.organization(), protectedPrincipal))
                    .isInstanceOfSatisfying(PrincipalConflictException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.ADMINISTRATOR_PROTECTED));
            assertThat(row(protectedPrincipal).get("status")).isEqualTo("active");
        }
        assertThat(sessionRevoked(adminSession)).isFalse();
        assertThat(sessionRevoked(otherSession)).isFalse();
    }

    @Test
    void theInstallAdministratorLikeTheBootstrapAdminCannotDeactivateItself() {
        UUID bootstrapLike = TargetFixtures.localPrincipal(tree.organization(), "boot-" + UUID.randomUUID(),
                CREDENTIAL, true);
        IdentityAdministrationFixtures.bindAtInstall(bootstrapLike, "Administrator");

        assertThatThrownBy(() -> service.deactivate(ref(bootstrapLike), origin(), tree.organization(), bootstrapLike))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CALLER_ITSELF));
        assertThat(row(bootstrapLike).get("status")).isEqualTo("active");
    }

    @Test
    void theSeededBootstrapAdminItselfCannotDeactivateNorResetItself() {
        // W3D-6: V007's own row (id ...0012 in organization ...0011), not a principal shaped like it.
        UUID bootstrap = UUID.fromString("00000000-0000-4000-8000-000000000012");
        UUID bootstrapOrganization = UUID.fromString("00000000-0000-4000-8000-000000000011");
        assertThat(row(bootstrap).get("username")).isEqualTo("bootstrap-admin");

        assertThatThrownBy(() -> service.deactivate(ref(bootstrap), origin(), bootstrapOrganization, bootstrap))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CALLER_ITSELF));
        assertThatThrownBy(() -> service.resetCredential(ref(bootstrap), origin(), bootstrapOrganization, bootstrap,
                NEW_CREDENTIAL.toCharArray()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CALLER_ITSELF));
        assertThat(row(bootstrap).get("status")).isEqualTo("active");
    }

    @Test
    void aCallerWithUserEditButNotTheTargetsVerbsCannotResetItsCredentialAndACoveringCallerCan() {
        // W3D-4, the stand-in for D-REG OQ-7 (c): a role with user.* but none of the Operator's verbs.
        String infra = IdentityAdministrationFixtures.customRole(com.solace.sm.cp.api.authz.Permission.USER_EDIT,
                com.solace.sm.cp.api.authz.Permission.USER_VIEW);
        PrincipalRef infraAdmin = ref(principalWith(tree, infra, tree.organizationScope()));
        UUID operator = TargetFixtures.localPrincipal(tree.organization(), username(), CREDENTIAL, false);
        TargetFixtures.bind(operator, "Operator", tree.organizationScope());
        String operatorSession = session(operator);
        String hashBefore = (String) row(operator).get("credential_hash");

        assertThatThrownBy(() -> service.resetCredential(infraAdmin, origin(), tree.organization(), operator,
                NEW_CREDENTIAL.toCharArray())).isInstanceOf(AccessDeniedException.class);

        assertThat(row(operator).get("credential_hash")).isEqualTo(hashBefore);
        assertThat(sessionRevoked(operatorSession)).isFalse();

        UUID unbound = plainUser();
        service.resetCredential(infraAdmin, origin(), tree.organization(), unbound, NEW_CREDENTIAL.toCharArray());
        service.resetCredential(orgAdmin(), origin(), tree.organization(), operator, NEW_CREDENTIAL.toCharArray());
        assertThat(row(unbound).get("must_rotate")).isEqualTo(true);
        assertThat(row(operator).get("credential_hash")).isNotEqualTo(hashBefore);
    }

    @Test
    void aPrincipalOfAnotherOrganizationOrNoneAtAllIsAbsentAndAnUnauthorizedCallerIsRefusedFirst() {
        PrincipalRef admin = orgAdmin();
        Tree other = TargetFixtures.seedTree();
        UUID foreign = TargetFixtures.localPrincipal(other.organization(), username(), CREDENTIAL, false);
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));
        UUID target = plainUser();

        assertThatThrownBy(() -> service.deactivate(admin, origin(), tree.organization(), foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(admin, origin(), tree.organization(), UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.deactivate(operator, origin(), tree.organization(), target))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(row(foreign).get("status")).isEqualTo("active");
        assertThat(row(target).get("status")).isEqualTo("active");
    }

    @Test
    void aUnitBoundAdministratorCannotDeactivateAUserBecauseTheActIsAnOrganizationOne() {
        PrincipalRef unitAdmin = ref(principalWith(tree, "Administrator", tree.unitScope()));
        UUID target = plainUser();

        assertThatThrownBy(() -> service.deactivate(unitAdmin, origin(), tree.organization(), target))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- reset

    @Test
    void aResetReplacesTheCredentialSetsMustRotateZeroesFailuresEndsSessionsAndAuditsWithoutAnyHash() {
        PrincipalRef admin = orgAdmin();
        UUID target = TargetFixtures.localPrincipal(tree.organization(), "reset-" + UUID.randomUUID(), CREDENTIAL, false);
        migrator.update("UPDATE sm_control.principal SET credential_failure_count = 3 WHERE id = :id",
                Map.of("id", target));
        String oldHash = (String) row(target).get("credential_hash");
        String targetSession = session(target);
        RequestOrigin origin = origin();

        UserView view = service.resetCredential(admin, origin, tree.organization(), target,
                NEW_CREDENTIAL.toCharArray());

        assertThat(view.mustRotate()).isTrue();
        assertThat(view.status()).isEqualTo(UserView.Status.ACTIVE);
        Map<String, Object> row = row(target);
        String newHash = (String) row.get("credential_hash");
        assertThat(newHash).isNotEqualTo(oldHash).startsWith("{argon2id}");
        assertThat(hasher.verify(NEW_CREDENTIAL.toCharArray(), newHash)).isTrue();
        assertThat(hasher.verify(CREDENTIAL.toCharArray(), newHash)).as("the old credential no longer works").isFalse();
        assertThat(row.get("must_rotate")).isEqualTo(true);
        assertThat(row.get("credential_failure_count")).isEqualTo(0);
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(sessionRevoked(targetSession)).isTrue();

        List<Map<String, Object>> audits = audit(origin.correlationId());
        assertThat(audits).extracting(a -> a.get("table_name") + ":" + a.get("audit_operation"))
                .contains("principal:PRINCIPAL_CREDENTIAL_RESET", "session:PRINCIPAL_CREDENTIAL_RESET");
        Map<String, Object> principalAudit = audits.stream().filter(a -> a.get("table_name").equals("principal"))
                .findFirst().orElseThrow();
        assertThat(principalAudit).containsEntry("actor_principal_id", admin.id());
        assertThat(principalAudit.get("old_values").toString() + principalAudit.get("new_values"))
                .doesNotContain(oldHash).doesNotContain(newHash).doesNotContain(CREDENTIAL)
                .doesNotContain(NEW_CREDENTIAL);
        assertThat(principalAudit.get("secret_columns_changed").toString()).contains("credential_hash");
    }

    @Test
    void aResetRefusedForTheCallerAnAdministratorAnIdentityProviderUserOrADeactivatedOneChangesNothing() {
        PrincipalRef admin = orgAdmin();
        UUID otherAdmin = principalWith(tree, "Administrator", tree.environmentScope());
        UUID idp = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (:id, :org, 'idp', :u)", Map.of("id", idp, "org", tree.organization(), "u", username()));
        UUID gone = plainUser();
        service.deactivate(admin, origin(), tree.organization(), gone);
        String otherAdminHash = (String) row(otherAdmin).get("credential_hash");
        String goneHash = (String) row(gone).get("credential_hash");

        assertReason(() -> service.resetCredential(admin, origin(), tree.organization(), admin.id(),
                NEW_CREDENTIAL.toCharArray()), Reason.CALLER_ITSELF);
        assertReason(() -> service.resetCredential(admin, origin(), tree.organization(), otherAdmin,
                NEW_CREDENTIAL.toCharArray()), Reason.ADMINISTRATOR_PROTECTED);
        assertReason(() -> service.resetCredential(admin, origin(), tree.organization(), idp,
                NEW_CREDENTIAL.toCharArray()), Reason.NOT_LOCAL);
        assertReason(() -> service.resetCredential(admin, origin(), tree.organization(), gone,
                NEW_CREDENTIAL.toCharArray()), Reason.DEACTIVATED);

        assertThat(row(otherAdmin).get("credential_hash")).isEqualTo(otherAdminHash);
        assertThat(row(gone).get("credential_hash")).isEqualTo(goneHash);
        assertThat(row(idp).get("credential_hash")).isNull();
    }

    @Test
    void aResetNeedsUserEditAndAnOperatorHasNeitherItNorUserDeactivate() {
        PrincipalRef operator = ref(principalWith(tree, "Operator", tree.organizationScope()));
        UUID target = plainUser();
        String hash = (String) row(target).get("credential_hash");

        assertThatThrownBy(() -> service.resetCredential(operator, origin(), tree.organization(), target,
                NEW_CREDENTIAL.toCharArray())).isInstanceOf(AccessDeniedException.class);

        assertThat(row(target).get("credential_hash")).isEqualTo(hash);
    }

    private static void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Reason reason) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PrincipalConflictException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    // ---------------------------------------------------------------- the schema's own limits stay

    @Test
    void thePrincipalStillCannotBeDeletedByTheRuntimeRoleSoDeactivationIsTheOnlyRemoval() {
        UUID target = plainUser();

        assertThatThrownBy(() -> runtime.update("DELETE FROM sm_control.principal WHERE id = :id", Map.of("id", target)))
                .hasStackTraceContaining("permission denied");
    }
}
