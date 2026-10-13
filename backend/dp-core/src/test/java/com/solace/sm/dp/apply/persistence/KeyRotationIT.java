package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-KEY 5 against a real database: the re-wrap command (plan 010 T2.4 Step 3b), including the gap OR-85 (OQ-1)
 * rules the operator closes by naming tenants.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeyRotationIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final Envelope ENVELOPE = Envelope.standard();

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @BeforeEach
    void clean() {
        SecretFixtures.resetCanaries();
    }

    /** Boots the application's canary check for these keys, as step 1 of the procedure does. */
    private static void boot(MasterKeyRing ring) {
        new KekCanaryCheck(ring, new JdbcKekCanaryRepository(SmDataPostgres.runtimeJdbc()), ENVELOPE,
                Clock.systemUTC()).run();
    }

    private static Map<String, Object> row(UUID tenant, UUID secret) {
        return SmDataPostgres.superuserJdbc().queryForMap(
                "SELECT key_id, wrapped_dek, dek_iv, value_iv, ciphertext FROM sm_secret.secret"
                        + " WHERE tenant_id = :t AND secret_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", secret));
    }

    @Test
    void rotationRewrapsEveryDekAndLeavesTheValueCiphertextUntouched() {
        String oldId = SecretFixtures.freshKeyId();
        String newId = SecretFixtures.freshKeyId();
        MasterKey oldKey = SecretFixtures.key(oldId, 31);
        MasterKey newKey = SecretFixtures.key(newId, 32);
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        SecretFixtures.seedInstance(t1, "active");
        SecretFixtures.seedInstance(t2, "active");

        boot(SecretFixtures.ring(oldKey, null));
        EnvelopeSecretStore before = SecretFixtures.store(SecretFixtures.ring(oldKey, null));
        Map<UUID, UUID> ids = new HashMap<>(); // secret id -> tenant
        for (UUID tenant : List.of(t1, t2)) {
            for (int i = 0; i < 3; i++) {
                ids.put(before.put(tenant, SecretPurpose.CONFIG_FIELD, ("value-" + i).toCharArray(), ACTOR), tenant);
            }
        }
        Map<UUID, Map<String, Object>> snapshot = new HashMap<>();
        ids.forEach((s, t) -> snapshot.put(s, row(t, s)));

        MasterKeyRing during = SecretFixtures.ring(newKey, oldKey);
        boot(during); // step 1: mints the new canary
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        KeyRotationMain.Report report = KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), during, ENVELOPE,
                List.of(), new PrintStream(progress));

        assertEquals(6, report.moved());
        assertEquals(0, report.remaining());
        ids.forEach((s, t) -> {
            Map<String, Object> was = snapshot.get(s);
            Map<String, Object> now = row(t, s);
            assertEquals(newId, now.get("key_id"));
            assertFalse(java.util.Arrays.equals((byte[]) was.get("wrapped_dek"), (byte[]) now.get("wrapped_dek")));
            assertFalse(java.util.Arrays.equals((byte[]) was.get("dek_iv"), (byte[]) now.get("dek_iv")));
            assertArrayEquals((byte[]) was.get("value_iv"), (byte[]) now.get("value_iv"));
            assertArrayEquals((byte[]) was.get("ciphertext"), (byte[]) now.get("ciphertext"),
                    "the value ciphertext is byte-identical across a rotation");
        });

        // and every value still resolves, now through the new key alone (the old one retired)
        EnvelopeSecretStore after = SecretFixtures.store(SecretFixtures.ring(newKey, null));
        ids.forEach((s, t) -> {
            try (SecretMaterial m = after.resolve(t, s, SecretPurpose.CONFIG_FIELD)) {
                assertTrue(new String(m.chars()).startsWith("value-"));
            }
        });
        String text = progress.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tenant " + t1) && text.contains("tenant " + t2), text);
        assertFalse(text.contains("value-"), "the report never carries a value");
    }

    @Test
    void aTenantOutsideTheSecondListIsNotCoveredAndTheReportSaysSoUntilTheOperatorNamesIt() {
        String oldId = SecretFixtures.freshKeyId();
        MasterKey oldKey = SecretFixtures.key(oldId, 33);
        MasterKey newKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 34);
        UUID listed = UUID.randomUUID();
        UUID straggler = UUID.randomUUID(); // secrets, but no instance: D-KEY 5.4's gap
        SecretFixtures.seedInstance(listed, "active");

        boot(SecretFixtures.ring(oldKey, null));
        EnvelopeSecretStore before = SecretFixtures.store(SecretFixtures.ring(oldKey, null));
        before.put(listed, SecretPurpose.SSH_PRIVATE_KEY, "a".toCharArray(), ACTOR);
        UUID stranded = before.put(straggler, SecretPurpose.SSH_PRIVATE_KEY, "b".toCharArray(), ACTOR);
        MasterKeyRing during = SecretFixtures.ring(newKey, oldKey);
        boot(during);

        KeyRotationMain.Report first = KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), during, ENVELOPE,
                List.of(), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(oldId, row(straggler, stranded).get("key_id"), "the straggler is untouched");
        assertEquals(0, first.remaining(), "the covered set is clean…");
        String summary = first.summary();
        assertTrue(summary.contains("NOT covered"), summary);
        assertFalse(summary.toLowerCase().contains("none remain") || summary.toLowerCase().contains("all rows"),
                "never an unqualified 'none remain': " + summary);
        assertTrue(summary.contains("ANY tenant"), "the operator's own check is the retire gate: " + summary);

        KeyRotationMain.Report second = KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), during, ENVELOPE,
                List.of(straggler), new PrintStream(new ByteArrayOutputStream()));

        assertEquals(1, second.named());
        assertEquals(1, second.moved());
        assertEquals(second.fromList() + 1, second.covered());
        assertNotEquals(oldId, row(straggler, stranded).get("key_id"), "--tenant reached it");
    }

    @Test
    void aMistypedNewKeyIsRefusedBeforeAnyRowMoves() {
        MasterKey oldKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 35);
        String newId = SecretFixtures.freshKeyId();
        UUID tenant = UUID.randomUUID();
        SecretFixtures.seedInstance(tenant, "active");
        boot(SecretFixtures.ring(oldKey, null));
        UUID id = SecretFixtures.store(SecretFixtures.ring(oldKey, null))
                .put(tenant, SecretPurpose.CONFIG_FIELD, "v".toCharArray(), ACTOR);
        boot(SecretFixtures.ring(SecretFixtures.key(newId, 36), oldKey)); // the application minted id newId with key 36

        // the operator runs the command with the right id and the WRONG bytes
        MasterKeyRing typo = SecretFixtures.ring(SecretFixtures.key(newId, 99), oldKey);
        KeyRotationMain.RotationHalted halted = assertThrows(KeyRotationMain.RotationHalted.class,
                () -> KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), typo, ENVELOPE, List.of(),
                        new PrintStream(new ByteArrayOutputStream())));

        assertTrue(halted.getMessage().contains(newId), halted.getMessage());
        assertEquals(oldKey.id().value(), row(tenant, id).get("key_id"), "nothing was changed");
        // and a command given a new id the application never booted with is refused the same way
        MasterKeyRing unbooted = SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 37), oldKey);
        assertThrows(KeyRotationMain.RotationHalted.class, () -> KeyRotationMain.rotate(
                SmDataPostgres.runtimeDataSource(), unbooted, ENVELOPE, List.of(),
                new PrintStream(new ByteArrayOutputStream())));
    }

    @Test
    void aRowThatDoesNotAuthenticateStopsTheRunNamingTheTenantAndKeyAndNeverABytes() {
        MasterKey oldKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 38);
        MasterKey newKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 39);
        UUID tenant = UUID.randomUUID();
        SecretFixtures.seedInstance(tenant, "active");
        boot(SecretFixtures.ring(oldKey, null));
        SecretFixtures.store(SecretFixtures.ring(oldKey, null))
                .put(tenant, SecretPurpose.CONFIG_FIELD, "v".toCharArray(), ACTOR);
        // a corrupt row under the old key: valid shape, garbage content
        SmDataPostgres.superuserJdbc().update("INSERT INTO sm_secret.secret (tenant_id, secret_id, purpose, key_id,"
                        + " wrapped_dek, dek_iv, value_iv, ciphertext, aad_version, created_at, created_by)"
                        + " VALUES (:t, gen_random_uuid(), 'CONFIG_FIELD', :k, :w, :iv, :iv, :c, 1, now(), :u)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("k", oldKey.id().value())
                        .addValue("w", new byte[48]).addValue("iv", new byte[12]).addValue("c", new byte[20])
                        .addValue("u", ACTOR));
        MasterKeyRing during = SecretFixtures.ring(newKey, oldKey);
        boot(during);

        KeyRotationMain.RotationHalted halted = assertThrows(KeyRotationMain.RotationHalted.class,
                () -> KeyRotationMain.rotate(SmDataPostgres.runtimeDataSource(), during, ENVELOPE, List.of(),
                        new PrintStream(new ByteArrayOutputStream())));

        assertTrue(halted.getMessage().contains(tenant.toString()) && halted.getMessage().contains(oldKey.id().value()),
                halted.getMessage());
        assertEquals(2L, SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t AND key_id = :k",
                new MapSqlParameterSource().addValue("t", tenant).addValue("k", oldKey.id().value()), Long.class),
                "the failing batch rolled back whole");
    }

    @Test
    void aReaderDuringTheRewrapSeesTheRowEntirelyOldOrEntirelyNewAndDecryptsEitherWay() throws Exception {
        MasterKey oldKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 40);
        MasterKey newKey = SecretFixtures.key(SecretFixtures.freshKeyId(), 41);
        MasterKeyRing during = SecretFixtures.ring(newKey, oldKey);
        UUID tenant = UUID.randomUUID();
        EnvelopeSecretStore reader = SecretFixtures.store(during);
        UUID id = SecretFixtures.store(SecretFixtures.ring(oldKey, null))
                .put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "resumed-apply-key".toCharArray(), ACTOR);

        // a re-wrap transaction that has updated the row but NOT committed
        var ds = SmDataPostgres.runtimeDataSource();
        JdbcSecretRepository secrets = new JdbcSecretRepository(SmDataPostgres.runtimeJdbc());
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread rotator = new Thread(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    var rows = secrets.lockBatchUnderKey(tenant, oldKey.id(), 10);
                    var moved = ENVELOPE.rewrap(oldKey, newKey, tenant, id, SecretPurpose.SSH_PRIVATE_KEY,
                            rows.get(0).sealed());
                    secrets.rewrap(tenant, id, oldKey.id(), moved);
                    updated.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
                updated.countDown();
            }
        });
        rotator.start();
        assertTrue(updated.await(20, TimeUnit.SECONDS));

        try (SecretMaterial m = reader.resolve(tenant, id, SecretPurpose.SSH_PRIVATE_KEY)) { // mid-flight: old triple
            assertEquals("resumed-apply-key", new String(m.chars()));
        }
        assertEquals(oldKey.id().value(), row(tenant, id).get("key_id"), "uncommitted: still entirely old");
        release.countDown();
        rotator.join(20_000);
        assertEquals(null, failure.get());
        try (SecretMaterial m = reader.resolve(tenant, id, SecretPurpose.SSH_PRIVATE_KEY)) { // committed: new triple
            assertEquals("resumed-apply-key", new String(m.chars()));
        }
        assertEquals(newKey.id().value(), row(tenant, id).get("key_id"));
    }

    @Test
    void theCommandRunsEndToEndFromTheEnvironmentAndExitsZeroOnlyWhenTheCoveredTenantsAreClean() {
        String oldId = SecretFixtures.freshKeyId();
        String newId = SecretFixtures.freshKeyId();
        MasterKey oldKey = SecretFixtures.key(oldId, 42);
        MasterKey newKey = SecretFixtures.key(newId, 43);
        UUID tenant = UUID.randomUUID();
        SecretFixtures.seedInstance(tenant, "active");
        boot(SecretFixtures.ring(oldKey, null));
        SecretFixtures.store(SecretFixtures.ring(oldKey, null))
                .put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "v".toCharArray(), ACTOR);
        boot(SecretFixtures.ring(newKey, oldKey));

        Map<String, String> env = new HashMap<>();
        env.put(KeyRotationMain.URL_VARIABLE, SmDataPostgres.jdbcUrl());
        env.put(KeyRotationMain.USERNAME_VARIABLE, "sm_dp_runtime");
        env.put(KeyRotationMain.PASSWORD_VARIABLE, SmDataPostgres.RUNTIME_PASSWORD);
        env.put("SM_DP_MASTER_KEY", SecretFixtures.keyBase64(43));
        env.put("SM_DP_MASTER_KEY_ID", newId);
        env.put("SM_DP_MASTER_KEY_PREVIOUS", SecretFixtures.keyBase64(42));
        env.put("SM_DP_MASTER_KEY_PREVIOUS_ID", oldId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exit = KeyRotationMain.exitCode(new String[0], env, new PrintStream(out), new PrintStream(err));

        assertEquals(0, exit, err.toString(StandardCharsets.UTF_8));
        String printed = out + "\n" + err;
        assertTrue(printed.contains("NOT covered"), printed);
        for (String secret : new String[] {SmDataPostgres.RUNTIME_PASSWORD, SecretFixtures.keyBase64(42),
                SecretFixtures.keyBase64(43)}) {
            assertFalse(printed.contains(secret), "the command never prints a credential or a key");
        }

        // run again: nothing under the old id any more, still exit 0, and a wrong key mid-way exits 1 not 0
        env.put("SM_DP_MASTER_KEY", SecretFixtures.keyBase64(99));
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        assertEquals(1, KeyRotationMain.exitCode(new String[0], env, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err2)));
        assertFalse(err2.toString(StandardCharsets.UTF_8).contains(SecretFixtures.keyBase64(99)));
    }
}
