package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcLoginThrottleRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

class LocalLoginIT {

    private NamedParameterJdbcTemplate jdbc;
    /** The organization every user of this test signs in to (a username is unique per organization, OR-125 (4)). */
    private UUID organization;
    private JdbcPrincipalRepository principals;
    private SessionService sessions;
    private LocalAuthenticationService login;
    private CredentialHasher hasher;
    private MutableClock clock;
    private TransactionOperations transactions;
    private AuditContext auditContext;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        jdbc.getJdbcTemplate().update("""
                DELETE FROM sm_control.session
                 WHERE principal_id IN (
                    SELECT principal_id FROM sm_control.principal
                     WHERE username LIKE 'login-%@example.test'
                 )
                """);
        organization = LoginFixtures.organization(jdbc);
        // No principal DELETE here (OR-19/V011+): each fixed username above is created by exactly ONE
        // test method per run, so nothing needs pre-cleanup — and once this class runs against a
        // schema at V011 or later, a principal that any EARLIER test method's successful login has
        // already made a change_audit actor can never be deleted at all (`ON DELETE RESTRICT`); the
        // very first test in run order to log in would poison every subsequent test's setUp().
        principals = new JdbcPrincipalRepository(jdbc);
        clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        transactions = transactionsFor(jdbc);
        auditContext = new JdbcAuditContext(jdbc);
        sessions = new SessionService(new JdbcSessionRepository(jdbc), principals, auditContext, transactions, clock);
        hasher = new CredentialHasher();
        login = new LocalLoginService(principals, sessions, hasher, new JdbcLoginThrottleRepository(jdbc),
                new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5)),
                LoginRateLimitPolicy.ADDRESS_DEFAULT, auditContext, transactions, clock);
    }

    @Test
    void unknownUserAndWrongCredentialUseTheSameGenericRefusal() {
        createLocal("login-known@example.test", "known credential".toCharArray());

        Throwable unknown = refusalFor(new LocalCredentials(organization.toString(), "login-unknown@example.test", "candidate".toCharArray()),
                originFor("login-unknown@example.test"));
        Throwable wrong = refusalFor(new LocalCredentials(organization.toString(), "login-known@example.test", "candidate".toCharArray()),
                originFor("login-known@example.test"));

        assertThat(unknown).isInstanceOf(LoginRefusedException.class);
        assertThat(wrong).isInstanceOf(LoginRefusedException.class);
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    }

    @Test
    void validLocalCredentialIssuesADurableSessionAndSessionBoundCsrfToken() {
        UUID principalId = createLocal("login-success@example.test", "correct credential".toCharArray());

        LocalLoginResult result = login.authenticate(
                new LocalCredentials(organization.toString(), "login-success@example.test", "correct credential".toCharArray()),
                originFor("login-success@example.test"));

        assertThat(result.session().principal()).isEqualTo(new PrincipalRef(principalId));
        assertThat(sessions.resolve(result.sessionToken())).isPresent();
        assertThat(sessions.verifyCsrf(result.session().id(), result.csrfToken())).isTrue();
    }

    @Test
    void deactivatedPrincipalsReceiveTheSameGenericRefusal() {
        UUID principalId = createLocal("login-deactivated@example.test", "correct credential".toCharArray());
        principals.deactivate(principalId);

        Throwable refusal = refusalFor(
                new LocalCredentials(organization.toString(), "login-deactivated@example.test", "correct credential".toCharArray()),
                originFor("login-deactivated@example.test"));

        assertThat(refusal).isInstanceOf(LoginRefusedException.class);
    }

    /**
     * OR-23: the username threshold still trips (it stays an ALARM, recorded and audited), but it is
     * no longer a GATE — a correct credential from this SAME, still-unlocked address succeeds despite
     * the username sitting at its own lockout plateau.
     */
    @Test
    void configuredWrongCredentialThresholdLocksTheUsernameAlarmButNotACorrectCredentialFromAnUnlockedAddress() {
        createLocal("login-locked@example.test", "correct credential".toCharArray());
        RequestOrigin origin = originFor("login-locked@example.test");

        refusalFor(new LocalCredentials(organization.toString(), "login-locked@example.test", "wrong credential".toCharArray()), origin);
        refusalFor(new LocalCredentials(organization.toString(), "login-locked@example.test", "wrong credential".toCharArray()), origin);
        assertThat(usernameIsLocked("login-locked@example.test"))
                .as("the username threshold (2) has tripped").isTrue();

        assertThat(login.authenticate(
                new LocalCredentials(organization.toString(), "login-locked@example.test", "correct credential".toCharArray()), origin))
                .isInstanceOf(LocalLoginResult.class);
    }

    @Test
    void successfulLoginClearsPriorFailuresBeforeTheThresholdIsReached() {
        createLocal("login-clears-failures@example.test", "correct credential".toCharArray());
        RequestOrigin origin = originFor("login-clears-failures@example.test");

        refusalFor(new LocalCredentials(organization.toString(), "login-clears-failures@example.test", "wrong credential".toCharArray()), origin);
        login.authenticate(
                new LocalCredentials(organization.toString(), "login-clears-failures@example.test", "correct credential".toCharArray()), origin);
        refusalFor(new LocalCredentials(organization.toString(), "login-clears-failures@example.test", "wrong credential".toCharArray()), origin);

        assertThat(login.authenticate(
                new LocalCredentials(organization.toString(), "login-clears-failures@example.test", "correct credential".toCharArray()), origin))
                .isInstanceOf(LocalLoginResult.class);
    }

    /**
     * The throttle mechanism itself (an expired window resets and can re-lock) is a USERNAME-counter
     * property under OR-23, so it is proven here directly against the throttle row's own state — not
     * through {@code authenticate}'s outcome, which no longer gates on the username at all.
     */
    @Test
    void expiredLockoutStartsANewFailureWindowThatCanLockAgain() {
        String username = "login-second-lockout@example.test";
        createLocal(username, "correct credential".toCharArray());
        RequestOrigin origin = originFor(username);
        LocalCredentials wrong = new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray());

        refusalFor(wrong, origin);
        refusalFor(wrong, origin);
        assertThat(usernameIsLocked(username)).as("locked after the 2nd failure (threshold 2)").isTrue();

        clock.advance(Duration.ofMinutes(6));
        assertThat(usernameIsLocked(username)).as("the lockout window has expired").isFalse();

        // OR-23: the username lock is an alarm, not a gate — the correct credential succeeds
        // regardless, and clears the (already expired) alarm.
        assertThat(login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()), origin))
                .isInstanceOf(LocalLoginResult.class);
        assertThat(usernameIsLocked(username)).isFalse();

        refusalFor(wrong, origin);
        refusalFor(wrong, origin);

        assertThat(usernameIsLocked(username)).as("a fresh window of failures can re-lock the username").isTrue();
    }

    /** {@code true} iff the USERNAME dimension's throttle row is locked right now, read directly. */
    private boolean usernameIsLocked(String username) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT locked_until > :now FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource().addValue("now", java.sql.Timestamp.from(clock.instant()))
                        .addValue("subjectHash", subjectHash(username)), Boolean.class));
    }

    /**
     * OR-23: the ADDRESS is the dimension {@code authenticate} actually gates on, so durability
     * across service instances is proven against IT, not the username (whose lock a fresh instance
     * would never even consult).
     */
    @Test
    void theAddressLockoutIsDurableAcrossABrandNewLocalLoginServiceInstanceAgainstTheSameDatabase() {
        String username = "login-durable-lockout@example.test";
        RequestOrigin origin = originFor(username);
        createLocal(username, "correct credential".toCharArray());
        LoginRateLimitPolicy tightAddressPolicy = new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5));
        LocalAuthenticationService firstInstanceLogin = new LocalLoginService(principals, sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), LoginRateLimitPolicy.LOCAL_DEFAULT, tightAddressPolicy,
                auditContext, transactions, clock);
        LocalCredentials wrong = new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray());
        LocalCredentials correct = new LocalCredentials(organization.toString(), username, "correct credential".toCharArray());

        // Lock the ADDRESS out through the FIRST service instance.
        refusalFor(firstInstanceLogin, wrong, origin);
        refusalFor(firstInstanceLogin, wrong, origin);

        // A brand new instance — new SessionService, new LocalLoginService, new JdbcLoginThrottleRepository
        // object graph, nothing shared with the first except the DATABASE and the same controllable clock.
        // If the lockout lived in either service's memory rather than in sm_control.login_throttle, this
        // fresh instance would see no failures recorded and would let the correct credential through.
        SessionService freshSessions = new SessionService(new JdbcSessionRepository(jdbc), principals, auditContext,
                transactions, clock);
        LocalAuthenticationService freshLogin = new LocalLoginService(principals, freshSessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), LoginRateLimitPolicy.LOCAL_DEFAULT, tightAddressPolicy,
                auditContext, transactions, clock);

        LoginRefusedException stillLocked = refusalFor(freshLogin, correct, origin);
        assertThat(stillLocked).isInstanceOf(LoginRefusedException.class);

        clock.advance(Duration.ofMinutes(6));

        assertThat(freshLogin.authenticate(correct, origin)).isInstanceOf(LocalLoginResult.class);
    }

    @Test
    void runtimeRoleCanRecordFailuresClearThemAndIssueASession() {
        String username = "runtime-login-" + UUID.randomUUID() + "@example.test";
        RequestOrigin origin = originFor(username);
        UUID principalId = principals.createLocal(organization, username, hasher.hash("correct credential".toCharArray()),
                false);
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        TransactionOperations runtimeTransactions = transactionsFor(runtime);
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);
        SessionService runtimeSessions = new SessionService(new JdbcSessionRepository(runtime),
                new JdbcPrincipalRepository(runtime), runtimeAuditContext, runtimeTransactions, clock);
        LocalAuthenticationService runtimeLogin = new LocalLoginService(new JdbcPrincipalRepository(runtime), runtimeSessions,
                new CredentialHasher(), new JdbcLoginThrottleRepository(runtime),
                new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5)), LoginRateLimitPolicy.ADDRESS_DEFAULT,
                runtimeAuditContext, runtimeTransactions, clock);

        refusalFor(runtimeLogin, new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()), origin);
        LocalLoginResult result = runtimeLogin.authenticate(
                new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()), origin);

        assertThat(result.session().principal()).isEqualTo(new PrincipalRef(principalId));
        assertThat(runtimeSessions.resolve(result.sessionToken())).isPresent();
    }

    // ---------------------------------------------------------------------------------- OR-125 (4): per organization

    /** The same username in two organizations is two users: each signs in to its own, with its own credential only. */
    @Test
    void theSameUsernameInTwoOrganizationsIsTwoUsersEachSignedInToByItsOwnOrganizationOnly() {
        UUID other = LoginFixtures.organization(jdbc);
        UUID mine = principals.createLocal(organization, "shared-name", hasher.hash("first credential".toCharArray()),
                false);
        UUID theirs = principals.createLocal(other, "shared-name", hasher.hash("second credential".toCharArray()),
                false);
        RequestOrigin origin = originFor("shared-name");

        assertThat(login.authenticate(new LocalCredentials(organization.toString(), "shared-name",
                "first credential".toCharArray()), origin).session().principal()).isEqualTo(new PrincipalRef(mine));
        assertThat(login.authenticate(new LocalCredentials(other.toString(), "shared-name",
                "second credential".toCharArray()), originFor("shared-name-2")).session().principal())
                .isEqualTo(new PrincipalRef(theirs));
        // the other organization's credential does not open this organization's user
        refusalFor(new LocalCredentials(organization.toString(), "shared-name", "second credential".toCharArray()),
                originFor("shared-name-3"));
        refusalFor(new LocalCredentials(other.toString(), "shared-name", "first credential".toCharArray()),
                originFor("shared-name-4"));
    }

    /** The organization is its id or its name, and the name is compared without regard to case. */
    @Test
    void theOrganizationMayBeNamedByIdOrByNameInAnyCase() {
        UUID principalId = createLocal("login-by-name@example.test", "correct credential".toCharArray());
        String name = "login-fixture-" + organization;

        for (String typed : new String[] {organization.toString(), organization.toString().toUpperCase(),
                name, name.toUpperCase()}) {
            assertThat(login.authenticate(new LocalCredentials(typed, "login-by-name@example.test",
                    "correct credential".toCharArray()), originFor("by-name-" + typed)).session().principal())
                    .as("organization typed as " + typed).isEqualTo(new PrincipalRef(principalId));
        }
    }

    /** Login matches the username without regard to case, the rule the unique index applies. */
    @Test
    void theUsernameMatchesWithoutRegardToCase() {
        UUID principalId = createLocal("Login-Mixed-Case", "correct credential".toCharArray());

        assertThat(login.authenticate(new LocalCredentials(organization.toString(), "LOGIN-MIXED-case",
                "correct credential".toCharArray()), originFor("mixed-case")).session().principal())
                .isEqualTo(new PrincipalRef(principalId));
    }

    /**
     * No oracle: an organization that does not exist is refused with the very exception, message and counters a wrong
     * credential gets. Both are counted (the unresolved organization too), so absence of a counter is not the tell.
     */
    @Test
    void anUnknownOrganizationIsRefusedExactlyAsAWrongCredentialIsAndIsCountedAsAFailureToo() {
        createLocal("login-no-oracle@example.test", "correct credential".toCharArray());
        String nowhere = "Nowhere-" + UUID.randomUUID();

        LoginRefusedException wrongCredential = refusalFor(new LocalCredentials(organization.toString(),
                "login-no-oracle@example.test", "wrong credential".toCharArray()), originFor("oracle-wrong"));
        LoginRefusedException unknownOrganization = refusalFor(new LocalCredentials(nowhere,
                "login-no-oracle@example.test", "correct credential".toCharArray()), originFor("oracle-org"));
        LoginRefusedException unknownIdAsOrganization = refusalFor(new LocalCredentials(UUID.randomUUID().toString(),
                "login-no-oracle@example.test", "correct credential".toCharArray()), originFor("oracle-id"));

        assertThat(unknownOrganization).isInstanceOf(wrongCredential.getClass()).hasMessage(wrongCredential.getMessage());
        assertThat(unknownIdAsOrganization).hasMessage(wrongCredential.getMessage());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.login_throttle WHERE subject_hash = :h",
                new MapSqlParameterSource("h", LocalLoginService.unresolvedLoginSubjectHash(nowhere,
                        "login-no-oracle@example.test")), Integer.class))
                .as("the unresolved organization is counted like a wrong username").isEqualTo(1);
    }

    /** A blank organization is simply no organization: refused like any other, never an error of another kind. */
    @Test
    void aBlankOrganizationIsRefusedLikeAnyOther() {
        createLocal("login-blank-org@example.test", "correct credential".toCharArray());

        refusalFor(new LocalCredentials("", "login-blank-org@example.test", "correct credential".toCharArray()),
                originFor("blank-org"));
        refusalFor(new LocalCredentials("  ", "login-blank-org@example.test", "correct credential".toCharArray()),
                originFor("blank-org-2"));
    }

    /** Spellings of a username that differ only in case are ONE failure counter (otherwise case is a lockout bypass). */
    @Test
    void failuresInDifferentCaseAccumulateInOneUsernameCounter() {
        createLocal("login-case-counter@example.test", "correct credential".toCharArray());

        refusalFor(new LocalCredentials(organization.toString(), "Login-Case-Counter@example.test",
                "wrong credential".toCharArray()), originFor("case-counter"));
        refusalFor(new LocalCredentials(organization.toString(), "LOGIN-CASE-COUNTER@EXAMPLE.TEST",
                "wrong credential".toCharArray()), originFor("case-counter"));

        assertThat(usernameIsLocked("login-case-counter@example.test"))
                .as("the 2nd failure of the threshold (2), spelt another way, tripped the SAME counter").isTrue();
    }

    /**
     * A name that is another organization's id must not take that organization's logins: an id is tried first, so
     * naming organization A by its id reaches A even though B is (by rename) NAMED with A's id.
     */
    @Test
    void anOrganizationNamedLikeAnotherOnesIdCannotTakeThatOrganizationsLogins() {
        UUID principalId = createLocal("login-hijack@example.test", "correct credential".toCharArray());
        UUID squatter = LoginFixtures.organization(jdbc);
        jdbc.getJdbcTemplate().update("UPDATE sm_control.organization SET name = ? WHERE organization_id = ?",
                organization.toString(), squatter);
        principals.createLocal(squatter, "login-hijack@example.test", hasher.hash("squatter credential".toCharArray()),
                false);

        assertThat(login.authenticate(new LocalCredentials(organization.toString(), "login-hijack@example.test",
                "correct credential".toCharArray()), originFor("hijack")).session().principal())
                .isEqualTo(new PrincipalRef(principalId));
    }

    @Test
    void loginInputsResultsAndRefusalsDoNotExposeCredentialsInToStringOrMessages() {
        LocalCredentials credentials = new LocalCredentials(organization.toString(), "login-secret@example.test", "secret-sentinel".toCharArray());
        LoginRefusedException refusal = new LoginRefusedException();

        assertThat(credentials.toString()).doesNotContain("secret-sentinel");
        assertThat(refusal.getMessage()).doesNotContain("secret-sentinel");
    }

    private LoginRefusedException refusalFor(LocalCredentials credentials, RequestOrigin origin) {
        return refusalFor(login, credentials, origin);
    }

    private static LoginRefusedException refusalFor(LocalAuthenticationService authentication,
                                                     LocalCredentials credentials, RequestOrigin origin) {
        try {
            authentication.authenticate(credentials, origin);
            throw new AssertionError("expected login refusal");
        } catch (LoginRefusedException refusal) {
            return refusal;
        }
    }

    private UUID createLocal(String username, char[] credential) {
        return principals.createLocal(organization, username, hasher.hash(credential), false);
    }

    /**
     * Delegates to {@link LocalLoginService#loginSubjectHash} rather than recomputing
     * {@code sha256(username)} independently — the login subject hash is domain-separated with a
     * {@code "local-login:"} prefix, and a test that recomputed the hash without that prefix would
     * silently stop cleaning up the right {@code login_throttle} row the moment the prefix changed.
     */
    private String subjectHash(String username) {
        return LoginFixtures.usernameSubject(jdbc, organization, username);
    }

    /**
     * A {@code RequestOrigin} whose client address is DERIVED from the test's own already-unique
     * username, so the new {@code OR-20} address dimension never accumulates failures shared across
     * different test methods (which would otherwise poison later tests with a stale lockout on a
     * fixed literal address) — it merely rides along, unlocked, behind the username dimension these
     * tests actually exercise.
     */
    private static RequestOrigin originFor(String username) {
        int hash = username.hashCode();
        String address = "10." + ((hash >>> 16) & 0xFF) + "." + ((hash >>> 8) & 0xFF) + "." + (hash & 0xFF);
        return new RequestOrigin(address, UUID.randomUUID());
    }

    private static TransactionOperations transactionsFor(NamedParameterJdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant initial) {
            current = initial;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
