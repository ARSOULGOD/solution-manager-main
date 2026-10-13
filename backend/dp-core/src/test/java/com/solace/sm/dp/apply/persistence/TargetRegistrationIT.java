package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretPurpose;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T1.4 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the data plane's half of {@code registerTarget}
 * (D-REG section 8) and {@code getTargetConnection}. What it adds to the offline {@code InventoryRegistrationTest} is
 * everything only the database decides: the primary-key and host-key unique violations of two requests that really race,
 * that the loser's secret is rolled back with its transaction, the V022 column and event table, and the back-fill of a
 * legacy row's fingerprint.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetRegistrationIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "198.51.100.7");

    private static NamedParameterJdbcTemplate superuser;
    private static EnvelopeSecretStore secrets;
    private static JdbcTargetConnectionRepository connections;
    private static InventoryService service;
    private static volatile List<OfferedHostKey> offered = List.of();

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 31), null));
        connections = new JdbcTargetConnectionRepository(jdbc);
        HostKeyScanService scanner = (host, port) -> offered;
        service = new InventoryService(
                new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc), connections, secrets, secrets,
                Clock.systemUTC(), new JdbcMetricDefinitionRepository(jdbc), LadderRecorder.none(),
                ApplyEventEmitter.silent(), scanner);
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static String fingerprint() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static TargetConnectionSpec spec(String pin, String... others) {
        return new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", pin, "/srv/sm", "sm-", Set.of(others));
    }

    private static SshTestKeys.Key ed25519() {
        return SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    }

    private static long count(String table, UUID tenant) {
        return superuser.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    private static long secretRows(UUID tenant) {
        return count("sm_secret.secret", tenant);
    }

    private static long connectionRows(UUID tenant) {
        return count("sm_data.target_connection", tenant);
    }

    private static long eventRows(UUID tenant) {
        return count("sm_data.target_connection_event", tenant);
    }

    // ---- a new registration ----------------------------------------------------------------------------------

    @Test
    void aNewRegistrationWritesTheRowTheFingerprintTheHostKeysTheSecretAndTheEvent() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        String pin = fingerprint();
        String second = fingerprint();
        SshTestKeys.Key key = ed25519();

        RegistrationOutcome outcome = service.registerConnection(tenant, target, spec(pin, second), key.chars(), ACTOR,
                ORIGIN);

        assertEquals(RegistrationOutcome.CREATED, outcome);
        TargetConnectionRecord row = service.connectionOf(tenant, target).orElseThrow();
        assertEquals(key.publicFingerprint(), row.credentialPublicFingerprint());
        assertEquals(1, secretRows(tenant));
        assertEquals(Set.of(pin, second), connections.liveHostKeys(tenant, target));
        Map<String, Object> event = superuser.queryForMap("SELECT * FROM sm_data.target_connection_event"
                + " WHERE tenant_id = :t AND target_id = :target", new MapSqlParameterSource("t", tenant)
                        .addValue("target", target));
        assertEquals("registered", event.get("kind"));
        assertEquals(ACTOR, event.get("actor"));
        assertEquals(ORIGIN.correlationId(), event.get("correlation_id"));
        assertEquals("198.51.100.7", event.get("client_address"));
        assertEquals(key.publicFingerprint(), event.get("public_key_fingerprint"));
        assertEquals(0, superuser.queryForObject("SELECT cardinality(changed_fields) FROM"
                + " sm_data.target_connection_event WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant),
                Integer.class), "no field name: a registration is not a change of fields");
    }

    @Test
    void theManagementReadReturnsTheLiveRecordAndNoSecret() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        String pin = fingerprint();
        SshTestKeys.Key key = ed25519();
        service.registerConnection(tenant, target, spec(pin), key.chars(), ACTOR, ORIGIN);

        TargetConnectionView view = service.connection(tenant, target).orElseThrow();

        assertEquals(pin, view.hostKeyFingerprint());
        assertEquals(List.of(pin), view.offeredHostKeys());
        assertEquals(key.publicFingerprint(), view.credential().publicKeyFingerprint());
        assertNotNull(view.credential().setAt());
        assertFalse(view.toString().contains(service.connectionOf(tenant, target).orElseThrow().credentialRef()
                .toString()));
    }

    // ---- replay -----------------------------------------------------------------------------------------------

    @Test
    void theSameRegistrationAgainIsAReplayAndWritesNoSecretNoRowAndNoEvent() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        TargetConnectionSpec spec = spec(fingerprint());
        SshTestKeys.Key key = ed25519();
        service.registerConnection(tenant, target, spec, key.chars(), ACTOR, ORIGIN);

        assertEquals(RegistrationOutcome.REPLAYED,
                service.registerConnection(tenant, target, spec, key.crlf().chars(), ACTOR, ORIGIN));

        assertEquals(1, secretRows(tenant));
        assertEquals(1, connectionRows(tenant));
        assertEquals(1, eventRows(tenant));
    }

    @Test
    void aDifferentKeyIsAConflictAndWritesNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        TargetConnectionSpec spec = spec(fingerprint());
        service.registerConnection(tenant, target, spec, ed25519().chars(), ACTOR, ORIGIN);

        assertThrows(TargetCredentialConflictException.class,
                () -> service.registerConnection(tenant, target, spec, ed25519().chars(), ACTOR, ORIGIN));

        assertEquals(1, secretRows(tenant));
        assertEquals(1, eventRows(tenant));
    }

    @Test
    void aLegacyRowWithNoFingerprintIsBackfilledOnItsFirstReplay() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        TargetConnectionSpec spec = spec(fingerprint());
        SshTestKeys.Key key = ed25519();
        UUID stored = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, key.chars(), ACTOR);
        connections.insert(new TargetConnectionRecord(tenant, target, spec.host(), spec.port(), spec.sshUser(),
                spec.hostKeyFingerprint(), stored, spec.allowedConfigRoot(), spec.allowedContainerPrefix(), ACTOR,
                java.time.Instant.now(), null));
        assertNull(service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint());

        assertEquals(RegistrationOutcome.REPLAYED,
                service.registerConnection(tenant, target, spec, key.chars(), ACTOR, ORIGIN));

        assertEquals(key.publicFingerprint(),
                service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint());
        assertEquals(1, secretRows(tenant), "the replay stored no second key");
    }

    @Test
    void rotatingRecordsTheNewKeysPublicFingerprint() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        service.registerConnection(tenant, target, spec(fingerprint()), ed25519().chars(), ACTOR, ORIGIN);
        SshTestKeys.Key next = ed25519();

        service.rotateCredential(tenant, target, next.chars(), ACTOR);

        assertEquals(next.publicFingerprint(),
                service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint());
        assertEquals(2, secretRows(tenant));
    }

    /** Review W3C-5. OWED (Docker was down): never run. */
    @Test
    void repointingTheKeyWithoutItsFingerprintClearsTheOldOnesSoAReplayOfTheOldKeyIsNotCalledTheSame() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        SshTestKeys.Key original = ed25519();
        TargetConnectionSpec spec = spec(fingerprint());
        service.registerConnection(tenant, target, spec, original.chars(), ACTOR, ORIGIN);
        SshTestKeys.Key replacement = ed25519();
        UUID stored = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, replacement.chars(), ACTOR);

        assertEquals(1, connections.updateCredential(tenant, target, stored));

        assertNull(service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint(),
                "the old key's fingerprint must not outlive the key");
        assertThrows(TargetCredentialConflictException.class,
                () -> service.registerConnection(tenant, target, spec, original.chars(), ACTOR, ORIGIN));
        assertEquals(RegistrationOutcome.REPLAYED,
                service.registerConnection(tenant, target, spec, replacement.chars(), ACTOR, ORIGIN));
        assertEquals(replacement.publicFingerprint(),
                service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint());
    }

    // ---- the races --------------------------------------------------------------------------------------------

    /** Runs {@code n} copies of {@code work}, released together; returns each one's result or exception. */
    private static <T> List<Object> concurrently(int n, java.util.function.IntFunction<java.util.concurrent.Callable<T>> work)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CyclicBarrier barrier = new CyclicBarrier(n);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                java.util.concurrent.Callable<T> task = work.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoIdenticalRequestsAtOnceAnswerOneCreatedAndOneReplayedWithOneRowOneSecretAndOneEvent() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID tenant = SmDataPostgres.freshTenant();
            UUID target = UUID.randomUUID();
            TargetConnectionSpec spec = spec(fingerprint(), fingerprint());
            SshTestKeys.Key key = ed25519();

            List<Object> results = concurrently(2, i -> () -> service.registerConnection(tenant, target, spec,
                    key.chars(), ACTOR, ORIGIN));

            assertEquals(1, results.stream().filter(RegistrationOutcome.CREATED::equals).count(), results.toString());
            assertEquals(1, results.stream().filter(RegistrationOutcome.REPLAYED::equals).count(), results.toString());
            assertEquals(1, connectionRows(tenant), "round " + round);
            assertEquals(1, secretRows(tenant), "the loser's secret was rolled back with its transaction");
            assertEquals(1, eventRows(tenant));
        }
    }

    @Test
    void twoDifferentTargetsRacingForOneMachineLeaveExactlyOneAndNothingOfTheLoser() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID tenant = SmDataPostgres.freshTenant();
            String pin = fingerprint();
            String shared = fingerprint();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            // Same machine: both pin the same key. Which of the two wins is the database's.
            List<Object> results = concurrently(2, i -> () -> service.registerConnection(tenant, i == 0 ? a : b,
                    spec(pin, shared), ed25519().chars(), ACTOR, ORIGIN));

            assertEquals(1, results.stream().filter(RegistrationOutcome.CREATED::equals).count(), results.toString());
            assertEquals(1, results.stream().filter(HostKeyHeldElsewhereException.class::isInstance).count(),
                    results.toString());
            assertEquals(1, connectionRows(tenant));
            assertEquals(1, secretRows(tenant), "the refused registration's secret was rolled back");
            assertEquals(1, eventRows(tenant));
        }
    }

    // ---- the pre-check against the real host-key table -------------------------------------------------------

    @Test
    void thePrecheckSeesALiveHolderAndNotARetiredOne() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        String second = fingerprint();
        UUID holder = UUID.randomUUID();
        service.registerConnection(tenant, holder, spec(pin, second), ed25519().chars(), ACTOR, ORIGIN);
        offered = List.of(new OfferedHostKey(second, "ssh-rsa"), new OfferedHostKey(fingerprint(), "ssh-ed25519"));
        String pinNow = offered.get(1).fingerprint();

        assertThrows(HostKeyHeldElsewhereException.class,
                () -> service.precheckRegistration(tenant, null, spec(pinNow), ed25519().chars()),
                "a key of the machine, even one that is not the pin, is held by another live target");
        // Now the target being replayed is told to the probe: its own keys are not another target's.
        offered = List.of(new OfferedHostKey(pin, "ssh-ed25519"), new OfferedHostKey(second, "ssh-rsa"));
        service.precheckRegistration(tenant, holder, spec(pin), ed25519().chars());

        // Retired: the keys are freed by V012's trigger.
        superuser.update("UPDATE sm_data.target_connection SET retired_at = now() WHERE tenant_id = :t"
                + " AND target_id = :target", new MapSqlParameterSource("t", tenant).addValue("target", holder));
        service.precheckRegistration(tenant, null, spec(pin), ed25519().chars());
    }

    @Test
    void aPinTheHostDidNotOfferIsRefusedAndNothingIsWritten() {
        UUID tenant = SmDataPostgres.freshTenant();
        offered = List.of(new OfferedHostKey(fingerprint(), "ssh-ed25519"));

        assertThrows(HostKeyNotOfferedException.class,
                () -> service.precheckRegistration(tenant, null, spec(fingerprint()), ed25519().chars()));

        assertEquals(0, secretRows(tenant));
        assertEquals(0, connectionRows(tenant));
        assertEquals(0, eventRows(tenant));
    }

    @Test
    void theRuntimeRoleCannotRewriteAnEvent() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = UUID.randomUUID();
        service.registerConnection(tenant, target, spec(fingerprint()), ed25519().chars(), ACTOR, ORIGIN);

        NamedParameterJdbcTemplate runtime = SmDataPostgres.runtimeJdbc();
        assertThrows(org.springframework.dao.DataAccessException.class, () -> runtime.update(
                "UPDATE sm_data.target_connection_event SET actor = :a WHERE tenant_id = :t",
                new MapSqlParameterSource("a", UUID.randomUUID()).addValue("t", tenant)));
        assertEquals(ACTOR, superuser.queryForObject("SELECT actor FROM sm_data.target_connection_event"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant), UUID.class));
    }
}
