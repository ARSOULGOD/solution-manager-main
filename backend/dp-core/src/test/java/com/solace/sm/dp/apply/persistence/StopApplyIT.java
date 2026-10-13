package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.apply.runtime.TestHashers;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code BE-D26}: a stop is scope-granular — one placement, or a whole instance.
 *
 * <p>The asymmetry with an apply is the point, and it is structural. {@code BE-D23} refuses an
 * instance-wide apply because {@code placement_scoped_steps} pins {@code replica_ref} to
 * {@code '@placement'} for {@code PREFLIGHT}/{@code ROLLBACK}/{@code ROLLBACK_ASSESS}, so one
 * {@code apply_id} can hold at most one of each. A stop writes none of those steps: its only step
 * is {@code STOP}, which carries a real {@code replica_ref}, so
 * {@code UNIQUE (tenant_id, apply_id, step, replica_ref)} admits one per replica across any number
 * of placements. These tests assert that against the shipped DDL rather than against the prose.
 */
@Testcontainers(disabledWithoutDocker = true)
class StopApplyIT {

    private static final RenderedConfig GENERATION_4 =
            TestHashers.rendered("generation: 4\n".getBytes(StandardCharsets.UTF_8));

    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementA;
    private PlacementId placementB;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, instance);
        // Registered, each on a target of its own: since V021 (BE-D44) an instance-wide stop's intent must name a real
        // placement of the request's instance, and a fixture with random ids no longer journals one. Since plan 008 S3
        // (BE-D46) the ENUMERATOR is this registry too: the two placements below are exactly what an instance-wide stop
        // addresses, and no apply has to name them first.
        placementA = new PlacementId(InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value())));
        placementB = new PlacementId(InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value())));
    }

    private ScopeKey scope(PlacementId p) {
        return new ScopeKey(tenant, instance, p);
    }

    private PlacementRef ref(PlacementId p) {
        return new PlacementRef(tenant, instance, p);
    }

    private ApplyCommand stop(ScopeKey s) {
        return new ApplyCommand(s, Operation.STOP, null, null, null, "operator-1");
    }

    private RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    private List<StepIntentRow> stopIntents(ApplyId id) {
        return ApplyEngineHarness.intents().forRequest(tenant, id).stream()
                .filter(i -> i.step() == Step.STOP).toList();
    }

    /** An apply, so the placement has a history for an instance-wide stop to find. */
    private void applyTo(PlacementId p, InMemoryRuntimeAdapter adapter, long generation) {
        assertInstanceOf(ApplyOutcome.Landed.class,
                ApplyEngineHarness.executor(adapter, (x, g) -> GENERATION_4)
                        .execute(new ApplyCommand(scope(p), Operation.APPLY, generation,
                                Strategy.MANUAL, null, "operator-1")));
    }

    // ---- one placement ---------------------------------------------------------------------------

    @Test
    void aPlacementStopStopsEveryReplicaOfThatPlacementAndNothingElse() {
        InMemoryRuntimeAdapter a =
                new InMemoryRuntimeAdapter(ref(placementA), true, "a-1", "a-2", "a-3");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "b-1", "b-2");

        ApplyOutcome outcome = ApplyEngineHarness
                .executor(new TwoPlacements(a, b, placementA), (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA)));

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.STOPPED, landed.state());
        assertEquals(3, stopIntents(landed.applyId()).size(),
                "one STOP intent per replica — placement_scoped_steps leaves STOP alone, so each "
                        + "carries a real replica_ref");
        for (TargetReplica r : a.replicas(ref(placementA))) {
            assertEquals(Liveness.STOPPED, a.status(r).liveness());
        }
        for (TargetReplica r : b.replicas(ref(placementB))) {
            assertEquals(Liveness.RUNNING, b.status(r).liveness(),
                    "placement B was not addressed and must be untouched");
        }
    }

    @Test
    void stoppingAnAlreadyStoppedReplicaSucceedsWithoutActing() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        a.stop(a.replicas(ref(placementA)).get(0));

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(a, null, placementA), (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA)));

        assertEquals(RequestState.STOPPED, landed.state(),
                "§2.2 rule 2: a replica already at the desired state is success, not a failure");
        assertEquals(AttemptOutcome.SUCCEEDED, ApplyEngineHarness.attempts()
                .latest(tenant, stopIntents(landed.applyId()).get(0).intentId())
                .orElseThrow().outcome());
    }

    // ---- instance-wide ------------------------------------------------------------------------------

    /**
     * The case {@code BE-D23} forbids for an apply and {@code BE-D26} permits for a stop. Both
     * placements' replicas land under ONE {@code apply_id}, which is only possible because no
     * step here is placement-scoped.
     *
     * <p>{@code BE-D46} (plan 008 S3): the placements it addresses are the REGISTRY's live ones. This test used to run an
     * apply against each placement first, because the enumerator read request history and a placement with none was
     * invisible to the stop; it runs none now, which is the point: a placement added by {@code addPlacement} and never acted
     * on is stopped too.
     */
    @Test
    void anInstanceWideStopAddressesExactlyTheLivePlacementsWhetherOrNotAnythingEverActedOnThem() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1", "a-2");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "b-1");
        TwoPlacements adapter = new TwoPlacements(a, b, null);

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE)));

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, outcome);
        assertEquals(RequestState.STOPPED, landed.state(),
                "an instance-wide STOP is admitted and executed; only an instance-wide APPLY is "
                        + "refused, and for a reason no stop meets");
        assertEquals(3, stopIntents(landed.applyId()).size(),
                "two placements, three replicas, ONE apply_id — UNIQUE (tenant, apply, step, "
                        + "replica_ref) admits it because STOP is not placement-scoped");
        assertEquals(List.of("a-1", "a-2", "b-1"), stopIntents(landed.applyId()).stream()
                .map(i -> i.replica().value()).sorted().toList());
        assertEquals(0, ApplyEngineHarness.jdbc().queryForObject(
                "SELECT count(*)::int FROM sm_data.apply_request WHERE tenant_id = :t AND operation = 'apply'",
                Map.of("t", tenant.value()), Integer.class), "precondition of the point: no apply ever named them");
        for (TargetReplica r : a.replicas(ref(placementA))) {
            assertEquals(Liveness.STOPPED, a.status(r).liveness());
        }
        assertEquals(Liveness.STOPPED, b.status(b.replicas(ref(placementB)).get(0)).liveness());
    }

    /**
     * {@code R6}'s defect, now reachable through {@code retirePlacement} (plan 008 S3): the history enumerator returned a
     * placement that was retired, the catalog refused it ({@code placement.unresolvable}), and the stop landed
     * {@code STOP_FAILED} although every LIVE placement had been stopped. The fake adapter refuses a retired placement
     * exactly as {@code TablePlacementCatalog} does ({@code RuntimeAdapterException}), so against the old enumerator this
     * test is red.
     */
    @Test
    void aRetiredPlacementInTheHistoryNoLongerFailsTheInstanceWideStop() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "b-1");
        TwoPlacements adapter = new TwoPlacements(a, b, null);
        applyTo(placementA, a, 1);
        applyTo(placementB, b, 1);             // placement B has a history ...
        ApplyEngineHarness.jdbc().update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t"
                + " AND placement_id = :p", Map.of("t", tenant.value(), "p", placementB.value()));
        adapter.refuse(placementB);            // ... and is retired, which the real catalog refuses to resolve

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE)));

        assertEquals(RequestState.STOPPED, landed.state(),
                "every LIVE placement was stopped, so the stop landed STOPPED (it was STOP_FAILED / placement.unresolvable)");
        assertEquals(null, landed.errorClass());
        assertEquals(List.of("a-1"), stopIntents(landed.applyId()).stream().map(i -> i.replica().value()).toList(),
                "only the live placement was addressed");
        assertEquals(Liveness.STOPPED, a.status(a.replicas(ref(placementA)).get(0)).liveness());
        assertEquals(Liveness.RUNNING, b.status(b.replicas(null).get(0)).liveness(),
                "a retired placement's container is not SM's to stop any more");
    }

    /** An instance-wide APPLY is still refused. The two decisions must not blur into each other. */
    @Test
    void anInstanceWideApplyIsStillRefusedBeforeAdmission() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");

        ApplyOutcome outcome = ApplyEngineHarness
                .executor(new TwoPlacements(a, null, placementA), (p, g) -> GENERATION_4)
                .execute(new ApplyCommand(scope(PlacementId.INSTANCE_WIDE), Operation.APPLY, 9L,
                        Strategy.MANUAL, null, "operator-1"));

        assertInstanceOf(ApplyOutcome.ScopeUnsupported.class, outcome);
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(), "no apply row");
    }

    /**
     * An instance with no live placement. Nothing was addressed, so nothing is known to be down - and {@code STOPPED}
     * would tell an operator the instance is down when SM never looked at anything.
     *
     * <p>The vocabulary is the database's, not a preference: {@code state_fits_operation} admits
     * only {@code REQUESTED}/{@code RUNNING}/{@code STOPPED}/{@code STOP_FAILED} for a stop, so
     * the apply path's {@code FAILED_NO_EFFECT} is refused here. The first draft used it and was
     * refused by name, which is the constraint doing its job.
     *
     * <p>Plan 008 S3: this used to be "an instance SM has never been asked to act on" and used this class's instance, which
     * has two registered placements now; it names an instance of its own that has none (declared, never given one).
     */
    @Test
    void anInstanceWideStopWithNoLivePlacementLandsStopFailed() {
        InstanceId bare = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, bare);
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(a, null, null), (p, g) -> GENERATION_4)
                .execute(stop(new ScopeKey(tenant, bare, PlacementId.INSTANCE_WIDE)));

        assertEquals(RequestState.STOP_FAILED, landed.state());
        assertEquals(new ErrorClass("stop.no.known.placement"), landed.errorClass());
        assertTrue(stopIntents(landed.applyId()).isEmpty());
        assertEquals(Liveness.RUNNING, a.status(a.replicas(ref(placementA)).get(0)).liveness());
    }

    /** The sentinel is still how a stop names "every placement": admitted, where an apply naming it is not. */
    @Test
    void theInstanceWideSentinelIsStillAcceptedForAStop() {
        assertInstanceOf(com.solace.sm.dp.apply.engine.AdmissionOutcome.Admitted.class,
                ApplyEngineHarness.admission().admit(stop(scope(PlacementId.INSTANCE_WIDE))));
    }

    /**
     * A LIVE placement whose only apply failed is still stopped: the enumerator is the registry (plan 008 S3), not the
     * watermark table (which holds only placements whose apply reached a watermark-raising terminal state, so a placement
     * SM wrote config to and then failed on would be invisible to it) and not the request history either.
     */
    @Test
    void aLivePlacementWhoseApplyFailedIsStillStoppedByAnInstanceWideStop() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        a.failMaterializeAlways();
        ApplyEngineHarness.executor(new TwoPlacements(a, null, placementA), (p, g) -> GENERATION_4)
                .execute(new ApplyCommand(scope(placementA), Operation.APPLY, 1L, Strategy.MANUAL,
                        null, "operator-1"));
        assertEquals(0, ApplyEngineHarness.jdbc().queryForObject(
                "SELECT count(*)::int FROM sm_data.placement_watermark WHERE tenant_id = :t",
                Map.of("t", tenant.value()), Integer.class),
                "precondition: the failed apply raised no watermark, so placement_watermark is "
                        + "empty — which is exactly why it is the wrong enumerator");

        // setUp registered BOTH placements, so the instance-wide stop addresses both: B needs an adapter too, or the
        // stop (rightly) lands STOP_FAILED on the live placement the fixture cannot reach. B never had an apply.
        InMemoryRuntimeAdapter fresh = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        InMemoryRuntimeAdapter untouchedB = new InMemoryRuntimeAdapter(ref(placementB), true, "b-1");
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(fresh, untouchedB, null), (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE)));

        assertEquals(RequestState.STOPPED, landed.state());
        assertEquals(2, stopIntents(landed.applyId()).size(), "one STOP intent per live placement's replica");
        assertEquals(Liveness.STOPPED,
                fresh.status(fresh.replicas(ref(placementA)).get(0)).liveness());
    }

    /**
     * {@code BE-D23}'s second clause, <b>closed by {@code BE-D44}</b> (plan 008 S2, {@code V021}); this was
     * {@code anInstanceWideStopIsRefusedWhenTwoPlacementsShareAReplicaLabel} and asserted
     * {@code stop.replica.label.collision}. Replica-scoped steps were keyed by {@code replica_ref} alone within the
     * apply, and {@code replica_ref} is adapter vocabulary — the SSH adapter's is the <i>configured container name</i>,
     * so {@code cache-engine} on host A and host B is the ordinary deployment shape, not a contrivance. The journal
     * could not tell the two STOPs apart, so the engine refused the whole stop before stopping anything.
     *
     * <p>The intent now carries its placement and the idempotency key of an instance-wide request names it, so both
     * replicas are journalled, both are stopped, and the request lands {@code STOPPED}.
     */
    @Test
    void anInstanceWideStopIsNotRefusedWhenTwoPlacementsShareAReplicaLabelAndBothAreStopped() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "cache-engine");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "cache-engine");
        TwoPlacements adapter = new TwoPlacements(a, b, null);
        applyTo(placementA, a, 1);
        applyTo(placementB, b, 1);

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(adapter, (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE)));

        assertEquals(RequestState.STOPPED, landed.state());
        assertEquals(null, landed.errorClass());
        List<StepIntentRow> intents = stopIntents(landed.applyId());
        assertEquals(2, intents.size(), "one STOP intent per replica per placement, under ONE apply_id");
        assertEquals(java.util.Set.of(placementA, placementB),
                intents.stream().map(StepIntentRow::placement).collect(java.util.stream.Collectors.toSet()),
                "each intent names the placement it addressed");
        for (StepIntentRow intent : intents) {
            assertEquals("cache-engine", intent.replica().value());
            assertEquals(ApplyExecutor.idempotencyKey(landed.applyId(), scope(PlacementId.INSTANCE_WIDE),
                            intent.placement(), Step.STOP, intent.replica()), intent.idempotencyKey(),
                    "an instance-wide request's key names the placement");
            assertEquals(landed.applyId().value() + ":" + intent.placement().value() + ":STOP:cache-engine",
                    intent.idempotencyKey());
            assertEquals(AttemptOutcome.SUCCEEDED,
                    ApplyEngineHarness.attempts().latest(tenant, intent.intentId()).orElseThrow().outcome());
        }
        for (InMemoryRuntimeAdapter placementAdapter : List.of(a, b)) {
            assertEquals(Liveness.STOPPED,
                    placementAdapter.status(placementAdapter.replicas(null).get(0)).liveness(),
                    "both replicas stopped, neither silently journalled as the other's retry");
        }
    }

    /**
     * W1 review A-5 (a): what an operator sees. {@code observeApply} reports each step of an instance-wide stop under
     * the placement that step addressed, not under the request's (absent) placement, so two same-labelled replicas are
     * told apart.
     */
    @Test
    void observeApplyReportsEachStepsOwnPlacementForAnInstanceWideStop() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "cache-engine");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "cache-engine");
        applyTo(placementA, a, 1);
        applyTo(placementB, b, 1);
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(a, b, null), (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE)));

        var jdbc = SmDataPostgres.runtimeJdbc();
        var config = new com.solace.sm.dp.config.ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(jdbc), TestHashers.keyed(), new com.solace.sm.dp.config.Retention(10),
                java.time.Clock.systemUTC(), new JdbcRollbackTargetRepository(jdbc));
        var service = new com.solace.sm.dp.apply.service.ApplyServiceImpl(() -> null,
                new JdbcPlacementRepository(jdbc), ApplyEngineHarness.requests(),
                new JdbcApplyObservationRepository(jdbc), config,
                new com.solace.sm.dp.config.RollbackTargets(new JdbcRollbackTargetRepository(jdbc)), () -> { });

        var view = service.observeApply(tenant.value(), instance.value(),
                new com.solace.sm.dp.api.apply.ObserveQuery(landed.applyId().value(), null, 50)).get(0);

        assertEquals(null, view.placementId(), "the request itself is instance-wide");
        assertEquals(java.util.Set.of(placementA.value(), placementB.value()),
                view.steps().stream().map(com.solace.sm.dp.api.apply.ApplyStepView::placementId)
                        .collect(java.util.stream.Collectors.toSet()),
                "each step names the placement it addressed");
        assertEquals(2, view.steps().size());
    }

    /**
     * W1 review A-5 (b): a crash mid-way through an instance-wide stop over two same-labelled placements resumes onto
     * the journalled intent and creates the other, never a duplicate of the first: two intents, not four.
     */
    @Test
    void aCrashedInstanceWideStopOverTwoSameLabelledPlacementsResumesToTheSameTwoIntents() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "cache-engine");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "cache-engine");
        TwoPlacements adapter = new TwoPlacements(a, b, null);
        // An instance-wide stop enumerates placements from their apply history: without these there is nothing to stop.
        applyTo(placementA, a, 1);
        applyTo(placementB, b, 1);
        adapter.crashOnStopOf = "cache-engine";

        assertThrowsRuntime(() -> ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .execute(stop(scope(PlacementId.INSTANCE_WIDE))));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        java.util.Set<java.util.UUID> before = stopIntents(id).stream().map(StepIntentRow::intentId)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(before.isEmpty(), "precondition: the crash left at least one journalled intent");

        adapter.crashOnStopOf = null;
        List<ApplyOutcome> resumed = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4).resume(tenant);

        assertEquals(RequestState.STOPPED, assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        List<StepIntentRow> intents = stopIntents(id);
        assertEquals(2, intents.size(), "the same two intents, not four");
        assertTrue(intents.stream().map(StepIntentRow::intentId).collect(java.util.stream.Collectors.toSet())
                .containsAll(before), "the pre-crash intents were resumed onto, not replaced");
        assertEquals(java.util.Set.of(placementA, placementB),
                intents.stream().map(StepIntentRow::placement).collect(java.util.stream.Collectors.toSet()));
    }

    /** A placement-scoped stop keeps its key format: its apply_id already fixes the placement (BE-D44). */
    @Test
    void aPlacementScopedStopKeepsTheUnchangedKeyFormatAndItsPlacement() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "cache-engine");

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(a, null, null), (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA)));

        StepIntentRow intent = stopIntents(landed.applyId()).get(0);
        assertEquals(placementA, intent.placement());
        assertEquals(landed.applyId().value() + ":STOP:cache-engine", intent.idempotencyKey());
    }

    /** The same labels on ONE placement are impossible, so a placement stop is never refused. */
    @Test
    void aPlacementStopIsNeverRefusedForALabelCollision() {
        InMemoryRuntimeAdapter a =
                new InMemoryRuntimeAdapter(ref(placementA), true, "cache-engine");
        InMemoryRuntimeAdapter b =
                new InMemoryRuntimeAdapter(ref(placementB), true, "cache-engine");

        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new TwoPlacements(a, b, null), (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA)));

        assertEquals(RequestState.STOPPED, landed.state(),
                "within one placement a replica label is unique by construction");
        assertEquals(Liveness.RUNNING, b.status(b.replicas(null).get(0)).liveness());
    }

    /**
     * <b>Regression, review MAJOR.</b> A stop has no {@code APPLY_INDETERMINATE} —
     * {@code state_fits_operation} gives it four states and that is not one of them — so a resume
     * that cannot make progress must strand it {@code STOP_FAILED}. Landing the apply path's
     * state would throw inside the very method that exists to stop an exception escaping, and
     * leave the emergency lever {@code RUNNING} with {@code stop_inflight_fence} blocking every
     * retry and no operator exit at all.
     */
    @Test
    void aStopThatCannotBeResumedIsStrandedStopFailedAndNeverLeftRunning() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        ApplyId id = ((com.solace.sm.dp.apply.engine.AdmissionOutcome.Admitted)
                ApplyEngineHarness.admission().admit(stop(scope(placementA)))).applyId();
        ApplyEngineHarness.outcomes().start(tenant, id);

        // An adapter that cannot enumerate this placement at all, and not with a classified
        // failure: a hard error, the shape resume must survive.
        List<ApplyOutcome> resumed = ApplyEngineHarness
                .executor(new HardFailure(), (p, g) -> GENERATION_4).resume(tenant);

        assertEquals(1, resumed.size());
        assertEquals(RequestState.STOP_FAILED, stateOf(id),
                "not RUNNING, and not APPLY_INDETERMINATE which state_fits_operation would refuse");
        assertTrue(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(),
                "the scope is released: an emergency stop must never be the thing that jams it");
        assertEquals(Liveness.RUNNING, a.status(a.replicas(null).get(0)).liveness());
    }

    /**
     * <b>Regression, review MINOR.</b> A placement whose containers are all gone is nothing to
     * stop, not a failure — the same answer {@code ResumePlanner} gives an {@code ABSENT} replica.
     * Execute and resume must not disagree about the same fact.
     */
    @Test
    void aPlacementWithNoReplicasIsNothingToStopRatherThanAFailure() {
        ApplyOutcome.Landed landed = (ApplyOutcome.Landed) ApplyEngineHarness
                .executor(new NoReplicas(), (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA)));

        assertEquals(RequestState.STOPPED, landed.state());
        assertTrue(stopIntents(landed.applyId()).isEmpty());
    }

    /**
     * <b>Regression, review MAJOR.</b> The workflow {@code BE-D26} creates: stop the instance,
     * then bring it back on a new generation. An unconditional "refuse a replica that is not
     * running" check made this impossible — every replica is down at pre-flight, so the apply
     * halted before issuing a single verb and left the instance down.
     *
     * <p>The condition that tells the two apart is whether the replica was running <b>when this
     * apply began</b>. Down at pre-flight is the state the operator asked SM to apply from; down
     * at the lifecycle step after running at pre-flight is obligation (r).
     */
    @Test
    void anApplyUnderRestartAllBringsAStoppedInstanceBackUp() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1", "a-2");
        TwoPlacements adapter = new TwoPlacements(a, null, placementA);
        applyTo(placementA, a, 1);

        assertEquals(RequestState.STOPPED, ((ApplyOutcome.Landed) ApplyEngineHarness
                .executor(adapter, (p, g) -> GENERATION_4).execute(stop(scope(placementA))))
                .state());
        for (TargetReplica r : a.replicas(null)) {
            assertEquals(Liveness.STOPPED, a.status(r).liveness(), "precondition: the instance is down");
        }

        ApplyOutcome outcome = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .execute(new ApplyCommand(scope(placementA), Operation.APPLY, 5L,
                        Strategy.RESTART_ALL, "operator-1", "operator-1"));

        assertEquals(RequestState.APPLIED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome).state(),
                "a stopped instance is brought UP by the apply that follows the stop — refusing "
                        + "it would make BE-D26's own workflow impossible");
        for (TargetReplica r : a.replicas(null)) {
            assertEquals(Liveness.RUNNING, a.status(r).liveness());
            assertEquals(GENERATION_4.hash(), a.status(r).observedHash());
        }
    }

    // ---- resume ---------------------------------------------------------------------------------------

    @Test
    void aStopInterruptedMidwayIsResumedAndIsIdempotent() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1", "a-2");
        TwoPlacements adapter = new TwoPlacements(a, null, placementA);
        adapter.crashOnStopOf = "a-2";

        assertThrowsRuntime(() -> ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .execute(stop(scope(placementA))));
        ApplyId id = ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
        assertEquals(Liveness.STOPPED, a.status(a.replicas(ref(placementA)).get(0)).liveness(),
                "precondition: replica 1 really was stopped before the crash");

        adapter.crashOnStopOf = null;
        List<ApplyOutcome> resumed =
                ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4).resume(tenant);

        assertEquals(RequestState.STOPPED,
                assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0)).state());
        assertEquals(RequestState.STOPPED, stateOf(id));
        assertEquals(2, stopIntents(id).size(), "the same two intents, not four");
        for (TargetReplica r : a.replicas(ref(placementA))) {
            assertEquals(Liveness.STOPPED, a.status(r).liveness());
        }
    }

    /**
     * <b>Regression, and a mutation that first did NOT fire.</b> {@code attempts.open} throws
     * {@code RetryCapExceeded} once a replica's {@code STOP} intent has spent its attempts, and
     * that must not escape: a stop has no {@code APPLY_INDETERMINATE} to be stranded in.
     *
     * <p>The first version of the strand test used a hard enumeration failure, which reaches the
     * sweep's own catch and therefore passed with the guard removed. This one exhausts the cap for
     * real, and distinguishes the two by what happens to the OTHER replica: guarded, the cap is
     * one replica's problem and the rest are still stopped; unguarded, the exception unwinds the
     * whole request.
     */
    @Test
    void aReplicaWhoseStopAttemptsAreSpentDoesNotUnwindTheWholeStop() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1", "a-2");
        TwoPlacements adapter = new TwoPlacements(a, null, placementA);

        ApplyId id = ((com.solace.sm.dp.apply.engine.AdmissionOutcome.Admitted)
                ApplyEngineHarness.admission().admit(stop(scope(placementA)))).applyId();
        ApplyEngineHarness.outcomes().start(tenant, id);

        // Spend a-1's STOP attempts on the journal directly. Driving the executor into this state
        // is not possible any more -- the sweep's own per-request isolation catches a crash and
        // strands the request terminal -- so the state is built through the same services the
        // executor uses, with the executor's own idempotency key.
        ReplicaRef spent = a.replicas(null).get(0).ref();
        StepIntentRow intent = ApplyEngineHarness.stepIntents().ensureIntent(tenant, id,
                placementA, Step.STOP, spent, ApplyExecutor.idempotencyKey(id, Step.STOP, spent), null);
        AttemptService attempts = ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts());
        for (int i = 0; i < ApplyEngineHarness.maxAttempts(); i++) {
            attempts.close(tenant,
                    attempts.open(tenant, id, intent, null, java.time.Duration.ofMinutes(5))
                            .attemptId(),
                    AttemptOutcome.FAILED, null, new ErrorClass("fake.spent"), null);
        }

        List<ApplyOutcome> resumed =
                ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4).resume(tenant);

        ApplyOutcome.Landed landed = assertInstanceOf(ApplyOutcome.Landed.class, resumed.get(0));
        assertEquals(RequestState.STOP_FAILED, landed.state());
        assertEquals(new ErrorClass("stop.attempt.unavailable"), landed.errorClass(),
                "the spent cap is reported as itself, not as a generic resume failure");
        assertEquals(Liveness.STOPPED, a.status(a.replicas(null).get(1)).liveness(),
                "and replica 2 is still stopped: one replica's spent cap is not the whole "
                        + "request's problem, which is the difference the guard makes");
    }

    /** {@code BE-D26}: resume narrows to a scope, so recovering one instance sweeps only it. */
    @Test
    void resumeNarrowedToAScopeLeavesOtherScopesAlone() {
        InMemoryRuntimeAdapter a = new InMemoryRuntimeAdapter(ref(placementA), true, "a-1");
        InMemoryRuntimeAdapter b = new InMemoryRuntimeAdapter(ref(placementB), true, "b-1");
        TwoPlacements adapter = new TwoPlacements(a, b, null);

        for (PlacementId p : List.of(placementA, placementB)) {
            ApplyId id = ((com.solace.sm.dp.apply.engine.AdmissionOutcome.Admitted)
                    ApplyEngineHarness.admission().admit(stop(scope(p)))).applyId();
            ApplyEngineHarness.outcomes().start(tenant, id);
        }

        List<ApplyOutcome> resumed = ApplyEngineHarness.executor(adapter, (p, g) -> GENERATION_4)
                .resume(scope(placementA));

        assertEquals(1, resumed.size(), "only placement A's request was swept");
        assertEquals(Liveness.STOPPED, a.status(a.replicas(ref(placementA)).get(0)).liveness());
        assertEquals(Liveness.RUNNING, b.status(b.replicas(ref(placementB)).get(0)).liveness(),
                "placement B's stop is still in flight and untouched");
        assertFalse(ApplyEngineHarness.requests().nonTerminal(tenant).isEmpty(),
                "and it is still there to be resumed later");
    }

    private static void assertThrowsRuntime(Runnable r) {
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, r::run);
    }

    /**
     * Two placements behind one adapter, which is what an instance-wide operation needs and what
     * {@link InMemoryRuntimeAdapter} — pinned to one placement — cannot be on its own. An unknown
     * placement is a {@code RuntimeAdapterException}, as a real catalog miss would be.
     */
    /** Enumeration fails hard — not a classified RuntimeAdapterException, a bare RuntimeException. */
    private static final class HardFailure extends HardFailureBase {

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            throw new RuntimeException("simulated unrecoverable enumeration failure");
        }
    }

    /** A placement the runtime knows and that currently holds no replicas. */
    private static final class NoReplicas extends HardFailureBase {

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            return List.of();
        }
    }

    /** The seven operations these two fakes never reach. */
    private abstract static class HardFailureBase implements RuntimeAdapter {

        @Override
        public com.solace.sm.dp.apply.runtime.PreflightResult prepare(PlacementRef p, long g) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult materializeConfig(TargetReplica r,
                long g, RenderedConfig c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult rematerializeIfCurrent(TargetReplica r,
                long g, RenderedConfig c, String expectedCurrentHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult startOrUpdate(TargetReplica r,
                long g) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult stop(TargetReplica r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RuntimeStatus status(TargetReplica r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RollbackAssessment assessRollback(PlacementRef p,
                long g, String h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RestoreResult restore(PlacementRef p, long g,
                String h) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TwoPlacements implements RuntimeAdapter {

        private final Map<PlacementId, InMemoryRuntimeAdapter> byPlacement = new java.util.HashMap<>();
        String crashOnStopOf;

        TwoPlacements(InMemoryRuntimeAdapter a, InMemoryRuntimeAdapter b, PlacementId unused) {
            register(a);
            if (b != null) {
                register(b);
            }
        }

        /** A placement the catalog no longer resolves (retired): its calls now fail like a real catalog miss. */
        void refuse(PlacementId placement) {
            byPlacement.remove(placement);
        }

        private void register(InMemoryRuntimeAdapter adapter) {
            PlacementRef any = adapter.replicas(null).get(0).placement();
            byPlacement.put(any.placement(), adapter);
        }

        private InMemoryRuntimeAdapter owner(PlacementRef p) {
            InMemoryRuntimeAdapter found = byPlacement.get(p.placement());
            if (found == null) {
                throw new com.solace.sm.dp.apply.runtime.RuntimeAdapterException(
                        new ErrorClass("fake.placement.unknown"));
            }
            return found;
        }

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            return owner(p).replicas(p);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.PreflightResult prepare(PlacementRef p, long g) {
            return owner(p).prepare(p, g);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult materializeConfig(TargetReplica r,
                long g, RenderedConfig c) {
            return owner(r.placement()).materializeConfig(r, g, c);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.MaterializeResult rematerializeIfCurrent(TargetReplica r,
                long g, RenderedConfig c, String expectedCurrentHash) {
            return owner(r.placement()).rematerializeIfCurrent(r, g, c, expectedCurrentHash);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult startOrUpdate(TargetReplica r,
                long g) {
            return owner(r.placement()).startOrUpdate(r, g);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.LifecycleResult stop(TargetReplica r) {
            if (r.ref().value().equals(crashOnStopOf)) {
                throw new RuntimeException("simulated process death mid-stop");
            }
            return owner(r.placement()).stop(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RuntimeStatus status(TargetReplica r) {
            return owner(r.placement()).status(r);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RollbackAssessment assessRollback(PlacementRef p,
                long g, String intendedHash) {
            return owner(p).assessRollback(p, g, intendedHash);
        }

        @Override
        public com.solace.sm.dp.apply.runtime.RestoreResult restore(PlacementRef p, long g,
                String targetHash) {
            return owner(p).restore(p, g, targetHash);
        }
    }
}
