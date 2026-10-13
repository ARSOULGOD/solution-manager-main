package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.metrics.bench.BenchSettings;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-8.1 (the live run's harness limitation): the application's master key for the storage gate is ONE key per
 * benchmark database. {@code TwoSchemaTestDatabase.testMasterKeyProperties()} mints a random key per call, which is right for a
 * throwaway container and wrong for a database that is booted twice (the first boot's canary row cannot be opened by a second,
 * different key). The key is derived from what the integrator already holds for that database, never a committed default (D-KEY).
 */
class GateMasterKeyTest {

    /** What {@link GateMasterKey#identityQuery()} returns: the cluster's system_identifier and the database name. */
    private static final String URL = "7123456789012345678|sm";
    private static final String PASSWORD = "an-admin-password-the-evidence-masks";

    @Test
    void theSameDatabaseGetsTheSameKeyAndIdOnEveryBoot() {
        Map<String, String> first = GateMasterKey.properties(URL, PASSWORD);
        Map<String, String> second = GateMasterKey.properties(URL, PASSWORD);
        assertEquals(first, second, "a second boot against the same database must present the key the first one sealed its canary with");
        assertEquals(first.get(TwoSchemaTestDatabase.MASTER_KEY), second.get(TwoSchemaTestDatabase.MASTER_KEY));
        assertEquals(first.get(TwoSchemaTestDatabase.MASTER_KEY_ID), second.get(TwoSchemaTestDatabase.MASTER_KEY_ID));
    }

    @Test
    void aDifferentDatabaseOrCredentialGetsADifferentKey() {
        Map<String, String> base = GateMasterKey.properties(URL, PASSWORD);
        Map<String, String> otherDatabase = GateMasterKey.properties("7123456789012345678|other", PASSWORD);
        Map<String, String> otherPassword = GateMasterKey.properties(URL, PASSWORD + "-2");
        assertNotEquals(base.get(TwoSchemaTestDatabase.MASTER_KEY), otherDatabase.get(TwoSchemaTestDatabase.MASTER_KEY));
        assertNotEquals(base.get(TwoSchemaTestDatabase.MASTER_KEY), otherPassword.get(TwoSchemaTestDatabase.MASTER_KEY));
        assertNotEquals(base.get(TwoSchemaTestDatabase.MASTER_KEY_ID), otherDatabase.get(TwoSchemaTestDatabase.MASTER_KEY_ID));
    }

    @Test
    void theKeyIsA32ByteKekSpelledAsTheEnvironmentVariablesTheProviderReads() {
        Map<String, String> properties = GateMasterKey.properties(URL, PASSWORD);
        assertEquals(32, Base64.getDecoder().decode(properties.get(TwoSchemaTestDatabase.MASTER_KEY)).length);
        assertFalse(properties.get(TwoSchemaTestDatabase.MASTER_KEY_ID).isBlank());
        assertEquals("true", properties.get(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES),
                "a benchmark cannot set a process environment variable; production refuses a key in any other source (OR-85)");
    }

    @Test
    void theCredentialItIsDerivedFromIsNeverTheKeyNorPartOfIt() {
        Map<String, String> properties = GateMasterKey.properties(URL, PASSWORD);
        String key = properties.get(TwoSchemaTestDatabase.MASTER_KEY);
        assertFalse(key.contains(PASSWORD), "the derived key is not the credential");
        assertFalse(properties.get(TwoSchemaTestDatabase.MASTER_KEY_ID).contains(PASSWORD));
        assertFalse(Base64.getEncoder().encodeToString(PASSWORD.getBytes(java.nio.charset.StandardCharsets.UTF_8)).contains(key));
    }

    @Test
    void thereIsNoCommittedDefault() {
        assertThrows(IllegalArgumentException.class, () -> GateMasterKey.properties(URL, ""));
        assertThrows(IllegalArgumentException.class, () -> GateMasterKey.properties(URL, null));
        assertThrows(IllegalArgumentException.class, () -> GateMasterKey.properties("", PASSWORD));
    }

    @Test
    void anotherClusterWithTheSameDatabaseNameGetsAnotherKey() {
        assertNotEquals(GateMasterKey.properties(URL, PASSWORD).get(TwoSchemaTestDatabase.MASTER_KEY),
                GateMasterKey.properties("7999999999999999999|sm", PASSWORD).get(TwoSchemaTestDatabase.MASTER_KEY));
    }

    @Test
    void theIdentityQueryIsReadOnlyAndNamesTheClusterAndTheDatabase() {
        String query = GateMasterKey.identityQuery();
        assertTrue(query.startsWith("SELECT") && query.contains("system_identifier") && query.contains("current_database()"), query);
        assertFalse(query.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\b(insert|update|delete|alter|create|drop)\\b.*"), query);
    }

    @Test
    void theBootThePlanRunsUsesTheDatabasesKeyEveryTime() {
        assertEquals(bootKey("jdbc:postgresql://127.0.0.1:55432/sm"), bootKey("jdbc:postgresql://127.0.0.1:55432/sm"));
    }

    @Test
    void aRemadeTunnelOrAnotherSpellingOfTheHostDoesNotChangeTheKeyOfTheSameDatabase() {
        String base = bootKey("jdbc:postgresql://127.0.0.1:55432/sm");
        assertEquals(base, bootKey("jdbc:postgresql://127.0.0.1:55433/sm"), "the SSH port-forward was re-made on another local port");
        assertEquals(base, bootKey("jdbc:postgresql://localhost:55432/sm"), "localhost for 127.0.0.1");
    }

    /** Boots the gate's property set against a database whose identity is {@link #URL}, reached through {@code jdbcUrl}. */
    private static String bootKey(String jdbcUrl) {
        Map<String, String> props = new HashMap<>();
        props.put("sm.bench.url", jdbcUrl);
        Map<String, String> env = Map.of("SM_BENCH_ADMIN_PASSWORD", PASSWORD, "SM_BENCH_RUNTIME_PASSWORD", "r",
                "SM_BENCH_CP_RUNTIME_PASSWORD", "c", "SM_BENCH_CP_MIGRATOR_PASSWORD", "m");
        BenchSettings settings = BenchSettings.from(props::get, env::get);
        try (MetricsGateBenchmark gate = new MetricsGateBenchmark(settings, GateConfig.from(props::get), env::get, System.out, () -> URL)) {
            Map<String, Object> boot = gate.bootProperties();
            assertTrue(boot.containsKey(TwoSchemaTestDatabase.MASTER_KEY));
            assertEquals(jdbcUrl, boot.get("sm.control.runtime.url"), "the application still connects through the URL it was given");
            return boot.get(TwoSchemaTestDatabase.MASTER_KEY) + "/" + boot.get(TwoSchemaTestDatabase.MASTER_KEY_ID);
        }
    }
}
