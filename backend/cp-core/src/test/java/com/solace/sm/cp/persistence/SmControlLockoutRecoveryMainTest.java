package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.ClientAddresses;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.identity.LoginFixtures;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link SmControlLockoutRecoveryMain}'s environment contract (mirroring
 * {@link SmControlMigrationMainTest}'s style), and the actual clearing against a migrated database.
 */
class SmControlLockoutRecoveryMainTest {

    private static final String PASSWORD = "s3cr3t-runtime-pw";
    private static final String CACHE_KEY = "lockout-recovery-it";

    @Test
    void aMissingUrlExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = completeWithUsername();
        env.remove(SmControlLockoutRecoveryMain.URL_VARIABLE);
        assertMissing(SmControlLockoutRecoveryMain.URL_VARIABLE, env);
    }

    @Test
    void aMissingUsernameExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = completeWithUsername();
        env.remove(SmControlLockoutRecoveryMain.USERNAME_VARIABLE);
        assertMissing(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, env);
    }

    @Test
    void aBlankPasswordCountsAsMissingBecauseAnEmptySecretIsAlmostAlwaysAnUnsetOne() {
        Map<String, String> env = completeWithUsername();
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, "   ");
        assertMissing(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, env);
    }

    @Test
    void aMalformedUrlIsRefusedBeforeAnyConnectionAttemptAndNeverEchoed() {
        Map<String, String> env = completeWithUsername();
        String sentinel = "SNTL-lockout-4b7e";
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm?password=" + sentinel);

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.URL_VARIABLE), run.stderr());
        assertFalse(run.stderr().contains(sentinel), run.stderr());
    }

    @Test
    void noSelectorVariableExitsTwoNamingAllThree() {
        Map<String, String> env = complete();
        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE), run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE), run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_PRINCIPAL_ID_VARIABLE), run.stderr());
    }

    @Test
    void twoSelectorVariablesAtOnceExitsTwo() {
        Map<String, String> env = complete();
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, "someone@example.test");
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE, "203.0.113.1");

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains("found 2"), run.stderr());
    }

    @Test
    void aMalformedPrincipalIdSelectorExitsTwo() {
        Map<String, String> env = complete();
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_PRINCIPAL_ID_VARIABLE, "not-a-uuid");

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_PRINCIPAL_ID_VARIABLE), run.stderr());
    }

    /**
     * m6: the operator types the SHORT IPv6 spelling ({@code "::1"}); the lockout was recorded under
     * the LONG spelling ({@code "0:0:0:0:0:0:0:1"}) a servlet container actually reports — the exact
     * pair {@link com.solace.sm.cp.api.audit.ClientAddresses}'s own javadoc names. Both must resolve
     * to the SAME throttle row.
     */
    @Test
    void aClientAddressSelectorIsCanonicalisedSoDifferentSpellingsClearTheSameLockout() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String recordedAddress = ClientAddresses.canonicalize("0:0:0:0:0:0:0:1").orElseThrow();
        String subjectHash = LocalLoginService.addressSubjectHash(recordedAddress);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE, "::1");

        Run run = run(env);
        assertEquals(0, run.code, run.stderr());
        assertTrue(run.stdout().contains("cleared 1"), run.stdout());

        Integer failureCount = jdbc.queryForObject(
                "SELECT failure_count FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(0, failureCount);
    }

    /**
     * M1: the client-address selector now clears by {@code /64} for IPv6, not by exact address
     * (see {@code ClientAddresses.throttleKey}) — an operator who types a DIFFERENT address within
     * the SAME {@code /64} the lockout was recorded under (a sibling address, never itself seen by
     * any request) must still clear the one lockout the whole {@code /64} shares.
     */
    @Test
    void aSiblingAddressInTheSameSlash64ClearsTheLockout() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String lockedAddress = "2001:db8:cccc:dddd::1";
        String siblingAddress = "2001:db8:cccc:dddd::2";
        String subjectHash = LocalLoginService.addressSubjectHash(lockedAddress);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE, siblingAddress);

        Run run = run(env);
        assertEquals(0, run.code, run.stderr());
        assertTrue(run.stdout().contains("cleared 1"), run.stdout());

        Integer failureCount = jdbc.queryForObject(
                "SELECT failure_count FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(0, failureCount);
    }

    @Test
    void aNonLiteralClientAddressSelectorExitsTwoWithoutAttemptingAnyConnection() {
        Map<String, String> env = complete();
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE, "not-an-address");

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_CLIENT_ADDRESS_VARIABLE), run.stderr());
    }

    /** m6: the username selector is trimmed before hashing, mirroring the address selector's canonicalisation. The organization is NOT (W5D-7). */
    @Test
    void aUsernameSelectorWithSurroundingWhitespaceIsTrimmedBeforeHashing() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String username = "lockout-recovery-trim-" + UUID.randomUUID() + "@example.test";
        UUID organization = LoginFixtures.organization(jdbc);
        String subjectHash = LocalLoginService.loginSubjectHash(organization, username);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, "  " + username + "  ");
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, organization.toString());

        Run run = run(env);
        assertEquals(0, run.code, run.stderr());
        assertTrue(run.stdout().contains("cleared 1"), run.stdout());
    }

    /**
     * W5D-7: login does not trim the organization (surrounding whitespace is a 400 there), so the command must not either:
     * a padded organization is an unresolved one, whose subject nothing was counted under, and clears nothing.
     */
    @Test
    void anOrganizationWithSurroundingWhitespaceIsNotTrimmedAndSoClearsNothing() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String username = "lockout-recovery-org-ws-" + UUID.randomUUID() + "@example.test";
        UUID organization = LoginFixtures.organization(jdbc);
        String subjectHash = LocalLoginService.loginSubjectHash(organization, username);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY));
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY));
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, "  " + organization + "  ");

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_NO_ACTIVE_LOCKOUT, run.code, run.stderr());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT locked_until > now() FROM sm_control.login_throttle"
                + " WHERE subject_hash = :h", new MapSqlParameterSource("h", subjectHash), Boolean.class));
    }

    /** W5D-3: a lockout recorded for an EXISTING principal is keyed by its id, and the command finds it by any case. */
    @Test
    void anExistingPrincipalsLockoutIsFoundByItsIdWhateverTheCaseTyped() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        UUID organization = LoginFixtures.organization(jdbc);
        String username = "Lockout-Principal-" + UUID.randomUUID();
        UUID principalId = new JdbcPrincipalRepository(jdbc).createLocal(organization, username, "hash", false);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource()
                .addValue("subjectHash", LocalLoginService.principalSubjectHash(principalId))
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY));
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY));
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username.toUpperCase(java.util.Locale.ROOT));
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, organization.toString());

        Run run = run(env);

        assertEquals(0, run.code, run.stderr());
    }

    @Test
    void aLockedUsernameIsClearedAndTheClearingIsAudited() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String username = "lockout-recovery-" + UUID.randomUUID() + "@example.test";
        UUID organization = LoginFixtures.organization(jdbc);
        String subjectHash = LocalLoginService.loginSubjectHash(organization, username);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, organization.toString());

        Run run = run(env);
        assertEquals(0, run.code, run.stderr());
        assertTrue(run.stdout().contains("cleared 1"), run.stdout());

        Integer failureCount = jdbc.queryForObject(
                "SELECT failure_count FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(0, failureCount);

        Integer auditRows = jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND operation = 'UPDATE'
                   AND audit_operation = 'LOCKOUT_CLEARED' AND actor_principal_id IS NULL
                   AND client_address = 'operator-cli' AND row_key ->> 'subject_hash' = :subjectHash
                """, new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(1, auditRows);
    }

    /** External finding 4: an absent row has no active lockout — the new, distinct exit code, not 0. */
    @Test
    void clearingANonexistentLockoutExitsTheNoActiveLockoutCodeAndClearsNothing() {
        // The two-arg call must run before any single-arg accessor for this cache key is the FIRST
        // one touching it in the whole test run — see SmControlPostgres.jdbcAtVersion's own javadoc.
        SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE,
                "no-such-lockout-" + UUID.randomUUID() + "@example.test");
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, "no-such-organization");

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_NO_ACTIVE_LOCKOUT, run.code, run.stderr());
        assertTrue(run.stdout().contains("no active lockout"), run.stdout());
    }

    /**
     * External finding 4: a row that EXISTS but is not locked (below the failure threshold, so
     * {@code locked_until} is NULL) has no active lockout either — the counter must not be reset,
     * and nothing may be audited, exactly as for an absent row.
     */
    @Test
    void aBelowThresholdCounterIsNotResetAndNothingIsAudited() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String username = "lockout-recovery-below-threshold-" + UUID.randomUUID() + "@example.test";
        UUID organization = LoginFixtures.organization(jdbc);
        String subjectHash = LocalLoginService.loginSubjectHash(organization, username);
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 1, NULL)
                """, new MapSqlParameterSource().addValue("subjectHash", subjectHash)
                .addValue("now", Timestamp.from(Instant.now())));

        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, organization.toString());

        Run run = run(env);

        assertEquals(SmControlLockoutRecoveryMain.EXIT_NO_ACTIVE_LOCKOUT, run.code, run.stderr());
        assertTrue(run.stdout().contains("no active lockout"), run.stdout());

        Integer failureCount = jdbc.queryForObject(
                "SELECT failure_count FROM sm_control.login_throttle WHERE subject_hash = :subjectHash",
                new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(1, failureCount, "the below-threshold counter must NOT be reset");

        Integer auditRows = jdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE table_name = 'login_throttle' AND audit_operation = 'LOCKOUT_CLEARED'
                   AND row_key ->> 'subject_hash' = :subjectHash
                """, new MapSqlParameterSource("subjectHash", subjectHash), Integer.class);
        assertEquals(0, auditRows, "nothing may be audited when nothing was cleared");
    }

    /** OR-125 (4): a username is unique per organization, so the selector without one is ambiguous: refused before any work. */
    @Test
    void aUsernameSelectorWithoutAnOrganizationExitsTwoNamingTheOrganizationVariable() {
        Run run = run(completeWithUsername());

        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE), run.stderr());
    }

    /**
     * OR-125 (4): the same username in two organizations is two throttle subjects. Clearing the one organization's
     * lockout, by the organization's NAME, in another case, leaves the other organization's lockout in place.
     */
    @Test
    void theSameUsernameInAnotherOrganizationIsAnotherSubjectAndAnOrganizationNamedInAnyCaseClearsOnlyItsOwn() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String username = "Lockout-Two-Orgs-" + UUID.randomUUID();
        UUID first = LoginFixtures.organization(jdbc);
        UUID second = LoginFixtures.organization(jdbc);
        for (UUID organization : new UUID[] {first, second}) {
            jdbc.update("""
                    INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                    VALUES (:subjectHash, :now, 5, :lockedUntil)
                    """, new MapSqlParameterSource()
                    .addValue("subjectHash", LocalLoginService.loginSubjectHash(organization, username))
                    .addValue("now", Timestamp.from(Instant.now()))
                    .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        // the username in the OTHER case, the organization by its NAME in upper case
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username.toUpperCase(java.util.Locale.ROOT));
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE,
                ("login-fixture-" + first).toUpperCase(java.util.Locale.ROOT));

        Run run = run(env);

        assertEquals(0, run.code, run.stderr());
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT coalesce(locked_until > now(), false) FROM sm_control.login_throttle"
                + " WHERE subject_hash = :h", new MapSqlParameterSource("h",
                LocalLoginService.loginSubjectHash(first, username)), Boolean.class), "the named organization's");
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT locked_until > now() FROM sm_control.login_throttle"
                + " WHERE subject_hash = :h", new MapSqlParameterSource("h",
                LocalLoginService.loginSubjectHash(second, username)), Boolean.class), "the other organization's");
    }

    /**
     * OR-125 (4): a login against an organization that names nothing is counted too (it must not differ from a login
     * against a missing user), so the operator can clear that subject the same way.
     */
    @Test
    void anOrganizationThatNamesNothingIsStillASubjectTheOperatorCanClear() {
        NamedParameterJdbcTemplate jdbc = SmControlPostgres.jdbcAtVersion(CACHE_KEY, SmControlPostgres.LATEST_VERSION);
        String url = SmControlPostgres.runtimeUrlAtVersion(CACHE_KEY);
        String password = SmControlPostgres.runtimePasswordAtVersion(CACHE_KEY);
        String username = "lockout-unresolved-" + UUID.randomUUID();
        String typedOrganization = "Nowhere-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 5, :lockedUntil)
                """, new MapSqlParameterSource()
                .addValue("subjectHash", LocalLoginService.unresolvedLoginSubjectHash(typedOrganization, username))
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("lockedUntil", Timestamp.from(Instant.now().plusSeconds(900))));
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, url);
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, SmControlPostgres.RUNTIME_USERNAME);
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, password);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, username);
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_ORGANIZATION_VARIABLE, typedOrganization);

        Run run = run(env);

        assertEquals(0, run.code, run.stderr());
        assertTrue(run.stdout().contains("cleared 1"), run.stdout());
    }

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmControlLockoutRecoveryMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm");
        env.put(SmControlLockoutRecoveryMain.USERNAME_VARIABLE, "sm_cp_runtime");
        env.put(SmControlLockoutRecoveryMain.PASSWORD_VARIABLE, PASSWORD);
        return env;
    }

    private static Map<String, String> completeWithUsername() {
        Map<String, String> env = complete();
        env.put(SmControlLockoutRecoveryMain.LOCKOUT_USERNAME_VARIABLE, "someone@example.test");
        return env;
    }

    private record Run(int code, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        String stdout() {
            return out.toString(StandardCharsets.UTF_8).strip();
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SmControlLockoutRecoveryMain.exitCode(env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out, err);
    }

    private static void assertMissing(String variable, Map<String, String> env) {
        Run run = run(env);
        assertEquals(SmControlLockoutRecoveryMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains(variable), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }
}
