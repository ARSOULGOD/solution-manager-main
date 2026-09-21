package com.solace.sm.cp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BootstrapAdminIT {

    private static final UUID BOOTSTRAP_PRINCIPAL_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final UUID INSTALL_SCOPE_ID = UUID.fromString("00000000-0000-4000-8000-000000000010");

    private final CredentialHasher hasher = new CredentialHasher();
    private NamedParameterJdbcTemplate jdbc;
    private NamedParameterJdbcTemplate runtime;
    private SessionService runtimeSessions;
    private char[] bootstrapCredential;

    @BeforeEach
    void setUp() {
        jdbc = SmControlPostgres.jdbcAtVersion("009");
        runtime = SmControlPostgres.runtimeJdbcAtVersion("009");
        bootstrapCredential = SmControlPostgres.bootstrapCredentialAtVersion("009");
        jdbc.update("DELETE FROM sm_control.session WHERE principal_id = :principalId",
                Map.of("principalId", BOOTSTRAP_PRINCIPAL_ID));
        jdbc.update("""
                UPDATE sm_control.principal
                   SET credential_hash = :credentialHash,
                       must_rotate = true,
                       credential_failure_count = 0
                 WHERE id = :principalId
                """, new MapSqlParameterSource().addValue("principalId", BOOTSTRAP_PRINCIPAL_ID)
                .addValue("credentialHash", SmControlPostgres.bootstrapCredentialHashAtVersion("009")));
        runtimeSessions = new SessionService(new JdbcSessionRepository(runtime), new JdbcPrincipalRepository(runtime),
                Clock.systemUTC());
    }

    @Test
    void phaseZeroArtifactAndV007SeedOneInstallScopeAndBootstrapAdministrator() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.scope", Map.of(), Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT kind FROM sm_control.scope WHERE scope_id = :scopeId",
                Map.of("scopeId", INSTALL_SCOPE_ID), String.class)).isEqualTo("install");
        assertThat(jdbc.queryForObject("SELECT must_rotate FROM sm_control.principal WHERE id = :principalId",
                Map.of("principalId", BOOTSTRAP_PRINCIPAL_ID), Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sm_control.role_binding rb
                  JOIN sm_control.role r ON r.role_id = rb.role_id
                 WHERE rb.principal_id = :principalId
                   AND rb.scope_id = :scopeId
                   AND r.name = 'Administrator'
                """, Map.of("principalId", BOOTSTRAP_PRINCIPAL_ID, "scopeId", INSTALL_SCOPE_ID), Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT has_schema_privilege('sm_cp_runtime', 'sm_control', 'USAGE')",
                Map.of(), Boolean.class)).isTrue();
    }

    @Test
    void singletonInstallBootstrapDoesNotPreventAdditionalOrganizations() {
        jdbc.update("INSERT INTO sm_control.organization (organization_id) VALUES (:id)",
                new MapSqlParameterSource("id", UUID.randomUUID()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sm_control.scope WHERE kind = 'install'",
                Map.of(), Integer.class)).isEqualTo(1);
    }

    @Test
    void suppliedBootstrapHashAuthenticatesButNoPlaintextAppearsInTheMigration() throws Exception {
        String migration = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/sm_control/V007__bootstrap_admin.sql").readAllBytes());
        PrincipalRepository.Principal principal = new JdbcPrincipalRepository(runtime)
                .find(BOOTSTRAP_PRINCIPAL_ID).orElseThrow();

        assertThat(hasher.verify(bootstrapCredential, SmControlPostgres.bootstrapCredentialHashAtVersion("009"))).isTrue();
        assertThat(principal.toString()).doesNotContain(principal.credentialHash());
        assertThat(migration).contains("${bootstrap_credential_hash}")
                .doesNotContain(String.valueOf(bootstrapCredential))
                .doesNotContainPattern("(?i)password\\s*=\\s*'");
    }

    @Test
    void mustRotateBlocksEveryCatalogVerbAndAllowsOnlyTheThreeLocalOperations() {
        MustRotateGate gate = new MustRotateGate();
        Session session = openBootstrapSession().session();

        for (Permission permission : Permission.values()) {
            assertThatThrownBy(() -> gate.check(session, permission))
                    .isInstanceOf(CredentialRotationRequiredException.class);
        }
        assertThat(LocalOperation.values()).hasSize(3);
        for (LocalOperation operation : LocalOperation.values()) {
            assertThatNoException().isThrownBy(() -> gate.check(session, operation));
        }
        assertThatThrownBy(() -> gate.check(session)).isInstanceOf(CredentialRotationRequiredException.class);
        assertThatNoException().isThrownBy(() -> gate.check(notRotatingSession(), Permission.SOLUTION_VIEW));
    }

    @Test
    void runtimeRotationClearsMustRotateInvalidatesTheOldCredentialAndRetainsOnlyTheCurrentSession() {
        OpenedSession current = openBootstrapSession();
        OpenedSession other = openBootstrapSession();
        char[] replacement = opaqueCredential();

        rotationService(new JdbcSessionRepository(runtime)).rotate(current.session(), bootstrapCredential, replacement);

        assertThat(runtimeSessions.resolve(current.token()).orElseThrow().mustRotate()).isFalse();
        assertThat(runtimeSessions.resolve(other.token())).isEmpty();
        String storedHash = jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), String.class);
        assertThat(hasher.verify(bootstrapCredential, storedHash)).isFalse();
        assertThat(hasher.verify(replacement, storedHash)).isTrue();
    }

    @Test
    void wrongCurrentCredentialIncrementsTheCounterWhileLeavingRotationRequired() {
        OpenedSession current = openBootstrapSession();

        assertThatThrownBy(() -> rotationService(new JdbcSessionRepository(runtime)).rotate(
                current.session(), opaqueCredential(), opaqueCredential()))
                .isInstanceOf(CredentialRotationRefusedException.class);

        assertThat(jdbc.queryForObject("SELECT credential_failure_count FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), Integer.class)).isEqualTo(1);
        assertThat(runtimeSessions.resolve(current.token()).orElseThrow().mustRotate()).isTrue();
    }

    @Test
    void rotatingToTheSameCredentialIsRefusedWithoutClearingTheRotationRequirement() {
        OpenedSession current = openBootstrapSession();

        assertThatThrownBy(() -> rotationService(new JdbcSessionRepository(runtime)).rotate(
                current.session(), bootstrapCredential, bootstrapCredential))
                .isInstanceOf(CredentialRotationRefusedException.class);

        assertThat(runtimeSessions.resolve(current.token()).orElseThrow().mustRotate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT credential_failure_count FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), Integer.class)).isZero();
    }

    @Test
    void aRevokedCurrentSessionCannotRotate() {
        OpenedSession current = openBootstrapSession();
        runtimeSessions.revoke(current.session().id());

        assertThatThrownBy(() -> rotationService(new JdbcSessionRepository(runtime)).rotate(
                current.session(), bootstrapCredential, opaqueCredential()))
                .isInstanceOf(CredentialRotationRefusedException.class);
        assertThat(jdbc.queryForObject("SELECT must_rotate FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), Boolean.class)).isTrue();
    }

    @Test
    void aFailureAfterTheCredentialCasRollsBackBothTheHashAndRotationFlag() {
        OpenedSession current = openBootstrapSession();
        String before = jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), String.class);
        SessionRepository failingSessions = new FailingRevocationRepository(new JdbcSessionRepository(runtime));

        assertThatThrownBy(() -> rotationService(failingSessions).rotate(
                current.session(), bootstrapCredential, opaqueCredential()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT must_rotate FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), Boolean.class)).isTrue();
    }

    @Test
    void aSessionRevokedBetweenThePrecheckAndRotationTransactionCannotRotate() {
        OpenedSession current = openBootstrapSession();
        String before = jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), String.class);

        assertThatThrownBy(() -> rotationService(new SecondCheckRefusesRepository(new JdbcSessionRepository(runtime))).rotate(
                current.session(), bootstrapCredential, opaqueCredential()))
                .isInstanceOf(CredentialRotationRefusedException.class);

        assertThat(jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), String.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT must_rotate FROM sm_control.principal WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID), Boolean.class)).isTrue();
    }

    @Test
    void runtimeCannotUpdateAPrincipalColumnOutsideTheAdditiveRotationGrant() {
        for (String table : new String[] {
                "scope", "organization", "organization_unit", "environment", "solution", "instance"}) {
            assertThat(runtime.queryForObject("SELECT count(*) FROM sm_control." + table, Map.of(), Integer.class))
                    .isNotNull();
        }
        assertThatThrownBy(() -> runtime.update("UPDATE sm_control.principal SET username = username WHERE id = :id",
                Map.of("id", BOOTSTRAP_PRINCIPAL_ID))).satisfies(error -> assertSqlState(error, "42501"));
    }

    private OpenedSession openBootstrapSession() {
        SessionToken token = runtimeSessions.open(new PrincipalRef(BOOTSTRAP_PRINCIPAL_ID));
        return new OpenedSession(token, runtimeSessions.resolve(token).orElseThrow());
    }

    private CredentialRotationService rotationService(SessionRepository sessionRepository) {
        return new CredentialRotationService(new JdbcPrincipalRepository(runtime), sessionRepository, hasher,
                new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource())),
                Clock.systemUTC());
    }

    private static Session notRotatingSession() {
        Instant now = Instant.now();
        return new Session(new SessionId(UUID.randomUUID()), new PrincipalRef(UUID.randomUUID()), UUID.randomUUID(), false,
                now, now, now.plus(Duration.ofMinutes(30)), now.plus(Duration.ofHours(12)));
    }

    private static char[] opaqueCredential() {
        return UUID.randomUUID().toString().toCharArray();
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

    private record OpenedSession(SessionToken token, Session session) {
    }

    private static class FailingRevocationRepository implements SessionRepository {
        private final SessionRepository delegate;

        protected FailingRevocationRepository(SessionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean createForActivePrincipal(Session session, String tokenHash) {
            return delegate.createForActivePrincipal(session, tokenHash);
        }

        @Override
        public Optional<Session> resolveAndTouch(String tokenHash, Instant now, Duration idleTimeout) {
            return delegate.resolveAndTouch(tokenHash, now, idleTimeout);
        }

        @Override
        public void revoke(SessionId id, Instant now) {
            delegate.revoke(id, now);
        }

        @Override
        public void revokeAllFor(PrincipalRef principal, Instant now) {
            delegate.revokeAllFor(principal, now);
        }

        @Override
        public void revokeAllExceptFor(PrincipalRef principal, SessionId retainedSession, Instant now) {
            throw new IllegalStateException("test revocation failure");
        }

        @Override
        public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
            return delegate.lockActiveFor(id, principal, now);
        }

        @Override
        public Optional<String> activeTokenHash(SessionId id, Instant now) {
            return delegate.activeTokenHash(id, now);
        }
    }

    private static final class SecondCheckRefusesRepository extends FailingRevocationRepository {
        private boolean firstCheck = true;

        private SecondCheckRefusesRepository(SessionRepository delegate) {
            super(delegate);
        }

        @Override
        public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
            if (firstCheck) {
                firstCheck = false;
                return super.lockActiveFor(id, principal, now);
            }
            return false;
        }
    }
}
