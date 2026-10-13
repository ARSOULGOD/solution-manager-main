package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.PrivateKeyInspector;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T1.1 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the ONE validation path (grammar, then key
 * parse, then compare) in {@link InventoryService}, and what it writes and does not write (D-REG section 8, R11).
 *
 * <p>A key is compared by the fingerprint of its PUBLIC half, so a re-export of the same key is a replay; a different
 * key is {@link TargetCredentialConflictException}; and neither writes a second {@code sm_secret.secret} row (the
 * runtime has no {@code DELETE} on {@code sm_secret}, so a stray row is forever: OR-85).
 *
 * <p><b>Not here, and why.</b> "A legacy {@code NULL} fingerprint is back-filled on replay" needs the
 * {@code credential_public_fingerprint} column, which is {@code sm_data} V022 (plan 013 T1.3, wave 2). Until then the
 * stored key's fingerprint is always derived by opening the stored secret in {@code dp-core} (the legacy path of
 * D-REG section 8), which is what every test below exercises.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetCredentialReplayIT {

    private static final UUID ACTOR = UUID.randomUUID();

    private static NamedParameterJdbcTemplate superuser;
    private static EnvelopeSecretStore secrets;
    private static JdbcTargetConnectionRepository connections;
    private static InventoryService service;
    private final PrivateKeyInspector inspector = new PrivateKeyInspector();

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 31), null));
        connections = new JdbcTargetConnectionRepository(jdbc);
        service = new InventoryService(
                new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc), connections, secrets,
                secrets, Clock.systemUTC(), new JdbcMetricDefinitionRepository(jdbc));
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static String fingerprint() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static TargetConnectionSpec spec() {
        return new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", fingerprint(), "/srv/sm", "sm-");
    }

    private static SshTestKeys.Key ed25519() {
        return SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    }

    private static long secretRows(UUID tenant) {
        return superuser.queryForObject("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    private static long connectionRows(UUID tenant) {
        return superuser.queryForObject("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    private static UUID registered(UUID tenant, TargetConnectionSpec spec, SshTestKeys.Key key) {
        UUID target = UUID.randomUUID();
        service.register(tenant, target, spec, key.chars(), ACTOR);
        return target;
    }

    private static String stored(UUID tenant, UUID target) {
        TargetConnectionRecord record = service.connectionOf(tenant, target).orElseThrow();
        try (SecretMaterial m = secrets.resolve(tenant, record.credentialRef(), SecretPurpose.SSH_PRIVATE_KEY)) {
            return new String(m.chars());
        }
    }

    /** I6: no thrown text, and no stack trace, carries a line of the key. */
    private static void assertNoKeyText(Throwable e, SshTestKeys.Key key) {
        StringWriter trace = new StringWriter();
        e.printStackTrace(new PrintWriter(trace));
        for (String line : key.text().split("\\R")) {
            if (line.length() >= 8 && !line.startsWith("-----")) {
                assertFalse(trace.toString().contains(line), "a key line is in: " + trace);
            }
        }
    }

    // ---- parse before store --------------------------------------------------------------------------------

    @Test
    void anUnparseableKeyIsRefusedAndNothingIsWritten() {
        UUID tenant = SmDataPostgres.freshTenant();

        CredentialInvalidException e = assertThrows(CredentialInvalidException.class,
                () -> service.register(tenant, UUID.randomUUID(), spec(), "sentinel-key-one".toCharArray(), ACTOR));

        assertEquals(CredentialInvalidException.MESSAGE, e.getMessage());
        assertFalse(e.getMessage().contains("sentinel"), e.getMessage());
        assertEquals(0, secretRows(tenant), "no secret row: the runtime cannot delete one (OR-85)");
        assertEquals(0, connectionRows(tenant));
    }

    @Test
    void aPassphraseProtectedKeyIsRefusedAndNothingIsWritten() {
        assumeTrue(SshTestKeys.sshKeygenAvailable(), "ssh-keygen is not on the path");
        UUID tenant = SmDataPostgres.freshTenant();
        SshTestKeys.Key encrypted = SshTestKeys.viaSshKeygen("ed25519", "OPENSSH", "test-passphrase");

        CredentialInvalidException e = assertThrows(CredentialInvalidException.class,
                () -> service.register(tenant, UUID.randomUUID(), spec(), encrypted.chars(), ACTOR));

        assertNoKeyText(e, encrypted);
        assertEquals(0, secretRows(tenant));
        assertEquals(0, connectionRows(tenant));
    }

    @Test
    void aFieldOutsideTheGrammarIsRefusedAndNothingIsWritten() {
        UUID tenant = SmDataPostgres.freshTenant();
        TargetConnectionSpec bad = new TargetConnectionSpec("vm 1; rm -rf /", 22, "ec2-user", fingerprint(), "/srv/sm",
                "sm-");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.register(tenant, UUID.randomUUID(), bad, ed25519().chars(), ACTOR));

        assertTrue(e.getMessage().contains("host"), e.getMessage());
        assertFalse(e.getMessage().contains("rm -rf"), "the value is never echoed: " + e.getMessage());
        assertEquals(0, secretRows(tenant));
        assertEquals(0, connectionRows(tenant));
    }

    // ---- replay: compared by the public key ---------------------------------------------------------------

    @Test
    void theSameKeyReExportedIsAReplayAndWritesNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        TargetConnectionSpec spec = spec();
        KeyPair pair = SshTestKeys.rsaPair();
        UUID target = registered(tenant, spec, SshTestKeys.rsaPkcs8(pair));
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();

        for (SshTestKeys.Key reExport : new SshTestKeys.Key[] {SshTestKeys.rsaPkcs1(pair),
                SshTestKeys.rsaPkcs8(pair).crlf(), SshTestKeys.rsaPkcs8(pair).withoutTrailingNewline(),
                SshTestKeys.rsaPkcs8(pair)}) {
            service.register(tenant, target, spec, reExport.chars(), ACTOR);
        }

        assertEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef(), "no new secret stored");
        assertEquals(1, secretRows(tenant), "a replay never stores a second copy of the key");
        assertEquals(1, connectionRows(tenant));
    }

    @Test
    void aDifferentKeyOnReplayIsRefusedNotSilentlyDropped() {
        UUID tenant = SmDataPostgres.freshTenant();
        TargetConnectionSpec spec = spec();
        SshTestKeys.Key first = ed25519();
        UUID target = registered(tenant, spec, first);
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();
        SshTestKeys.Key other = ed25519();

        TargetCredentialConflictException e = assertThrows(TargetCredentialConflictException.class,
                () -> service.register(tenant, target, spec, other.chars(), ACTOR));

        assertEquals(TargetCredentialConflictException.MESSAGE, e.getMessage());
        assertNoKeyText(e, other);
        assertNoKeyText(e, first);
        assertEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef());
        assertEquals(first.text(), stored(tenant, target), "the stored key is untouched");
        assertEquals(1, secretRows(tenant));
    }

    @Test
    void aDifferingRecordIsStillRefusedFirst() {
        UUID tenant = SmDataPostgres.freshTenant();
        TargetConnectionSpec spec = spec();
        SshTestKeys.Key key = ed25519();
        UUID target = registered(tenant, spec, key);
        TargetConnectionSpec wider = new TargetConnectionSpec(spec.host(), spec.port(), spec.sshUser(),
                spec.hostKeyFingerprint(), "/srv", spec.allowedContainerPrefix());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.register(tenant, target, wider, ed25519().chars(), ACTOR));

        assertFalse(e instanceof TargetCredentialConflictException, "the record differs; that is the refusal: " + e);
        assertTrue(e.getMessage().contains("different connection record"), e.getMessage());
    }

    // ---- rotation ------------------------------------------------------------------------------------------

    @Test
    void rotatingToTheSameKeyWritesNoSecretRow() {
        UUID tenant = SmDataPostgres.freshTenant();
        KeyPair pair = SshTestKeys.rsaPair();
        UUID target = registered(tenant, spec(), SshTestKeys.rsaPkcs8(pair));
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();
        long before = secretRows(tenant);

        service.rotateCredential(tenant, target, SshTestKeys.rsaPkcs1(pair).chars(), ACTOR);
        service.rotateCredential(tenant, target, SshTestKeys.rsaPkcs8(pair).crlf().chars(), ACTOR);

        assertEquals(before, secretRows(tenant), "a double-click mints no second permanent secret (OR-85)");
        assertEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef());
    }

    @Test
    void rotatingToADifferentKeyStoresOneNewSecretAndRepointsTheRecord() {
        UUID tenant = SmDataPostgres.freshTenant();
        SshTestKeys.Key first = ed25519();
        UUID target = registered(tenant, spec(), first);
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();
        SshTestKeys.Key second = ed25519();

        service.rotateCredential(tenant, target, second.chars(), ACTOR);

        assertNotEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef());
        assertEquals(2, secretRows(tenant), "the old row stays: the runtime has no DELETE on sm_secret");
        assertEquals(second.text(), stored(tenant, target));
        assertEquals(second.publicFingerprint(), inspector.publicFingerprint(stored(tenant, target).toCharArray()));
    }

    @Test
    void rotatingToAnUnparseableKeyIsRefusedAndNothingIsWritten() {
        UUID tenant = SmDataPostgres.freshTenant();
        SshTestKeys.Key first = ed25519();
        UUID target = registered(tenant, spec(), first);
        UUID ref = service.connectionOf(tenant, target).orElseThrow().credentialRef();

        assertThrows(CredentialInvalidException.class,
                () -> service.rotateCredential(tenant, target, "sentinel-key-two".toCharArray(), ACTOR));

        assertEquals(ref, service.connectionOf(tenant, target).orElseThrow().credentialRef());
        assertEquals(1, secretRows(tenant));
    }

    @Test
    void rotatingAnUnknownTargetIsStillNoSuchElement() {
        UUID tenant = SmDataPostgres.freshTenant();

        assertThrows(NoSuchElementException.class,
                () -> service.rotateCredential(tenant, UUID.randomUUID(), ed25519().chars(), ACTOR));
        assertEquals(0, secretRows(tenant));
    }

    /**
     * The stored key is opened only for a compare that has no recorded fingerprint to use, and its material is closed
     * (zeroised) on every path. Since V022 a row carries its key's public fingerprint, so a normal replay, conflict or
     * same-key rotation opens NOTHING (D-REG section 8: "no secret is opened on the normal path"); only a legacy row
     * (a {@code NULL} fingerprint) is opened, once, and the first compare back-fills it.
     */
    @Test
    void theStoredKeyIsOpenedOnlyForALegacyRowAndClosedAfterEveryCompare() {
        UUID tenant = SmDataPostgres.freshTenant();
        java.util.List<SecretMaterial> opened = new java.util.ArrayList<>();
        com.solace.sm.dp.secret.SecretResolver recording = (t, id, purpose) -> {
            SecretMaterial m = secrets.resolve(t, id, purpose);
            opened.add(m);
            return m;
        };
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        InventoryService recordingService = new InventoryService(
                new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc), connections, secrets,
                recording, Clock.systemUTC(), new JdbcMetricDefinitionRepository(jdbc));
        TargetConnectionSpec spec = spec();
        SshTestKeys.Key key = ed25519();
        UUID target = UUID.randomUUID();
        recordingService.register(tenant, target, spec, key.chars(), ACTOR);
        assertEquals(0, opened.size(), "a first registration opens no stored key");

        recordingService.register(tenant, target, spec, key.chars(), ACTOR);                       // same: replay
        assertThrows(TargetCredentialConflictException.class,
                () -> recordingService.register(tenant, target, spec, ed25519().chars(), ACTOR));  // different
        recordingService.rotateCredential(tenant, target, key.chars(), ACTOR);                      // same: no write
        assertEquals(0, opened.size(), "the recorded public fingerprint is compared; no secret is opened");

        // A legacy row: no fingerprint column value. Its stored key is opened for the first compare, closed after it,
        // and the compare back-fills the column so the next one opens nothing.
        TargetConnectionSpec legacySpec = spec();
        UUID legacy = UUID.randomUUID();
        UUID stored = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, key.chars(), ACTOR);
        connections.insert(new TargetConnectionRecord(tenant, legacy, legacySpec.host(), legacySpec.port(),
                legacySpec.sshUser(), legacySpec.hostKeyFingerprint(), stored, legacySpec.allowedConfigRoot(),
                legacySpec.allowedContainerPrefix(), ACTOR, java.time.Instant.now(), null));
        recordingService.register(tenant, legacy, legacySpec, key.chars(), ACTOR);                 // same: replay
        assertEquals(1, opened.size());
        assertThrows(TargetCredentialConflictException.class,
                () -> recordingService.register(tenant, legacy, legacySpec, ed25519().chars(), ACTOR));
        recordingService.rotateCredential(tenant, legacy, key.chars(), ACTOR);
        assertEquals(1, opened.size(), "the first compare back-filled the fingerprint: nothing more is opened");
        for (SecretMaterial m : opened) {
            assertTrue(m.isClosed(), "the stored key's material was closed after the compare");
        }
    }

    // ---- a row registered before this validation existed ---------------------------------------------------

    /**
     * Rows registered by the operator command before T1.1 hold whatever key the operator had, parsed or not. The
     * stored key cannot be shown to be the candidate's, so a replay is a conflict, and rotation (the remedy the
     * conflict names) must still be able to replace it.
     */
    @Test
    void aStoredKeyThatCannotBeOpenedIsDifferentOnReplayAndReplaceableByRotation() {
        UUID tenant = SmDataPostgres.freshTenant();
        TargetConnectionSpec spec = spec();
        UUID target = UUID.randomUUID();
        UUID legacy = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "legacy-not-a-key".toCharArray(), ACTOR);
        connections.insert(new TargetConnectionRecord(tenant, target, spec.host(), spec.port(), spec.sshUser(),
                spec.hostKeyFingerprint(), legacy, spec.allowedConfigRoot(), spec.allowedContainerPrefix(), ACTOR,
                java.time.Instant.now(), null));
        SshTestKeys.Key real = ed25519();

        assertThrows(TargetCredentialConflictException.class,
                () -> service.register(tenant, target, spec, real.chars(), ACTOR));
        service.rotateCredential(tenant, target, real.chars(), ACTOR);

        assertEquals(real.text(), stored(tenant, target));
        assertEquals(2, secretRows(tenant));
        service.register(tenant, target, spec, real.chars(), ACTOR);          // now the same key: a replay
        assertEquals(2, secretRows(tenant));
    }
}
