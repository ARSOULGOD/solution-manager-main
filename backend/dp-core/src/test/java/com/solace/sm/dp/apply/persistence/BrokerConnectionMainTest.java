package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link BrokerConnectionMain}'s input handling (plan 011 P11-5.1; OR-107 OQ-3 (a)): every refusal below happens before
 * a database is reached, and the broker credential, the credential file's path, the database password and the master key
 * are never echoed, on any path (the {@code brokerConnectionMainNeverEchoesTheCredential} obligation: success and failure,
 * stdout, stderr, and every escaping exception's message and cause chain).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class BrokerConnectionMainTest {

    private static final String DB_PASSWORD = "SENTINEL-db-password";
    private static final String CREDENTIAL = "SENTINEL-broker-credential";
    private static final String CREDENTIAL_PATH = "/secret/SENTINEL-path/broker.pw";

    private static String masterKey() {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) 7);
        return Base64.getEncoder().encodeToString(b);
    }

    private static Map<String, String> good() {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost:1/sm");
        env.put("SM_DATA_RUNTIME_USERNAME", "sm_dp_runtime");
        env.put("SM_DATA_RUNTIME_PASSWORD", DB_PASSWORD);
        env.put("SM_DP_MASTER_KEY", masterKey());
        env.put("SM_DP_MASTER_KEY_ID", "unit");
        env.put(BrokerConnectionMain.TENANT_VARIABLE, UUID.randomUUID().toString());
        env.put(BrokerConnectionMain.ENVIRONMENT_VARIABLE, UUID.randomUUID().toString());
        env.put(BrokerConnectionMain.SCHEME_VARIABLE, "tcps");
        env.put(BrokerConnectionMain.HOST_VARIABLE, "broker.example.test");
        env.put(BrokerConnectionMain.PORT_VARIABLE, "55443");
        env.put(BrokerConnectionMain.VPN_VARIABLE, "customer-vpn");
        env.put(BrokerConnectionMain.USERNAME_VARIABLE, "sm-push");
        env.put(BrokerConnectionMain.CREDENTIAL_FILE_VARIABLE, CREDENTIAL_PATH);
        return env;
    }

    private record Result(int exit, String out, String err, char[] buffer) {
    }

    private static Result run(Map<String, String> env, Function<String, char[]> reader) {
        char[] buffer = CREDENTIAL.toCharArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = BrokerConnectionMain.exitCode(env, reader == null ? path -> buffer : reader, new PrintStream(out),
                new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), buffer);
    }

    private static void assertNoLeak(Result r) {
        String all = r.out + r.err;
        assertFalse(all.contains(DB_PASSWORD), all);
        assertFalse(all.contains(CREDENTIAL), all);
        assertFalse(all.contains("SENTINEL-path"), all);
        assertFalse(all.contains(masterKey()), all);
    }

    @Test
    void aMissingVariableIsNamedAndNothingIsAttempted() {
        for (String name : new String[] {"SM_DATA_RUNTIME_URL", "SM_DATA_RUNTIME_USERNAME", "SM_DATA_RUNTIME_PASSWORD",
                BrokerConnectionMain.TENANT_VARIABLE, BrokerConnectionMain.ENVIRONMENT_VARIABLE,
                BrokerConnectionMain.SCHEME_VARIABLE, BrokerConnectionMain.HOST_VARIABLE,
                BrokerConnectionMain.PORT_VARIABLE, BrokerConnectionMain.VPN_VARIABLE,
                BrokerConnectionMain.USERNAME_VARIABLE, BrokerConnectionMain.CREDENTIAL_FILE_VARIABLE}) {
            Map<String, String> env = good();
            env.remove(name);
            Result r = run(env, path -> {
                throw new AssertionError("the credential file is not read when the configuration is wrong");
            });
            assertEquals(BrokerConnectionMain.EXIT_MISSING_VARIABLE, r.exit, name);
            assertTrue(r.err.contains(name), r.err);
            assertNoLeak(r);
        }
    }

    @Test
    void aUrlThatCarriesACredentialIsRefusedWithoutBeingEchoed() {
        Map<String, String> env = good();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost/sm?password=" + DB_PASSWORD);

        Result r = run(env, null);

        assertEquals(BrokerConnectionMain.EXIT_MISSING_VARIABLE, r.exit);
        assertNoLeak(r);
    }

    @Test
    void everyFieldKeepsItsGrammarAndAnInvalidOneIsRefusedBeforeTheCredentialIsRead() {
        Map<String, Map<String, String>> bad = new java.util.LinkedHashMap<>();
        bad.put(BrokerConnectionMain.SCHEME_VARIABLE, Map.of(BrokerConnectionMain.SCHEME_VARIABLE, "https"));
        bad.put(BrokerConnectionMain.HOST_VARIABLE + " shell", Map.of(BrokerConnectionMain.HOST_VARIABLE, "b; rm -rf /"));
        bad.put(BrokerConnectionMain.HOST_VARIABLE + " url", Map.of(BrokerConnectionMain.HOST_VARIABLE, "tcps://b:1"));
        bad.put(BrokerConnectionMain.PORT_VARIABLE + " high", Map.of(BrokerConnectionMain.PORT_VARIABLE, "70000"));
        bad.put(BrokerConnectionMain.PORT_VARIABLE + " word", Map.of(BrokerConnectionMain.PORT_VARIABLE, "smf"));
        bad.put(BrokerConnectionMain.VPN_VARIABLE, Map.of(BrokerConnectionMain.VPN_VARIABLE, "my vpn"));
        bad.put(BrokerConnectionMain.USERNAME_VARIABLE, Map.of(BrokerConnectionMain.USERNAME_VARIABLE, "user name"));
        bad.put(BrokerConnectionMain.TENANT_VARIABLE, Map.of(BrokerConnectionMain.TENANT_VARIABLE, "not-a-uuid"));
        bad.put(BrokerConnectionMain.ENVIRONMENT_VARIABLE, Map.of(BrokerConnectionMain.ENVIRONMENT_VARIABLE, "x"));
        bad.put(BrokerConnectionMain.CONNECTION_ID_VARIABLE, Map.of(BrokerConnectionMain.CONNECTION_ID_VARIABLE, "x"));
        for (Map.Entry<String, Map<String, String>> entry : bad.entrySet()) {
            Map<String, String> env = good();
            env.putAll(entry.getValue());
            Result r = run(env, path -> {
                throw new AssertionError("refused before the credential file is read: " + entry.getKey());
            });
            assertEquals(BrokerConnectionMain.EXIT_MISSING_VARIABLE, r.exit, entry.getKey());
            assertTrue(r.err.contains(entry.getValue().keySet().iterator().next()), r.err);
            assertNoLeak(r);
        }
    }

    @Test
    void anUnreadableCredentialFileIsNamedByItsVariableNotItsPathOrContent() {
        Result r = run(good(), path -> null);

        assertEquals(BrokerConnectionMain.EXIT_MISSING_VARIABLE, r.exit);
        assertTrue(r.err.contains(BrokerConnectionMain.CREDENTIAL_FILE_VARIABLE), r.err);
        assertNoLeak(r);
    }

    @Test
    void aFailureAfterTheCredentialWasReadNeverEchoesItAndZeroisesTheBuffer() {
        // localhost:1 refuses at once: the command reaches the database step with the credential in memory and fails
        Result r = run(good(), null);

        assertEquals(BrokerConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertEquals("", r.out, "nothing is reported as recorded");
        assertNoLeak(r);
        for (char c : r.buffer) {
            assertEquals('\0', c, "the credential buffer is zeroised in a finally");
        }
    }

    @Test
    void theConnectionIdDefaultsToOneDerivedFromTheTenantAndEnvironmentAndNothingElse() {
        UUID tenant = UUID.randomUUID();
        UUID environment = UUID.randomUUID();

        UUID id = BrokerConnectionMain.defaultConnectionId(tenant, environment);

        assertEquals(id, BrokerConnectionMain.defaultConnectionId(tenant, environment), "the same pair, the same id");
        assertNotEquals(id, BrokerConnectionMain.defaultConnectionId(tenant, UUID.randomUUID()));
        assertNotEquals(id, BrokerConnectionMain.defaultConnectionId(UUID.randomUUID(), environment));
    }
}
