package com.solace.sm.cp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionToken;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.identity.CredentialRotationService;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.identity.LoginFixtures;
import com.solace.sm.cp.identity.LoginRateLimitPolicy;
import com.solace.sm.cp.identity.SessionService;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcLoginThrottleRepository;
import com.solace.sm.cp.persistence.JdbcPrincipalRepository;
import com.solace.sm.cp.persistence.JdbcSessionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OR-19}: the {@code sm_control.change_audit} trigger, exercised through the real cp-core
 * services rather than raw SQL, on its OWN container ({@code CACHE_KEY}) so its row-count-sensitive
 * assertions never see another test class's rows.
 */
class ChangeAuditIT {

    private static final String CACHE_KEY = "change-audit-it";

    private final NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    /** The organization every user of a test signs in to; a username is unique per organization (OR-125 (4)). */
    private final UUID organization = LoginFixtures.organization(jdbc);
    private final CredentialHasher hasher = new CredentialHasher();
    private final AuditContext auditContext = new JdbcAuditContext(jdbc);
    private final TransactionOperations transactions =
            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));

    @Test
    void successfulLoginLeavesAuditRowsForItsThrottleClearAndSessionInsertWithLoginSucceeded() {
        String username = "change-audit-success-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        RequestOrigin origin = new RequestOrigin("203.0.113.201", UUID.randomUUID());
        LoginRateLimitPolicy policy = new LoginRateLimitPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        LocalLoginService login = new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), policy, policy, auditContext, transactions, Clock.systemUTC());

        // A prior failure so the throttle row EXISTS: clearFailures() on success only fires the
        // trigger (and so is only auditable) when it actually changes an existing row.
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);

        login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()), origin);

        assertThat(countMatching("""
                table_name = 'login_throttle' AND operation = 'UPDATE' AND audit_operation = 'LOGIN_SUCCEEDED'
                  AND correlation_id = :correlationId AND client_address = :clientAddress
                """, origin)).isEqualTo(1);
        assertThat(countMatching("""
                table_name = 'session' AND operation = 'INSERT' AND audit_operation = 'LOGIN_SUCCEEDED'
                  AND actor_principal_id = :principalId AND correlation_id = :correlationId
                  AND client_address = :clientAddress
                """, origin, principalId)).isEqualTo(1);
    }

    @Test
    void refusedLoginForAnUnknownUsernameLeavesAThrottleInsertAuditRowWithNullActor() {
        String username = "change-audit-unknown-" + UUID.randomUUID() + "@example.test";
        RequestOrigin origin = new RequestOrigin("203.0.113.202", UUID.randomUUID());
        LoginRateLimitPolicy policy = new LoginRateLimitPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        LocalLoginService login = new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), policy, policy, auditContext, transactions, Clock.systemUTC());

        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "candidate".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);

        // OR-20 also records a failure on the CLIENT-ADDRESS dimension in the same transaction, so
        // this filters down to the USERNAME dimension's own row_key specifically.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'INSERT' AND audit_operation = 'LOGIN_REFUSED'
                   AND actor_principal_id IS NULL AND correlation_id = :correlationId
                   AND client_address = :clientAddress AND row_key ->> 'subject_hash' = :subjectHash
                """, new MapSqlParameterSource().addValue("correlationId", origin.correlationId())
                .addValue("clientAddress", origin.clientAddress())
                .addValue("subjectHash", LoginFixtures.usernameSubject(jdbc, organization, username)), Integer.class)).isEqualTo(1);
    }

    /**
     * M1, updated for OR-23: the username dimension is now an alarm, not a gate. An ALREADY-LOCKED
     * username no longer refuses a CORRECT credential (that is proven from the throttle-state side in
     * {@code LocalLoginIT}/{@code LoginAddressThrottleIT}); what stays true, and is proven here, is
     * that a FURTHER WRONG attempt against an already-locked username still leaves its own
     * LOGIN_REFUSED audit row every time (recordFailure fires the OR-19 trigger on every fresh
     * failure, locked or not — unlike the address dimension's OR-24 dedup below, the username
     * dimension is never probed at all).
     */
    @Test
    void aRefusedLoginAgainstALockedUsernameLeavesALoginRefusedAuditRowWithoutChangingTheThrottleRow() {
        String username = "change-audit-locked-username-" + UUID.randomUUID() + "@example.test";
        createLocal(username, "correct credential".toCharArray(), false);
        RequestOrigin origin = new RequestOrigin("203.0.113.205", UUID.randomUUID());
        LoginRateLimitPolicy usernamePolicy = new LoginRateLimitPolicy(1, Duration.ofMinutes(15), Duration.ofMinutes(15));
        LoginRateLimitPolicy looseAddressPolicy =
                new LoginRateLimitPolicy(100, Duration.ofMinutes(15), Duration.ofMinutes(15));
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        LocalLoginService login = new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), usernamePolicy, looseAddressPolicy, auditContext, transactions,
                Clock.systemUTC());
        String subjectHash = LoginFixtures.usernameSubject(jdbc, organization, username);

        // One wrong attempt locks the username (threshold 1) — its OWN audit row is an INSERT (the
        // row did not exist before), not an UPDATE.
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        Map<String, Object> before = throttleRow(subjectHash);

        // Still LOCKED (an alarm, not a gate) — TWO further WRONG attempts are each refused the
        // ordinary way (recordFailure's own INSERT .. ON CONFLICT DO UPDATE path, not a probe), and
        // the throttle row's plateau is unchanged throughout because recordFailure's CASE logic
        // leaves an already-locked row's values exactly as they were — but each is still its OWN
        // genuine UPDATE statement, so each still fires its OWN OR-19 audit row.
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), username, "wrong credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);

        assertThat(throttleRow(subjectHash))
                .as("recordFailure leaves an already-locked row's failure_count/locked_until unchanged")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'UPDATE' AND audit_operation = 'LOGIN_REFUSED'
                   AND actor_principal_id IS NULL AND row_key ->> 'subject_hash' = :subjectHash
                """, Map.of("subjectHash", subjectHash), Integer.class))
                .as("EVERY wrong attempt against the username leaves its own row — no dedup on this dimension "
                        + "(2 of the 3 total attempts are UPDATEs; the first, which created the row, was an INSERT)")
                .isEqualTo(2);

        // OR-23's other half: a CORRECT credential from this SAME (unlocked) address still succeeds
        // despite the username's alarm, and clears it.
        assertThat(login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()), origin))
                .isNotNull();
        assertThat(throttleRow(subjectHash).get("locked_until")).as("success clears the username alarm").isNull();
    }

    /**
     * M1 (still-locked audit) plus OR-24 (once-per-lockout dedup, not once-per-request), against the
     * ADDRESS dimension — the only dimension {@code LocalLoginService} still gates on.
     */
    @Test
    void aRefusedLoginAgainstALockedAddressLeavesExactlyOneAuditRowPerLockoutNotPerRequest() {
        String realUsername = "change-audit-locked-address-real-" + UUID.randomUUID() + "@example.test";
        createLocal(realUsername, "correct credential".toCharArray(), false);
        String address = "203.0.113.206";
        RequestOrigin origin = new RequestOrigin(address, UUID.randomUUID());
        LoginRateLimitPolicy looseUsernamePolicy =
                new LoginRateLimitPolicy(100, Duration.ofMinutes(15), Duration.ofMinutes(15));
        LoginRateLimitPolicy addressPolicy = new LoginRateLimitPolicy(1, Duration.ofMinutes(1), Duration.ofMinutes(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, clock);
        LocalLoginService login = new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), looseUsernamePolicy, addressPolicy, auditContext, transactions,
                clock);
        String subjectHash = LocalLoginService.addressSubjectHash(address);

        // One guess against an unknown username locks the ADDRESS (threshold 1) — that guess's OWN
        // row is an INSERT (the address row did not exist before this), so it never matches the
        // operation = 'UPDATE' filter loginRefusedRowsFor uses below; the count starts at zero.
        String unknownUsername = "change-audit-locked-address-unknown-" + UUID.randomUUID() + "@example.test";
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), unknownUsername, "guess".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        Map<String, Object> before = throttleRow(subjectHash);

        // THREE more refusals against this SAME, still-active lockout — deduped to exactly ONE probe
        // UPDATE, not three.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> login.authenticate(
                    new LocalCredentials(organization.toString(), realUsername, "correct credential".toCharArray()), origin))
                    .isInstanceOf(LoginRefusedException.class);
        }
        assertThat(throttleRow(subjectHash))
                .as("the probe must not change failure_count or locked_until")
                .isEqualTo(before);
        assertThat(loginRefusedRowsFor(subjectHash))
                .as("THREE refusals against the ONE active lockout leave exactly ONE audit row, not three")
                .isEqualTo(1);

        // The lockout expires; a further wrong guess RE-LOCKS the row via recordFailure's own
        // INSERT .. ON CONFLICT DO UPDATE — a genuine value change (failure_count/locked_until), so
        // this transition is its own, always-audited UPDATE: +1 (now 2). Unlike a probe, recordFailure
        // never touches refusal_recorded_for, so it is NOT what OR-24 dedups.
        clock.advance(Duration.ofMinutes(6));
        String secondUnknownUsername = "change-audit-locked-address-unknown-2-" + UUID.randomUUID() + "@example.test";
        assertThatThrownBy(() -> login.authenticate(new LocalCredentials(organization.toString(), secondUnknownUsername, "guess".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(loginRefusedRowsFor(subjectHash))
                .as("the failure that CAUSED the new lockout is its own genuine state change — always audited")
                .isEqualTo(2);

        // The row is locked again (a NEW, later locked_until than the one refusal_recorded_for still
        // holds from lockout #1) — this first probe against lockout #2 fires once more: +1 (now 3).
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(organization.toString(), realUsername, "correct credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(loginRefusedRowsFor(subjectHash))
                .as("the first probe against the NEW lockout is audited once")
                .isEqualTo(3);

        // OR-24's actual dedup guarantee: a REPEAT probe against this SAME, still-active lockout #2
        // leaves the count exactly where it was — no new row.
        assertThatThrownBy(() -> login.authenticate(
                new LocalCredentials(organization.toString(), realUsername, "correct credential".toCharArray()), origin))
                .isInstanceOf(LoginRefusedException.class);
        assertThat(loginRefusedRowsFor(subjectHash))
                .as("a repeat probe against the same lockout is deduped — the count does not move")
                .isEqualTo(3);
    }

    /** {@code failure_count}/{@code locked_until} row_key-filtered LOGIN_REFUSED audit-row count for one subject. */
    private int loginRefusedRowsFor(String subjectHash) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'UPDATE' AND audit_operation = 'LOGIN_REFUSED'
                   AND actor_principal_id IS NULL AND row_key ->> 'subject_hash' = :subjectHash
                """, Map.of("subjectHash", subjectHash), Integer.class);
    }

    /** M2: TRUNCATE bypasses row-level triggers entirely, so it must be refused outright, not audited. */
    @Test
    void ownerTruncateOfSessionFailsBecauseTruncatingControlPlaneStateIsNeverLegitimate() {
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().execute("TRUNCATE TABLE sm_control.session"))
                .satisfies(error -> assertSqlState(error, "P0001"));
    }

    /** M2, second table named in the fix: login_throttle is itself one of the 13 audited tables. */
    @Test
    void ownerTruncateOfLoginThrottleFailsBecauseTruncatingControlPlaneStateIsNeverLegitimate() {
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().execute("TRUNCATE TABLE sm_control.login_throttle"))
                .satisfies(error -> assertSqlState(error, "P0001"));
    }

    /**
     * Fix 4 (MINOR): {@code reject_change_audit_mutation()} used to always say "sm_control.change_audit
     * rows are immutable", even when the table actually refused was a different one (e.g. {@code
     * session}, refused by the {@code truncate_refused} trigger, not by change_audit's own
     * immutability triggers). The message must name {@code TG_TABLE_NAME} and say which rule fired.
     */
    @Test
    void truncateRefusalNamesTheRefusedTableAndWhichRuleFired() {
        String sessionMessage = truncateFailureMessage("sm_control.session");
        assertThat(sessionMessage).as(sessionMessage).contains("session");
        assertThat(sessionMessage).as(sessionMessage)
                .doesNotContain("sm_control.change_audit rows are immutable");

        String changeAuditMessage = truncateFailureMessage("sm_control.change_audit");
        assertThat(changeAuditMessage).as(changeAuditMessage).contains("change_audit").contains("immutable");
    }

    /**
     * M4: the {@code token_hash} exclusion ({@code v_secret_cols} in {@code record_change_audit()})
     * was never actually exercised by a test before this one.
     */
    @Test
    void sessionInsertExcludesTokenHashFromNewValuesRecordsItAsSecretAndNeverLeaksTheHashText() {
        String username = "change-audit-token-hash-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        RequestOrigin origin = new RequestOrigin("203.0.113.207", UUID.randomUUID());
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        LoginRateLimitPolicy policy = new LoginRateLimitPolicy(5, Duration.ofMinutes(15), Duration.ofMinutes(15));
        LocalLoginService login = new LocalLoginService(new JdbcPrincipalRepository(jdbc), sessions, hasher,
                new JdbcLoginThrottleRepository(jdbc), policy, policy, auditContext, transactions, Clock.systemUTC());

        var result = login.authenticate(new LocalCredentials(organization.toString(), username, "correct credential".toCharArray()), origin);
        String tokenHashHex = sessionTokenSha256Hex(result.sessionToken().value());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (new_values ->> 'token_hash') IS NULL AS token_hash_absent,
                       ('token_hash' = ANY (secret_columns_changed)) AS token_hash_is_secret
                  FROM sm_control.change_audit
                 WHERE table_name = 'session' AND operation = 'INSERT' AND actor_principal_id = :principalId
                """, Map.of("principalId", principalId));
        assertThat(row.get("token_hash_absent")).as("new_values must not carry token_hash").isEqualTo(true);
        assertThat(row.get("token_hash_is_secret")).as("token_hash must be named in secret_columns_changed")
                .isEqualTo(true);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit ca WHERE position(:tokenHash IN ca::text) > 0
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHashHex), Integer.class))
                .as("the issued session token's hash must appear nowhere in change_audit")
                .isZero();
    }

    /**
     * M5: {@code CredentialRotationService.clearFailures} used to run in autocommit, OUTSIDE the
     * {@code CREDENTIAL_ROTATED} transaction, so its own audit row carried NULL context.
     */
    @Test
    void aSuccessfulRotationAfterAPriorWrongAttemptClearsTheThrottleAuditedAsCredentialRotatedWithTheCorrelationId() {
        String username = "change-audit-rotate-throttle-" + UUID.randomUUID() + "@example.test";
        char[] current = "current credential".toCharArray();
        UUID principalId = createLocal(username, current, true);
        RequestOrigin origin = new RequestOrigin("203.0.113.208", UUID.randomUUID());
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        Session session = sessions.resolve(token).orElseThrow();
        CredentialRotationService rotation = new CredentialRotationService(new JdbcPrincipalRepository(jdbc),
                new JdbcSessionRepository(jdbc), hasher, new JdbcLoginThrottleRepository(jdbc),
                LoginRateLimitPolicy.LOCAL_DEFAULT, auditContext, transactions, Clock.systemUTC());
        String subjectHash = CredentialRotationService.rotationSubjectHash(principalId);

        // A prior WRONG attempt so the throttle row exists with a nonzero failure_count.
        assertThatThrownBy(() -> rotation.rotate(session, "wrong current credential".toCharArray(),
                "replacement credential value".toCharArray(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);

        rotation.rotate(session, current.clone(), "brand new credential value".toCharArray(), origin);

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'UPDATE' AND audit_operation = 'CREDENTIAL_ROTATED'
                   AND correlation_id = :correlationId AND client_address = :clientAddress
                   AND row_key ->> 'subject_hash' = :subjectHash
                """, new MapSqlParameterSource().addValue("correlationId", origin.correlationId())
                .addValue("clientAddress", origin.clientAddress()).addValue("subjectHash", subjectHash), Integer.class))
                .as("clearFailures' own UPDATE must carry CREDENTIAL_ROTATED and the correlation id, not NULL context")
                .isEqualTo(1);
    }

    /**
     * Fix 1 (MAJOR), extended for OR-24: a rotation against an ALREADY-LOCKED rotation subject must
     * still leave a CREDENTIAL_ROTATION_REFUSED audit row — mirroring M1's login-side proof above —
     * but, per OR-24, AT MOST ONCE PER LOCKOUT, never once per refused request, and never by calling
     * the hasher at all.
     */
    @Test
    void aRotationAgainstALockedRotationSubjectLeavesExactlyOneAuditRowPerLockoutNotPerRequest() {
        String username = "change-audit-rotate-locked-" + UUID.randomUUID() + "@example.test";
        char[] current = "current credential".toCharArray();
        UUID principalId = createLocal(username, current, true);
        RequestOrigin origin = new RequestOrigin("203.0.113.209", UUID.randomUUID());
        LoginRateLimitPolicy strictPolicy = new LoginRateLimitPolicy(1, Duration.ofMinutes(1), Duration.ofMinutes(5));
        MutableClock clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, clock);
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        Session session = sessions.resolve(token).orElseThrow();
        CredentialRotationService rotation = new CredentialRotationService(new JdbcPrincipalRepository(jdbc),
                new JdbcSessionRepository(jdbc), hasher, new JdbcLoginThrottleRepository(jdbc), strictPolicy,
                auditContext, transactions, clock);
        String subjectHash = CredentialRotationService.rotationSubjectHash(principalId);

        // One wrong attempt locks the rotation subject (threshold 1) — its OWN row is an INSERT (the
        // subject row did not exist before this), so it never matches the operation = 'UPDATE' filter
        // credentialRotationRefusedRowsFor uses below; the count starts at zero.
        assertThatThrownBy(() -> rotation.rotate(session, "wrong current credential".toCharArray(),
                "replacement credential value".toCharArray(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
        Map<String, Object> before = throttleRow(subjectHash);

        // THREE more refusals against this SAME, still-active lockout — deduped to exactly ONE probe
        // UPDATE, not three.
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> rotation.rotate(session, current.clone(),
                    "another replacement value".toCharArray(), origin))
                    .isInstanceOf(CredentialRotationRefusedException.class);
        }
        assertThat(throttleRow(subjectHash))
                .as("the probe must not change failure_count or locked_until")
                .isEqualTo(before);
        assertThat(credentialRotationRefusedRowsFor(subjectHash, principalId))
                .as("THREE refusals against the ONE active lockout leave exactly ONE audit row, not three")
                .isEqualTo(1);

        // The lockout expires; a further wrong current credential RE-LOCKS the row via
        // recordFailure's own INSERT .. ON CONFLICT DO UPDATE — a genuine value change, so this
        // transition is its own, always-audited UPDATE: +1 (now 2). recordFailure never touches
        // refusal_recorded_for, so it is NOT what OR-24 dedups.
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> rotation.rotate(session, "wrong current credential".toCharArray(),
                "replacement credential value".toCharArray(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
        assertThat(credentialRotationRefusedRowsFor(subjectHash, principalId))
                .as("the failure that CAUSED the new lockout is its own genuine state change — always audited")
                .isEqualTo(2);

        // Locked again (a NEW, later locked_until than the one refusal_recorded_for still holds from
        // lockout #1) — this first probe against lockout #2 fires once more: +1 (now 3).
        assertThatThrownBy(() -> rotation.rotate(session, current.clone(),
                "another replacement value".toCharArray(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
        assertThat(credentialRotationRefusedRowsFor(subjectHash, principalId))
                .as("the first probe against the NEW lockout is audited once")
                .isEqualTo(3);

        // OR-24's actual dedup guarantee: a REPEAT probe against this SAME, still-active lockout #2
        // leaves the count exactly where it was — no new row.
        assertThatThrownBy(() -> rotation.rotate(session, current.clone(),
                "yet another replacement value".toCharArray(), origin))
                .isInstanceOf(CredentialRotationRefusedException.class);
        assertThat(credentialRotationRefusedRowsFor(subjectHash, principalId))
                .as("a repeat probe against the same lockout is deduped — the count does not move")
                .isEqualTo(3);
    }

    private int credentialRotationRefusedRowsFor(String subjectHash, UUID principalId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'UPDATE'
                   AND audit_operation = 'CREDENTIAL_ROTATION_REFUSED'
                   AND actor_principal_id = :principalId AND row_key ->> 'subject_hash' = :subjectHash
                """, new MapSqlParameterSource().addValue("principalId", principalId)
                .addValue("subjectHash", subjectHash), Integer.class);
    }

    @Test
    void rotationAuditsAPrincipalUpdateNamingCredentialHashWithoutEverStoringItsValue() {
        String username = "change-audit-rotate-" + UUID.randomUUID() + "@example.test";
        char[] current = "current credential".toCharArray();
        char[] replacement = "replacement credential value".toCharArray();
        UUID principalId = createLocal(username, current, true);
        RequestOrigin origin = new RequestOrigin("203.0.113.203", UUID.randomUUID());
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        Session session = sessions.resolve(token).orElseThrow();
        CredentialRotationService rotation = new CredentialRotationService(new JdbcPrincipalRepository(jdbc),
                new JdbcSessionRepository(jdbc), hasher, new JdbcLoginThrottleRepository(jdbc),
                LoginRateLimitPolicy.LOCAL_DEFAULT, auditContext, transactions, Clock.systemUTC());
        String oldHash = hasher.hash(current);

        rotation.rotate(session, current.clone(), replacement.clone(), origin);

        String newHash = jdbc.queryForObject("SELECT credential_hash FROM sm_control.principal WHERE id = :id",
                Map.of("id", principalId), String.class);

        assertThat(countMatching("""
                table_name = 'principal' AND operation = 'UPDATE' AND audit_operation = 'CREDENTIAL_ROTATED'
                  AND actor_principal_id = :principalId AND 'credential_hash' = ANY (secret_columns_changed)
                """, origin, principalId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE position(:oldHash IN COALESCE(old_values::text, '')) > 0
                    OR position(:oldHash IN COALESCE(new_values::text, '')) > 0
                    OR position(:newHash IN COALESCE(old_values::text, '')) > 0
                    OR position(:newHash IN COALESCE(new_values::text, '')) > 0
                """, new MapSqlParameterSource().addValue("oldHash", oldHash).addValue("newHash", newHash), Integer.class))
                .as("neither the old nor the new credential hash string appears anywhere in change_audit")
                .isZero();
    }

    @Test
    void logoutAuditsASessionUpdateWithLogoutAttributedToTheSessionsPrincipal() {
        String username = "change-audit-logout-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        RequestOrigin origin = new RequestOrigin("203.0.113.204", UUID.randomUUID());
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        Session session = sessions.resolve(token).orElseThrow();

        sessions.revoke(session.id(), origin);

        assertThat(countMatching("""
                table_name = 'session' AND operation = 'UPDATE' AND audit_operation = 'LOGOUT'
                  AND actor_principal_id = :principalId AND correlation_id = :correlationId
                  AND client_address = :clientAddress
                """, origin, principalId)).isEqualTo(1);
    }

    @Test
    void resolvingAndTouchingASessionLeavesNoAdditionalAuditRow() {
        String username = "change-audit-touch-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        RequestOrigin origin = new RequestOrigin("203.0.113.210", UUID.randomUUID());
        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                auditContext, transactions, Clock.systemUTC());
        SessionToken token = sessions.open(new PrincipalRef(principalId), origin);
        Session opened = sessions.resolve(token).orElseThrow();
        long rowsAfterOpen = countSessionRowsFor(opened.id().value());
        assertThat(rowsAfterOpen).isEqualTo(1);

        sessions.resolve(token);
        sessions.resolve(token);

        assertThat(countSessionRowsFor(opened.id().value())).isEqualTo(rowsAfterOpen);
    }

    /**
     * OR-25 flips this from the pre-OR-25 behaviour ({@code aMalformedAuditContextNeverBlocksTheBusinessWrite}):
     * a malformed correlation id from sm_cp_runtime used to be silently nulled and tolerated; it is
     * now a reason to refuse the business write outright, aborting the transaction. Operation and
     * client address are deliberately VALID here, so this isolates the correlation-id check.
     */
    @Test
    void aMalformedCorrelationIdFromTheRuntimeIsRefused() {
        String username = "change-audit-malformed-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    // LOGIN_REFUSED is one of the two operations that tolerates a NULL actor, so this
                    // probe needs no valid principal id to isolate the correlation-id check alone.
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.operation', 'LOGIN_REFUSED', true)", String.class);
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.client_address', '203.0.113.213', true)", String.class);
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.correlation_id', 'not-a-uuid', true)", String.class);
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own — the transaction never committed "
                        + "(createLocal's own INSERT row is unaffected and deliberately excluded here)")
                .isZero();
    }

    /**
     * OR-25 flips this from the pre-OR-25 behaviour
     * ({@code aDirectRuntimeUpdateOutsideAnyServiceIsStillAuditedWithNullContext}): a runtime write
     * with NO audit context at all used to be tolerated and audited with NULL context; it is now
     * refused outright.
     */
    @Test
    void aDirectRuntimeUpdateWithNoAuditContextIsRefused() {
        String username = "change-audit-direct-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);

        assertThatThrownBy(() -> runtime.update(
                "UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                Map.of("id", principalId)))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own — the transaction never committed "
                        + "(createLocal's own INSERT row is unaffected and deliberately excluded here)")
                .isZero();
    }

    /** OR-25: an unknown audit_operation from the runtime is refused with the new OR-25 message. */
    @Test
    void anUnknownAuditOperationFromTheRuntimeIsRefusedWithTheOr25Message() {
        String username = "change-audit-unknown-op-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        UUID correlationId = UUID.randomUUID();
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.operation', 'NOT_A_REAL_OPERATION', true)", String.class);
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.correlation_id', '" + correlationId + "', true)", String.class);
                    runtime.getJdbcTemplate().queryForObject(
                            "SELECT set_config('sm.audit.client_address', '203.0.113.214', true)", String.class);
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> {
                    assertSqlState(error, "OR025");
                    assertThat(sqlExceptionMessage(error)).contains("OR-25").contains("audit_operation");
                });

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class)).isZero();
    }

    /**
     * (m3) The LOGIN_REFUSED/LOCKOUT_CLEARED actor exemption is scoped to login_throttle ONLY:
     * neither production path that uses either operation (LocalLoginService's refusal recording,
     * SmControlLockoutRecoveryMain's clearing) ever writes any other audited table. A runtime write
     * to a DIFFERENT table under LOGIN_REFUSED with a NULL actor must still be refused, exactly as
     * if the operation were not exempt at all.
     */
    @Test
    void aRuntimeWriteToANonThrottleTableUnderLoginRefusedWithNullActorIsRefused() {
        String username = "change-audit-refused-wrong-table-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    runtimeAuditContext.set(null, AuditOperations.LOGIN_REFUSED, UUID.randomUUID(), "203.0.113.221");
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own — the transaction never committed "
                        + "(createLocal's own INSERT row is unaffected and deliberately excluded here)")
                .isZero();
    }

    /**
     * (m3) The same restriction, proven against LOCKOUT_CLEARED — the operation
     * SmControlLockoutRecoveryMain uses, and (like LOGIN_REFUSED above) never against any table but
     * login_throttle.
     */
    @Test
    void aRuntimeWriteToANonThrottleTableUnderLockoutClearedWithNullActorIsRefused() {
        String username = "change-audit-lockout-cleared-wrong-table-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    runtimeAuditContext.set(null, AuditOperations.LOCKOUT_CLEARED, UUID.randomUUID(), "203.0.113.222");
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own")
                .isZero();
    }

    /**
     * (m4a) The CLIENT-ADDRESS branch, isolated: a known operation and a well-formed correlation id
     * and actor are all present, but no client address was ever set. Every existing malformed-context
     * test fails EARLIER (on operation or correlation), so none of them actually reaches this branch.
     */
    @Test
    void aMissingClientAddressFromTheRuntimeIsRefused() {
        String username = "change-audit-no-client-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    runtimeAuditContext.set(principalId, AuditOperations.LOGIN_SUCCEEDED, UUID.randomUUID(), null);
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own")
                .isZero();
    }

    /**
     * (m4b) The ACTOR branch for a NON-exempt operation, isolated: correlation id and client
     * address are both present and valid, only the actor is NULL, and the operation (LOGOUT) is
     * neither LOGIN_REFUSED nor LOCKOUT_CLEARED — so this is refused by the FIRST actor check, not
     * the m3 table-restriction added above.
     */
    @Test
    void aKnownNonExemptOperationWithCorrelationAndClientButNoActorIsRefused() {
        String username = "change-audit-no-actor-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);
        TransactionOperations runtimeTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));
        AuditContext runtimeAuditContext = new JdbcAuditContext(runtime);

        assertThatThrownBy(() -> runtimeTransactions.executeWithoutResult(status -> {
                    runtimeAuditContext.set(null, AuditOperations.LOGOUT, UUID.randomUUID(), "203.0.113.223");
                    runtime.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                            Map.of("id", principalId));
                }))
                .satisfies(error -> assertSqlState(error, "OR025"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'principal' AND operation = 'UPDATE'
                 AND row_key ->> 'id' = :principalId
                """, Map.of("principalId", principalId.toString()), Integer.class))
                .as("the refused UPDATE leaves no audit row of its own")
                .isZero();
    }

    /** OR-25: sm_cp_migrator/sm_cp_owner writes with NO audit context still succeed, exactly as before. */
    @Test
    void anOwnerWriteWithNoAuditContextStillSucceedsAndIsAudited() {
        String username = "change-audit-owner-no-context-" + UUID.randomUUID() + "@example.test";
        UUID principalId = createLocal(username, "correct credential".toCharArray(), false);

        jdbc.update("UPDATE sm_control.principal SET must_rotate = must_rotate WHERE id = :id",
                Map.of("id", principalId));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'principal' AND operation = 'UPDATE'
                   AND row_key ->> 'id' = :principalId
                   AND actor_principal_id IS NULL AND audit_operation IS NULL
                   AND correlation_id IS NULL AND client_address IS NULL
                   AND db_role = 'sm_cp_migrator'
                """, Map.of("principalId", principalId.toString()), Integer.class)).isEqualTo(1);
    }

    @Test
    void theRuntimeRoleCanSelectButNeverInsertUpdateOrDeleteChangeAudit() {
        assertThat(runtime.queryForObject("SELECT count(*) FROM sm_control.change_audit", Map.of(), Integer.class))
                .isNotNull();

        assertRefused42501(() -> runtime.getJdbcTemplate().update("""
                INSERT INTO sm_control.change_audit (recorded_at, txid, table_name, operation, row_key,
                    changed_columns, secret_columns_changed, db_role)
                VALUES (now(), 0, 'probe', 'INSERT', '{}'::jsonb, ARRAY[]::text[], ARRAY[]::text[], 'probe')
                """));
        assertRefused42501(() -> runtime.getJdbcTemplate()
                .update("UPDATE sm_control.change_audit SET table_name = table_name"));
        assertRefused42501(() -> runtime.getJdbcTemplate().update("DELETE FROM sm_control.change_audit"));
    }

    /**
     * The table OWNER ({@code sm_cp_migrator}, a member of {@code sm_cp_owner}) always bypasses
     * GRANT/REVOKE — {@code theRuntimeRoleCanSelectButNeverInsertUpdateOrDeleteChangeAudit} above
     * proves only the grant-based half. This proves the design decision the migration's own comment
     * claims: the {@code BEFORE UPDATE/DELETE} triggers refuse the OWNER path too, since a trigger
     * fires regardless of privilege.
     */
    @Test
    void evenTheSchemaOwnerCannotUpdateOrDeleteChangeAuditBecauseTheImmutabilityTriggerIgnoresPrivilege() {
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("UPDATE sm_control.change_audit SET table_name = table_name"))
                .satisfies(error -> assertSqlState(error, "P0001"));
        assertThatThrownBy(() -> jdbc.getJdbcTemplate().update("DELETE FROM sm_control.change_audit"))
                .satisfies(error -> assertSqlState(error, "P0001"));
    }

    /** {@code failure_count}/{@code locked_until} for a throttle row — used to prove a probe is a no-op. */
    private Map<String, Object> throttleRow(String subjectHash) {
        return jdbc.queryForMap("""
                SELECT failure_count, locked_until FROM sm_control.login_throttle WHERE subject_hash = :subjectHash
                """, Map.of("subjectHash", subjectHash));
    }

    /** M4: reproduces {@code SessionService}'s OWN private token-hash algorithm — SHA-256 hex, US-ASCII. */
    private static String sessionTokenSha256Hex(String tokenValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(tokenValue.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private UUID createLocal(String username, char[] credential, boolean mustRotate) {
        return new JdbcPrincipalRepository(jdbc).createLocal(organization, username, hasher.hash(credential), mustRotate);
    }

    private long countSessionRowsFor(UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'session' AND row_key ->> 'session_id' = :sessionId
                """, Map.of("sessionId", sessionId.toString()), Long.class);
    }

    private int countMatching(String whereClause, RequestOrigin origin) {
        return countMatching(whereClause, origin, null);
    }

    private int countMatching(String whereClause, RequestOrigin origin, UUID principalId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("correlationId", origin.correlationId())
                .addValue("clientAddress", origin.clientAddress());
        if (principalId != null) {
            params.addValue("principalId", principalId);
        }
        return jdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE " + whereClause, params,
                Integer.class);
    }

    private void assertRefused42501(Runnable action) {
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

    /** Fix 4: the refused table's own trigger message, for asserting it names the table and rule. */
    private String truncateFailureMessage(String qualifiedTable) {
        try {
            jdbc.getJdbcTemplate().execute("TRUNCATE TABLE " + qualifiedTable);
        } catch (RuntimeException error) {
            assertSqlState(error, "P0001");
            return sqlExceptionMessage(error);
        }
        throw new AssertionError("expected TRUNCATE to be refused: " + qualifiedTable);
    }

    private static String sqlExceptionMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                return sql.getMessage();
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException", error);
    }

    /** OR-24's once-per-lockout dedup needs a controllable clock to force a lockout to expire. */
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
