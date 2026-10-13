package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ssh.HostKeyScanner;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link TargetConnectionMain} against a real {@code sm_data} schema, connecting <b>as {@code sm_dp_runtime}</b> with
 * a real master key whose canary the application would have minted at boot (plan 010 T2.7). The key is stored sealed,
 * by reference, and opens again with the same master key; nothing is echoed; a re-run writes nothing.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetConnectionMainIT {

    /** Plan 013 T1.1: the key must OPEN as an unencrypted private key before it is sealed, so it is a real one. */
    private static final SshTestKeys.Key KEY = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    private static final String KEY_TEXT = KEY.text();
    /** A line of the key's body: its presence in any output, row or table would be a leak. */
    private static final String KEY_BODY = KEY_TEXT.lines().skip(1).findFirst().orElseThrow();

    private MasterKey master;
    private MasterKeyRing ring;
    private UUID tenant;
    private UUID targetId;
    private String fingerprint;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @BeforeEach
    void setUp() {
        SecretFixtures.resetCanaries();
        master = SecretFixtures.key(SecretFixtures.freshKeyId(), 41);
        ring = SecretFixtures.ring(master, null);
        // the application's boot check, as the install procedure does it before an operator registers anything
        new KekCanaryCheck(ring, new JdbcKekCanaryRepository(SmDataPostgres.runtimeJdbc()), Envelope.standard(),
                Clock.systemUTC()).run();
        tenant = UUID.randomUUID();
        targetId = UUID.randomUUID();
        fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                UUID.randomUUID().toString().concat(UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8))
                .substring(0, 43);
    }

    private Map<String, String> env(MasterKey key, UUID tenantId, UUID target, String host, String fp) {
        Map<String, String> env = new HashMap<>();
        env.put("SM_DATA_RUNTIME_URL", SmDataPostgres.jdbcUrl());
        env.put("SM_DATA_RUNTIME_USERNAME", "sm_dp_runtime");
        env.put("SM_DATA_RUNTIME_PASSWORD", SmDataPostgres.RUNTIME_PASSWORD);
        env.put("SM_DP_MASTER_KEY", Base64.getEncoder().encodeToString(SecretFixtures.keyBytes(41)));
        env.put("SM_DP_MASTER_KEY_ID", key.id().toString());
        env.put(TargetConnectionMain.TENANT_VARIABLE, tenantId.toString());
        env.put(TargetConnectionMain.TARGET_ID_VARIABLE, target.toString());
        env.put(TargetConnectionMain.HOST_VARIABLE, host);
        env.put(TargetConnectionMain.SSH_USER_VARIABLE, "sm");
        env.put(TargetConnectionMain.FINGERPRINT_VARIABLE, fp);
        env.put(TargetConnectionMain.CONFIG_ROOT_VARIABLE, "/srv/sm");
        env.put(TargetConnectionMain.CONTAINER_PREFIX_VARIABLE, "sm-");
        env.put(TargetConnectionMain.KEY_FILE_VARIABLE, "/not/read/by/this/reader");
        return env;
    }

    private record Result(int exit, String out, String err, char[] buffer) {
    }

    private static Result run(Map<String, String> env) {
        return run(env, (host, port) -> Set.of(env.get(TargetConnectionMain.FINGERPRINT_VARIABLE)));
    }

    private static Result run(Map<String, String> env, HostKeyScanner scanner) {
        return run(env, scanner, KEY_TEXT);
    }

    private static Result run(Map<String, String> env, HostKeyScanner scanner, String keyFileText) {
        char[] buffer = keyFileText.toCharArray();
        Function<String, char[]> reader = path -> buffer;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = TargetConnectionMain.exitCode(env, reader, scanner, new PrintStream(out), new PrintStream(err));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8), buffer);
    }

    private Map<String, Object> connection(UUID tenantId, UUID target) {
        return SmDataPostgres.superuserJdbc().queryForMap("SELECT host, port, ssh_user, host_key_fingerprint,"
                        + " credential_ref, allowed_config_root, allowed_container_prefix, created_by, retired_at"
                        + " FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :g",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("g", target));
    }

    private int secrets(UUID tenantId) {
        return SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenantId), Integer.class);
    }

    @Test
    void theCommandStoresTheKeyByReferenceAndTheEnvelopeAndNeverEchoesTheKey() {
        Result r = run(env(master, tenant, targetId, "vm-a.example.test", fingerprint));

        assertEquals(0, r.exit, r.err);
        assertTrue(r.out.contains(targetId.toString()), r.out);
        String all = r.out + r.err;
        assertFalse(all.contains(KEY_BODY) || all.contains(SmDataPostgres.RUNTIME_PASSWORD), all);
        for (char c : r.buffer) {
            assertEquals('\0', c, "the buffer is zeroised");
        }
        Map<String, Object> row = connection(tenant, targetId);
        assertEquals("vm-a.example.test", row.get("host"));
        assertEquals(22, row.get("port"));
        assertEquals("/srv/sm", row.get("allowed_config_root"));
        assertEquals("sm-", row.get("allowed_container_prefix"));
        assertEquals(TargetConnectionMain.OPERATOR_ACTOR, row.get("created_by"));
        assertEquals(null, row.get("retired_at"));
        // the reference opens, under the same master key, to exactly the key that was in the file
        try (SecretMaterial m = SecretFixtures.store(ring).resolve(tenant, (UUID) row.get("credential_ref"),
                SecretPurpose.SSH_PRIVATE_KEY)) {
            assertArrayEquals(KEY_TEXT.toCharArray(), m.chars());
        }
        String stored = SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT encode(ciphertext, 'escape') FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), String.class);
        assertFalse(stored.contains(KEY_BODY), "the table holds ciphertext, never the key");
    }

    @Test
    void runningTheCommandAgainWritesNothingAndStoresNoSecondKey() {
        Map<String, String> env = env(master, tenant, targetId, "vm-b.example.test", fingerprint);
        assertEquals(0, run(env).exit);
        Object credential = connection(tenant, targetId).get("credential_ref");

        Result again = run(env);

        assertEquals(0, again.exit, again.err);
        assertEquals(credential, connection(tenant, targetId).get("credential_ref"));
        assertEquals(1, secrets(tenant), "an identical re-run stores no second key");
        assertFalse(again.out.contains("recorded"), "a re-run records nothing and must not say it did: " + again.out);
        assertTrue(again.out.contains("already registered") && again.out.contains("credential unchanged"),
                again.out);
    }

    @Test
    void aRetiredTargetIsNotReportedAsRecordedAndStoresNoKey() {
        Map<String, String> env = env(master, tenant, targetId, "vm-r.example.test", fingerprint);
        assertEquals(0, run(env).exit);
        SmDataPostgres.superuserJdbc().update("UPDATE sm_data.target_connection SET retired_at = now()"
                        + " WHERE tenant_id = :t AND target_id = :g",
                new MapSqlParameterSource().addValue("t", tenant).addValue("g", targetId));

        Result r = run(env);

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.out);
        assertTrue(r.err.contains("retired"), r.err);
        assertEquals("", r.out);
        assertEquals(1, secrets(tenant));
    }

    @Test
    void aDifferingRecordForTheSameTargetIsRefusedAndStoresNoKey() {
        assertEquals(0, run(env(master, tenant, targetId, "vm-c.example.test", fingerprint)).exit);

        Map<String, String> different = env(master, tenant, targetId, "vm-c2.example.test", fingerprint);
        Result r = run(different);

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("different connection record"), r.err);
        assertEquals("vm-c.example.test", connection(tenant, targetId).get("host"));
        assertEquals(1, secrets(tenant));
        assertEquals("", r.out);
    }

    @Test
    void aRerunWithADifferentKeyFileIsRefusedAndTheStoredKeyIsKept() {
        Map<String, String> env = env(master, tenant, targetId, "vm-k.example.test", fingerprint);
        assertEquals(0, run(env).exit);
        Object credential = connection(tenant, targetId).get("credential_ref");
        SshTestKeys.Key other = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());

        Result r = run(env, (host, port) -> Set.of(fingerprint), other.text());

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("different private key") && r.err.contains("rotate"), r.err);
        assertEquals("", r.out, "nothing was recorded, and the command must not say it did");
        assertEquals(credential, connection(tenant, targetId).get("credential_ref"));
        assertEquals(1, secrets(tenant));
        assertFalse((r.out + r.err).contains(other.text().lines().skip(1).findFirst().orElseThrow()));
    }

    @Test
    void aRerunWithTheSameKeyReExportedIsStillAReplay() {
        java.security.KeyPair pair = SshTestKeys.rsaPair();
        Map<String, String> env = env(master, tenant, targetId, "vm-x.example.test", fingerprint);
        assertEquals(0, run(env, (host, port) -> Set.of(fingerprint), SshTestKeys.rsaPkcs8(pair).text()).exit);

        Result again = run(env, (host, port) -> Set.of(fingerprint), SshTestKeys.rsaPkcs1(pair).crlf().text());

        assertEquals(0, again.exit, again.err);
        assertTrue(again.out.contains("already registered"), again.out);
        assertEquals(1, secrets(tenant));
    }

    @Test
    void aKeyFileThatIsNotAnUnencryptedPrivateKeyIsRefusedAndNothingIsWritten() {
        Map<String, String> env = env(master, tenant, targetId, "vm-n.example.test", fingerprint);

        Result r = run(env, (host, port) -> Set.of(fingerprint), "-----BEGIN OPENSSH PRIVATE KEY-----\nSENTINEL-key-body"
                + "\n-----END OPENSSH PRIVATE KEY-----\n");

        assertEquals(TargetConnectionMain.EXIT_MISSING_VARIABLE, r.exit, r.err);
        assertTrue(r.err.contains("not accepted"), r.err);
        assertFalse((r.out + r.err).contains("SENTINEL"), r.err);
        assertEquals(0, secrets(tenant));
        assertEquals(0, SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), Integer.class));
    }

    @Test
    void aMasterKeyThatIsNotTheInstallsIsRefusedBeforeAnyKeyIsSealed() {
        MasterKey stranger = SecretFixtures.key(SecretFixtures.freshKeyId(), 99);
        Map<String, String> env = env(stranger, tenant, targetId, "vm-d.example.test", fingerprint);
        env.put("SM_DP_MASTER_KEY", Base64.getEncoder().encodeToString(SecretFixtures.keyBytes(99)));

        Result r = run(env);

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit);
        assertTrue(r.err.contains("canary"), r.err);
        assertEquals(0, secrets(tenant));
        assertEquals(0, SmDataPostgres.superuserJdbc().queryForObject(
                "SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t",
                new MapSqlParameterSource().addValue("t", tenant), Integer.class));
        assertFalse((r.out + r.err).contains(KEY_BODY));
    }

    @Test
    void anotherOrganizationMayRegisterTheSameHostKey() {
        assertEquals(0, run(env(master, tenant, targetId, "vm-f.example.test", fingerprint)).exit);
        UUID otherTenant = UUID.randomUUID();

        Result r = run(env(master, otherTenant, UUID.randomUUID(), "vm-f-other-org.example.test", fingerprint));

        assertEquals(0, r.exit, "OR-89: host-key uniqueness is per organization: " + r.err);
        assertEquals(1, secrets(otherTenant));
    }

    @Test
    void oneLiveTargetPerSshHostIsRefusedByNamingTheConstraintAndLeavesNoStrandedKey() {
        assertEquals(0, run(env(master, tenant, targetId, "vm-e.example.test", fingerprint)).exit);
        int before = secrets(tenant);

        // OR-89: the host key is unique PER ORGANIZATION, so the refusal is the same organization registering it twice
        Result r = run(env(master, tenant, UUID.randomUUID(), "vm-e-alias.example.test", fingerprint));

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("target_connection_host_once"), r.err);
        assertEquals(before, secrets(tenant), "the key and the row are one transaction: a refused row strands no key");
    }

    // ---- OR-89's residual (plan 010 T7.1): a host offering several key types ------------------------------------

    private static String freshFingerprint() {
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                UUID.randomUUID().toString().concat(UUID.randomUUID().toString()).getBytes(StandardCharsets.UTF_8))
                .substring(0, 43);
    }

    private Set<String> hostKeys(UUID tenantId, UUID target) {
        return new java.util.TreeSet<>(SmDataPostgres.superuserJdbc().queryForList(
                "SELECT fingerprint FROM sm_data.target_host_key WHERE tenant_id = :t AND target_id = :g",
                new MapSqlParameterSource().addValue("t", tenantId).addValue("g", target), String.class));
    }

    @Test
    void everyKeyTypeTheHostOffersIsRecordedForTheTarget() {
        String other = freshFingerprint();
        Result r = run(env(master, tenant, targetId, "vm-k1.example.test", fingerprint),
                (host, port) -> Set.of(fingerprint, other));

        assertEquals(0, r.exit, r.err);
        assertEquals(new java.util.TreeSet<>(Set.of(fingerprint, other)), hostKeys(tenant, targetId));
    }

    @Test
    void aHostOfferingSeveralKeyTypesCannotBePinnedTwiceInOneOrganization() {
        String other = freshFingerprint();
        HostKeyScanner both = (host, port) -> Set.of(fingerprint, other);
        assertEquals(0, run(env(master, tenant, targetId, "vm-k2.example.test", fingerprint), both).exit);
        int before = secrets(tenant);

        // the same machine, reached by another name, pinned by its OTHER key type: V007's pinned-key index cannot see it
        Result r = run(env(master, tenant, UUID.randomUUID(), "vm-k2-alias.example.test", other), both);

        assertEquals(TargetConnectionMain.EXIT_FAILED, r.exit, r.err);
        assertTrue(r.err.contains("target_host_key_once"), r.err);
        assertEquals(before, secrets(tenant), "a refused registration strands no key");
    }

    @Test
    void theOperatorsExplicitListIsUsedAndTheHostIsNotContacted() {
        String other = freshFingerprint();
        Map<String, String> env = env(master, tenant, targetId, "vm-k3.example.test", fingerprint);
        env.put(TargetConnectionMain.FINGERPRINTS_VARIABLE, fingerprint + " , " + other);

        Result r = run(env, (host, port) -> {
            throw new AssertionError("the host must not be contacted when the operator listed its keys");
        });

        assertEquals(0, r.exit, r.err);
        assertEquals(new java.util.TreeSet<>(Set.of(fingerprint, other)), hostKeys(tenant, targetId));
    }

    @Test
    void aHostThatCannotBeAskedAndNoListIsRefusedBeforeAnythingIsStored() {
        Result r = run(env(master, tenant, targetId, "vm-k4.example.test", fingerprint), (host, port) -> {
            throw new IllegalStateException("unreachable");
        });

        assertEquals(2, r.exit, r.err);
        assertTrue(r.err.contains(TargetConnectionMain.FINGERPRINTS_VARIABLE), r.err);
        assertEquals(0, secrets(tenant));
        assertTrue(hostKeys(tenant, targetId).isEmpty());
    }

    @Test
    void aPinThatIsNotOneOfTheHostsKeysIsRefused() {
        Result r = run(env(master, tenant, targetId, "vm-k5.example.test", fingerprint),
                (host, port) -> Set.of(freshFingerprint()));

        assertEquals(2, r.exit, r.err);
        assertTrue(r.err.contains(TargetConnectionMain.FINGERPRINT_VARIABLE), r.err);
        assertEquals(0, secrets(tenant));
    }

    @Test
    void aRerunLearnsAKeyTypeTheFirstRunDidNotKnowAndRefusesOneAnotherTargetHolds() {
        String later = freshFingerprint();
        assertEquals(0, run(env(master, tenant, targetId, "vm-k6.example.test", fingerprint)).exit);
        UUID second = UUID.randomUUID();
        String secondPin = freshFingerprint();
        assertEquals(0, run(env(master, tenant, second, "vm-k6b.example.test", secondPin)).exit);

        Result learned = run(env(master, tenant, targetId, "vm-k6.example.test", fingerprint),
                (host, port) -> Set.of(fingerprint, later));
        assertEquals(0, learned.exit, learned.err);
        assertEquals(new java.util.TreeSet<>(Set.of(fingerprint, later)), hostKeys(tenant, targetId));

        // ...but a key the OTHER live target holds is a second registration of that machine
        Result clash = run(env(master, tenant, targetId, "vm-k6.example.test", fingerprint),
                (host, port) -> Set.of(fingerprint, later, secondPin));
        assertEquals(TargetConnectionMain.EXIT_FAILED, clash.exit, clash.err);
        assertTrue(clash.err.contains("target_host_key_once"), clash.err);
    }

    @Test
    void anotherOrganizationMayRecordEveryKeyOfTheSameHost() {
        String other = freshFingerprint();
        HostKeyScanner both = (host, port) -> Set.of(fingerprint, other);
        assertEquals(0, run(env(master, tenant, targetId, "vm-k7.example.test", fingerprint), both).exit);

        Result r = run(env(master, UUID.randomUUID(), UUID.randomUUID(), "vm-k7-other-org.example.test", other), both);

        assertEquals(0, r.exit, "OR-89: uniqueness is per organization: " + r.err);
    }
}
