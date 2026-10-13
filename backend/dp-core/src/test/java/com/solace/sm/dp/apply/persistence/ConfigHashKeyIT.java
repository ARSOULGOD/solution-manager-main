package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.HashKey;
import com.solace.sm.dp.apply.runtime.HashKeySource;
import com.solace.sm.dp.apply.runtime.HmacConfigHasher;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeHashKeySource;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T5.1 / OR-85 (OQ-5): the per-tenant config-hash key against a real PostgreSQL 14, as the runtime role.
 * The lazy mint is the new write path; this proves it lands once, as ciphertext, survives a KEK rotation with
 * every hash unchanged, and is wired as the production {@link ConfigHasher}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigHashKeyIT {

    private static final Envelope ENVELOPE = Envelope.standard();
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
    }

    @BeforeEach
    void cleanInstallLevelState() {
        SecretFixtures.resetCanaries();
    }

    private static EnvelopeHashKeySource source(MasterKeyRing ring) {
        return new EnvelopeHashKeySource(new JdbcSecretRepository(SmDataPostgres.runtimeJdbc()), ring, ENVELOPE,
                Clock.systemUTC());
    }

    private static List<Map<String, Object>> hashKeyRows(UUID tenant) {
        return superuser.queryForList("SELECT * FROM sm_secret.secret WHERE tenant_id = :t"
                        + " AND purpose = 'CONFIG_HASH_KEY'", new MapSqlParameterSource().addValue("t", tenant));
    }

    @Test
    void theFirstUseMintsOneRowAsTheRuntimeRoleAndItHoldsOnlyCiphertext() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 41);
        EnvelopeHashKeySource keys = source(SecretFixtures.ring(kek, null));
        UUID tenant = SmDataPostgres.freshTenant();
        assertTrue(hashKeyRows(tenant).isEmpty());

        String keyBase64;
        try (HashKey k = keys.current(new TenantId(tenant))) {
            assertEquals(1, k.version());
            keyBase64 = Base64.getEncoder().encodeToString(k.material());
        }

        List<Map<String, Object>> rows = hashKeyRows(tenant);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals(1, row.get("key_version"));
        assertNull(row.get("created_by"), "a lazy mint is a system action");
        assertEquals(kek.id().value(), row.get("key_id"));
        for (Object column : row.values()) {
            if (column instanceof byte[] bytes) {
                assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(keyBase64),
                        "no column holds the key");
            }
        }
        try (HashKey again = keys.current(new TenantId(tenant))) {
            assertEquals(keyBase64, Base64.getEncoder().encodeToString(again.material()));
        }
        assertEquals(1, hashKeyRows(tenant).size(), "a second use reads; it does not mint");
    }

    @Test
    void manyCallersRacingToMintTheFirstKeyLeaveOneRowAndOneKey() throws Exception {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 42);
        MasterKeyRing ring = SecretFixtures.ring(kek, null);
        UUID tenant = SmDataPostgres.freshTenant();
        int callers = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                Callable<String> call = () -> {
                    EnvelopeHashKeySource keys = source(ring);
                    go.await();
                    try (HashKey k = keys.current(new TenantId(tenant))) {
                        return Base64.getEncoder().encodeToString(k.material());
                    }
                };
                results.add(pool.submit(call));
            }
            go.countDown();
            String first = results.get(0).get();
            for (Future<String> f : results) {
                assertEquals(first, f.get(), "every caller ends up with the winner's key");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, hashKeyRows(tenant).size(), "ON CONFLICT (tenant_id, key_version) DO NOTHING");
    }

    @Test
    void twoTenantsHaveDifferentKeysSoIdenticalBytesHashDifferently() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 43);
        ConfigHasher hasher = new HmacConfigHasher(source(SecretFixtures.ring(kek, null)));
        TenantId a = new TenantId(SmDataPostgres.freshTenant());
        TenantId b = new TenantId(SmDataPostgres.freshTenant());
        byte[] bytes = "secret: same\n".getBytes(StandardCharsets.UTF_8);

        String ha = hasher.hash(a, bytes);
        String hb = hasher.hash(b, bytes);

        assertNotEquals(ha, hb);
        assertTrue(ha.matches("hmacsha256v1:[0-9a-f]{64}"), ha);
        assertEquals(ha, hasher.hash(a, bytes), "stable across calls and across reads of the stored key");
        assertTrue(hasher.verifies(a, ha, bytes));
        assertFalse(hasher.verifies(b, ha, bytes));
    }

    /** D-KEY 7.1(b), K3: a KEK rotation re-wraps the key's DEK; the key and every hash stay as they were. */
    @Test
    void aKekRotationLeavesEveryHashUnchanged() {
        MasterKey oldKek = SecretFixtures.key(SecretFixtures.freshKeyId(), 44);
        MasterKey newKek = SecretFixtures.key(SecretFixtures.freshKeyId(), 45);
        UUID tenantId = SmDataPostgres.freshTenant();
        SecretFixtures.seedInstance(tenantId, "active");
        TenantId tenant = new TenantId(tenantId);
        byte[] bytes = "password: hunter2\n".getBytes(StandardCharsets.UTF_8);

        MasterKeyRing before = SecretFixtures.ring(oldKek, null);
        new KekCanaryCheck(before, new JdbcKekCanaryRepository(SmDataPostgres.runtimeJdbc()), ENVELOPE,
                Clock.systemUTC()).run();
        String hashBefore = new HmacConfigHasher(source(before)).hash(tenant, bytes);

        MasterKeyRing during = SecretFixtures.ring(newKek, oldKek);
        new KekCanaryCheck(during, new JdbcKekCanaryRepository(SmDataPostgres.runtimeJdbc()), ENVELOPE,
                Clock.systemUTC()).run();
        KeyRotationMain.Report report = KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), during, ENVELOPE,
                List.of(), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(1, report.moved(), "the hash key's data key was re-wrapped like any other secret's");

        ConfigHasher after = new HmacConfigHasher(source(SecretFixtures.ring(newKek, null)));
        assertEquals(hashBefore, after.hash(tenant, bytes), "same key, same hash, now unwrapped by the new KEK");
        assertTrue(after.verifies(tenant, hashBefore, bytes));
        assertEquals(newKek.id().value(), hashKeyRows(tenantId).get(0).get("key_id"));
    }

    @Test
    void theRuntimeRoleCannotRewriteAHashKeysValueOrVersion() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 46);
        UUID tenant = SmDataPostgres.freshTenant();
        source(SecretFixtures.ring(kek, null)).current(new TenantId(tenant)).close();

        // A "rotation" of the hash key (a new value under the same version) is exactly what v1 does not build,
        // and exactly what the column grants forbid the runtime role.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> SmDataPostgres.runtimeJdbc().update(
                "UPDATE sm_secret.secret SET ciphertext = ciphertext WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> SmDataPostgres.runtimeJdbc().update(
                "UPDATE sm_secret.secret SET key_version = 2 WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant)));
    }

    @Test
    void theStoresConfigurationWiresTheProductionHasherAndItMintsThroughTheRealPorts() {
        String id = SecretFixtures.freshKeyId();
        UUID tenant = SmDataPostgres.freshTenant();

        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(47), "SM_DP_MASTER_KEY_ID=" + id)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertNotNull(ctx.getBean(HashKeySource.class));
                    ConfigHasher hasher = ctx.getBean(ConfigHasher.class);
                    assertTrue(hasher instanceof HmacConfigHasher, "the one production hasher is the keyed one");
                    String hash = hasher.hash(new TenantId(tenant), "a: 1\n".getBytes(StandardCharsets.UTF_8));
                    assertTrue(hash.startsWith("hmacsha256v1:"), hash);
                });
        assertEquals(1, hashKeyRows(tenant).size());
    }

    @Test
    void aWrongMasterKeyAbortsTheContextBeforeAnyHashKeyIsOpened() {
        String id = SecretFixtures.freshKeyId();
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(48), "SM_DP_MASTER_KEY_ID=" + id)
                .run(ctx -> assertNull(ctx.getStartupFailure()));

        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(49), "SM_DP_MASTER_KEY_ID=" + id)
                .run(ctx -> assertNotNull(ctx.getStartupFailure(),
                        "the hash key hangs off the canary check like the store does"));
    }

    @Configuration(proxyBeanMethods = false)
    @Import({SecretConfiguration.class, SecretPersistenceConfiguration.class})
    static class Wiring {

        @Bean
        NamedParameterJdbcTemplate smDataJdbc() {
            return SmDataPostgres.runtimeJdbc();
        }
    }
}
