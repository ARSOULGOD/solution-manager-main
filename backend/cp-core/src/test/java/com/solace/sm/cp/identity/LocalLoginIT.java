package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.LocalAuthenticationService;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcLoginThrottleRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class LocalLoginIT {

    private static final List<String> FIXED_TEST_USERNAMES = List.of(
            "login-known@example.test", "login-unknown@example.test", "login-success@example.test",
            "login-deactivated@example.test", "login-locked@example.test", "login-clears-failures@example.test",
            "login-second-lockout@example.test");

    private NamedParameterJdbcTemplate jdbc;
    private JdbcPrincipalRepository principals;
    private SessionService sessions;
    private LocalAuthenticationService login;
    private CredentialHasher hasher;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("010");
        jdbc.getJdbcTemplate().update("""
                DELETE FROM sm_control.session
                 WHERE principal_id IN (
                    SELECT principal_id FROM sm_control.principal
                     WHERE username LIKE 'login-%@example.test'
                 )
                """);
        jdbc.update("DELETE FROM sm_control.login_throttle WHERE subject_hash IN (:subjectHashes)",
                new MapSqlParameterSource("subjectHashes", FIXED_TEST_USERNAMES.stream()
                        .map(LocalLoginIT::subjectHash).toList()));
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.principal WHERE username LIKE 'login-%@example.test'");
        principals = new JdbcPrincipalRepository(jdbc);
        clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        sessions = new SessionService(new JdbcSessionRepository(jdbc), principals, clock);
        hasher = new CredentialHasher();
        login = new LocalLoginService(principals, sessions, hasher, new JdbcLoginThrottleRepository(jdbc),
                new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5)), clock);
    }

    @Test
    void unknownUserAndWrongCredentialUseTheSameGenericRefusal() {
        createLocal("login-known@example.test", "known credential".toCharArray());

        Throwable unknown = refusalFor(new LocalCredentials("login-unknown@example.test", "candidate".toCharArray()));
        Throwable wrong = refusalFor(new LocalCredentials("login-known@example.test", "candidate".toCharArray()));

        assertThat(unknown).isInstanceOf(LoginRefusedException.class);
        assertThat(wrong).isInstanceOf(LoginRefusedException.class);
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
    }

    @Test
    void validLocalCredentialIssuesADurableSessionAndSessionBoundCsrfToken() {
        UUID principalId = createLocal("login-success@example.test", "correct credential".toCharArray());

        LocalLoginResult result = login.authenticate(
                new LocalCredentials("login-success@example.test", "correct credential".toCharArray()));

        assertThat(result.session().principal()).isEqualTo(new PrincipalRef(principalId));
        assertThat(sessions.resolve(result.sessionToken())).isPresent();
        assertThat(sessions.verifyCsrf(result.session().id(), result.csrfToken())).isTrue();
    }

    @Test
    void deactivatedPrincipalsReceiveTheSameGenericRefusal() {
        UUID principalId = createLocal("login-deactivated@example.test", "correct credential".toCharArray());
        principals.deactivate(principalId);

        Throwable refusal = refusalFor(
                new LocalCredentials("login-deactivated@example.test", "correct credential".toCharArray()));

        assertThat(refusal).isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void configuredWrongCredentialThresholdLocksOutSubsequentAttempts() {
        createLocal("login-locked@example.test", "correct credential".toCharArray());

        refusalFor(new LocalCredentials("login-locked@example.test", "wrong credential".toCharArray()));
        refusalFor(new LocalCredentials("login-locked@example.test", "wrong credential".toCharArray()));
        Throwable locked = refusalFor(
                new LocalCredentials("login-locked@example.test", "correct credential".toCharArray()));

        assertThat(locked).isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void successfulLoginClearsPriorFailuresBeforeTheThresholdIsReached() {
        createLocal("login-clears-failures@example.test", "correct credential".toCharArray());

        refusalFor(new LocalCredentials("login-clears-failures@example.test", "wrong credential".toCharArray()));
        login.authenticate(new LocalCredentials("login-clears-failures@example.test", "correct credential".toCharArray()));
        refusalFor(new LocalCredentials("login-clears-failures@example.test", "wrong credential".toCharArray()));

        assertThat(login.authenticate(
                new LocalCredentials("login-clears-failures@example.test", "correct credential".toCharArray())))
                .isInstanceOf(LocalLoginResult.class);
    }

    @Test
    void expiredLockoutStartsANewFailureWindowThatCanLockAgain() {
        createLocal("login-second-lockout@example.test", "correct credential".toCharArray());
        LocalCredentials wrong = new LocalCredentials("login-second-lockout@example.test", "wrong credential".toCharArray());

        refusalFor(wrong);
        refusalFor(wrong);
        clock.advance(Duration.ofMinutes(6));
        refusalFor(wrong);
        assertThat(login.authenticate(new LocalCredentials(
                "login-second-lockout@example.test", "correct credential".toCharArray())))
                .isInstanceOf(LocalLoginResult.class);
        refusalFor(wrong);
        refusalFor(wrong);

        assertThat(refusalFor(new LocalCredentials(
                "login-second-lockout@example.test", "correct credential".toCharArray())))
                .isInstanceOf(LoginRefusedException.class);
    }

    @Test
    void runtimeRoleCanRecordFailuresClearThemAndIssueASessionAtV010() {
        UUID organizationId = UUID.randomUUID();
        String username = "runtime-login-" + UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        UUID principalId = principals.createLocal(organizationId, username, hasher.hash("correct credential".toCharArray()),
                false);
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("010");
        SessionService runtimeSessions = new SessionService(new JdbcSessionRepository(runtime),
                new JdbcPrincipalRepository(runtime), clock);
        LocalAuthenticationService runtimeLogin = new LocalLoginService(new JdbcPrincipalRepository(runtime), runtimeSessions,
                new CredentialHasher(), new JdbcLoginThrottleRepository(runtime),
                new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5)), clock);

        refusalFor(runtimeLogin, new LocalCredentials(username, "wrong credential".toCharArray()));
        LocalLoginResult result = runtimeLogin.authenticate(new LocalCredentials(username, "correct credential".toCharArray()));

        assertThat(result.session().principal()).isEqualTo(new PrincipalRef(principalId));
        assertThat(runtimeSessions.resolve(result.sessionToken())).isPresent();
    }

    @Test
    void loginInputsResultsAndRefusalsDoNotExposeCredentialsInToStringOrMessages() {
        LocalCredentials credentials = new LocalCredentials("login-secret@example.test", "secret-sentinel".toCharArray());
        LoginRefusedException refusal = new LoginRefusedException();

        assertThat(credentials.toString()).doesNotContain("secret-sentinel");
        assertThat(refusal.getMessage()).doesNotContain("secret-sentinel");
    }

    private LoginRefusedException refusalFor(LocalCredentials credentials) {
        return refusalFor(login, credentials);
    }

    private static LoginRefusedException refusalFor(LocalAuthenticationService authentication,
                                                     LocalCredentials credentials) {
        try {
            authentication.authenticate(credentials);
            throw new AssertionError("expected login refusal");
        } catch (LoginRefusedException refusal) {
            return refusal;
        }
    }

    private UUID createLocal(String username, char[] credential) {
        UUID organizationId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        return principals.createLocal(organizationId, username, hasher.hash(credential), false);
    }

    private static String subjectHash(String username) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(username.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
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
