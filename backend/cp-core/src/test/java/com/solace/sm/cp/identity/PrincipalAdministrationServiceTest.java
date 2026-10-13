package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.PrincipalAdministration;
import com.solace.sm.cp.api.identity.PrincipalConflictException;
import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.authz.AuthorizationGrant;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * {@link PrincipalAdministrationService} with every collaborator a fake (plan 013 T3.1): the order of validate,
 * authorize, hash, write; the protection of the caller and of every Administrator; that sessions end in the same
 * transaction as the change; and that no credential, hash or username is placed in an exception. The database half is
 * {@code PrincipalAdministrationServiceIT}.
 */
class PrincipalAdministrationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final PrincipalRef ACTOR = new PrincipalRef(UUID.randomUUID());
    private static final RequestOrigin ORIGIN = new RequestOrigin("203.0.113.40", UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final char[] GOOD = "a-good-credential".toCharArray();

    /** One ordered log shared by every fake, so a test can assert the order of effects across collaborators. */
    private final List<String> calls = new ArrayList<>();
    private final List<ResourceRef> required = new ArrayList<>();
    private final List<Permission> verbs = new ArrayList<>();
    private RuntimeException refuse;
    private final FakePrincipals principals = new FakePrincipals(calls);
    private final FakeSessions sessions = new FakeSessions(calls);
    private final FakeHasher hasher = new FakeHasher(calls);
    private final UUID minted = UUID.randomUUID();

    private final AuthorizationService authorization = (principal, permission, resource) -> {
        calls.add("authorize " + permission.id() + " " + resource.kind());
        verbs.add(permission);
        required.add(resource);
        if (refuse != null) {
            throw refuse;
        }
    };
    /** What each principal's role bindings give, by principal id (the caller's and the target's). */
    private final Map<UUID, List<AuthorizationGrant>> grantsOf = new HashMap<>();
    private final AuthorizationGrantRepository grants = principal -> {
        calls.add("grantsFor " + (principal.id().equals(ACTOR.id()) ? "actor" : principal.id()));
        return grantsOf.getOrDefault(principal.id(), List.of());
    };
    private final AuditContext auditContext = (actor, operation, correlation, client) ->
            calls.add("audit " + operation + "@" + client + "/" + actor);
    private final RecordingTransactions transactions = new RecordingTransactions(calls);
    private final PrincipalAdministration service = new PrincipalAdministrationService(principals, sessions, hasher,
            authorization, grants, auditContext, transactions, Clock.fixed(NOW, ZoneOffset.UTC), () -> minted);

    // ---------------------------------------------------------------- authorization: the verb, the node, the order

    @Test
    void eachOperationIsAuthorizedWithItsOwnVerbBeforeAnyReadWriteOrHash() {
        UUID target = principals.addActiveLocal(ORG, "alice");

        service.list(ACTOR, ORG);
        service.create(ACTOR, ORIGIN, ORG, "bob", GOOD.clone());
        service.deactivate(ACTOR, ORIGIN, ORG, target);
        UUID other = principals.addActiveLocal(ORG, "carol");
        service.resetCredential(ACTOR, ORIGIN, ORG, other, GOOD.clone());

        assertThat(verbs).containsExactly(Permission.USER_VIEW, Permission.USER_CREATE, Permission.USER_DEACTIVATE,
                Permission.USER_EDIT);
        assertThat(required).extracting(ResourceRef::kind).containsExactly(ResourceKind.ORGANIZATION,
                ResourceKind.ORGANIZATION, ResourceKind.PRINCIPAL, ResourceKind.PRINCIPAL);
        assertThat(required.get(0).id()).isEqualTo(ORG);
        assertThat(required.get(2).id()).isEqualTo(target);
        assertThat(required).allSatisfy(resource -> assertThat(resource.tenant().value()).isEqualTo(ORG));
    }

    @Test
    void aRefusedAuthorizationHashesReadsAndWritesNothingAndSetsNoAuditContext() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        calls.clear();
        refuse = new AccessDeniedException(Permission.USER_CREATE);

        assertThatThrownBy(() -> service.list(ACTOR, ORG)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, "bob", GOOD.clone()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.deactivate(ACTOR, ORIGIN, ORG, target))
                .isInstanceOf(AccessDeniedException.class);
        refuse = new ResourceNotFoundException(ResourceKind.PRINCIPAL);
        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(calls).as("only the four authorizations ran: no hash, no read, no write, no audit context")
                .allMatch(call -> call.startsWith("authorize "));
    }

    // ---------------------------------------------------------------- create

    @Test
    void aCreateHashesAfterAuthorizationWritesOnceUnderTheMintedIdAndAuditsPrincipalCreated() {
        UserView created = service.create(ACTOR, ORIGIN, ORG, "bob", GOOD.clone());

        assertThat(created.id()).isEqualTo(minted);
        assertThat(created.username()).isEqualTo("bob");
        assertThat(created.source()).isEqualTo(PrincipalSource.LOCAL);
        assertThat(created.status()).isEqualTo(UserView.Status.ACTIVE);
        assertThat(created.mustRotate()).as("the initial credential is provisional").isTrue();
        assertThat(calls).containsExactly(
                "authorize user.create ORGANIZATION",
                "hash",
                "begin",
                "audit " + AuditOperations.PRINCIPAL_CREATED + "@203.0.113.40/" + ACTOR.id(),
                "insertLocal " + ORG + " bob",
                "commit");
        assertThat(principals.storedHash(minted)).isEqualTo("hashed:a-good-credential");
    }

    @Test
    void aUsernameIsKeptExactlyAndIsNotStripped() {
        assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, " bob", GOOD.clone()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, "bob ", GOOD.clone()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aUsernameWithAnInvisibleOrLookAlikeMakingCodePointIsRefusedBeforeAuthorization() {
        // W3D-3: each of these is one visible name next to another, or a name that hides part of itself.
        List<String> bad = List.of(
                "bootstrap-admin\u200b",   // zero-width space (FORMAT)
                "bootstrap\u202eadmin",    // right-to-left override (FORMAT)
                "boot\u2060strap",         // word joiner (FORMAT)
                "\ufeffbootstrap",         // byte-order mark (FORMAT)
                "boot\u00adstrap",         // soft hyphen (FORMAT)
                "bo\ud800ot",              // unpaired high surrogate
                "bo\udc00ot",              // unpaired low surrogate
                "boot\ue000",              // private use
                "boot\u0378",              // unassigned
                "caf\u0065\u0301",        // 'e' + combining acute: not NFC, looks like the precomposed one
                "\u2028boot");             // line separator
        for (String name : bad) {
            assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, name, GOOD.clone()))
                    .as("username with code points %s", name.codePoints().boxed().map(Integer::toHexString).toList())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).as("refused on shape: no authorization, hash or write").isEmpty();
    }

    @Test
    void anOrdinaryUsernameInAnyScriptInNormalizationFormCIsAccepted() {
        for (String name : List.of("alice.ops", "o'brien", "caf\u00e9", "\u7528\u6237", "\u0430lice", "x@example.com",
                "emoji\ud83d\ude00")) {
            service.create(ACTOR, ORIGIN, ORG, name, GOOD.clone());
        }
        assertThat(principals.writes).hasSize(7);
    }

    @Test
    void aUsernameOrCredentialOfTheWrongShapeIsRefusedBeforeAuthorizationAndNeverEchoed() {
        List<String> badUsernames = List.of("", "x".repeat(65), "has space", "tab\there", "nul\u0000x", "bell\u0007",
                "c1\u0085x", "line\nbreak");
        for (String bad : badUsernames) {
            assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, bad, GOOD.clone()))
                    .as("username %s", bad.length() > 10 ? "of length " + bad.length() : bad.replace("\n", "\\n"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bad.isEmpty() ? "\u0001" : bad));
        }
        for (char[] bad : List.of("short-11-chr".substring(0, 11).toCharArray(), new char[0],
                "x".repeat(1025).toCharArray())) {
            assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, "bob", bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(new String(bad).isEmpty() ? "\u0001"
                            : new String(bad)));
        }
        assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, "bob", null))
                .isInstanceOf(NullPointerException.class);

        assertThat(calls).as("no authorization, hash or write happened for any of them").isEmpty();
    }

    @Test
    void theFloorAndTheCeilingOfACredentialAreExactlyTwelveAndOneThousandAndTwentyFour() {
        service.create(ACTOR, ORIGIN, ORG, "twelve", "x".repeat(12).toCharArray());
        service.create(ACTOR, ORIGIN, ORG, "max", "x".repeat(1024).toCharArray());
        assertThat(principals.writes).containsExactly("insertLocal", "insertLocal");
    }

    @Test
    void aTakenUsernameIsTheNamedConflictWithNoCauseAndNoUsernameInTheMessage() {
        principals.takenUsernames.add("bob");

        assertThatThrownBy(() -> service.create(ACTOR, ORIGIN, ORG, "bob", GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class, e -> {
                    assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.USERNAME_TAKEN);
                    assertThat(e.getMessage()).doesNotContain("bob");
                    assertThat(e.getCause()).as("no driver text is chained: it carries the key").isNull();
                });
    }

    @Test
    void aCreateNeverLooksTheUsernameUpFirstSoTheDatabaseConstraintIsTheOnlyRule() {
        service.create(ACTOR, ORIGIN, ORG, "bob", GOOD.clone());
        assertThat(calls).noneMatch(call -> call.startsWith("find") || call.startsWith("lock"));
    }

    // ---------------------------------------------------------------- list

    @Test
    void aListIsTheRepositorysOrganizationList() {
        principals.addActiveLocal(ORG, "alice");
        principals.addActiveLocal(UUID.randomUUID(), "stranger");

        assertThat(service.list(ACTOR, ORG)).extracting(UserView::username).containsExactly("alice");
    }

    // ---------------------------------------------------------------- deactivate

    @Test
    void aDeactivationEndsEverySessionInTheSameTransactionAfterTheAuditContextAndTheWrite() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        calls.clear();

        UserView view = service.deactivate(ACTOR, ORIGIN, ORG, target);

        assertThat(view.status()).isEqualTo(UserView.Status.DEACTIVATED);
        assertThat(calls).containsExactly(
                "authorize user.deactivate PRINCIPAL",
                "begin",
                "audit " + AuditOperations.PRINCIPAL_DEACTIVATED + "@203.0.113.40/" + ACTOR.id(),
                "lock " + target,
                "holdsRole " + target,
                "deactivate " + target,
                "revokeAllFor " + target + " at " + NOW,
                "commit");
    }

    @Test
    void theCallerItselfIsRefusedAndNothingIsWritten() {
        UUID self = principals.addActiveLocal(ORG, "me");
        PrincipalRef me = new PrincipalRef(self);

        assertThatThrownBy(() -> service.deactivate(me, ORIGIN, ORG, self))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.CALLER_ITSELF));
        assertThatThrownBy(() -> service.resetCredential(me, ORIGIN, ORG, self, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.CALLER_ITSELF));

        assertThat(principals.writes).isEmpty();
        assertThat(sessions.revoked).isEmpty();
        assertThat(calls).doesNotContain("hash");
    }

    @Test
    void aPrincipalHoldingAnAdministratorBindingAtAnyScopeIsRefusedForBothOperations() {
        UUID admin = principals.addActiveLocal(ORG, "other-admin");
        principals.administrators.add(admin);

        assertThatThrownBy(() -> service.deactivate(ACTOR, ORIGIN, ORG, admin))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.ADMINISTRATOR_PROTECTED));
        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, admin, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.ADMINISTRATOR_PROTECTED));

        assertThat(principals.writes).isEmpty();
        assertThat(sessions.revoked).isEmpty();
        assertThat(calls).as("a refused reset pays no Argon2").doesNotContain("hash");
        assertThat(principals.administratorRoleAsked).as("the Administrator role, by its seeded id")
                .containsOnly(BuiltInRoles.ADMINISTRATOR);
    }

    @Test
    void aPrincipalOfAnotherOrganizationOrNoPrincipalAtAllIsNotFoundAfterAuthorization() {
        UUID foreign = principals.addActiveLocal(UUID.randomUUID(), "foreign");

        assertThatThrownBy(() -> service.deactivate(ACTOR, ORIGIN, ORG, foreign))
                .isInstanceOfSatisfying(ResourceNotFoundException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("principal not found"));
        assertThatThrownBy(() -> service.deactivate(ACTOR, ORIGIN, ORG, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, foreign, GOOD.clone()))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(principals.writes).isEmpty();
        assertThat(calls).doesNotContain("hash");
    }

    @Test
    void deactivatingAnAlreadyDeactivatedUserReturnsItUnchangedAndWritesNothing() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        service.deactivate(ACTOR, ORIGIN, ORG, target);
        principals.writes.clear();
        sessions.revoked.clear();

        UserView again = service.deactivate(ACTOR, ORIGIN, ORG, target);

        assertThat(again.status()).isEqualTo(UserView.Status.DEACTIVATED);
        assertThat(principals.writes).isEmpty();
        assertThat(sessions.revoked).isEmpty();
    }

    @Test
    void aDeactivationThatLostTheRaceWritesNothingAndRevokesNothing() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        principals.deactivateReportsNoChange = true;

        UserView view = service.deactivate(ACTOR, ORIGIN, ORG, target);

        assertThat(view).isNotNull();
        assertThat(sessions.revoked).isEmpty();
    }

    // ---------------------------------------------------------------- reset

    @Test
    void aResetHashesAfterTheRefusalsReplacesTheCredentialSetsMustRotateAndEndsSessionsInOneTransaction() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        calls.clear();

        UserView view = service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone());

        assertThat(view.mustRotate()).isTrue();
        assertThat(calls).containsExactly(
                "authorize user.edit PRINCIPAL",
                "find " + target,
                "holdsRole " + target,
                "grantsFor " + target,
                "hash",
                "begin",
                "audit " + AuditOperations.PRINCIPAL_CREDENTIAL_RESET + "@203.0.113.40/" + ACTOR.id(),
                "lock " + target,
                "holdsRole " + target,
                "grantsFor " + target,
                "resetCredential " + target,
                "revokeAllFor " + target + " at " + NOW,
                "commit");
        assertThat(principals.storedHash(target)).isEqualTo("hashed:a-good-credential");
    }

    @Test
    void aResetOfAPrincipalHoldingVerbsTheCallerLacksIsRefusedBeforeTheHashAndWritesNothing() {
        // W3D-4: resetting a credential takes the account over, so the caller must hold what the target holds.
        UUID target = principals.addActiveLocal(ORG, "alice");
        ScopePath org = ScopePath.organization(ORG);
        grantsOf.put(target, List.of(new AuthorizationGrant(org,
                Set.of(Permission.CONFIG_APPLY, Permission.SOLUTION_VIEW))));
        grantsOf.put(ACTOR.id(), List.of(new AuthorizationGrant(org,
                Set.of(Permission.USER_EDIT, Permission.SOLUTION_VIEW))));

        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone()))
                .isInstanceOfSatisfying(AccessDeniedException.class,
                        e -> assertThat(e.getMessage()).as("names nothing of the target's standing")
                                .isEqualTo("not permitted: user.edit"));

        assertThat(principals.writes).isEmpty();
        assertThat(sessions.revoked).isEmpty();
        assertThat(calls).doesNotContain("hash").doesNotContain("begin");
    }

    @Test
    void aResetIsAllowedWhenTheCallersGrantsCoverEveryVerbOfTheTargetAtTheTargetsScopeAndNotOtherwise() {
        UUID unit = UUID.randomUUID();
        UUID target = principals.addActiveLocal(ORG, "alice");
        UUID other = principals.addActiveLocal(ORG, "bob");
        Set<Permission> verbs = Set.of(Permission.CONFIG_APPLY, Permission.SOLUTION_VIEW);
        grantsOf.put(target, List.of(new AuthorizationGrant(ScopePath.subOrganization(ORG, unit), verbs)));
        grantsOf.put(other, List.of(new AuthorizationGrant(ScopePath.organization(ORG), verbs)));
        // The caller holds the verbs at the organization (above the target's unit: covers it) ...
        grantsOf.put(ACTOR.id(), List.of(new AuthorizationGrant(ScopePath.organization(ORG), verbs)));
        service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone());

        // ... but not when it holds them only at one unit and the other principal holds them at the organization
        // (the target's scope is above the caller's: not covered).
        grantsOf.put(ACTOR.id(), List.of(new AuthorizationGrant(ScopePath.subOrganization(ORG, unit), verbs)));
        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, other, GOOD.clone()))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(principals.writes).containsExactly("resetCredential");
    }

    @Test
    void aTargetWithNoBindingsIsResetWithoutReadingTheCallersGrants() {
        UUID target = principals.addActiveLocal(ORG, "alice");

        service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone());

        assertThat(calls).doesNotContain("grantsFor actor");
    }

    @Test
    void anIdentityProviderPrincipalAndADeactivatedOneCannotBeReset() {
        UUID idp = principals.add(ORG, "sso-user", PrincipalSource.IDP, UserView.Status.ACTIVE, false);
        UUID gone = principals.add(ORG, "gone", PrincipalSource.LOCAL, UserView.Status.DEACTIVATED, false);

        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, idp, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.NOT_LOCAL));
        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, gone, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.DEACTIVATED));

        assertThat(principals.writes).isEmpty();
        assertThat(calls).doesNotContain("hash");
    }

    @Test
    void aResetRechecksUnderTheRowLockSoAnAdministratorBindingThatAppearedAfterThePreCheckStillRefuses() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        principals.becomeAdministratorAfterFirstCheck = target;

        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.ADMINISTRATOR_PROTECTED));

        assertThat(principals.writes).isEmpty();
        assertThat(sessions.revoked).isEmpty();
    }

    @Test
    void aResetThatLostTheRaceToADeactivationWritesNothingAndIsTheDeactivatedConflict() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        principals.resetReportsNoChange = true;

        assertThatThrownBy(() -> service.resetCredential(ACTOR, ORIGIN, ORG, target, GOOD.clone()))
                .isInstanceOfSatisfying(PrincipalConflictException.class,
                        e -> assertThat(e.reason()).isEqualTo(PrincipalConflictException.Reason.DEACTIVATED));
        assertThat(sessions.revoked).isEmpty();
    }

    // ---------------------------------------------------------------- nothing secret anywhere

    @Test
    void noViewExceptionOrAuditContextCarriesTheCredentialItsHashOrTheUsernameBeyondTheViewItself() {
        UUID target = principals.addActiveLocal(ORG, "alice");
        UserView view = service.resetCredential(ACTOR, ORIGIN, ORG, target, "super-secret-credential".toCharArray());

        assertThat(view.toString()).doesNotContain("super-secret-credential").doesNotContain("hashed:");
        assertThat(calls).noneMatch(call -> call.contains("super-secret-credential") || call.contains("hashed:"));
    }

    // ================================================================ fakes

    /** An in-memory principal store whose every call is logged in the shared order. */
    private static final class FakePrincipals implements PrincipalAdministrationRepository {

        private final List<String> calls;
        private final Map<UUID, UserView> byId = new HashMap<>();
        private final Map<UUID, UUID> organizationOf = new HashMap<>();
        private final Map<UUID, String> hashes = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        final java.util.Set<String> takenUsernames = new java.util.HashSet<>();
        final java.util.Set<UUID> administrators = new java.util.HashSet<>();
        final List<UUID> administratorRoleAsked = new ArrayList<>();
        UUID becomeAdministratorAfterFirstCheck;
        boolean deactivateReportsNoChange;
        boolean resetReportsNoChange;
        private int holdsRoleCalls;

        FakePrincipals(List<String> calls) {
            this.calls = calls;
        }

        UUID addActiveLocal(UUID organization, String username) {
            return add(organization, username, PrincipalSource.LOCAL, UserView.Status.ACTIVE, false);
        }

        UUID add(UUID organization, String username, PrincipalSource source, UserView.Status status,
                boolean mustRotate) {
            UUID id = UUID.randomUUID();
            byId.put(id, new UserView(id, username, source, status, mustRotate, NOW));
            organizationOf.put(id, organization);
            return id;
        }

        String storedHash(UUID id) {
            return hashes.get(id);
        }

        @Override
        public UserView insertLocal(UUID id, UUID organizationId, String username, String credentialHash) {
            calls.add("insertLocal " + organizationId + " " + username);
            if (takenUsernames.contains(username)) {
                throw new UsernameTakenException();
            }
            writes.add("insertLocal");
            UserView view = new UserView(id, username, PrincipalSource.LOCAL, UserView.Status.ACTIVE, true, NOW);
            byId.put(id, view);
            organizationOf.put(id, organizationId);
            hashes.put(id, credentialHash);
            return view;
        }

        @Override
        public List<UserView> list(UUID organizationId) {
            calls.add("list " + organizationId);
            return byId.values().stream().filter(v -> organizationId.equals(organizationOf.get(v.id())))
                    .sorted(java.util.Comparator.comparing(UserView::username)).toList();
        }

        @Override
        public Optional<UserView> find(UUID organizationId, UUID id) {
            calls.add("find " + id);
            return Optional.ofNullable(byId.get(id)).filter(v -> organizationId.equals(organizationOf.get(id)));
        }

        @Override
        public Optional<UserView> lock(UUID organizationId, UUID id) {
            calls.add("lock " + id);
            return Optional.ofNullable(byId.get(id)).filter(v -> organizationId.equals(organizationOf.get(id)));
        }

        @Override
        public boolean holdsRole(UUID principalId, UUID roleId) {
            calls.add("holdsRole " + principalId);
            administratorRoleAsked.add(roleId);
            holdsRoleCalls++;
            if (becomeAdministratorAfterFirstCheck != null && holdsRoleCalls > 1) {
                administrators.add(becomeAdministratorAfterFirstCheck);
            }
            return administrators.contains(principalId);
        }

        @Override
        public boolean deactivate(UUID organizationId, UUID id) {
            calls.add("deactivate " + id);
            if (deactivateReportsNoChange) {
                return false;
            }
            UserView v = byId.get(id);
            if (v == null || v.status() != UserView.Status.ACTIVE) {
                return false;
            }
            writes.add("deactivate");
            byId.put(id, new UserView(id, v.username(), v.source(), UserView.Status.DEACTIVATED, v.mustRotate(),
                    v.createdAt()));
            return true;
        }

        @Override
        public boolean resetCredential(UUID organizationId, UUID id, String credentialHash) {
            calls.add("resetCredential " + id);
            if (resetReportsNoChange) {
                return false;
            }
            UserView v = byId.get(id);
            writes.add("resetCredential");
            hashes.put(id, credentialHash);
            byId.put(id, new UserView(id, v.username(), v.source(), v.status(), true, v.createdAt()));
            return true;
        }
    }

    private static final class FakeHasher implements CredentialVerifier {

        private final List<String> calls;

        FakeHasher(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public String hash(char[] credential) {
            calls.add("hash");
            return "hashed:" + new String(credential);
        }

        @Override
        public boolean verify(char[] credential, String credentialHash) {
            return credentialHash.equals("hashed:" + new String(credential));
        }
    }

    /** Only {@code revokeAllFor} is exercised; the rest of the port is not part of this surface. */
    private static final class FakeSessions implements SessionRepository {

        private final List<String> calls;
        final List<PrincipalRef> revoked = new ArrayList<>();

        FakeSessions(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void revokeAllFor(PrincipalRef principal, Instant now) {
            calls.add("revokeAllFor " + principal.id() + " at " + now);
            revoked.add(principal);
        }

        @Override
        public boolean createForActivePrincipal(Session session, String tokenHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Session> resolveAndTouch(String tokenHash, Instant now, Duration idleTimeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revoke(SessionId id, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void revokeAllExceptFor(PrincipalRef principal, SessionId retainedSession, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> activeTokenHash(SessionId id, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UUID> principalIdFor(SessionId id) {
            throw new UnsupportedOperationException();
        }
    }

    /** Logs {@code begin} and {@code commit} around the callback, and nothing on an exception (a rollback). */
    private static final class RecordingTransactions implements TransactionOperations {

        private final List<String> calls;

        RecordingTransactions(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            calls.add("begin");
            T result = action.doInTransaction(null);
            calls.add("commit");
            return result;
        }
    }
}
