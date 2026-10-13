package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
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

/**
 * The durable rotation-specific brute-force throttle (fix for: {@code CredentialRotationService} had
 * no throttle at all — {@code principals.recordCredentialFailure} incremented a counter nothing read).
 * Applies the same {@link JdbcLoginThrottleRepository}/{@link LoginRateLimitPolicy} mechanism local
 * login uses, keyed by {@link CredentialRotationService#rotationSubjectHash} — a subject-hash space
 * distinct from login's {@code sha256(username)}, proven not to interfere either way.
 */
class CredentialRotationThrottleIT {

    private static final LoginRateLimitPolicy POLICY =
            new LoginRateLimitPolicy(2, Duration.ofMinutes(1), Duration.ofMinutes(5));

    private NamedParameterJdbcTemplate jdbc;
    private JdbcPrincipalRepository principals;
    private SessionService sessions;
    private MutableClock clock;
    private CredentialHasher hasher;
    private AuditContext auditContext;
    private TransactionOperations transactions;
    private UUID principalId;
    private UUID organizationId;
    private char[] currentCredential;

    /**
     * Derived, fresh in {@code setUp()}, from this test's own unique username, so the {@code OR-20}
     * address dimension this class's logins also drive never accumulates failures shared across
     * different test METHODS of this class (a fixed literal address would).
     */
    private RequestOrigin origin;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion(SmControlPostgres.LATEST_VERSION);
        hasher = new CredentialHasher();
        clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        currentCredential = "current credential".toCharArray();
        auditContext = new JdbcAuditContext(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));

        organizationId = LoginFixtures.organization(jdbc);
        String username = "rotation-throttle-" + UUID.randomUUID() + "@example.test";
        int hash = username.hashCode();
        String address = "10." + ((hash >>> 16) & 0xFF) + "." + ((hash >>> 8) & 0xFF) + "." + (hash & 0xFF);
        origin = new RequestOrigin(address, UUID.randomUUID());
        principals = new JdbcPrincipalRepository(jdbc);
        principalId = principals.createLocal(organizationId, username, hasher.hash(currentCredential), true);
        sessions = new SessionService(new JdbcSessionRepository(jdbc), principals, auditContext, transactions, clock);
    }

    @Test
    void afterTheThresholdOfWrongCurrentCredentialsTheCorrectOneIsRefusedWhileLocked() {
        Session session = openSession();

        refuseWith(session, "wrong 1".toCharArray());
        refuseWith(session, "wrong 2".toCharArray());

        assertThatThrownBy(() -> rotationService().rotate(session, currentCredential, opaqueCredential(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
    }

    @Test
    void theRotationLockoutIsDurableAcrossABrandNewServiceInstance() {
        Session session = openSession();
        refuseWith(session, "wrong 1".toCharArray());
        refuseWith(session, "wrong 2".toCharArray());

        // A fresh CredentialRotationService object graph, sharing nothing with the one that locked the
        // subject out except the database and the same controllable clock.
        CredentialRotationService fresh = rotationService();

        assertThatThrownBy(() -> fresh.rotate(session, currentCredential, opaqueCredential(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
    }

    @Test
    void afterLockoutExpiryRotationWithTheCorrectCredentialSucceeds() {
        Session session = openSession();
        refuseWith(session, "wrong 1".toCharArray());
        refuseWith(session, "wrong 2".toCharArray());

        clock.advance(Duration.ofMinutes(6));
        char[] replacement = opaqueCredential();

        rotationService().rotate(session, currentCredential.clone(), replacement.clone(), origin);

        assertThat(hasher.verify(replacement, currentPrincipal().credentialHash())).isTrue();
    }

    @Test
    void aRotationLockoutDoesNotLockLoginForTheSamePrincipalsUsername() {
        Session session = openSession();
        refuseWith(session, "wrong 1".toCharArray());
        refuseWith(session, "wrong 2".toCharArray());
        assertThatThrownBy(() -> rotationService().rotate(session, currentCredential, opaqueCredential(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);

        String username = currentPrincipal().username();
        LocalLoginService login = new LocalLoginService(principals, sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), POLICY, LoginRateLimitPolicy.ADDRESS_DEFAULT, auditContext,
                transactions, clock);

        assertThat(login.authenticate(new LocalCredentials(organizationId.toString(), username, currentCredential.clone()), origin))
                .isInstanceOf(LocalLoginResult.class);
    }

    @Test
    void aLoginLockoutDoesNotLockRotationForTheSamePrincipal() {
        String username = currentPrincipal().username();
        LocalLoginService login = new LocalLoginService(principals, sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), POLICY, LoginRateLimitPolicy.ADDRESS_DEFAULT, auditContext,
                transactions, clock);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organizationId.toString(), username, "wrong".toCharArray()), origin))
                    .isInstanceOf(LoginRefusedException.class);
        }
        // Login for this username is now locked (an alarm, not a gate under OR-23 — checked directly
        // against the throttle row's own state, since a correct credential from this SAME, unlocked
        // address would otherwise just succeed); rotation must be unaffected either way.
        assertThat(jdbc.queryForObject(
                "SELECT locked_until > :now FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource().addValue("now", java.sql.Timestamp.from(clock.instant()))
                        .addValue("subjectHash", LoginFixtures.usernameSubject(jdbc, organizationId, username)), Boolean.class))
                .isTrue();

        Session session = openSession();
        char[] replacement = opaqueCredential();
        rotationService().rotate(session, currentCredential.clone(), replacement.clone(), origin);

        assertThat(hasher.verify(replacement, currentPrincipal().credentialHash())).isTrue();
    }

    private void refuseWith(Session session, char[] wrongCredential) {
        assertThatThrownBy(() -> rotationService().rotate(session, wrongCredential, opaqueCredential(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
    }

    private Session openSession() {
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        return sessions.resolve(token).orElseThrow();
    }

    private CredentialRotationService rotationService() {
        return new CredentialRotationService(principals, new JdbcSessionRepository(jdbc), hasher,
                new JdbcLoginThrottleRepository(jdbc), POLICY, auditContext, transactions, clock);
    }

    private PrincipalRepository.Principal currentPrincipal() {
        return principals.find(principalId).orElseThrow();
    }

    private static char[] opaqueCredential() {
        return UUID.randomUUID().toString().toCharArray();
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
