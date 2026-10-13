package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The engine's own landings, publishing through the production composition (plan 010 T6.4): the real
 * {@code OutcomeService}/{@code AdmissionService}/{@code ApplyExecutor} over the real {@code sm_data} journal, an
 * {@link ApplyEventEmitter} over {@link AfterCommitEventPublisher} + {@link SpringTransactionBoundary}, and a
 * recording transport at the bottom. The recorder reads the request back through a SEPARATE auto-commit connection at
 * the instant an event arrives, so "after commit" is observed from outside the transaction, not asserted from inside.
 *
 * <p>The broker half - the same events on a real local broker - is {@code ApplyOutcomePublishIT}.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyEventsLandingIT {

    private static final RenderedConfig GENERATION_7 =
            TestHashers.rendered("generation: 7\n".getBytes(StandardCharsets.UTF_8));

    /** One arrival: the event, and what the journal said about its request when it arrived. */
    private record Arrival(EventEnvelope event, RequestState requestStateAtArrival) {

        String label() {
            String step = (String) event.payload().get("step");
            String state = (String) event.payload().get("state");
            String replica = (String) event.payload().get("replicaId");
            return event.event().replace("instance.", "")
                    + (step != null ? ":" + step + "@" + replica : ":" + state);
        }
    }

    private final List<Arrival> arrivals = new ArrayList<>();
    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private ApplyEventEmitter emitter;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        instance = new InstanceId(UUID.randomUUID());
        placementId = new PlacementId(UUID.randomUUID());
        scope = InventoryFixtures.registered(new ScopeKey(tenant, instance, placementId));
        placement = new PlacementRef(tenant, instance, placementId);
        EventPublisher recorder = event -> {
            ApplyId id = new ApplyId(UUID.fromString((String) event.payload().get("applyId")));
            arrivals.add(new Arrival(event,
                    ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state()));
        };
        emitter = emitterOver(recorder);
    }

    private ApplyEventEmitter emitterOver(EventPublisher transport) {
        return new ApplyEventEmitter(new AfterCommitEventPublisher(transport, new SpringTransactionBoundary()),
                Clock.systemUTC(), UUID::randomUUID);
    }

    private AdmissionService admission(ApplyEventEmitter e) {
        return new AdmissionService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e);
    }

    private OutcomeService outcomes(ApplyEventEmitter e) {
        return new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e);
    }

    private ApplyExecutor executor(InMemoryRuntimeAdapter adapter, ApplyEventEmitter e) {
        var props = ApplyEngineHarness.engineProperties();
        return new ApplyExecutor(admission(e), ApplyEngineHarness.stepIntents(),
                ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()), outcomes(e), adapter,
                (p, g) -> GENERATION_7, new HealthGate(Clock.systemUTC(), Sleeper.real(), props),
                new ResumePlanner(), props, e);
    }

    private InMemoryRuntimeAdapter twoReplicas() {
        return new InMemoryRuntimeAdapter(placement, true, "replica-1", "replica-2");
    }

    private ApplyCommand apply(long generation, Strategy strategy) {
        return ApplyEngineHarness.rollingApply(scope, generation, strategy);
    }

    private ApplyCommand manual(long generation) {
        return ApplyEngineHarness.apply(scope, generation);
    }

    private List<String> labels() {
        return arrivals.stream().map(Arrival::label).toList();
    }

    private long stampOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().stateVersion();
    }

    private ApplyId landed(ApplyOutcome o) {
        return assertInstanceOf(ApplyOutcome.Landed.class, o).applyId();
    }

    // ---- a landing publishes once, after commit, with the row's own stamp ----------------------------------------

    @Test
    void aManualApplyPublishesItsStagedRolloutsThenExactlyOneStagedOutcomeAfterCommit() {
        ApplyId id = landed(executor(twoReplicas(), emitter).execute(manual(7)));

        assertEquals(List.of("rollout:staged@replica-1", "rollout:staged@replica-2", "apply-outcome:STAGED"),
                labels());
        Arrival outcome = arrivals.get(2);
        assertEquals(RequestState.STAGED, outcome.requestStateAtArrival(),
                "at the instant the event arrived the journal already held STAGED from OUTSIDE the transaction: "
                        + "the publish followed the commit");
        assertEquals(stampOf(id), outcome.event().stateVersion(),
                "the event carries apply_request.state_version, the value the trigger wrote");
        assertEquals(placementId.value().toString(), outcome.event().entity().placementId());
    }

    @Test
    void aGatedApplyPublishesStagedThenRequestedThenObservedPerReplicaAndTheOutcomeIsStrictlyNewest() {
        ApplyId id = landed(executor(twoReplicas(), emitter).execute(apply(7, Strategy.ROLLING_HEALTH_GATED)));

        assertEquals(List.of(
                "rollout:staged@replica-1", "rollout:staged@replica-2",
                "rollout:restart-requested@replica-1", "rollout:restart-observed@replica-1",
                "rollout:restart-requested@replica-2", "rollout:restart-observed@replica-2",
                "apply-outcome:APPLIED"), labels());
        long outcomeStamp = arrivals.get(6).event().stateVersion();
        assertEquals(stampOf(id), outcomeStamp);
        for (Arrival a : arrivals.subList(0, 6)) {
            assertTrue(a.event().stateVersion() < outcomeStamp,
                    "the terminal notice is strictly newer than every rollout notice of its apply: " + a.label());
        }
    }

    @Test
    void anUngatedApplyNeverClaimsToHaveObservedTheRestart() {
        executor(twoReplicas(), emitter).execute(apply(7, Strategy.ROLLING_NO_VALIDATION));

        assertEquals(List.of(
                "rollout:staged@replica-1", "rollout:staged@replica-2",
                "rollout:restart-requested@replica-1", "rollout:restart-requested@replica-2",
                "apply-outcome:APPLIED"), labels(),
                "only the gate observes a restart; a rollout notice must not say more than the engine saw");
    }

    @Test
    void aStopPublishesNothingAtAllItIsRestObserved() {
        InMemoryRuntimeAdapter adapter = twoReplicas();
        ApplyId id = landed(executor(adapter, emitter).execute(ApplyEngineHarness.stop(scope)));

        assertEquals(RequestState.STOPPED, ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state());
        assertEquals(List.of(), labels(), "M10: stop outcomes are not in this event; stops are REST-observed");
    }

    @Test
    void aSupersededElderPublishesItsSupersededOutcome() {
        ApplyExecutor executor = executor(twoReplicas(), emitter);
        ApplyId first = landed(executor.execute(manual(7)));
        arrivals.clear();

        ApplyId second = landed(executor.execute(manual(8)));

        List<String> outcomes = arrivals.stream().filter(a -> a.event().event().equals("instance.apply-outcome"))
                .map(Arrival::label).toList();
        assertEquals(List.of("apply-outcome:SUPERSEDED", "apply-outcome:STAGED"), outcomes);
        Arrival superseded = arrivals.stream().filter(a -> a.label().equals("apply-outcome:SUPERSEDED"))
                .findFirst().orElseThrow();
        assertEquals(first.value().toString(), superseded.event().payload().get("applyId"));
        assertEquals(stampOf(first), superseded.event().stateVersion());
        assertEquals(RequestState.SUPERSEDED, superseded.requestStateAtArrival());
        assertTrue(stampOf(second) > stampOf(first));
    }

    @Test
    void applyIndeterminateAndItsResolutionEachPublishAnOutcome() {
        AdmissionService admission = admission(emitter);
        OutcomeService outcomes = outcomes(emitter);
        ApplyId id = ((AdmissionOutcome.Admitted) admission.admit(manual(9))).applyId();
        outcomes.start(tenant, id);

        outcomes.land(tenant, id, RequestState.APPLY_INDETERMINATE);
        outcomes.resolveIndeterminate(tenant, id, new Resolution("duty-operator", ResolutionOutcome.APPLIED,
                new ApplyResolutionEvidence(9, List.of(new ReplicaObservation(new ReplicaRef("replica-1"), true,
                        null)))));

        assertEquals(List.of("apply-outcome:APPLY_INDETERMINATE", "apply-outcome:INDETERMINATE_RESOLVED"), labels(),
                "OR-58: the first landing on the human-action state is announced, and so is its resolution");
        assertEquals(RequestState.APPLY_INDETERMINATE, arrivals.get(0).requestStateAtArrival());
        assertTrue(arrivals.get(0).event().stateVersion() < arrivals.get(1).event().stateVersion());
    }

    @Test
    void anAdmissionThatIsRefusedAnnouncesNothing() {
        ApplyExecutor executor = executor(twoReplicas(), emitter);
        executor.execute(manual(7));
        arrivals.clear();

        // Same generation: fenced. The refusal returns before any supersede, so nothing is owed to anybody.
        assertInstanceOf(ApplyOutcome.NotAdmitted.class, executor.execute(manual(7)));

        assertEquals(List.of(), labels());
    }

    // ---- the journal outranks the notice -------------------------------------------------------------------------

    @Test
    void aBrokerOutagePublishesNothingAndTheApplyStillCompletes() {
        JcsmpEventPublisher down = JcsmpEventPublisher.disabledByConfiguration();
        ApplyEventEmitter outage = emitterOver(down);

        ApplyId id = landed(executor(twoReplicas(), outage).execute(manual(7)));

        assertEquals(RequestState.STAGED, ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state());
        assertTrue(down.droppedCount() >= 3,
                "two rollout notices and the outcome were each a counted drop; was " + down.droppedCount());
    }

    @Test
    void aDefectInBuildingANoticeNeverStrandsALanding() {
        EventPublisher defective = event -> {
            throw new IllegalArgumentException("a defect in the notice");
        };
        ApplyEventEmitter broken = new ApplyEventEmitter(defective, Clock.systemUTC(), UUID::randomUUID);

        ApplyOutcome outcome = assertDoesNotThrow(() -> executor(twoReplicas(), broken).execute(manual(7)));

        ApplyId id = landed(outcome);
        assertEquals(RequestState.STAGED, ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state(),
                "the journal outranks the notice: the request landed, it is not stuck RUNNING");
    }
}
