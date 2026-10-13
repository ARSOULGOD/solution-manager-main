package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The command's argument and environment handling: every refusal happens before a connection is attempted. */
class KeyRotationMainTest {

    private static String key(int fill) {
        byte[] b = new byte[32];
        java.util.Arrays.fill(b, (byte) fill);
        return Base64.getEncoder().encodeToString(b);
    }

    private static Map<String, String> good() {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost:5432/sm");
        env.put("SM_DATA_RUNTIME_USERNAME", "sm_dp_runtime");
        env.put("SM_DATA_RUNTIME_PASSWORD", "SENTINEL-db-password");
        env.put("SM_DP_MASTER_KEY", key(1));
        env.put("SM_DP_MASTER_KEY_ID", "new");
        env.put("SM_DP_MASTER_KEY_PREVIOUS", key(2));
        env.put("SM_DP_MASTER_KEY_PREVIOUS_ID", "old");
        return env;
    }

    private record Result(int exit, String out, String err) {
    }

    private static Result run(Map<String, String> env, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = KeyRotationMain.exitCode(args, env, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void assertNoLeak(Result r) {
        String all = r.out + r.err;
        assertFalse(all.contains("SENTINEL-db-password"), all);
        assertFalse(all.contains(key(1)) || all.contains(key(2)), all);
    }

    @Test
    void aMissingCredentialVariableIsNamedAndNothingIsAttempted() {
        for (String name : new String[] {"SM_DATA_RUNTIME_URL", "SM_DATA_RUNTIME_USERNAME",
                "SM_DATA_RUNTIME_PASSWORD"}) {
            Map<String, String> env = good();
            env.remove(name);
            Result r = run(env);
            assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, r.exit);
            assertTrue(r.err.contains(name), r.err);
            assertNoLeak(r);
        }
    }

    @Test
    void aUrlThatCarriesACredentialIsRefusedWithoutBeingEchoed() {
        Map<String, String> env = good();
        env.put("SM_DATA_RUNTIME_URL", "jdbc:postgresql://localhost/sm?password=SENTINEL-db-password");
        Result r = run(env);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, r.exit);
        assertNoLeak(r);
    }

    @Test
    void thePreviousKeyIsRequiredBecauseTheCommandMovesKeysFromItToTheCurrentOne() {
        Map<String, String> env = good();
        env.remove("SM_DP_MASTER_KEY_PREVIOUS");
        env.remove("SM_DP_MASTER_KEY_PREVIOUS_ID");
        Result r = run(env);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, r.exit);
        assertTrue(r.err.contains("SM_DP_MASTER_KEY_PREVIOUS"), r.err);
    }

    @Test
    void aMalformedKeyIsRefusedByTheSameProviderTheApplicationUsesAndNeverEchoed() {
        Map<String, String> env = good();
        env.put("SM_DP_MASTER_KEY", "SENTINEL-not-base64");
        Result r = run(env);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, r.exit);
        assertTrue(r.err.contains("SM_DP_MASTER_KEY"), r.err);
        assertFalse(r.err.contains("SENTINEL-not-base64"), r.err);
    }

    @Test
    void theTenantArgumentTakesAUuidAndNothingElseIsAccepted() {
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, run(good(), "--tenant").exit);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, run(good(), "--tenant", "not-a-uuid").exit);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, run(good(), "--all-tenants").exit);
        assertEquals(KeyRotationMain.EXIT_MISSING_VARIABLE, run(good(), "stray").exit);
    }

    @Test
    void theSummaryNeverClaimsMoreThanItCovered() {
        String s = new KeyRotationMain.Report(5, 3, 2, new com.solace.sm.dp.secret.KeyId("old"),
                new com.solace.sm.dp.secret.KeyId("new"), 9, 0).summary();
        assertTrue(s.contains("covered 5 tenants (3 from the active-instance list, 2 named with --tenant)"), s);
        assertTrue(s.contains("NOT covered"), s);
        assertFalse(s.toLowerCase().contains("none remain"), s);
    }

    @Test
    void theSummaryPrintsTheQueryTheOperatorRunsBeforeRetiringTheOldKey() {
        String s = new KeyRotationMain.Report(1, 1, 0, new com.solace.sm.dp.secret.KeyId("old"),
                new com.solace.sm.dp.secret.KeyId("new"), 1, 0).summary();
        assertTrue(s.contains("SELECT key_id, count(*) FROM sm_secret.secret GROUP BY key_id"), s);
        assertTrue(s.contains("count is 0"), s);
    }
}
