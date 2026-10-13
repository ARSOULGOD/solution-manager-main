package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.SecretConfiguration;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T2.4 against a real PostgreSQL 14: the {@code sm_secret} schema, what the runtime role may and may
 * not do to it, the store through the JDBC ports, and the boot canary through a real Spring context.
 */
@Testcontainers(disabledWithoutDocker = true)
class SecretStoreIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    @BeforeEach
    void cleanInstallLevelState() {
        SecretFixtures.resetCanaries();
    }

    // ---- the store through the real ports -------------------------------------------------------------------

    @Test
    void aValueRoundTripsThroughPostgresAndTheRowHoldsOnlyCiphertext() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 11);
        EnvelopeSecretStore store = SecretFixtures.store(SecretFixtures.ring(kek, null));
        UUID tenant = SmDataPostgres.freshTenant();

        UUID id = store.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "sentinel-ssh-key-material".toCharArray(), ACTOR);

        assertTrue(store.exists(tenant, id));
        try (SecretMaterial m = store.resolve(tenant, id, SecretPurpose.SSH_PRIVATE_KEY)) {
            assertEquals("sentinel-ssh-key-material", new String(m.chars()));
        }
        var row = superuser.queryForMap("SELECT * FROM sm_secret.secret WHERE tenant_id = :t AND secret_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", id));
        assertEquals("SSH_PRIVATE_KEY", row.get("purpose"));
        assertEquals(kek.id().value(), row.get("key_id"));
        assertNotNull(row.get("created_at"));
        assertEquals(ACTOR, row.get("created_by"));
        assertEquals(null, row.get("key_version"));
        for (Object column : row.values()) {
            if (column instanceof byte[] bytes) {
                assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("sentinel"),
                        "no column holds the plaintext");
            }
        }
    }

    @Test
    void neitherALogLineNorAnExceptionCarriesTheValueWhileItIsStoredReadAndRefused() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 12);
        EnvelopeSecretStore store = SecretFixtures.store(SecretFixtures.ring(kek, null));
        UUID tenant = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();

        LogCapture.Captured<String> run = LogCapture.during(() -> {
            UUID id = store.put(tenant, SecretPurpose.CONFIG_FIELD, "sentinel-log-leak-check".toCharArray(), ACTOR);
            try (SecretMaterial m = store.resolve(tenant, id, SecretPurpose.CONFIG_FIELD)) {
                assertNotNull(m.bytes());
            }
            // a copied row fails authentication; the failure must not describe the bytes either
            copyRow(tenant, other, id);
            return assertThrows(SecretException.class,
                    () -> store.resolve(other, id, SecretPurpose.CONFIG_FIELD)).toString();
        });

        assertFalse(run.value().contains("sentinel-log-leak-check"), run.value());
        assertFalse(run.text().contains("sentinel-log-leak-check"),
                "even at TRACE (where Spring prints bind values) nothing logs the value: " + run.text());
    }

    @Test
    void theRuntimeRoleCannotDeleteOrReadAcrossTenants() {
        // The plan's name, kept; D-KEY 9 note 1 narrows what it can honestly assert: the runtime role holds a
        // table-wide SELECT, so the DATABASE does not fence tenants. What holds is the repository's tenant
        // predicate and the AAD. Asserted: no DELETE, no write of a value column, and resolve for the wrong
        // tenant (and for a copied row) fails.
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 13);
        EnvelopeSecretStore store = SecretFixtures.store(SecretFixtures.ring(kek, null));
        UUID tenant = SmDataPostgres.freshTenant();
        UUID other = SmDataPostgres.freshTenant();
        UUID id = store.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "v".toCharArray(), ACTOR);

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_secret.secret WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.getJdbcTemplate()
                .execute("TRUNCATE sm_secret.secret"));
        for (String column : new String[] {"ciphertext = '\\x00'::bytea", "value_iv = '\\x00'::bytea",
                "purpose = 'CONFIG_FIELD'", "tenant_id = gen_random_uuid()", "secret_id = gen_random_uuid()",
                "aad_version = 2", "created_at = now()", "created_by = gen_random_uuid()"}) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_secret.secret SET " + column + " WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", tenant)), column);
        }

        assertEquals(SecretException.Code.NOT_FOUND,
                assertThrows(SecretException.class, () -> store.resolve(other, id, SecretPurpose.SSH_PRIVATE_KEY))
                        .code());
        assertFalse(store.exists(other, id));
        copyRow(tenant, other, id);
        assertEquals(SecretException.Code.AUTHENTICATION_FAILED,
                assertThrows(SecretException.class, () -> store.resolve(other, id, SecretPurpose.SSH_PRIVATE_KEY))
                        .code(), "a row copied to another tenant does not authenticate");
        try (SecretMaterial m = store.resolve(tenant, id, SecretPurpose.SSH_PRIVATE_KEY)) {
            assertEquals("v", new String(m.chars()));
        }
    }

    @Test
    void theRuntimeRoleMayChangeExactlyTheThreeRotationColumnsInOneStatement() {
        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 14);
        EnvelopeSecretStore store = SecretFixtures.store(SecretFixtures.ring(kek, null));
        UUID tenant = SmDataPostgres.freshTenant();
        UUID id = store.put(tenant, SecretPurpose.CONFIG_FIELD, "v".toCharArray(), ACTOR);

        // the positive control: the rotation statement is legal, FOR UPDATE included
        runtime.queryForList("SELECT secret_id FROM sm_secret.secret WHERE tenant_id = :t FOR UPDATE",
                new MapSqlParameterSource("t", tenant));
        assertEquals(1, runtime.update("UPDATE sm_secret.secret SET key_id = 'moved', wrapped_dek = wrapped_dek,"
                        + " dek_iv = dek_iv WHERE tenant_id = :t AND secret_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", id)));

        // …and the owner is bound by the trigger too: a value column cannot change even for the owner
        Refusals.assertRefused(Refusals.RAISE_EXCEPTION, null, () -> superuser.update(
                "UPDATE sm_secret.secret SET ciphertext = '\\x00112233445566778899aabbccddeeff00'::bytea"
                        + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant)));
    }

    @Test
    void theCanaryTableIsInstallLevelAndTheRuntimeRoleCanOnlyReadAndInsertIt() {
        assertEquals(java.util.List.of(), superuser.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'sm_secret'"
                        + " AND table_name = 'kek_canary' AND column_name LIKE '%tenant%'",
                new MapSqlParameterSource(), String.class), "B4: the canary carries no tenant");

        MasterKey kek = SecretFixtures.key(SecretFixtures.freshKeyId(), 15);
        var canaries = new JdbcKekCanaryRepository(runtime);
        assertFalse(canaries.anyExists());
        var sealed = Envelope.standard().sealCanary(kek, "c".getBytes());
        assertTrue(canaries.insertIfAbsent(new com.solace.sm.dp.secret.CanaryRow(sealed, Instant.now())));
        assertFalse(canaries.insertIfAbsent(new com.solace.sm.dp.secret.CanaryRow(sealed, Instant.now())),
                "ON CONFLICT DO NOTHING: the second insert is a no-op, not an error");
        assertTrue(canaries.anyExists());
        assertArrayEquals(sealed.ciphertext(), canaries.find(kek.id()).orElseThrow().sealed().ciphertext());

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.getJdbcTemplate()
                .execute("UPDATE sm_secret.kek_canary SET ciphertext = ciphertext"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.getJdbcTemplate()
                .execute("DELETE FROM sm_secret.kek_canary"));
    }

    @Test
    void noDefaultReadBaselineReachesSmSecret() {
        // sm_data's V001 ALTER DEFAULT PRIVILEGES is scoped to sm_data; sm_secret's grants are explicit per table.
        assertEquals(java.util.List.of(), superuser.queryForList(
                "SELECT DISTINCT table_name FROM information_schema.role_table_grants"
                        + " WHERE grantee = 'sm_dp_runtime' AND table_schema = 'sm_secret'"
                        + "   AND privilege_type IN ('DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER')",
                new MapSqlParameterSource(), String.class));
        assertEquals(java.util.List.of("kek_canary", "secret"), superuser.queryForList(
                "SELECT DISTINCT table_name FROM information_schema.role_table_grants"
                        + " WHERE grantee = 'sm_dp_runtime' AND table_schema = 'sm_secret'"
                        + "   AND privilege_type = 'SELECT' ORDER BY table_name",
                new MapSqlParameterSource(), String.class));
    }

    // ---- the schema's own checks ----------------------------------------------------------------------------

    @Test
    void theSchemaRefusesWhatTheStoreWouldNeverWrite() {
        UUID tenant = SmDataPostgres.freshTenant();
        byte[] w = new byte[48];
        byte[] iv = new byte[12];
        byte[] ct = new byte[20];

        refused("secret_purpose", insertParams(tenant, "CANARY", w, iv, iv, ct, null, ACTOR));
        refused("secret_wrapped_dek_length", insertParams(tenant, "CONFIG_FIELD", new byte[47], iv, iv, ct, null, ACTOR));
        refused("secret_dek_iv_length", insertParams(tenant, "CONFIG_FIELD", w, new byte[11], iv, ct, null, ACTOR));
        refused("secret_value_iv_length", insertParams(tenant, "CONFIG_FIELD", w, iv, new byte[13], ct, null, ACTOR));
        refused("secret_ciphertext_length", insertParams(tenant, "CONFIG_FIELD", w, iv, iv, new byte[15], null, ACTOR));
        refused("secret_ciphertext_length", insertParams(tenant, "CONFIG_FIELD", w, iv, iv, new byte[16401], null, ACTOR));
        refused("secret_key_version_iff_hash_key", insertParams(tenant, "CONFIG_FIELD", w, iv, iv, ct, 1, ACTOR));
        refused("secret_key_version_iff_hash_key", insertParams(tenant, "CONFIG_HASH_KEY", w, iv, iv, ct, null, null));
        refused("secret_created_by_unless_hash_key", insertParams(tenant, "SSH_PRIVATE_KEY", w, iv, iv, ct, null, null));

        // the legal edges: the cap itself, and a hash key minted by the system (created_by null)
        superuser.update(INSERT, insertParams(tenant, "CONFIG_FIELD", w, iv, iv, new byte[16400], null, ACTOR));
        superuser.update(INSERT, insertParams(tenant, "CONFIG_HASH_KEY", w, iv, iv, ct, 1, null));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "secret_hash_key_version_once", () ->
                superuser.update(INSERT, insertParams(tenant, "CONFIG_HASH_KEY", w, iv, iv, ct, 1, null)));
        superuser.update(INSERT, insertParams(tenant, "CONFIG_HASH_KEY", w, iv, iv, ct, 2, null));
    }

    @Test
    void theKeyIdGrammarIsCheckedInTheSchemaToo() {
        UUID tenant = SmDataPostgres.freshTenant();
        MapSqlParameterSource p = insertParams(tenant, "CONFIG_FIELD", new byte[48], new byte[12], new byte[12],
                new byte[20], null, ACTOR).addValue("keyId", "has space");
        refused("secret_key_id", p);
    }

    // ---- the boot canary, through a real context -------------------------------------------------------------

    @Test
    void aWrongMasterKeyFailsTheCanaryBeforeTheWorkerStarts() {
        String id = SecretFixtures.freshKeyId();
        WorkerStandIn.started.set(false);

        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(21), "SM_DP_MASTER_KEY_ID=" + id)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "a fresh install with a well-formed key starts");
                    assertTrue(WorkerStandIn.started.get(), "…and the worker stand-in started");
                });
        assertEquals(1, canaryCount(), "the first boot minted exactly one canary");

        WorkerStandIn.started.set(false);
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(22), "SM_DP_MASTER_KEY_ID=" + id)
                .run(ctx -> {
                    Throwable failure = ctx.getStartupFailure();
                    assertNotNull(failure, "the wrong key under the same id must abort context refresh");
                    String chain = messages(failure);
                    assertTrue(chain.contains("SM_DP_MASTER_KEY") && chain.contains(id), chain);
                    assertFalse(chain.contains(SecretFixtures.keyBase64(22)) || chain.contains(SecretFixtures.keyBase64(21)),
                            "the message never carries a key: " + chain);
                    assertFalse(WorkerStandIn.started.get(), "the worker stand-in never started");
                });
        assertEquals(1, canaryCount(), "a wrong key never writes a canary for itself");
    }

    @Test
    void aMissingOrMalformedMasterKeyRefusesTheContextWithoutEchoingIt() {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY=SENTINEL-not-base64", "SM_DP_MASTER_KEY_ID=x")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure());
                    assertFalse(messages(ctx.getStartupFailure()).contains("SENTINEL"),
                            messages(ctx.getStartupFailure()));
                });
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true", "SM_DP_MASTER_KEY_ID=x")
                .run(ctx -> assertNotNull(ctx.getStartupFailure(), "no key at all: no context"));
        assertEquals(0, canaryCount(), "a refused boot minted nothing");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static final String INSERT = "INSERT INTO sm_secret.secret (tenant_id, secret_id, purpose, key_id,"
            + " wrapped_dek, dek_iv, value_iv, ciphertext, aad_version, key_version, created_at, created_by)"
            + " VALUES (:t, gen_random_uuid(), :purpose, :keyId, :w, :dekIv, :valueIv, :ct, 1, :kv, now(), :by)";

    private static MapSqlParameterSource insertParams(UUID tenant, String purpose, byte[] w, byte[] dekIv,
            byte[] valueIv, byte[] ct, Integer keyVersion, UUID by) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("purpose", purpose)
                .addValue("keyId", "schema-check").addValue("w", w).addValue("dekIv", dekIv)
                .addValue("valueIv", valueIv).addValue("ct", ct).addValue("kv", keyVersion, Types.INTEGER)
                .addValue("by", by, Types.OTHER);
    }

    private static void refused(String constraint, MapSqlParameterSource params) {
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, constraint, () -> superuser.update(INSERT, params));
    }

    /** Someone with database write access copies a row to another tenant (D-KEY scenario K5). */
    private static void copyRow(UUID from, UUID to, UUID secretId) {
        superuser.update("INSERT INTO sm_secret.secret SELECT :to, secret_id, purpose, key_id, wrapped_dek, dek_iv,"
                        + " value_iv, ciphertext, aad_version, key_version, created_at, created_by"
                        + " FROM sm_secret.secret WHERE tenant_id = :from AND secret_id = :s",
                new MapSqlParameterSource().addValue("from", from).addValue("to", to).addValue("s", secretId));
    }

    private static int canaryCount() {
        return superuser.getJdbcOperations().queryForObject("SELECT count(*)::int FROM sm_secret.kek_canary",
                Integer.class);
    }

    private static String messages(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(c.getMessage()).append(" | ");
        }
        return b.toString();
    }

    /** The real configuration pair, over the runtime template, plus a stand-in for the apply worker. */
    @Configuration(proxyBeanMethods = false)
    @Import({SecretConfiguration.class, SecretPersistenceConfiguration.class})
    static class Wiring {

        @Bean
        NamedParameterJdbcTemplate smDataJdbc() {
            return SmDataPostgres.runtimeJdbc();
        }

        @Bean
        @DependsOn("kekCanaryCheck")
        WorkerStandIn worker() {
            return new WorkerStandIn();
        }
    }

    /** Stands for ApplyWorker/ApplyStartupRecovery: a lifecycle that must not start behind a failed canary. */
    static final class WorkerStandIn implements SmartLifecycle {

        static final AtomicBoolean started = new AtomicBoolean();
        private boolean running;

        @Override
        public void start() {
            started.set(true);
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
