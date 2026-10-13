package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigLadderView.Rung;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricExec;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.health.StartObserver;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigLadderReader;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.5 end to end over the real {@code sm_data} journal and the real config of record (OR-37): the ladder's
 * {@code persisted} and {@code restart-observed} rungs are derived from what the engine journaled, and the landing that
 * moves the rung writes {@code instance_state.reached_rung} in its own transaction and announces
 * {@code instance.config-state} after commit.
 *
 * <p>(In the persistence test package, not {@code config}: the engine harness is package-private. Same reason as
 * {@code PostRestartVerifyIT}.)
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigLadderIT {

    private static final Pattern KEYED = Pattern.compile("^hmacsha256v[0-9]+:[0-9a-f]{64}$");
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private final ConfigHasher hasher = TestHashers.keyed();
    private final List<EventEnvelope> configStates = new ArrayList<>();
    private NamedParameterJdbcTemplate runtime;
    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private ConfigRecordServiceImpl config;
    private ConfigLadderReader reader;
    private JdbcLadderRepository ladderRows;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = new TenantId(SmDataPostgres.freshTenant());
        instance = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, instance);
        UUID livePlacement = InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value()));
        placementId = new PlacementId(livePlacement);
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), hasher, new Retention(10), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
        ladderRows = new JdbcLadderRepository(runtime);
        reader = new ConfigLadderReader(config, ladderRows);
    }

    // ---- wiring ---------------------------------------------------------------------------------------------------

    private ApplyEventEmitter emitter() {
        return new ApplyEventEmitter(new AfterCommitEventPublisher(event -> {
            if (event.event().equals("instance.config-state")) {
                configStates.add(event);
            }
        }, new SpringTransactionBoundary()), Clock.systemUTC(), UUID::randomUUID);
    }

    private ApplyExecutor executor(InMemoryRuntimeAdapter adapter) {
        ApplyEventEmitter e = emitter();
        var props = ApplyEngineHarness.engineProperties();
        OutcomeService outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e, new LadderRecorder(ladderRows));
        return new ApplyExecutor(new AdmissionService(ApplyEngineHarness.transactions(),
                ApplyEngineHarness.scopes(), ApplyEngineHarness.requests(), Clock.systemUTC(), e),
                ApplyEngineHarness.stepIntents(), ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()),
                outcomes, adapter,
                (p, g) -> RenderedConfig.of(tenant, ("generation: " + g + "\n").getBytes(StandardCharsets.UTF_8), hasher),
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, e);
    }

    private InMemoryRuntimeAdapter oneReplica() {
        return new InMemoryRuntimeAdapter(placement, true, "replica-1");
    }

    /** Stages the next generation through the real config of record and returns its number. */
    private long stage(String marker) {
        long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        return config.stage(tenant.value(), instance.value(), new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }

    private ConfigLadderView ladder() {
        return reader.read(tenant.value(), instance.value());
    }

    private static Rung rung(ConfigLadderView view, LadderRung rung) {
        return view.rungs().get(rung.ordinal());
    }

    private String storedRung() {
        return runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), String.class);
    }

    private long instanceStamp() {
        return runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
    }

    private ApplyOutcome.Landed apply(InMemoryRuntimeAdapter adapter, long generation, Strategy strategy) {
        ApplyCommand command = strategy == Strategy.MANUAL ? ApplyEngineHarness.apply(scope, generation)
                : ApplyEngineHarness.rollingApply(scope, generation, strategy);
        return assertInstanceOf(ApplyOutcome.Landed.class, executor(adapter).execute(command));
    }

    private String boundHash(ApplyId id) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == Step.MATERIALIZE).findFirst().orElseThrow().configHash();
    }

    // ---- the rungs ------------------------------------------------------------------------------------------------

    @Test
    void aStagedGenerationNothingHasAppliedIsRendered() {
        long g = stage("one");

        ConfigLadderView view = ladder();

        assertEquals(LadderRung.RENDERED, view.reached());
        assertEquals(g, rung(view, LadderRung.DESIRED).generation());
        assertFalse(rung(view, LadderRung.PERSISTED).present());
    }

    @Test
    void aManualApplyReachesPersistedAndStopsThere() {
        long g = stage("one");

        ApplyOutcome.Landed landed = apply(oneReplica(), g, Strategy.MANUAL);

        assertEquals(RequestState.STAGED, landed.state());
        ConfigLadderView view = ladder();
        assertEquals(LadderRung.PERSISTED, view.reached());
        assertEquals(g, rung(view, LadderRung.PERSISTED).generation());
        assertEquals(boundHash(landed.applyId()), rung(view, LadderRung.PERSISTED).configHash(),
                "the rung carries the keyed hash of the bytes the read-back saw");
        assertFalse(rung(view, LadderRung.RESTART_OBSERVED).present());
        assertEquals("persisted", storedRung(), "the landing wrote the instance rung in its own transaction");
    }

    @Test
    void aGatedApplyReachesRestartObservedWithTheVerifiedReadBackHash() {
        long g = stage("one");

        ApplyOutcome.Landed landed = apply(new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2"), g,
                Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.APPLIED, landed.state());
        ConfigLadderView view = ladder();
        assertEquals(LadderRung.RESTART_OBSERVED, view.reached());
        assertEquals(boundHash(landed.applyId()), rung(view, LadderRung.RESTART_OBSERVED).configHash());
        assertFalse(rung(view, LadderRung.EFFECTIVE_VERIFIED).present());
        assertEquals("restart-observed", storedRung());
    }

    @Test
    void anUngatedRestartingApplyStaysAtPersistedUntilAContainerStartIsObserved() {
        long g = stage("one");

        apply(oneReplica(), g, Strategy.ROLLING_NO_VALIDATION);

        assertEquals(LadderRung.PERSISTED, ladder().reached());
        assertEquals("persisted", storedRung());
    }

    @Test
    void everyPresentRungCarriesAKeyedHash() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.ROLLING_HEALTH_GATED);

        for (Rung r : ladder().rungs()) {
            if (r.present()) {
                assertTrue(KEYED.matcher(r.configHash()).matches(), r.rung().wire() + " " + r.configHash());
            }
        }
    }

    @Test
    void aStageAfterAnApplyDropsTheLadderBackToRenderedWithoutAWrite() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(LadderRung.RESTART_OBSERVED, ladder().reached());

        long g2 = stage("two");

        ConfigLadderView view = ladder();
        assertNotEquals(g1, g2);
        assertEquals(LadderRung.RENDERED, view.reached(), "the new generation has not been applied");
        assertEquals(g2, rung(view, LadderRung.DESIRED).generation());
        assertFalse(rung(view, LadderRung.PERSISTED).present());
    }

    @Test
    void aFailedGateIsNotRestartObservedAndTheLadderFallsBack() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        adapter.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);

        ApplyOutcome.Landed landed = apply(adapter, g, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());
        assertEquals(LadderRung.RENDERED, ladder().reached(),
                "the file is on the host but the apply did not complete: nothing past rendered is claimed");
    }

    @Test
    void persistedIsNotReportedFromAnIntentTheEngineNeverActedOn() {
        long g = stage("one");
        // admit and start a manual apply, bind a MATERIALIZE intent, and land STAGED without ever opening an attempt
        ApplyId applyId = assertInstanceOf(AdmissionOutcome.Admitted.class,
                ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(scope, g))).applyId();
        ApplyEngineHarness.outcomes().start(tenant, applyId);
        ApplyEngineHarness.stepIntents().ensureIntent(tenant, applyId, scope.placement(), Step.MATERIALIZE,
                new ReplicaRef("replica-1"),
                ApplyExecutor.idempotencyKey(applyId, Step.MATERIALIZE, new ReplicaRef("replica-1")),
                "hmacsha256v1:" + "a".repeat(64));
        ApplyEngineHarness.outcomes().land(tenant, applyId, RequestState.STAGED);

        assertEquals(LadderRung.RENDERED, ladder().reached(), "an intent is a promise, not evidence");
    }

    // ---- the landing writes the rung and announces it --------------------------------------------------------------

    @Test
    void theRungMovesOnceAndInstanceConfigStateIsPublishedAfterCommitWithTheInstancesOwnStamp() {
        long g = stage("one");

        apply(oneReplica(), g, Strategy.MANUAL);

        assertEquals(1, configStates.size(), "one movement, one notice");
        EventEnvelope event = configStates.get(0);
        assertEquals("persisted", event.payload().get("reached"));
        assertEquals(instanceStamp(), event.stateVersion(),
                "the stamp the reached_rung write drew on instance_state, read back from outside the transaction");
        assertEquals("instance", event.entity().kind());
        assertEquals(instance.value().toString(), event.entity().instanceId());
        assertNull(event.entity().placementId());
    }

    @Test
    void aSecondLandingAtTheSameRungPublishesNothingAndDrawsNoStamp() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        long second = stage("two");
        long stampAfterFirst = instanceStamp();
        configStates.clear();

        apply(oneReplica(), second, Strategy.MANUAL);

        assertEquals(LadderRung.PERSISTED, ladder().reached());

        assertEquals(List.of(), configStates, "the lowest rung did not move");
        assertEquals(stampAfterFirst, instanceStamp(), "an unchanged rung is not written: a write is an observable change");
    }

    @Test
    void aStopMovesNoRungAndPublishesNoConfigState() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        apply(adapter, g, Strategy.MANUAL);
        configStates.clear();
        long stamp = instanceStamp();

        assertInstanceOf(ApplyOutcome.Landed.class, executor(adapter).execute(ApplyEngineHarness.stop(scope)));

        assertEquals(List.of(), configStates);
        assertEquals("persisted", storedRung());
        assertEquals(stamp, instanceStamp());
    }

    @Test
    void aLaterApplyThatFallsBackWritesTheLowerRungAndAnnouncesIt() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.ROLLING_HEALTH_GATED);
        assertEquals("restart-observed", storedRung());
        long g2 = stage("two");
        configStates.clear();
        InMemoryRuntimeAdapter adapter = oneReplica();
        adapter.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);

        apply(adapter, g2, Strategy.ROLLING_HEALTH_GATED);

        assertEquals("rendered", storedRung());
        assertEquals(1, configStates.size());
        assertEquals("rendered", configStates.get(0).payload().get("reached"));
    }

    @Test
    void anotherTenantsInstanceSharesNothing() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);

        var other = ladderRows.evidence(new TenantId(UUID.randomUUID()), instance);

        assertEquals(0, other.lastGeneration());
        assertTrue(other.livePlacements().isEmpty(), "I5: the tenant is bound, so another tenant sees no journal");
    }

    // ---- OR-112 (1): a container start the data plane observed after the write -------------------------------------

    /** A scripted host: answers {@code docker inspect} with what the test sets, and counts the asks. */
    private static final class ScriptedInspect implements HostMetricExec {
        volatile InspectResult answer = new InspectResult(1, "");
        volatile RuntimeException failure;
        int calls;

        void startedAt(Instant start) {
            answer = new InspectResult(0, "c".repeat(64) + " " + start + "\n");
        }

        @Override
        public com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult curl(SshDockerPlacement placement,
                HostMetricProbe.Curl command) {
            throw new AssertionError("the start observer never runs a curl");
        }

        @Override
        public InspectResult inspect(SshDockerPlacement placement, HostMetricProbe.Inspect command) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private StartObserver observer(ScriptedInspect inspect) {
        return new StartObserver(inspect, new TablePlacementCatalog(new JdbcPlacementRepository(runtime),
                new JdbcTargetConnectionRepository(runtime), new JdbcInstanceStateRepository(runtime)), ladderRows,
                new JdbcStartObservationRepository(runtime), ApplyEngineHarness.transactions(),
                new LadderRecorder(ladderRows), emitter(), Clock.systemUTC(), Duration.ofSeconds(5));
    }

    private PlacementRecord livePlacement() {
        return new JdbcPlacementRepository(runtime).live(tenant.value(), instance.value()).get(0);
    }

    /** The start the host reported when the generation's file was read back: what an observed start must be later than. */
    private Instant writeBaseline(long generation) {
        return Instant.parse(runtime.queryForObject("SELECT a.write_baseline->>'startedAt' FROM sm_data.apply_attempt a"
                + " JOIN sm_data.apply_request r ON r.tenant_id = a.tenant_id AND r.apply_id = a.apply_id"
                + " WHERE a.tenant_id = :t AND r.instance_id = :i AND r.generation = :g AND a.step = 'MATERIALIZE'"
                + " AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1", key().addValue("g", generation),
                String.class));
    }

    private int observationRows() {
        return runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t AND placement_id = :p",
                key().addValue("p", placementId.value()), Integer.class);
    }

    @Test
    void anUngatedApplyReachesRestartObservedWhenTheObserverSeesAStartAfterTheWriteAndAnnouncesItOnce() {
        long g = stage("one");
        ApplyOutcome.Landed landed = apply(oneReplica(), g, Strategy.MANUAL);
        configStates.clear();
        ScriptedInspect host = new ScriptedInspect();
        StartObserver observer = observer(host);
        assertEquals(Set.of(placementId.value()), observer.awaiting(tenant, instance), "persisted, waiting for a start");

        host.startedAt(writeBaseline(g).plusSeconds(1));
        assertTrue(observer.observe(livePlacement()));

        ConfigLadderView view = ladder();
        assertEquals(LadderRung.RESTART_OBSERVED, view.reached());
        assertEquals(boundHash(landed.applyId()), rung(view, LadderRung.RESTART_OBSERVED).configHash());
        assertEquals("restart-observed", storedRung(), "the one writer moved the cache");
        assertEquals(1, configStates.size(), "one movement, one notice");
        assertEquals("restart-observed", configStates.get(0).payload().get("reached"));
        assertEquals(instanceStamp(), configStates.get(0).stateVersion(), "the instance's own stamp, read back");
        assertEquals(Set.of(), observer.awaiting(tenant, instance), "nothing left to wait for: the host is not asked again");

        long stamp = instanceStamp();
        assertFalse(observer.observe(livePlacement()), "the same start again records nothing");
        assertEquals(1, configStates.size(), "and announces nothing: exactly once");
        assertEquals(stamp, instanceStamp(), "and draws no stamp");
    }

    @Test
    void aStartObservedBeforeTheWriteIsRecordedButMovesNothingAndTheNextOneDoes() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        configStates.clear();
        ScriptedInspect host = new ScriptedInspect();
        StartObserver observer = observer(host);

        host.startedAt(writeBaseline(g).minusSeconds(3600));
        assertTrue(observer.observe(livePlacement()), "a new start is recorded");

        assertEquals(LadderRung.PERSISTED, ladder().reached(), "the predecessor running is not the new file loaded");
        assertEquals("persisted", storedRung());
        assertEquals(List.of(), configStates);
        assertEquals(Set.of(placementId.value()), observer.awaiting(tenant, instance), "still waiting");

        host.startedAt(writeBaseline(g).plusSeconds(1));
        assertTrue(observer.observe(livePlacement()));

        assertEquals(LadderRung.RESTART_OBSERVED, ladder().reached());
        assertEquals(1, configStates.size());
        assertEquals(1, observationRows(), "one row per placement, moved");
    }

    @Test
    void aReadThatFailsOrIsNotAStartRecordsNothingAndIsAskedAgainNextRound() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        configStates.clear();
        ScriptedInspect host = new ScriptedInspect();
        StartObserver observer = observer(host);

        host.answer = new HostMetricExec.InspectResult(124, "");                                 // the host's own timeout
        assertFalse(observer.observe(livePlacement()));
        host.answer = new HostMetricExec.InspectResult(0, "no such file or directory");          // not a start line
        assertFalse(observer.observe(livePlacement()));
        host.answer = new HostMetricExec.InspectResult(0, "c".repeat(64) + " 2026-13-45T25:61:61Z\n");   // shaped, not a time
        assertFalse(observer.observe(livePlacement()));
        host.failure = new RuntimeAdapterException(new ErrorClass("ssh.pool.busy"));              // a load signal
        assertFalse(observer.observe(livePlacement()));

        assertEquals(0, observationRows());
        assertEquals(LadderRung.PERSISTED, ladder().reached());
        assertEquals(List.of(), configStates);
    }

    @Test
    void aGatedApplyIsNeverWaitedOnForAStartBecauseItsGateIsItsProof() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.ROLLING_HEALTH_GATED);
        ScriptedInspect host = new ScriptedInspect();

        assertEquals(Set.of(), observer(host).awaiting(tenant, instance));
        assertEquals(0, host.calls);
    }

    @Test
    void aLaterApplyDropsTheRungAndTheNextStartAfterItsWriteMovesItAgainEachExactlyOnce() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.MANUAL);
        ScriptedInspect host = new ScriptedInspect();
        StartObserver observer = observer(host);
        Instant start = writeBaseline(g1).plusSeconds(1);   // the restart onto g1, long before g2's write
        host.startedAt(start);
        observer.observe(livePlacement());
        assertEquals("restart-observed", storedRung());
        configStates.clear();

        long g2 = stage("two");
        InMemoryRuntimeAdapter running = oneReplica();
        running.hostClock(start);                          // g2 is written while that start is the one running
        apply(running, g2, Strategy.MANUAL);

        assertEquals(LadderRung.PERSISTED, ladder().reached(), "g1's start is older than g2's write");
        assertEquals(List.of("persisted"), configStates.stream().map(e -> (String) e.payload().get("reached")).toList(),
                "the landing announced the fall once");
        assertEquals(Set.of(placementId.value()), observer.awaiting(tenant, instance), "waiting again, for g2's own start");
        assertFalse(observer.observe(livePlacement()), "the old start is unchanged: nothing recorded, nothing announced");

        host.startedAt(writeBaseline(g2).plusSeconds(1));
        assertTrue(observer.observe(livePlacement()));

        assertEquals(List.of("persisted", "restart-observed"),
                configStates.stream().map(e -> (String) e.payload().get("reached")).toList());
        assertEquals(LadderRung.RESTART_OBSERVED, ladder().reached());
    }

    /**
     * The observation takes the instance's state row {@code FOR UPDATE} first, as a landing does, so its rung write and a
     * landing's are serial (the cache's compare-and-set would otherwise throw "moved under the landing"). Proved with a
     * start BEFORE the write: it moves no rung, so nothing but that lock can make the observation wait.
     */
    @Test
    void theObservationWaitsForALandingsLockOnTheInstanceBeforeItWritesAnything() throws Exception {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        ScriptedInspect host = new ScriptedInspect();
        host.startedAt(writeBaseline(g).minusSeconds(3600));
        StartObserver observer = observer(host);
        PlacementRecord live = livePlacement();
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Boolean> observed = ApplyEngineHarness.transactions().execute(status -> {
                runtime.queryForList("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i"
                        + " FOR UPDATE", key(), Integer.class);                    // a landing in flight holds this
                java.util.concurrent.Future<Boolean> future = other.submit(() -> observer.observe(live));
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                assertFalse(future.isDone(), "the observation must wait for the landing's lock");
                assertEquals(0, observationRows(), "and has written nothing while it waits");
                return future;
            });
            assertTrue(observed.get(10, java.util.concurrent.TimeUnit.SECONDS), "released: the observation proceeds");
        } finally {
            other.shutdownNow();
        }
        assertEquals(1, observationRows());
    }
}
