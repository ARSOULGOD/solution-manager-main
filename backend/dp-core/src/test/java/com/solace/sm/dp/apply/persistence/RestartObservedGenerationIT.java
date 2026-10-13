package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.reconcile.ReconcileProperties;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigLadder;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-4.3 (M-Q15 {@code OR-74}): the collector switches its active metric list at the newest generation that
 * reached {@code restart-observed}. Plan 010 keeps ONE instance-level rung beside {@code last_generation}, which
 * describes only the newest generation, so the answer is derived from the apply journal the engine really wrote: every
 * case below drives the real engine over an in-memory adapter (as {@code ConfigLadderIT} does), never a planted row.
 *
 * <p>(In the persistence test package: the engine harness is package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class RestartObservedGenerationIT {

    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private final ConfigHasher hasher = TestHashers.keyed();
    private NamedParameterJdbcTemplate runtime;
    private TenantId tenant;
    private InstanceId instance;
    private ScopeKey scope;
    private PlacementRef placement;
    private ConfigRecordServiceImpl config;
    private JdbcRestartObservedGenerationRepository repository;
    private JdbcLadderRepository ladderRows;
    private JdbcStartObservationRepository starts;
    private ApplyExecutor lastExecutor;
    private OutcomeService lastOutcomes;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = new TenantId(SmDataPostgres.freshTenant());
        instance = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, instance);
        PlacementId placementId = new PlacementId(InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value())));
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), hasher, new Retention(20), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime));
        ladderRows = new JdbcLadderRepository(runtime);
        repository = new JdbcRestartObservedGenerationRepository(runtime);
        starts = new JdbcStartObservationRepository(runtime);
    }

    // ---- driving the real engine -----------------------------------------------------------------------------------

    private ApplyExecutor executor(InMemoryRuntimeAdapter adapter) {
        ApplyEventEmitter e = new ApplyEventEmitter(new AfterCommitEventPublisher(event -> { },
                new SpringTransactionBoundary()), Clock.systemUTC(), UUID::randomUUID);
        var props = ApplyEngineHarness.engineProperties();
        OutcomeService outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e, new LadderRecorder(ladderRows));
        lastOutcomes = outcomes;
        lastExecutor = new ApplyExecutor(new AdmissionService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e), ApplyEngineHarness.stepIntents(),
                ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()), outcomes, adapter,
                (p, g) -> RenderedConfig.of(tenant, ("generation: " + g + "\n").getBytes(StandardCharsets.UTF_8), hasher),
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, e);
        return lastExecutor;
    }

    private long stage(String marker) {
        long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        return config.stage(tenant.value(), instance.value(), new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    private ApplyOutcome.Landed apply(InMemoryRuntimeAdapter adapter, long generation, Strategy strategy) {
        ApplyCommand command = strategy == Strategy.MANUAL ? ApplyEngineHarness.apply(scope, generation)
                : ApplyEngineHarness.rollingApply(scope, generation, strategy);
        return assertInstanceOf(ApplyOutcome.Landed.class, executor(adapter).execute(command));
    }

    private InMemoryRuntimeAdapter oneReplica() {
        return new InMemoryRuntimeAdapter(placement, true, "replica-1");
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }

    private String storedRung() {
        return runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), String.class);
    }

    private long lastGeneration() {
        return runtime.queryForObject("SELECT last_generation FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
    }

    private OptionalLong latest() {
        return repository.latestRestartObservedGeneration(tenant, instance);
    }

    // ---- the answers ---------------------------------------------------------------------------------------------

    @Test
    void noRestartObservedGenerationYieldsEmptyAndThePlanUsesGenerationZero() {
        assertEquals(OptionalLong.empty(), latest(), "nothing staged");

        long g1 = stage("one");
        assertEquals(OptionalLong.empty(), latest(), "staged, never applied");

        apply(oneReplica(), g1, Strategy.MANUAL);
        assertEquals(OptionalLong.empty(), latest(), "persisted is not restart-observed");
    }

    @Test
    void aGatedApplyThatObservedTheRestartIsTheAnswer() {
        long g1 = stage("one");

        apply(new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2"), g1, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(OptionalLong.of(g1), latest());
        assertEquals("restart-observed", storedRung());
    }

    @Test
    void genNPlusOneStagedOrAppliedButNotRestartedStillYieldsGenN() {
        long g1 = stage("one");
        ApplyOutcome.Landed landed = apply(oneReplica(), g1, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(RequestState.APPLIED, landed.state());
        assertEquals(OptionalLong.of(g1), latest());

        long g2 = stage("two");
        assertEquals(OptionalLong.of(g1), latest(), "staged only: the old list is still the one in force");
        assertNotEquals(g1, lastGeneration(), "the instance's head moved on");

        apply(oneReplica(), g2, Strategy.MANUAL);
        assertEquals("persisted", storedRung(), "the instance rung moved on and no longer says restart-observed");
        assertEquals(OptionalLong.of(g1), latest(), "persisted-only: the answer did not move with it");

        long g3 = stage("three");
        apply(oneReplica(), g3, Strategy.ROLLING_NO_VALIDATION);
        assertEquals(OptionalLong.of(g1), latest(), "an ungated restart is never observed");

        long g4 = stage("four");
        apply(oneReplica(), g4, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(OptionalLong.of(g4), latest(), "the newest generation that DID observe a restart");
    }

    @Test
    void aGateThatFailedIsNotARestartObserved() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        adapter.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);

        ApplyOutcome.Landed landed = apply(adapter, g, Strategy.ROLLING_HEALTH_GATED);

        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());
        assertEquals(OptionalLong.empty(), latest());
    }

    @Test
    void theQueryIsTenantBound() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.ROLLING_HEALTH_GATED);
        assertTrue(latest().isPresent());

        TenantId other = new TenantId(UUID.randomUUID());

        assertEquals(OptionalLong.empty(), repository.latestRestartObservedGeneration(other, instance),
                "another tenant's name for this instance id finds nothing");
    }

    @Test
    void aReTargetedInstanceNeedsItsNewPlacementToHaveObservedTheRestartToo() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.ROLLING_HEALTH_GATED);
        UUID old = placement.placement().value();
        // the instance is re-targeted: a new live placement, the old one retired (V007: a re-target mints a new placement)
        UUID fresh = InventoryFixtures.placement(tenant.value(), instance.value(), InventoryFixtures.target(tenant.value()));
        runtime.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("p", old));

        assertEquals(OptionalLong.empty(), latest(),
                "every LIVE placement must have observed it (the ladder's lowest-across-placements rule, OR-37): the new one has not");
        assertNotEquals(old, fresh);
    }

    // ---- OR-112 (1): the routes an ungated or a confirmed-gated apply takes ----------------------------------------

    /** When the request's write ended, on the DATA PLANE's clock: used only where a test needs the real time of a write. */
    private Instant writeEnd(long generation) {
        return runtime.queryForObject("SELECT max(a.ended_at) FROM sm_data.apply_attempt a"
                + " JOIN sm_data.apply_request r ON r.tenant_id = a.tenant_id AND r.apply_id = a.apply_id"
                + " WHERE a.tenant_id = :t AND r.instance_id = :i AND r.generation = :g AND a.step = 'MATERIALIZE'",
                key().addValue("g", generation), java.time.OffsetDateTime.class).toInstant();
    }

    private void startObserved(Instant startedAt) {
        starts.record(tenant.value(), placement.placement().value(), startedAt, "c".repeat(64), Instant.now());
    }

    /**
     * The Java ladder's verdict for the newest generation, which must agree with the SQL's answer (the two restate one rule;
     * a drift between them is what this asserts against).
     */
    private LadderRung ladderRung(long generation) {
        ConfigDraft draft = new ConfigDraft(generation, "cache", "2.4", SELECTION, "{}", "hmacsha256v1:" + "d".repeat(64));
        return ConfigLadder.view(Optional.of(draft), ladderRows.evidence(tenant, instance)).reached();
    }

    @Test
    void anUngatedApplyIsObservedWhenAStartFollowsItsWrite() {
        for (Strategy strategy : List.of(Strategy.MANUAL, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
            setUp();
            long g = stage("one");
            apply(oneReplica(), g, strategy);
            assertEquals(OptionalLong.empty(), latest(), strategy.sql() + ": no start observed yet");
            assertEquals(LadderRung.PERSISTED, ladderRung(g));

            startObserved(writeBaseline(g).plusSeconds(1));

            assertEquals(OptionalLong.of(g), latest(), strategy.sql() + ": a start after the write is the new file loaded");
            assertEquals(LadderRung.RESTART_OBSERVED, ladderRung(g), "the ladder agrees");
        }
    }

    @Test
    void aStartObservedBeforeTheWriteDoesNotCount() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        Instant written = writeBaseline(g);

        for (Instant before : List.of(written.minusSeconds(3600), written.minusSeconds(1), written)) {
            startObserved(before);
            assertEquals(OptionalLong.empty(), latest(), "a start at " + before + " is the PREDECESSOR running");
            assertEquals(LadderRung.PERSISTED, ladderRung(g));
        }
    }

    @Test
    void aNewerUngatedWriteIsNotObservedByTheOlderWritesStart() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.MANUAL);
        Instant start = writeBaseline(g1).plusSeconds(1);
        startObserved(start);
        assertEquals(OptionalLong.of(g1), latest());

        long g2 = stage("two");
        InMemoryRuntimeAdapter running = oneReplica();
        running.hostClock(start);       // g2 is written while that very start is the one running
        apply(running, g2, Strategy.MANUAL);

        assertEquals(OptionalLong.of(g1), latest(),
                "g2 was written after that start: g1 is still the newest generation to have observed one");
        assertEquals(LadderRung.PERSISTED, ladderRung(g2));

        startObserved(writeBaseline(g2).plusSeconds(1));
        assertEquals(OptionalLong.of(g2), latest(), "a start after g2's write moves the answer to g2");
        assertEquals(LadderRung.RESTART_OBSERVED, ladderRung(g2));
    }

    @Test
    void aStartNeverRescuesAGenerationThatDidNotPersist() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        adapter.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);
        ApplyOutcome.Landed landed = apply(adapter, g, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(RequestState.CONFIG_DIVERGED, landed.state());

        startObserved(writeEnd(g).plusSeconds(60));

        assertEquals(OptionalLong.empty(), latest());
        assertEquals(LadderRung.RENDERED, ladderRung(g));
    }

    @Test
    void aStartDoesNotStandInForAGatedAppliesOwnProof() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        adapter.healthOverride(HealthSignal.NOT_DECLARED);   // the gate sees nothing: indeterminate, not observed
        ApplyOutcome.Landed landed = apply(adapter, g, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(RequestState.APPLY_INDETERMINATE, landed.state());

        startObserved(writeEnd(g).plusSeconds(60));

        assertEquals(OptionalLong.empty(), latest(), "indeterminate is not persisted, and a start is the UNGATED route");
    }

    @Test
    void everyLivePlacementMustHaveSeenAStartAfterItsWrite() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        startObserved(writeBaseline(g).plusSeconds(1));
        assertEquals(OptionalLong.of(g), latest());
        // a second live placement joins; nothing has been applied to it
        InventoryFixtures.placement(tenant.value(), instance.value(), InventoryFixtures.target(tenant.value()));

        assertEquals(OptionalLong.empty(), latest(), "the lowest across live placements (OR-37), as the ladder's rule");
    }

    @Test
    void theStartIsTenantBound() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        startObserved(writeBaseline(g).plusSeconds(1));

        assertEquals(OptionalLong.empty(), repository.latestRestartObservedGeneration(new TenantId(UUID.randomUUID()), instance));
    }

    // ---- a gated apply the reconciler confirmed ---------------------------------------------------------------------

    /** A gated apply whose gate saw no health signal (so it landed indeterminate), then reconciled over a healthy host. */
    private ApplyId gatedIndeterminate(InMemoryRuntimeAdapter adapter, long generation) {
        adapter.healthOverride(HealthSignal.NOT_DECLARED);
        ApplyOutcome.Landed landed = apply(adapter, generation, Strategy.ROLLING_HEALTH_GATED);
        assertEquals(RequestState.APPLY_INDETERMINATE, landed.state());
        adapter.healthOverride(null);
        return landed.applyId();
    }

    private IndeterminateReconciler.Result reconcile(ApplyId id) {
        IndeterminateReconciler reconciler = new IndeterminateReconciler(lastExecutor, lastOutcomes,
                new JdbcReconciliationRepository(runtime), ReconcileProperties.defaults(), Clock.systemUTC());
        return reconciler.reconcileOne(tenant, id);
    }

    @Test
    void aGatedApplyVerifyThenRetryConfirmedWithTheGateProvedIsObserved() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        ApplyId id = gatedIndeterminate(adapter, g);
        assertEquals(OptionalLong.empty(), latest(), "indeterminate, not yet reconciled");

        assertEquals(IndeterminateReconciler.Result.CONFIRMED, reconcile(id));

        assertEquals(RequestState.INDETERMINATE_RESOLVED, ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state());
        assertEquals(OptionalLong.of(g), latest(), "OR-112 (1)(b): confirmed, gate proved, restarted onto the bound hash");
        assertEquals(LadderRung.RESTART_OBSERVED, ladderRung(g), "the ladder agrees");
        assertEquals("restart-observed", storedRung(),
                "the reconciler's landing settles the ladder through the one writer, so the cache moves with it");
    }

    @Test
    void aGatedConfirmationWhoseGateWasNeverProvedIsNotObserved() {
        long g = stage("one");
        InMemoryRuntimeAdapter adapter = oneReplica();
        ApplyId id = gatedIndeterminate(adapter, g);
        adapter.healthOverride(HealthSignal.UNHEALTHY);   // the reconciler's own gate observation says "not healthy"

        assertEquals(IndeterminateReconciler.Result.GAVE_UP, reconcile(id));

        assertEquals(OptionalLong.empty(), latest());
        assertEquals(RequestState.APPLY_INDETERMINATE, ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state());
    }

    @Test
    void anOperatorsOwnResolutionOfAGatedApplyWithNoGateProofIsNotObserved() {
        long g = stage("one");
        ApplyId id = gatedIndeterminate(oneReplica(), g);
        String hash = ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == com.solace.sm.dp.apply.model.Step.MATERIALIZE).findFirst().orElseThrow().configHash();
        // the operator says "applied, and restarted" - but no passed gate is in the journal
        ApplyEngineHarness.outcomes().resolveIndeterminate(tenant, id, new com.solace.sm.dp.apply.model.Resolution(
                "operator-1", com.solace.sm.dp.apply.model.ResolutionOutcome.APPLIED,
                new com.solace.sm.dp.apply.model.ApplyResolutionEvidence(g, List.of(
                        new com.solace.sm.dp.apply.model.ReplicaObservation(
                                new com.solace.sm.dp.apply.model.ReplicaRef("replica-1"), true, hash)))));

        assertEquals(OptionalLong.empty(), latest(), "an unproven claim is persisted at most");
        assertEquals(LadderRung.PERSISTED, ladderRung(g));

        startObserved(writeEnd(g).plusSeconds(60));

        assertEquals(OptionalLong.empty(), latest(),
                "and a start is the UNGATED route: it does not stand in for the gate a gated strategy was to prove");
        assertEquals(LadderRung.PERSISTED, ladderRung(g));
    }

    @Test
    void aStartNeverRescuesAWriteWhoseReadBackDidNotMatchWhatTheIntentBound() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.MANUAL);
        // the journal as a divergent read-back would have left it (the engine would not land STAGED over one; planted)
        SmDataPostgres.superuserJdbc().update("UPDATE sm_data.apply_attempt SET observed_hash = :other"
                + " WHERE tenant_id = :t AND step = 'MATERIALIZE' AND apply_id IN"
                + " (SELECT apply_id FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i)",
                key().addValue("other", "hmacsha256v1:" + "0".repeat(64)));

        startObserved(writeEnd(g).plusSeconds(60));

        assertEquals(OptionalLong.empty(), latest(), "an intent is a promise and a start proves only a restart: both need the read-back");
        assertEquals(LadderRung.RENDERED, ladderRung(g));
    }

    // ---- OR-112 round 2: a newer write the start post-dates, and a host clock that is not the data plane's ---------------

    @Test
    void aLaterWriteThatNeverReachedPersistedShadowsTheElderGenerationAStartPostDates() {
        long g1 = stage("one");
        apply(oneReplica(), g1, Strategy.MANUAL);
        long g2 = stage("two");
        InMemoryRuntimeAdapter diverging = oneReplica();
        diverging.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);
        // g2's file was written, the container restarted onto it, and the gate failed: the host now runs g2, not g1
        assertEquals(RequestState.CONFIG_DIVERGED, apply(diverging, g2, Strategy.ROLLING_HEALTH_GATED).state());

        startObserved(writeEnd(g2).plusSeconds(1));

        assertEquals(OptionalLong.empty(), latest(),
                "the start loaded g2's file: g1 was never the generation in force, however it was persisted");
        assertEquals(LadderRung.RENDERED, ladderRung(g2), "and g2 itself never persisted");
    }

    @Test
    void aLaterWriteTheStartPrecedesLeavesTheElderGenerationObserved() {
        long g1 = stage("one");
        InMemoryRuntimeAdapter host = oneReplica();
        apply(host, g1, Strategy.MANUAL);
        Instant start = writeBaseline(g1).plusSeconds(5);   // the operator restarted onto g1
        startObserved(start);
        assertEquals(OptionalLong.of(g1), latest());

        long g2 = stage("two");
        InMemoryRuntimeAdapter later = oneReplica();
        later.hostClock(start);                             // g2 is written while that start is still the running one
        apply(later, g2, Strategy.MANUAL);

        assertEquals(OptionalLong.of(g1), latest(), "g2's write came after the start: it did not overwrite what ran");
    }

    private Instant writeBaseline(long generation) {
        return Instant.parse(runtime.queryForObject("SELECT a.write_baseline->>'startedAt' FROM sm_data.apply_attempt a"
                + " JOIN sm_data.apply_request r ON r.tenant_id = a.tenant_id AND r.apply_id = a.apply_id"
                + " WHERE a.tenant_id = :t AND r.instance_id = :i AND r.generation = :g AND a.step = 'MATERIALIZE'"
                + " AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1", key().addValue("g", generation),
                String.class));
    }

    private static final String REPLICA = "replica-1";

    @Test
    void aHostClockAheadOfTheDataPlaneDoesNotPromoteAGenerationNoOneRestarted() {
        for (Duration ahead : List.of(Duration.ofSeconds(60), Duration.ofSeconds(90), Duration.ofHours(1))) {
            setUp();
            InMemoryRuntimeAdapter host = oneReplica();
            host.hostClock(Instant.now().plus(ahead));
            long g = stage("one");
            apply(host, g, Strategy.MANUAL);

            startObserved(host.startOf(REPLICA));   // the poller sees the container that was already running

            assertEquals(OptionalLong.empty(), latest(), "host " + ahead + " ahead: no restart happened");
            assertEquals(LadderRung.PERSISTED, ladderRung(g));
        }
    }

    @Test
    void aHostClockBehindTheDataPlaneStillObservesTheRestartOfEveryUngatedStrategy() {
        for (Duration behind : List.of(Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofHours(1))) {
            for (Strategy strategy : List.of(Strategy.MANUAL, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
                setUp();
                InMemoryRuntimeAdapter host = oneReplica();
                host.hostClock(Instant.now().minus(behind));
                long g = stage("one");
                apply(host, g, strategy);
                if (strategy == Strategy.MANUAL) {
                    host.startOrUpdate(host.replicas().get(0), g);   // the operator restarts
                }

                startObserved(host.startOf(REPLICA));

                assertEquals(OptionalLong.of(g), latest(), strategy.sql() + ", host " + behind + " behind");
                assertEquals(LadderRung.RESTART_OBSERVED, ladderRung(g), "the ladder agrees");
            }
        }
    }

    @Test
    void aStartWithinTheRoundingOfTheBaselineIsTheSameStart() {
        long g = stage("one");
        InMemoryRuntimeAdapter host = oneReplica();
        apply(host, g, Strategy.MANUAL);

        startObserved(writeBaseline(g).plusNanos(900_000));   // under a millisecond: the container's own start, as stored

        assertEquals(OptionalLong.empty(), latest());
        assertEquals(LadderRung.PERSISTED, ladderRung(g));
    }

    @Test
    void theTableRefusesAWriteBaselineOnAnyStepButMaterializeAndOneThatIsNotTheShape() {
        long g = stage("one");
        apply(oneReplica(), g, Strategy.RESTART_ALL);
        var superuser = SmDataPostgres.superuserJdbc();
        String set = "UPDATE sm_data.apply_attempt SET write_baseline = CAST(:wb AS jsonb)"
                + " WHERE tenant_id = :t AND step = :step";
        String good = "{\"startedAt\":\"2026-10-07T10:00:00.123456789Z\",\"processRef\":\"replica-1\"}";

        var onLifecycle = key().addValue("wb", good).addValue("step", "LIFECYCLE");
        var refused = org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> superuser.update(set, onLifecycle));
        assertTrue(String.valueOf(refused.getMessage()).contains("write_baseline_only_for_materialize"), refused.getMessage());

        for (String bad : List.of("[]", "\"x\"", "{}", "{\"startedAt\":\"2026-10-07T10:00:00Z\"}",
                "{\"startedAt\":5,\"processRef\":\"replica-1\"}",
                "{\"startedAt\":\"yesterday\",\"processRef\":\"replica-1\"}",
                "{\"startedAt\":\"2026-10-07T10:00:00Z\",\"processRef\":\"has a space\"}",
                "{\"startedAt\":\"2026-10-07T10:00:00Z\",\"processRef\":\"replica-1\",\"extra\":1}")) {
            var onMaterialize = key().addValue("wb", bad).addValue("step", "MATERIALIZE");
            var shape = org.junit.jupiter.api.Assertions.assertThrows(
                    org.springframework.dao.DataIntegrityViolationException.class, () -> superuser.update(set, onMaterialize),
                    bad);
            assertTrue(String.valueOf(shape.getMessage()).contains("write_baseline_shape"), bad + ": " + shape.getMessage());
        }
        assertTrue(superuser.update(set, key().addValue("wb", good).addValue("step", "MATERIALIZE")) > 0,
                "the shape the engine writes is accepted, nanoseconds and all");
    }
}
