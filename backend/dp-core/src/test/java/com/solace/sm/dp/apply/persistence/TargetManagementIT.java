package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.api.inventory.TargetWorkInFlightException;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.dp.inventory.TargetConnectionEvent;
import com.solace.sm.dp.inventory.TargetConnectionRecord;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 013 T1.5 against a real PostgreSQL 14, as {@code sm_dp_runtime}: the data plane's half of
 * {@code editTargetConnection}, {@code rotateTargetCredential} and {@code retireTarget}. What it adds to the offline
 * {@code InventoryTargetManagementTest} is everything only the database decides: that the locks really exclude admission
 * (and admission really excludes an edit), that V012's triggers and unique indexes behave the way the re-pin assumes, that
 * V007's guard is mapped, and that a failure after the write rolls the whole change back, event and secret included.
 *
 * <p>The scan is a stub that answers {@link #offered}; the address policy and the wire are {@code SshHostKeyScannerTest}'s.
 */
@Testcontainers(disabledWithoutDocker = true)
class TargetManagementIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "198.51.100.7");

    private static NamedParameterJdbcTemplate superuser;
    private static NamedParameterJdbcTemplate runtime;
    private static EnvelopeSecretStore secrets;
    private static JdbcTargetConnectionRepository connections;
    private static JdbcPlacementRepository placements;
    private static InventoryService service;
    private static volatile List<OfferedHostKey> offered = List.of();
    /** What a named host answers, when a test needs two hosts to answer differently; every other host answers {@link #offered}. */
    private static final Map<String, List<OfferedHostKey>> OFFERED_BY_HOST = new java.util.concurrent.ConcurrentHashMap<>();
    private static ExecutorService pool;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        runtime = SmDataPostgres.runtimeJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 41), null));
        connections = new JdbcTargetConnectionRepository(runtime);
        placements = new JdbcPlacementRepository(runtime);
        service = serviceOver(connections);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterAll
    static void stop() {
        pool.shutdownNow();
    }

    private static InventoryService serviceOver(TargetConnectionRows rows) {
        HostKeyScanService scanner = (host, port) -> OFFERED_BY_HOST.getOrDefault(host, offered);
        return new InventoryService(
                new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(runtime), placements, rows, secrets, secrets, Clock.systemUTC(),
                new JdbcMetricDefinitionRepository(runtime), LadderRecorder.none(), ApplyEventEmitter.silent(), scanner);
    }

    // ---- arrangement ---------------------------------------------------------------------------------------

    private static String fingerprint() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static List<OfferedHostKey> keys(String... fingerprints) {
        List<OfferedHostKey> out = new ArrayList<>();
        for (String fingerprint : fingerprints) {
            out.add(new OfferedHostKey(fingerprint, "ssh-ed25519"));
        }
        return out;
    }

    /** A registered target, pinned {@code pin}, whose host also offers {@code others}. */
    private static UUID register(UUID tenant, String host, String pin, SshTestKeys.Key key, String... others) {
        UUID target = UUID.randomUUID();
        assertEquals(RegistrationOutcome.CREATED, service.registerConnection(tenant, target,
                new TargetConnectionSpec(host, 22, "ec2-user", pin, "/srv/sm", "sm-", Set.of(others)), key.chars(), ACTOR,
                ORIGIN));
        return target;
    }

    private static SshTestKeys.Key ed25519() {
        return SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    }

    /** An active instance with one live placement on the target. */
    private static UUID[] placed(UUID tenant, UUID target) {
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(runtime, tenant, instance, "active");
        UUID placement = InventoryFixtures.placement(runtime, tenant, instance, target, UUID.randomUUID());
        return new UUID[] {instance, placement};
    }

    private static TargetConnectionEdit edit(String host, Integer port, String user, String pin) {
        return new TargetConnectionEdit(host, port, user, pin);
    }

    private static MapSqlParameterSource tenantTarget(UUID tenant, UUID target) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("x", target);
    }

    private static long count(String table, UUID tenant) {
        return superuser.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    private static List<Map<String, Object>> events(UUID tenant, UUID target) {
        return superuser.queryForList("SELECT kind, actor, correlation_id, client_address, changed_fields::text AS fields,"
                + " public_key_fingerprint FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :x"
                + " ORDER BY recorded_at, event_id", tenantTarget(tenant, target));
    }

    private static Map<String, Boolean> keyStates(UUID tenant, UUID target) {
        Map<String, Boolean> out = new java.util.TreeMap<>();
        superuser.query("SELECT fingerprint, retired_at IS NOT NULL AS retired FROM sm_data.target_host_key"
                        + " WHERE tenant_id = :t AND target_id = :x", tenantTarget(tenant, target),
                rs -> {
                    out.put(rs.getString("fingerprint"), rs.getBoolean("retired"));
                });
        return out;
    }

    private static void fleetMember(UUID tenant, UUID instance, UUID placement) {
        UUID fleet = UUID.randomUUID();
        superuser.update("INSERT INTO sm_data.fleet_action (tenant_id, fleet_id, catalog_solution_id, strategy, batch_size,"
                        + " on_failure, state, current_batch, requested_by, requested_at, correlation_id)"
                        + " VALUES (:t, :f, :c, 'manual', 1, 'halt-remaining', 'RUNNING', 0, 'operator-1', :now, :corr)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("f", fleet).addValue("c", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()).addValue("corr", UUID.randomUUID()));
        superuser.update("INSERT INTO sm_data.fleet_member (tenant_id, fleet_id, ordinal, batch, instance_id, placement_id,"
                        + " generation, apply_id, disposition) VALUES (:t, :f, 0, 0, :i, :p, 3, :a, 'pending')",
                new MapSqlParameterSource().addValue("t", tenant).addValue("f", fleet).addValue("i", instance)
                        .addValue("p", placement).addValue("a", UUID.randomUUID()));
    }

    // ---- S2: an edit keeps placements, writes the event, retires and revives keys ---------------------------

    @Test
    void anSshUserOnlyEditWritesTheRecordAndAnEditedEventAndTouchesNeitherKeysNorSecrets() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        long secretsBefore = count("sm_secret.secret", tenant);

        TargetConnectionView view = service.editConnection(tenant, target, edit(null, null, "deploy", null), ACTOR, ORIGIN);

        assertEquals("deploy", view.sshUser());
        assertEquals(Map.of(pin, false), keyStates(tenant, target));
        assertEquals(secretsBefore, count("sm_secret.secret", tenant));
        List<Map<String, Object>> events = events(tenant, target);
        assertEquals(2, events.size());
        assertEquals("edited", events.get(1).get("kind"));
        assertEquals("{sshUser}", events.get(1).get("fields"));
        assertEquals(ACTOR, events.get(1).get("actor"));
        assertEquals(ORIGIN.correlationId(), events.get(1).get("correlation_id"));
        assertNull(events.get(1).get("public_key_fingerprint"));
    }

    @Test
    void aRepinKeepsPlacementsRetiresWhatIsNoLongerOfferedRevivesWhatIsOfferedAgainAndRecordsTheRest() {
        UUID tenant = SmDataPostgres.freshTenant();
        String a = fingerprint();
        String b = fingerprint();
        String c = fingerprint();
        String d = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", a, ed25519(), b, c);
        UUID[] placed = placed(tenant, target);
        // c was retired once (the host stopped offering it)
        superuser.update("UPDATE sm_data.target_host_key SET retired_at = now() WHERE tenant_id = :t AND target_id = :x"
                + " AND fingerprint = '" + c + "'", tenantTarget(tenant, target));
        offered = keys(b, c, d);        // the host now offers b (the new pin), c again, and a new d; no longer a

        service.editConnection(tenant, target, edit(null, null, null, b), ACTOR, ORIGIN);

        assertEquals(Map.of(a, true, b, false, c, false, d, false), keyStates(tenant, target));
        assertEquals(b, service.connectionOf(tenant, target).orElseThrow().hostKeyFingerprint());
        assertEquals(1, PlacementItSupport.livePlacements(tenant, placed[0]), "the placement is kept");
        assertEquals("repinned", events(tenant, target).get(1).get("kind"));
        assertEquals("{hostKeyFingerprint}", events(tenant, target).get(1).get("fields"));
        // target_host_key_once still holds for the live keys of this tenant
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> superuser.update(
                "INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint) VALUES (:t, :other, '" + b + "')",
                new MapSqlParameterSource().addValue("t", tenant).addValue("other", register(tenant,
                        "vm-2.corp.example", fingerprint(), ed25519()))));
    }

    @Test
    void aPinTheHostDoesNotOfferIsRefusedAndTheRecordKeysAndEventsAreUntouched() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        offered = keys(fingerprint());

        assertThrows(HostKeyNotOfferedException.class,
                () -> service.editConnection(tenant, target, edit(null, null, null, fingerprint()), ACTOR, ORIGIN));

        assertEquals(pin, service.connectionOf(tenant, target).orElseThrow().hostKeyFingerprint());
        assertEquals(1, events(tenant, target).size());
    }

    @Test
    void aKeyAnotherLiveTargetHoldsRefusesTheEditAndEveryUniqueIndexIsTheFinalArbiter() {
        UUID tenant = SmDataPostgres.freshTenant();
        String heldPin = fingerprint();
        String heldOther = fingerprint();
        register(tenant, "vm-9.corp.example", heldPin, ed25519(), heldOther);
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());

        // the pre-write probe sees an offered key the other target holds
        offered = keys(pin, heldOther);
        assertThrows(HostKeyHeldElsewhereException.class,
                () -> service.editConnection(tenant, target, edit("vm-3.corp.example", null, null, null), ACTOR, ORIGIN));
        // and the unique indexes refuse it at the write itself, with the named constraint mapped to the same exception
        assertThrows(HostKeyHeldElsewhereException.class,
                () -> connections.updateConnection(tenant, target, "vm-1.corp.example", 22, "ec2-user", heldPin, null));
        assertThrows(HostKeyHeldElsewhereException.class,
                () -> connections.addHostKeysOnce(tenant, target, List.of(heldOther)));
        superuser.update("INSERT INTO sm_data.target_host_key (tenant_id, target_id, fingerprint, retired_at)"
                + " VALUES (:t, :x, '" + heldOther + "', now()) ON CONFLICT DO NOTHING", tenantTarget(tenant, target));
        assertThrows(HostKeyHeldElsewhereException.class,
                () -> connections.reviveHostKeys(tenant, target, List.of(heldOther)));

        assertEquals("vm-1.corp.example", service.connectionOf(tenant, target).orElseThrow().host());
    }

    @Test
    void theEnvelopeHasNoStatementThatCouldChangeItAndTheRuntimeCannotChangeItDirectly() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, "vm-1.corp.example", fingerprint(), ed25519());

        org.springframework.dao.DataAccessException refused = assertThrows(
                org.springframework.dao.DataAccessException.class, () -> runtime.update(
                        "UPDATE sm_data.target_connection SET allowed_config_root = '/srv/other' WHERE tenant_id = :t"
                                + " AND target_id = :x", tenantTarget(tenant, target)));
        Throwable cause = refused;
        while (cause != null && !(cause instanceof org.postgresql.util.PSQLException)) {
            cause = cause.getCause();
        }
        assertNotNull(cause, "a PostgreSQL refusal");
        assertEquals("42501", ((org.postgresql.util.PSQLException) cause).getSQLState(), "insufficient_privilege");
    }

    // ---- S2': work in flight ------------------------------------------------------------------------------------

    @Test
    void anApplyInFlightOnAPlacementOfTheTargetRefusesAHostEditAndTheSameEditAfterItEndsSucceeds() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        UUID[] placed = placed(tenant, target);
        UUID request = PlacementItSupport.request(tenant, placed[0], placed[1], "apply", "RUNNING");
        offered = keys(pin);

        assertThrows(TargetWorkInFlightException.class,
                () -> service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN));
        assertEquals(22, service.connectionOf(tenant, target).orElseThrow().port());
        assertEquals(1, events(tenant, target).size(), "the refused edit left no event");

        // an SSH-user-only edit and a rotation are NOT refused while work is in flight
        service.editConnection(tenant, target, edit(null, null, "deploy", null), ACTOR, ORIGIN);
        service.rotateConnectionCredential(tenant, target, ed25519().chars(), ACTOR, ORIGIN);

        superuser.update("UPDATE sm_data.apply_request SET state = 'APPLIED', terminal_at = now() WHERE apply_id = :a",
                new MapSqlParameterSource("a", request));
        assertEquals(2222, service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN).port());
    }

    @Test
    void anInstanceWideRequestAndAPendingFleetMemberAlsoRefuseTheEdit() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        UUID[] placed = placed(tenant, target);
        offered = keys(pin);
        UUID wide = PlacementItSupport.request(tenant, placed[0], ApplyRows.INSTANCE_WIDE, "stop", "REQUESTED");
        assertThrows(TargetWorkInFlightException.class,
                () -> service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN));
        superuser.update("UPDATE sm_data.apply_request SET state = 'STOPPED', terminal_at = now() WHERE apply_id = :a",
                new MapSqlParameterSource("a", wide));

        fleetMember(tenant, placed[0], placed[1]);
        assertThrows(TargetWorkInFlightException.class,
                () -> service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN));
    }

    @Test
    void workOnAnotherTargetOrAnotherTenantDoesNotRefuseTheEdit() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        UUID busy = register(tenant, "vm-2.corp.example", fingerprint(), ed25519());
        UUID[] placedBusy = placed(tenant, busy);
        PlacementItSupport.request(tenant, placedBusy[0], placedBusy[1], "apply", "RUNNING");
        UUID otherTenant = SmDataPostgres.freshTenant();
        UUID otherTarget = register(otherTenant, "vm-1.corp.example", fingerprint(), ed25519());
        UUID[] placedOther = placed(otherTenant, otherTarget);
        PlacementItSupport.request(otherTenant, placedOther[0], placedOther[1], "apply", "RUNNING");
        offered = keys(pin);

        assertEquals(2222, service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN).port());
    }

    @Test
    void anEditHoldsTheApplyScopeSoAnAdmissionWaitsForItAndThenSeesTheNewHost() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        UUID[] placed = placed(tenant, target);
        // The scope row exists already, as it does for any instance that has had an admission: the edit's lock on it is then
        // the only thing an admission can wait on (a missing row would make the upsert itself wait, which proves nothing).
        PlacementItSupport.ensureScopeRow(tenant, placed[0]);
        offered = keys(pin);

        try (PlacementItSupport.TargetGate gate = new PlacementItSupport.TargetGate(tenant, target)) {
            // the edit takes the apply_scope lock, then waits on the record row the gate holds
            Future<TargetConnectionView> editing = pool.submit(() -> service.editConnection(tenant, target,
                    edit("vm-2.corp.example", null, null, null), ACTOR, ORIGIN));
            PlacementItSupport.awaitLockWaiters(1);
            // an admission (simulated: it asks for the same lock admission asks for) must wait behind the edit
            Future<String> admitted = pool.submit(() -> {
                return new org.springframework.transaction.support.TransactionTemplate(
                        new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())).execute(status -> {
                            assertTrue(placements.lockApplyScope(tenant, placed[0]));
                            return connections.find(tenant, target).orElseThrow().host();
                        });
            });
            PlacementItSupport.awaitLockWaiters(2);
            assertFalse(admitted.isDone(), "the admission is blocked by the edit's apply_scope lock");
            gate.release();

            assertEquals("vm-2.corp.example", editing.get(15, TimeUnit.SECONDS).host());
            assertEquals("vm-2.corp.example", admitted.get(15, TimeUnit.SECONDS),
                    "the admission, once it has the lock, sees the edited record whole: neither straddles");
        }
    }

    @Test
    void anAdmissionInFlightMakesTheEditWaitThenRefuseExactlyOneWinsAndNeitherStraddles() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        UUID[] placed = placed(tenant, target);
        PlacementItSupport.ensureScopeRow(tenant, placed[0]);
        offered = keys(pin);
        CompletableFuture<Void> admitted = new CompletableFuture<>();
        CompletableFuture<Void> holding = new CompletableFuture<>();

        // the admission: takes the scope lock, then (once the edit is waiting) records a REQUESTED request and commits
        Future<?> admission = pool.submit(() -> new TransactionTemplate(
                new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())).executeWithoutResult(status -> {
                    assertTrue(placements.lockApplyScope(tenant, placed[0]));
                    holding.complete(null);
                    try {
                        admitted.get(15, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    runtime.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id,"
                                    + " operation, generation, strategy, requested_by, requested_at, state)"
                                    + " VALUES (:a, :t, :i, :p, 'apply', 3, 'manual', 'operator-1', now(), 'REQUESTED')",
                            new MapSqlParameterSource().addValue("a", UUID.randomUUID()).addValue("t", tenant)
                                    .addValue("i", placed[0]).addValue("p", placed[1]));
                }));
        holding.get(15, TimeUnit.SECONDS);
        Future<TargetConnectionView> editing = pool.submit(
                () -> service.editConnection(tenant, target, edit(null, 2222, null, null), ACTOR, ORIGIN));
        PlacementItSupport.awaitLockWaiters(1);
        assertFalse(editing.isDone(), "the edit waits for the admission's lock");
        admitted.complete(null);
        admission.get(15, TimeUnit.SECONDS);

        ExecutionException refused = assertThrows(ExecutionException.class, () -> editing.get(15, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof TargetWorkInFlightException, String.valueOf(refused.getCause()));
        assertEquals(22, service.connectionOf(tenant, target).orElseThrow().port(), "the admitted apply sees one host");
    }

    @Test
    void twoEditsOfTwoTargetsThatShareInstancesTakeTheirScopeLocksInOneOrderAndNeverDeadlock() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        String pinA = fingerprint();
        String pinB = fingerprint();
        UUID a = register(tenant, "vm-a.corp.example", pinA, ed25519());
        UUID b = register(tenant, "vm-b.corp.example", pinB, ed25519());
        // six instances, each placed on BOTH targets: an edit of either target locks all six scope rows, so two concurrent
        // edits meet on every row and a lock order that differed between them would deadlock within a few rounds
        for (int i = 0; i < 6; i++) {
            UUID instance = UUID.randomUUID();
            InventoryFixtures.declare(runtime, tenant, instance, "active");
            InventoryFixtures.placement(runtime, tenant, instance, a, UUID.randomUUID());
            InventoryFixtures.placement(runtime, tenant, instance, b, UUID.randomUUID());
        }
        OFFERED_BY_HOST.put("vm-a.corp.example", keys(pinA));       // each host answers its own key: each edit keeps its pin
        OFFERED_BY_HOST.put("vm-b.corp.example", keys(pinB));
        int rounds = 25;
        for (int round = 0; round < rounds; round++) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            int port = 2000 + round;
            List<Future<TargetConnectionView>> both = new ArrayList<>();
            for (UUID target : List.of(a, b)) {
                both.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.editConnection(tenant, target, edit(null, port, null, null), ACTOR, ORIGIN);
                }));
            }
            for (Future<TargetConnectionView> f : both) {
                assertEquals(port, f.get(30, TimeUnit.SECONDS).port(), "round " + round);
            }
        }
    }

    // ---- rotation -------------------------------------------------------------------------------------------

    @Test
    void twoConcurrentRotationsToTheSameNewKeyStoreOneSecretAndRecordOneEvent() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, "vm-1.corp.example", fingerprint(), ed25519());
        SshTestKeys.Key next = ed25519();
        long secretsBefore = count("sm_secret.secret", tenant);
        CyclicBarrier barrier = new CyclicBarrier(2);

        List<Future<TargetConnectionView.CredentialSummary>> both = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            both.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return service.rotateConnectionCredential(tenant, target, next.chars(), ACTOR, ORIGIN);
            }));
        }
        for (Future<TargetConnectionView.CredentialSummary> f : both) {
            assertEquals(next.publicFingerprint(), f.get(20, TimeUnit.SECONDS).publicKeyFingerprint());
        }

        assertEquals(secretsBefore + 1, count("sm_secret.secret", tenant), "one new secret row, not two (OR-85)");
        assertEquals(1, events(tenant, target).stream().filter(e -> "credential_rotated".equals(e.get("kind"))).count());
        assertEquals(next.publicFingerprint(),
                service.connectionOf(tenant, target).orElseThrow().credentialPublicFingerprint());
    }

    @Test
    void aRotationWritesTheNewSecretTheFingerprintAndTheEventTogetherAndASecondOneToTheSameKeyWritesNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, "vm-1.corp.example", fingerprint(), ed25519());
        SshTestKeys.Key next = ed25519();
        long secretsBefore = count("sm_secret.secret", tenant);

        TargetConnectionView.CredentialSummary summary = service.rotateConnectionCredential(tenant, target,
                next.chars(), ACTOR, ORIGIN);

        assertEquals(next.publicFingerprint(), summary.publicKeyFingerprint());
        assertNotNull(summary.setAt());
        assertEquals(secretsBefore + 1, count("sm_secret.secret", tenant), "the old row stays (the runtime cannot delete)");
        List<Map<String, Object>> events = events(tenant, target);
        assertEquals("credential_rotated", events.get(1).get("kind"));
        assertEquals(next.publicFingerprint(), events.get(1).get("public_key_fingerprint"));

        service.rotateConnectionCredential(tenant, target, next.crlf().chars(), ACTOR, ORIGIN);
        assertEquals(secretsBefore + 1, count("sm_secret.secret", tenant));
        assertEquals(2, events(tenant, target).size());
    }

    // ---- retirement -----------------------------------------------------------------------------------------

    @Test
    void retiringATargetWithNoPlacementRetiresItFreesItsKeysAndWritesTheEventAndTheSecretRemains() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        String second = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519(), second);
        long secretsBefore = count("sm_secret.secret", tenant);

        assertEquals(ConnectionRetirement.RETIRED, service.retireConnection(tenant, target, ACTOR, ORIGIN));

        assertNotNull(service.connectionOf(tenant, target).orElseThrow().retiredAt());
        assertEquals(Map.of(pin, true, second, true), keyStates(tenant, target));
        assertEquals("retired", events(tenant, target).get(1).get("kind"));
        assertEquals(secretsBefore, count("sm_secret.secret", tenant), "the secret row is kept (OR-85)");
        assertTrue(service.connection(tenant, target).isEmpty(), "a retired record is not live");
        // the freed key and host are registrable again
        assertEquals(RegistrationOutcome.CREATED, service.registerConnection(tenant, UUID.randomUUID(),
                new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", pin, "/srv/sm", "sm-", Set.of(second)),
                ed25519().chars(), ACTOR, ORIGIN));
        // idempotent
        assertEquals(ConnectionRetirement.ALREADY_RETIRED, service.retireConnection(tenant, target, ACTOR, ORIGIN));
        assertEquals(2, events(tenant, target).size());
        assertEquals(ConnectionRetirement.NO_CONNECTION, service.retireConnection(tenant, UUID.randomUUID(), ACTOR, ORIGIN));
    }

    @Test
    void aLivePlacementRefusesTheRetirementThroughThePreCheckAndThroughV007sGuardAlike() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, "vm-1.corp.example", fingerprint(), ed25519());
        placed(tenant, target);

        assertThrows(TargetHasLivePlacementsException.class,
                () -> service.retireConnection(tenant, target, ACTOR, ORIGIN));
        // the trigger itself, reached directly (a placement that slipped in after the pre-check), mapped to the same type
        assertThrows(TargetHasLivePlacementsException.class,
                () -> connections.retire(tenant, target, Instant.now()));

        assertNull(service.connectionOf(tenant, target).orElseThrow().retiredAt());
        assertEquals(1, events(tenant, target).size());
    }

    @Test
    void aFailureAfterTheRetirementWriteRollsTheRetirementAndTheKeysBackWithIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519());
        InventoryService failing = serviceOver(new FailingEvents(connections));

        assertThrows(IllegalStateException.class, () -> failing.retireConnection(tenant, target, ACTOR, ORIGIN));

        assertNull(service.connectionOf(tenant, target).orElseThrow().retiredAt(), "the retirement rolled back");
        assertEquals(Map.of(pin, false), keyStates(tenant, target), "and the trigger's key retirement with it");
        assertEquals(1, events(tenant, target).size());
    }

    @Test
    void aFailureAfterAnEditOrARotationWriteRollsTheRecordTheKeysAndTheSecretBack() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        String other = fingerprint();
        UUID target = register(tenant, "vm-1.corp.example", pin, ed25519(), other);
        InventoryService failing = serviceOver(new FailingEvents(connections));
        long secretsBefore = count("sm_secret.secret", tenant);
        offered = keys(other);

        assertThrows(IllegalStateException.class,
                () -> failing.editConnection(tenant, target, edit(null, null, null, other), ACTOR, ORIGIN));
        assertThrows(IllegalStateException.class,
                () -> failing.rotateConnectionCredential(tenant, target, ed25519().chars(), ACTOR, ORIGIN));

        TargetConnectionRecord row = service.connectionOf(tenant, target).orElseThrow();
        assertEquals(pin, row.hostKeyFingerprint());
        assertEquals(Map.of(pin, false, other, false), keyStates(tenant, target));
        assertEquals(secretsBefore, count("sm_secret.secret", tenant), "the secret stored in the failed rotation is gone");
        assertEquals(1, events(tenant, target).size());
    }

    /** A store whose event insert fails, to prove the change and its event are one transaction. */
    private static final class FailingEvents implements TargetConnectionRows {
        private final JdbcTargetConnectionRepository delegate;

        FailingEvents(JdbcTargetConnectionRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public void insert(TargetConnectionRecord row) {
            delegate.insert(row);
        }

        @Override
        public Optional<TargetConnectionRecord> find(UUID tenantId, UUID targetId) {
            return delegate.find(tenantId, targetId);
        }

        @Override
        public void addHostKeys(UUID tenantId, UUID targetId, java.util.Collection<String> fingerprints) {
            delegate.addHostKeys(tenantId, targetId, fingerprints);
        }

        @Override
        public Set<String> hostKeys(UUID tenantId, UUID targetId) {
            return delegate.hostKeys(tenantId, targetId);
        }

        @Override
        public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef) {
            return delegate.updateCredential(tenantId, targetId, credentialRef);
        }

        @Override
        public int updateCredential(UUID tenantId, UUID targetId, UUID credentialRef, String publicFingerprint) {
            return delegate.updateCredential(tenantId, targetId, credentialRef, publicFingerprint);
        }

        @Override
        public Optional<TargetConnectionRecord> findForRetirement(UUID tenantId, UUID targetId) {
            return delegate.findForRetirement(tenantId, targetId);
        }

        @Override
        public Optional<TargetConnectionRecord> findLiveForChange(UUID tenantId, UUID targetId) {
            return delegate.findLiveForChange(tenantId, targetId);
        }

        @Override
        public int updateConnection(UUID tenantId, UUID targetId, String host, int port, String sshUser,
                String hostKeyFingerprint, com.solace.sm.dp.api.inventory.HostKeyType hostKeyType) {
            return delegate.updateConnection(tenantId, targetId, host, port, sshUser, hostKeyFingerprint, hostKeyType);
        }

        @Override
        public int retireHostKeysNotIn(UUID tenantId, UUID targetId, java.util.Collection<String> keep, Instant at) {
            return delegate.retireHostKeysNotIn(tenantId, targetId, keep, at);
        }

        @Override
        public int reviveHostKeys(UUID tenantId, UUID targetId, java.util.Collection<String> offered) {
            return delegate.reviveHostKeys(tenantId, targetId, offered);
        }

        @Override
        public int retire(UUID tenantId, UUID targetId, Instant at) {
            return delegate.retire(tenantId, targetId, at);
        }

        @Override
        public void addHostKeysOnce(UUID tenantId, UUID targetId, java.util.Collection<String> fingerprints) {
            delegate.addHostKeysOnce(tenantId, targetId, fingerprints);
        }

        @Override
        public Optional<UUID> liveHolderOfAny(UUID tenantId, java.util.Collection<String> fingerprints, UUID except) {
            return delegate.liveHolderOfAny(tenantId, fingerprints, except);
        }

        @Override
        public void backfillCredentialFingerprint(UUID tenantId, UUID targetId, String fingerprint) {
            delegate.backfillCredentialFingerprint(tenantId, targetId, fingerprint);
        }

        @Override
        public void recordEvent(TargetConnectionEvent event) {
            throw new IllegalStateException("simulated failure after the change was written");
        }

        @Override
        public Optional<Instant> credentialSetAt(UUID tenantId, UUID targetId) {
            return delegate.credentialSetAt(tenantId, targetId);
        }

        @Override
        public boolean holdLive(UUID tenantId, UUID targetId) {
            return delegate.holdLive(tenantId, targetId);
        }

        @Override
        public boolean insertOnce(TargetConnectionRecord row) {
            return delegate.insertOnce(row);
        }

        @Override
        public Set<String> liveHostKeys(UUID tenantId, UUID targetId) {
            return delegate.liveHostKeys(tenantId, targetId);
        }
    }
}
