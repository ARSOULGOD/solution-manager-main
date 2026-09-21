package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.CsrfToken;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class SessionIT {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
    private NamedParameterJdbcTemplate jdbc;
    private PrincipalRepository principals;
    private SessionService sessions;

    @BeforeEach
    void setUp() {
        clock.reset(Instant.parse("2026-09-17T00:00:00Z"));
        jdbc = SmControlPostgres.jdbcAtVersion("004");
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.session");
        jdbc.getJdbcTemplate().update("DELETE FROM sm_control.principal");
        principals = new JdbcPrincipalRepository(jdbc);
        sessions = new SessionService(new JdbcSessionRepository(jdbc), principals, clock);
    }

    @Test
    void aSessionIsIssuedOnlyToAnActivePrincipal() {
        UUID principalId = localPrincipal(false);
        SessionToken token = sessions.open(new PrincipalRef(principalId));

        assertThat(sessions.resolve(token)).isPresent();

        principals.deactivate(principalId);
        assertThatThrownBy(() -> sessions.open(new PrincipalRef(principalId)))
                .isInstanceOf(SessionNotIssuableException.class);
    }

    @Test
    void deactivationCommittedBeforeTheIssuanceStatementPreventsSessionIssuance() {
        UUID principalId = localPrincipal(false);
        SessionService raceAwareSessions = new SessionService(new JdbcSessionRepository(jdbc),
                deactivatingAfterRead(principalId), clock);

        assertThatThrownBy(() -> raceAwareSessions.open(new PrincipalRef(principalId)))
                .isInstanceOf(SessionNotIssuableException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.session WHERE principal_id = :id",
                Map.of("id", principalId), Integer.class)).isZero();
    }

    @Test
    void deactivatingAPrincipalEndsEverySessionItHolds() {
        UUID principalId = localPrincipal(false);
        SessionToken first = sessions.open(new PrincipalRef(principalId));
        SessionToken second = sessions.open(new PrincipalRef(principalId));

        principals.deactivate(principalId);

        assertThat(sessions.resolve(first)).isEmpty();
        assertThat(sessions.resolve(second)).isEmpty();
    }

    @Test
    void anExpiredSessionResolvesEmptyWithoutBeingDeleted() {
        SessionToken token = sessions.open(new PrincipalRef(localPrincipal(false)));
        UUID sessionId = sessions.resolve(token).orElseThrow().id().value();

        clock.advance(Duration.ofMinutes(31));

        assertThat(sessions.resolve(token)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.session WHERE session_id = :id",
                Map.of("id", sessionId), Integer.class)).isEqualTo(1);
    }

    @Test
    void bothIdleAndAbsoluteExpiryEndASession() {
        SessionToken token = sessions.open(new PrincipalRef(localPrincipal(false)));

        clock.advance(Duration.ofMinutes(29));
        assertThat(sessions.resolve(token)).isPresent();
        clock.advance(Duration.ofMinutes(29));
        assertThat(sessions.resolve(token)).isPresent();

        clock.advance(Duration.ofHours(11).plusMinutes(3));
        assertThat(sessions.resolve(token)).isEmpty();
    }

    @Test
    void aRevokedSessionResolvesEmptyImmediately() {
        SessionToken token = sessions.open(new PrincipalRef(localPrincipal(false)));
        Session session = sessions.resolve(token).orElseThrow();

        sessions.revoke(session.id());

        assertThat(sessions.resolve(token)).isEmpty();
    }

    @Test
    void revokeAllForEndsOnlyTheNamedPrincipalsSessions() {
        UUID firstPrincipal = localPrincipal(false);
        SessionToken first = sessions.open(new PrincipalRef(firstPrincipal));
        SessionToken second = sessions.open(new PrincipalRef(firstPrincipal));
        SessionToken otherPrincipal = sessions.open(new PrincipalRef(localPrincipal(false)));

        sessions.revokeAllFor(new PrincipalRef(firstPrincipal));

        assertThat(sessions.resolve(first)).isEmpty();
        assertThat(sessions.resolve(second)).isEmpty();
        assertThat(sessions.resolve(otherPrincipal)).isPresent();
    }

    @Test
    void eachOpenedSessionGetsAUniqueHighEntropyToken() {
        UUID principalId = localPrincipal(false);
        sessions.open(new PrincipalRef(principalId));
        sessions.open(new PrincipalRef(principalId));

        assertThat(jdbc.queryForObject("SELECT count(DISTINCT token_hash) FROM sm_control.session", Map.of(), Integer.class))
                .isEqualTo(2);
    }

    @Test
    void theStoredSessionValueIsADigestNotTheCookieValueAndTheTokenIsRedacted() {
        SessionToken token = sessions.open(new PrincipalRef(localPrincipal(false)));
        String storedDigest = jdbc.queryForObject("SELECT token_hash FROM sm_control.session", Map.of(), String.class);

        assertThat(token.value().getBytes(StandardCharsets.US_ASCII)).hasSizeGreaterThanOrEqualTo(43);
        assertThat(storedDigest).isEqualTo(sha256(token.value()));
        assertThat(token).hasToString("[redacted session token]");
    }

    @Test
    void aCsrfTokenIsBoundToOneSessionAndVerifiesOnlyForIt() {
        SessionToken first = sessions.open(new PrincipalRef(localPrincipal(false)));
        SessionToken second = sessions.open(new PrincipalRef(localPrincipal(false)));
        Session firstSession = sessions.resolve(first).orElseThrow();
        Session secondSession = sessions.resolve(second).orElseThrow();

        CsrfToken csrf = sessions.csrfTokenFor(firstSession.id()).orElseThrow();

        assertThat(sessions.verifyCsrf(firstSession.id(), csrf)).isTrue();
        assertThat(sessions.verifyCsrf(secondSession.id(), csrf)).isFalse();
        assertThat(csrf).hasToString("[redacted csrf token]");
    }

    @Test
    void repeatedCsrfFetchKeepsTheExistingSessionBoundTokenValid() {
        Session session = sessions.resolve(sessions.open(new PrincipalRef(localPrincipal(false)))).orElseThrow();
        CsrfToken first = sessions.csrfTokenFor(session.id()).orElseThrow();
        CsrfToken second = sessions.csrfTokenFor(session.id()).orElseThrow();

        assertThat(sha256(first.value())).isEqualTo(sha256(second.value()));
        assertThat(sessions.verifyCsrf(session.id(), first)).isTrue();
        assertThat(sessions.verifyCsrf(session.id(), second)).isTrue();
    }

    @Test
    void nullAndInvalidSessionInputsFailClosed() {
        assertThat(sessions.resolve(null)).isEmpty();
        assertThat(sessions.csrfTokenFor(null)).isEmpty();
        assertThat(sessions.verifyCsrf(null, null)).isFalse();
        assertThat(sessions.resolve(new SessionToken("not-a-valid-issued-token"))).isEmpty();

        Session session = sessions.resolve(sessions.open(new PrincipalRef(localPrincipal(false)))).orElseThrow();
        assertThat(sessions.verifyCsrf(session.id(), new CsrfToken("not-a-valid-csrf-token"))).isFalse();
    }

    @Test
    void mustRotateOnThePrincipalIsVisibleOnTheResolvedSession() {
        SessionToken token = sessions.open(new PrincipalRef(localPrincipal(true)));

        assertThat(sessions.resolve(token).orElseThrow().mustRotate()).isTrue();
    }

    @Test
    void theActualRuntimeRoleCanOpenResolveRevokeAndVerifyCsrfSessions() {
        RuntimeSession runtimeSession = runtimeSession();

        assertThat(runtimeSession.service().resolve(runtimeSession.token())).isPresent();
        CsrfToken csrf = runtimeSession.service().csrfTokenFor(runtimeSession.session().id()).orElseThrow();
        assertThat(runtimeSession.service().verifyCsrf(runtimeSession.session().id(), csrf)).isTrue();

        runtimeSession.service().revoke(runtimeSession.session().id());

        assertThat(runtimeSession.service().resolve(runtimeSession.token())).isEmpty();
    }

    @Test
    void theActualRuntimeRoleCannotAlterImmutableSessionColumnsOrDeleteSessions() {
        RuntimeSession runtimeSession = runtimeSession();

        assertRuntimeRefused(() -> runtimeSession.jdbc().update(
                "UPDATE sm_control.session SET token_hash = token_hash WHERE session_id = :id",
                Map.of("id", runtimeSession.session().id().value())));
        assertRuntimeRefused(() -> runtimeSession.jdbc().update(
                "UPDATE sm_control.session SET absolute_expires_at = absolute_expires_at WHERE session_id = :id",
                Map.of("id", runtimeSession.session().id().value())));
        assertRuntimeRefused(() -> runtimeSession.jdbc().update(
                "DELETE FROM sm_control.session WHERE session_id = :id",
                Map.of("id", runtimeSession.session().id().value())));
    }

    @Test
    void mutationGrantingAbsoluteExpiryUpdateMakesTheRuntimeRefusalFail() {
        RuntimeSession runtimeSession = runtimeSession();
        NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion("006");
        migrator.getJdbcTemplate().execute("GRANT UPDATE (absolute_expires_at) ON sm_control.session TO sm_cp_runtime");
        try {
            assertThatThrownBy(() -> assertRuntimeRefused(() -> runtimeSession.jdbc().update(
                    "UPDATE sm_control.session SET absolute_expires_at = absolute_expires_at WHERE session_id = :id",
                    Map.of("id", runtimeSession.session().id().value()))))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Expecting code to raise a throwable");
        } finally {
            migrator.getJdbcTemplate().execute("REVOKE UPDATE (absolute_expires_at) ON sm_control.session FROM sm_cp_runtime");
        }
    }

    private UUID localPrincipal(boolean mustRotate) {
        UUID organizationId = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        return principals.createLocal(organizationId, "session-" + UUID.randomUUID() + "@example.test",
                "{argon2id}stored", mustRotate);
    }

    private PrincipalRepository deactivatingAfterRead(UUID principalId) {
        return new PrincipalRepository() {
            @Override
            public UUID createLocal(UUID organizationId, String username, String credentialHash, boolean mustRotate) {
                throw new UnsupportedOperationException();
            }

            @Override
            public UUID createIdp(UUID organizationId, String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Principal> find(UUID id) {
                Optional<Principal> found = principals.find(id);
                if (principalId.equals(id)) {
                    principals.deactivate(id);
                }
                return found;
            }

            @Override
            public Optional<Principal> findLocalByUsername(String username) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean rotateCredential(UUID id, String expectedCredentialHash, String replacementCredentialHash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void recordCredentialFailure(UUID id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deactivate(UUID id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private RuntimeSession runtimeSession() {
        NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion("006");
        UUID organizationId = UUID.randomUUID();
        migrator.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", organizationId));
        UUID principalId = new JdbcPrincipalRepository(migrator).createLocal(
                organizationId, "runtime-session-" + UUID.randomUUID() + "@example.test", "{argon2id}stored", false);
        NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion("006");
        SessionService runtimeService = new SessionService(
                new JdbcSessionRepository(runtime), new JdbcPrincipalRepository(runtime), clock);
        SessionToken token = runtimeService.open(new PrincipalRef(principalId));
        Session session = runtimeService.resolve(token).orElseThrow();
        return new RuntimeSession(runtimeService, runtime, token, session);
    }

    private static void assertRuntimeRefused(SqlAction action) {
        assertThatThrownBy(action::run).satisfies(error -> assertSqlState(error, "42501"));
    }

    private static void assertSqlState(Throwable error, String sqlState) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertThat(sql.getSQLState()).isEqualTo(sqlState);
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    private record RuntimeSession(
            SessionService service, NamedParameterJdbcTemplate jdbc, SessionToken token, Session session) {
    }

    @FunctionalInterface
    private interface SqlAction {
        void run();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
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

        void reset(Instant instant) {
            current = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
