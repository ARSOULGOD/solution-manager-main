package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link BrokerConnectionMain} against a real {@code sm_data} schema, connecting as {@code sm_dp_runtime} with a real
 * master key whose canary the application would have minted at boot (plan 011 P11-5.1; OR-107 OQ-3 (a)). The credential is
 * stored sealed, by reference, under the NEW purpose {@code BROKER_CREDENTIAL}, and opens again only under that purpose;
 * nothing is echoed; a re-run writes nothing; one live connection per (organization, environment).
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BrokerConnectionMainIT {

    private static final String CREDENTIAL = "SENTINEL-broker-credential-0042";

    private MasterKey master;
    private MasterKeyRing ring;
    private UUID tenant;
    private UUID environment;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @BeforeEach
    void setUp() {
        SecretFixtures.resetCanaries();
        master = SecretFixtures.key(SecretFixtures.freshKeyId(), 41);
        ring = SecretFixtures.ring(master, null);
        new KekCanaryCheck(ring, new JdbcKekCanaryRepository(SmDataPostgres.runtimeJdbc()), Envelope.standard(),
                Clock.systemUTC()).run();
        tenant = UUID.randomUUID();
        environment = UUID.randomUUID();
    }

    private Map<String, String> env(UUID tenantId, UUID environmentId, String host) {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DATA_RUNTIME_URL", SmDataPostgres.jdbcUrl());
        env.put("SM_DATA_RUNTIME_USERNAME", "sm_dp_runtime");
        env.put("SM_DATA_RUNTIME_PASSWORD", SmDataPostgres.RUNTIME_PASSWORD);
        env.put("SM_DP_MASTER_KEY", Base64.getEncoder().encodeToString(SecretFixtures.keyBytes(41)));
        env.put("SM_DP_MASTER_KEY_ID", master.id().toString());
        env.put(BrokerConnectionMain.TENANT_VARIABLE, tenantId.toString());
        env.put(BrokerConnectionMain.ENVIRONMENT_VARIABLE, environmentId.toString());
        env.put(BrokerConnectionMain.SCHEME_VARIABLE, "tcps");
        env.put(BrokerConnectionMain.HOST_VARIABLE, host);
        env.put(BrokerConnectionMain.PORT_VARIABLE, "55443");
        env.put(BrokerConnectionMain.VPN_VARIABLE, "customer-vpn");
        env.put(BrokerConnectionMain.USERNAME_VARIABLE, "sm-push");
        env.put(BrokerConnectionMain.CREDENTIAL_FILE_VARIABLE, "/not/read/by/this/reader");
        return env;
    }

    private record Result(int exit, String out, String err, char[] buffer) {
    }

    private static Result run(Map<String, String> env) {
        char[] buffer = CREDENTIAL.toCharArray();
        Function<String, char[]> reader = path -> buffer;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BrokerConnectionMain.exitCode(env, reader, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), buffer);
    }

    private Map<String, Object> connection(UUID tenantId, UUID environmentId) {
        return SmDataPostgres.superuserJdbc().queryForMap("SELECT broker_connection_id, scheme, host, port, vpn,"
                        + " client_username, credential_ref, created_by, retired_at FROM sm_data.broker_connection"
                        + " WHERE tenant_id = :t AND environment_id = :e",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("e", environmentId));
    }

    private int secrets(UUID tenantId) {
        return SmDataPostgres.superuserJdbc().queryForObject("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenantId), Integer.class);
    }

    @Test
    void theCommandStoresTheCredentialByReferenceUnderItsOwnPurposeAndNeverEchoesIt() {
        Result r = run(env(tenant, environment, "broker-a.example.test"));

        assertEquals(0, r.exit, r.err);
        UUID id = BrokerConnectionMain.defaultConnectionId(tenant, environment);
        assertTrue(r.out.contains(id.toString()), r.out);
        String all = r.out + r.err;
        assertFalse(all.contains("SENTINEL") || all.contains(SmDataPostgres.RUNTIME_PASSWORD), all);
        for (char c : r.buffer) {
            assertEquals('\0', c, "the buffer is zeroised");
        }
        Map<String, Object> row = connection(tenant, environment);
        assertEquals(id, row.get("broker_connection_id"));
        assertEquals("tcps", row.get("scheme"));
        assertEquals("broker-a.example.test", row.get("host"));
        assertEquals(55443, row.get("port"));
        assertEquals("customer-vpn", row.get("vpn"));
        assertEquals("sm-push", row.get("client_username"));
        assertEquals(BrokerConnectionMain.OPERATOR_ACTOR, row.get("created_by"));
        assertEquals(null, row.get("retired_at"));
        UUID reference = (UUID) row.get("credential_ref");

        // the row holds a REFERENCE: the credential is in no column of broker_connection
        String rowText = SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT row_to_json(b)::text FROM sm_data.broker_connection b WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), String.class);
        assertFalse(rowText.contains("SENTINEL"), rowText);

        // ... it opens, under the same master key and the NEW purpose, to exactly what the file held ...
        try (SecretMaterial m = SecretFixtures.store(ring).resolve(tenant, reference, SecretPurpose.BROKER_CREDENTIAL)) {
            assertArrayEquals(CREDENTIAL.toCharArray(), m.chars());
        }
        // ... and not under any other: the purpose is part of the authenticated data (BE-D28, amended by BE-D43)
        assertThrows(SecretException.class,
                () -> SecretFixtures.store(ring).resolve(tenant, reference, SecretPurpose.SSH_PRIVATE_KEY));
        String stored = SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT encode(ciphertext, 'escape') FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), String.class);
        assertFalse(stored.contains("SENTINEL"), "the table holds ciphertext, never the credential");
        assertEquals("BROKER_CREDENTIAL", SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT purpose FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), String.class));
    }

    @Test
    void anExplicitConnectionIdIsUsedWhenGiven() {
        UUID chosen = UUID.randomUUID();
        Map<String, String> env = env(tenant, environment, "broker-b.example.test");
        env.put(BrokerConnectionMain.CONNECTION_ID_VARIABLE, chosen.toString());

        assertEquals(0, run(env).exit);

        assertEquals(chosen, connection(tenant, environment).get("broker_connection_id"));
    }

    @Test
    void runningTheCommandAgainWritesNothingAndStoresNoSecondCredential() {
        Map<String, String> env = env(tenant, environment, "broker-c.example.test");
        assertEquals(0, run(env).exit);
        Object credential = connection(tenant, environment).get("credential_ref");

        Result again = run(env);

        assertEquals(0, again.exit, again.err);
        assertEquals(credential, connection(tenant, environment).get("credential_ref"));
        assertEquals(1, secrets(tenant), "an identical re-run stores no second credential");
        assertFalse(again.out.contains("recorded"), "a re-run records nothing and must not say it did: " + again.out);
        assertTrue(again.out.contains("already registered") && again.out.contains("credential unchanged"), again.out);
    }

    @Test
    void aDifferingRecordForTheSameConnectionIsRefusedAndStoresNoCredential() {
        assertEquals(0, run(env(tenant, environment, "broker-d.example.test")).exit);

        Result r = run(env(tenant, environment, "broker-d2.example.test"));

        assertEquals(BrokerConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("different connection record"), r.err);
        assertEquals("broker-d.example.test", connection(tenant, environment).get("host"));
        assertEquals(1, secrets(tenant));
        assertEquals("", r.out);
    }

    @Test
    void aSecondLiveConnectionForTheSameEnvironmentIsRefusedByNameAndLeavesNoStrandedCredential() {
        assertEquals(0, run(env(tenant, environment, "broker-e.example.test")).exit);
        int before = secrets(tenant);
        Map<String, String> second = env(tenant, environment, "broker-e-other.example.test");
        second.put(BrokerConnectionMain.CONNECTION_ID_VARIABLE, UUID.randomUUID().toString());

        Result r = run(second);

        assertEquals(BrokerConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("broker_connection_live_once"), r.err);
        assertEquals(before, secrets(tenant), "the credential and the row are one transaction: a refused row strands none");
        assertFalse((r.out + r.err).contains("SENTINEL"));
    }

    @Test
    void anotherEnvironmentAndAnotherOrganizationEachHaveTheirOwn() {
        assertEquals(0, run(env(tenant, environment, "broker-f.example.test")).exit);

        // OR-112 (2): each on its OWN broker; a shared broker and VPN is the next tests' refusal
        assertEquals(0, run(env(tenant, UUID.randomUUID(), "broker-f2.example.test")).exit,
                "one connection per ENVIRONMENT, not per organization");
        assertEquals(0, run(env(UUID.randomUUID(), environment, "broker-f3.example.test")).exit,
                "an environment id means nothing across organizations (I5)");
    }

    /** The refusal is the command's own sentence: it names the rule, never a value, and strands no credential. */
    private void assertRefusedForSharingABroker(Result r, UUID tenantId, int secretsBefore) {
        assertEquals(BrokerConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("one live connection per broker and message VPN"), r.err);
        assertEquals("", r.out);
        assertEquals(secretsBefore, secrets(tenantId), "the refusal comes before any credential is sealed");
        assertFalse((r.out + r.err).contains("SENTINEL"));
        assertFalse(r.err.contains("broker_connection_live_broker_once"),
                "the command's own pre-check answers first, in its own words: " + r.err);
    }

    @Test
    void aSecondLiveConnectionToTheSameBrokerAndVpnFromAnotherEnvironmentIsRefused() {
        assertEquals(0, run(env(tenant, environment, "broker-h.example.test")).exit);
        UUID other = UUID.randomUUID();
        int before = secrets(tenant);

        Result r = run(env(tenant, other, "broker-h.example.test"));

        assertRefusedForSharingABroker(r, tenant, before);
        assertEquals(0, SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_data.broker_connection WHERE tenant_id = :t AND environment_id = :e",
                new MapSqlParameterSource().addValue("t", tenant).addValue("e", other), Integer.class));
    }

    @Test
    void theRuleHoldsAcrossOrganizationsBecauseItIsPerInstall() {
        assertEquals(0, run(env(tenant, environment, "broker-i.example.test")).exit);
        UUID otherOrganization = UUID.randomUUID();

        Result r = run(env(otherOrganization, UUID.randomUUID(), "broker-i.example.test"));

        assertRefusedForSharingABroker(r, otherOrganization, 0);
        assertFalse(r.err.contains(tenant.toString()), "a refusal never names the other organization: " + r.err);
        assertFalse(r.err.contains(environment.toString()), "nor its environment: " + r.err);
    }

    @Test
    void aHostNameIsComparedWithoutRegardToCase() {
        assertEquals(0, run(env(tenant, environment, "broker-j.example.test")).exit);
        int before = secrets(tenant);

        Result r = run(env(tenant, UUID.randomUUID(), "Broker-J.Example.TEST"));

        assertRefusedForSharingABroker(r, tenant, before);
    }

    @Test
    void anotherPortOrAnotherVpnOnTheSameHostIsAnotherBroker() {
        assertEquals(0, run(env(tenant, environment, "broker-k.example.test")).exit);
        Map<String, String> otherPort = env(tenant, UUID.randomUUID(), "broker-k.example.test");
        otherPort.put(BrokerConnectionMain.PORT_VARIABLE, "55444");
        Map<String, String> otherVpn = env(tenant, UUID.randomUUID(), "broker-k.example.test");
        otherVpn.put(BrokerConnectionMain.VPN_VARIABLE, "another-vpn");

        assertEquals(0, run(otherPort).exit);
        assertEquals(0, run(otherVpn).exit);
    }

    @Test
    void aRetiredConnectionNoLongerHoldsItsBroker() {
        assertEquals(0, run(env(tenant, environment, "broker-l.example.test")).exit);
        SmDataPostgres.superuserJdbc().update("UPDATE sm_data.broker_connection SET retired_at = now()"
                + " WHERE tenant_id = :t AND environment_id = :e",
                new MapSqlParameterSource().addValue("t", tenant).addValue("e", environment));

        assertEquals(0, run(env(tenant, UUID.randomUUID(), "broker-l.example.test")).exit,
                "retiring is how a broker is released");
    }

    @Test
    void theSameConnectionRunAgainIsStillIdempotentNotARefusal() {
        Map<String, String> env = env(tenant, environment, "broker-m.example.test");
        assertEquals(0, run(env).exit);

        Result again = run(env);

        assertEquals(0, again.exit, "the broker is held by THIS connection: " + again.err);
        assertTrue(again.out.contains("already registered"), again.out);
    }

    @Test
    void theUniquenessReadIsTheOperatorCommandsOwnAndNotReachableFromApplicationRuntime() throws Exception {
        java.lang.reflect.Method read = JdbcBrokerConnectionRepository.class.getDeclaredMethod("liveAtBroker",
                String.class, int.class, String.class);
        assertFalse(java.lang.reflect.Modifier.isPublic(read.getModifiers()),
                "package-private: only BrokerConnectionMain (same package) can call it; the runtime path cannot");
        assertTrue(java.util.Arrays.stream(com.solace.sm.dp.metrics.push.BrokerConnectionRows.class.getMethods())
                        .noneMatch(m -> m.getName().equals("liveAtBroker")),
                "and it is not on the interface the push path holds");
    }

    @Test
    void aMasterKeyThatIsNotTheInstallsIsRefusedBeforeAnyCredentialIsSealed() {
        MasterKey stranger = SecretFixtures.key(SecretFixtures.freshKeyId(), 99);
        Map<String, String> env = env(tenant, environment, "broker-g.example.test");
        env.put("SM_DP_MASTER_KEY", Base64.getEncoder().encodeToString(SecretFixtures.keyBytes(99)));
        env.put("SM_DP_MASTER_KEY_ID", stranger.id().toString());

        Result r = run(env);

        assertEquals(BrokerConnectionMain.EXIT_FAILED, r.exit);
        assertTrue(r.err.contains("canary"), r.err);
        assertEquals(0, secrets(tenant));
        assertEquals(0, SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_data.broker_connection WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), Integer.class));
        assertFalse((r.out + r.err).contains("SENTINEL"));
    }
}
