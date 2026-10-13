package com.solace.sm.dp.apply.events;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.EventJson;
import com.solace.sm.events.NotifyEventShapes;
import com.solace.sm.events.NotifyTopics;
import com.solace.sm.events.contracttest.ContractArtifacts;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link ApplyEventEmitter} under Spring's real synchronization dispatch (plan 010 T6.4, executing plan 007 Task 5):
 * the emitter sits over the same {@link AfterCommitEventPublisher} + {@link SpringTransactionBoundary} composition
 * production uses, over a recording transport and a datastore-less transaction manager, so "after commit" is
 * Spring's own machinery and not a hand-poked static.
 *
 * <p>The database half - the engine's own landings, with real stamps - is {@code ApplyEventsLandingIT}.
 */
class ApplyEventEmitterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC);

    /** A real {@link AbstractPlatformTransactionManager} with no resource; commit flips a flag the tests read. */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        volatile boolean committed;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    /** The transport: records each event with whether the transaction had committed when it arrived. */
    static final class Transport implements EventPublisher {
        final RecordingTransactionManager ptm;
        final List<EventEnvelope> sent = new ArrayList<>();
        final List<Boolean> committedAtArrival = new ArrayList<>();

        Transport(RecordingTransactionManager ptm) {
            this.ptm = ptm;
        }

        @Override
        public void publish(EventEnvelope event) {
            sent.add(event);
            committedAtArrival.add(ptm.committed);
        }
    }

    private RecordingTransactionManager ptm;
    private Transport transport;
    private ApplyEventEmitter emitter;
    private TransactionTemplate tx;
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final InstanceId instance = new InstanceId(UUID.randomUUID());
    private final PlacementId placement = new PlacementId(UUID.randomUUID());

    @BeforeEach
    void setUp() {
        ptm = new RecordingTransactionManager();
        transport = new Transport(ptm);
        emitter = new ApplyEventEmitter(new AfterCommitEventPublisher(transport, new SpringTransactionBoundary()),
                CLOCK, UUID::randomUUID);
        tx = new TransactionTemplate(ptm);
    }

    private ApplyRequestRow row(Operation op, RequestState state, PlacementId at, Long stamp) {
        ScopeKey scope = new ScopeKey(tenant, instance, at);
        boolean terminal = state.isTerminal();
        return new ApplyRequestRow(ApplyId.next(), scope, op, op == Operation.STOP ? null : 7L,
                op == Operation.STOP ? null : Strategy.MANUAL, null, "operator-1",
                Instant.parse("2026-10-05T09:59:00Z"), state, terminal ? Instant.parse("2026-10-05T09:59:30Z") : null,
                stamp);
    }

    private ApplyRequestRow applied(long stamp) {
        return row(Operation.APPLY, RequestState.APPLIED, placement, stamp);
    }

    // ---- the apply-outcome event --------------------------------------------------------------------------------

    @Test
    void aTerminalLandingPublishesExactlyOneApplyOutcomeAfterCommit() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            assertTrue(transport.sent.isEmpty(), "nothing may reach the transport while the transaction is open");
        });

        assertEquals(1, transport.sent.size());
        assertEquals(List.of(Boolean.TRUE), transport.committedAtArrival,
                "the event reached the transport only after the transaction's doCommit");
        assertEquals("instance.apply-outcome", transport.sent.get(0).event());
    }

    @Test
    void aStopLandingPublishesNoApplyOutcome() {
        PlacementId wide = PlacementId.INSTANCE_WIDE;
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(row(Operation.STOP, RequestState.STOPPED, placement, 9L));
            emitter.onApplyOutcome(row(Operation.STOP, RequestState.STOP_FAILED, placement, 10L));
            // M10: an instance-wide stop has no legal placement-scoped envelope; it is REST-observed.
            emitter.onApplyOutcome(row(Operation.STOP, RequestState.STOPPED, wide, 11L));
            emitter.onApplyOutcome(row(Operation.STOP, RequestState.STOP_FAILED, wide, 12L));
            // A restart request is not an apply either.
            emitter.onApplyOutcome(row(Operation.RESTART, RequestState.APPLIED, placement, 13L));
        });

        assertEquals(List.of(), transport.sent, "stops are REST-observed; this event is the apply operation's only");
    }

    @Test
    void anInstanceWideApplyHasNoLegalEnvelopeAndPublishesNothing() {
        tx.executeWithoutResult(status ->
                emitter.onApplyOutcome(row(Operation.APPLY, RequestState.APPLIED, PlacementId.INSTANCE_WIDE, 14L)));

        assertEquals(List.of(), transport.sent,
                "OR-4 requires a real placementId; the nil sentinel is not a storable placement");
    }

    @Test
    void applyOutcomeCarriesKindPlacementAndARequiredPlacementId() {
        ApplyRequestRow landed = applied(41);
        tx.executeWithoutResult(status -> emitter.onApplyOutcome(landed));

        EventEnvelope e = transport.sent.get(0);
        assertEquals("placement", e.entity().kind(), "OR-4's const: the kind is placement, never instance");
        assertEquals(instance.value().toString(), e.entity().instanceId());
        assertEquals(placement.value().toString(), e.entity().placementId());
        assertEquals("dp-core", e.producer());
        assertEquals(tenant.value().toString(), e.organization());
        assertEquals("sm/v1/" + tenant.value() + "/notify/instance/" + instance.value() + "/apply-outcome/"
                + landed.applyId().value(), NotifyTopics.topicFor(e));
    }

    @Test
    void applyOutcomeCarriesNoErrorDetail() throws Exception {
        tx.executeWithoutResult(status ->
                emitter.onApplyOutcome(row(Operation.APPLY, RequestState.CONFIG_DIVERGED, placement, 5L)));

        EventEnvelope e = transport.sent.get(0);
        assertEquals(Set.of("applyId", "state"), e.payload().keySet(),
                "OR-4: applyId and state, nothing else - no message, no ErrorClass token");
        String json = EventJson.mapper().writeValueAsString(e).toLowerCase();
        assertFalse(json.contains("error"), "no error text on the wire: " + json);
        assertFalse(json.contains("message"), "no message on the wire: " + json);
    }

    @Test
    void applyOutcomeStateVersionIsTheRequestsOwnStamp() {
        tx.executeWithoutResult(status -> emitter.onApplyOutcome(applied(4242)));

        assertEquals(4242L, transport.sent.get(0).stateVersion(), "apply_request.state_version, not a counter here");

        IllegalArgumentException unstamped = assertThrows(IllegalArgumentException.class,
                () -> emitter.onApplyOutcome(row(Operation.APPLY, RequestState.APPLIED, placement, null)));
        assertTrue(unstamped.getMessage().contains("state_version"),
                "an unstamped row cannot carry the ordering input: " + unstamped.getMessage());
    }

    @Test
    void applyIndeterminatePublishesAnOutcomeToo() {
        tx.executeWithoutResult(status ->
                emitter.onApplyOutcome(row(Operation.APPLY, RequestState.APPLY_INDETERMINATE, placement, 77L)));

        assertEquals(1, transport.sent.size());
        assertEquals("APPLY_INDETERMINATE", transport.sent.get(0).payload().get("state"),
                "OR-58: not terminal, but where a human must act - a consumer must be told");
    }

    /** The enum parity the slice review asked for: mesh-events cannot import dp-core, so this test is the lock. */
    @Test
    void theApplyOutcomeStateEnumIsExactlyTheApplyTerminalStatesPlusIndeterminate() {
        Set<RequestState> accepted = EnumSet.noneOf(RequestState.class);
        for (RequestState s : RequestState.values()) {
            if (!s.validFor(Operation.APPLY)) {
                continue;
            }
            // The shape validator is the contract's Java mirror; ask it, through the emitter, which states it takes.
            try {
                new ApplyEventEmitter(NotifyEventShapes::validate, CLOCK, UUID::randomUUID)
                        .onApplyOutcome(row(Operation.APPLY, s, placement, 1L));
                accepted.add(s);
            } catch (IllegalArgumentException refused) {
                // not in the notify enum
            }
        }
        Set<RequestState> expected = EnumSet.noneOf(RequestState.class);
        for (RequestState s : RequestState.values()) {
            if (s.validFor(Operation.APPLY) && (s.isTerminal() || s == RequestState.APPLY_INDETERMINATE)) {
                expected.add(s);
            }
        }
        assertEquals(expected, accepted,
                "NotifyEventShapes' instance.apply-outcome state enum must equal {apply-valid and (terminal or "
                        + "APPLY_INDETERMINATE)}; a new terminal state would otherwise go unnoticed");
        assertTrue(accepted.contains(RequestState.INDETERMINATE_RESOLVED));
        assertFalse(accepted.contains(RequestState.RUNNING));
    }

    // ---- the health event (OR-105 c: it moved here from HealthEventEmitter, wire unchanged) ---------------------

    @Test
    void aHealthTransitionPublishesInstanceHealthAfterCommitWithTheRowsOwnStamp() {
        UUID instanceId = instance.value();
        UUID placementId = placement.value();
        tx.executeWithoutResult(status -> {
            emitter.onHealthTransition(tenant, instanceId, placementId, HealthState.RED, 5150L);
            assertTrue(transport.sent.isEmpty(), "nothing reaches the transport while the transaction is open");
        });

        assertEquals(List.of(Boolean.TRUE), transport.committedAtArrival);
        EventEnvelope e = transport.sent.get(0);
        assertEquals("instance.health", e.event());
        assertEquals("dp-core", e.producer());
        assertEquals(tenant.value().toString(), e.organization());
        assertEquals("placement", e.entity().kind());
        assertEquals(instanceId.toString(), e.entity().instanceId());
        assertEquals(placementId.toString(), e.entity().placementId());
        assertEquals(5150L, e.stateVersion(), "the placement row's own stamp, never invented here");
        assertEquals(java.util.Map.of("state", "RED"), e.payload(), "identifiers and a closed token only (I6)");
        assertDoesNotThrow(() -> NotifyEventShapes.validate(e), "the contract's own shape check accepts it");
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.STAGED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 8L);
            status.setRollbackOnly();
        });

        assertEquals(List.of(), transport.sent, "the write happened to nobody, so no notice is owed");
    }

    @Test
    void aBrokerOutagePublishesNothingAndTheApplyStillCompletes() {
        // The raw transport in its declared degraded state: every publish is a COUNTED drop (BE-D1) - the same path a
        // dead session takes. The caller must neither see an exception nor lose its commit.
        JcsmpEventPublisher down = JcsmpEventPublisher.disabledByConfiguration();
        ApplyEventEmitter outage = new ApplyEventEmitter(
                new AfterCommitEventPublisher(down, new SpringTransactionBoundary()), CLOCK, UUID::randomUUID);

        assertDoesNotThrow(() -> tx.executeWithoutResult(status -> outage.onApplyOutcome(applied(41))));

        assertTrue(ptm.committed, "the write committed");
        assertEquals(1L, down.droppedCount(), "the drop is counted (in-process; the export is routed, not built)");
    }

    // ---- the rollout event ---------------------------------------------------------------------------------------

    @Test
    void aRolloutEventIsPublishedForEachOfTheThreeStepsAndNoOthers() {
        tx.executeWithoutResult(status -> {
            for (RolloutStep step : RolloutStep.values()) {
                emitter.onRollout(applied(41), new ReplicaRef("cache-1"), step);
            }
        });

        List<Object> steps = transport.sent.stream().map(e -> e.payload().get("step")).toList();
        assertEquals(List.of("staged", "restart-requested", "restart-observed"), steps);
        assertEquals(Set.of("staged", "restart-requested", "restart-observed"),
                Set.of(RolloutStep.values()[0].wire(), RolloutStep.values()[1].wire(), RolloutStep.values()[2].wire()));
    }

    @Test
    void everyRolloutEventCarriesPlacementIdBecauseRolloutIsPlacementScoped() {
        ApplyRequestRow request = applied(41);
        tx.executeWithoutResult(status -> emitter.onRollout(request, new ReplicaRef("cache-1"), RolloutStep.STAGED));

        EventEnvelope e = transport.sent.get(0);
        assertEquals("instance.rollout", e.event());
        assertEquals("placement", e.entity().kind());
        assertEquals(placement.value().toString(), e.entity().placementId());
        assertEquals(request.applyId().value().toString(), e.payload().get("applyId"));
        assertEquals("cache-1", e.payload().get("replicaId"));
        assertEquals("sm/v1/" + tenant.value() + "/notify/instance/" + instance.value() + "/rollout/"
                + request.applyId().value(), NotifyTopics.topicFor(e));
    }

    @Test
    void aRolloutOfAnInstanceWideRequestPublishesNothing() {
        tx.executeWithoutResult(status -> emitter.onRollout(
                row(Operation.APPLY, RequestState.RUNNING, PlacementId.INSTANCE_WIDE, 3L),
                new ReplicaRef("cache-1"), RolloutStep.STAGED));

        assertEquals(List.of(), transport.sent);
    }

    // ---- the config-state event ---------------------------------------------------------------------------------

    @Test
    void aConfigStateEventCarriesNoPlacementIdBecauseItIsInstanceScoped() {
        tx.executeWithoutResult(status ->
                emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 8L));

        EventEnvelope e = transport.sent.get(0);
        assertEquals("instance.config-state", e.event());
        assertEquals("instance", e.entity().kind());
        assertEquals(null, e.entity().placementId(), "the schema forbids placementId on this branch");
        assertEquals("persisted", e.payload().get("reached"));
        assertEquals(8L, e.stateVersion(), "instance_state's stamp, which the reached_rung write bumps (OR-35)");
    }

    @Test
    void configStateFiresOnlyWhenTheInstancesLowestRungMoves() {
        AtomicLong fired = new AtomicLong();
        tx.executeWithoutResult(status -> {
            // null -> desired: the first rung ever reached is a move.
            if (emitter.onConfigStateReached(tenant, instance, null, LadderRung.DESIRED, 1L)) {
                fired.incrementAndGet();
            }
            // desired -> desired: the lowest did not move (e.g. a second placement caught up while the lowest held).
            if (emitter.onConfigStateReached(tenant, instance, LadderRung.DESIRED, LadderRung.DESIRED, 2L)) {
                fired.incrementAndGet();
            }
            // desired -> persisted: moved.
            if (emitter.onConfigStateReached(tenant, instance, LadderRung.DESIRED, LadderRung.PERSISTED, 3L)) {
                fired.incrementAndGet();
            }
            // persisted -> persisted: did not move.
            if (emitter.onConfigStateReached(tenant, instance, LadderRung.PERSISTED, LadderRung.PERSISTED, 4L)) {
                fired.incrementAndGet();
            }
        });

        assertEquals(2L, fired.get());
        assertEquals(List.of("desired", "persisted"),
                transport.sent.stream().map(e -> e.payload().get("reached")).toList());
        assertEquals(List.of(1L, 3L), transport.sent.stream().map(EventEnvelope::stateVersion).toList());
    }

    // ---- the rules that hold for every event this class emits ---------------------------------------------------

    @Test
    void nothingIsPublishedUntilTheTransactionCommits() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.RESTART_REQUESTED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.RENDERED, 9L);
            assertEquals(0, transport.sent.size());
        });
        assertEquals(3, transport.sent.size());
        assertEquals(List.of(Boolean.TRUE, Boolean.TRUE, Boolean.TRUE), transport.committedAtArrival);
    }

    @Test
    void noPayloadCarriesAConfigValueASecretOrAMetricValue() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.STAGED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 9L);
        });

        Set<String> allowed = Set.of("applyId", "state", "replicaId", "step", "reached");
        for (EventEnvelope e : transport.sent) {
            assertTrue(allowed.containsAll(e.payload().keySet()),
                    "I6: identifiers and closed enums only. " + e.event() + " carried " + e.payload().keySet());
        }
    }

    @Test
    void stateVersionIsMonotonicPerEntityAcrossEmissions() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(10));
            emitter.onApplyOutcome(applied(11));
            emitter.onApplyOutcome(applied(15));
        });

        List<Long> stamps = transport.sent.stream().map(EventEnvelope::stateVersion).toList();
        assertEquals(List.of(10L, 11L, 15L), stamps, "the emitter forwards the row's stamp and never renumbers it");
    }

    @Test
    void aMalformedEventThrowsIntoTheCallerAndNeverReachesTheWire() {
        // A blank replica ref cannot be built (ReplicaRef refuses it); the malformed case that CAN reach the emitter
        // is a request row in a state the notify enum does not carry - RUNNING is not an outcome.
        assertThrows(IllegalArgumentException.class, () -> tx.executeWithoutResult(status ->
                emitter.onApplyOutcome(row(Operation.APPLY, RequestState.RUNNING, placement, 3L))));

        assertEquals(List.of(), transport.sent);
    }

    @Test
    void theEmittedEventsValidateAgainstTheContractFixturesShapes() throws Exception {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.RESTART_OBSERVED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 9L);
        });

        for (String fixture : List.of("apply-outcome.json", "rollout-step.json", "config-state.json")) {
            EventEnvelope golden = EventJson.reader().readValue(
                    Files.readString(ContractArtifacts.dir("events/fixtures").resolve(fixture)));
            EventEnvelope ours = transport.sent.stream().filter(e -> e.event().equals(golden.event())).findFirst()
                    .orElseThrow();
            assertEquals(golden.entity().kind(), ours.entity().kind(), fixture);
            assertEquals(golden.entity().placementId() == null, ours.entity().placementId() == null, fixture);
            assertEquals(golden.payload().keySet(), ours.payload().keySet(), fixture);
            assertEquals(golden.producer(), ours.producer(), fixture);
            NotifyEventShapes.validate(ours);
        }
    }

    @Test
    void noEventOutsideTheApprovedContractIsEverPublished() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.STAGED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 9L);
        });

        assertEquals(Set.of("instance.apply-outcome", "instance.rollout", "instance.config-state"),
                Set.copyOf(transport.sent.stream().map(EventEnvelope::event).toList()));
        for (EventEnvelope e : transport.sent) {
            assertDoesNotThrow(() -> NotifyEventShapes.validate(e), e.event() + " must be catalogued");
        }
    }

    @Test
    void theEmitterEmitsOnlyDpProducerEventsAndNeverClaimsCpCore() {
        tx.executeWithoutResult(status -> {
            emitter.onApplyOutcome(applied(41));
            emitter.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.STAGED);
            emitter.onConfigStateReached(tenant, instance, null, LadderRung.PERSISTED, 9L);
        });

        assertEquals(Set.of("dp-core"), Set.copyOf(transport.sent.stream().map(EventEnvelope::producer).toList()));
    }

    @Test
    void aSilentEmitterPublishesNowhereAndDoesNotThrow() {
        ApplyEventEmitter silent = ApplyEventEmitter.silent();

        assertDoesNotThrow(() -> silent.onApplyOutcome(applied(41)));
        assertDoesNotThrow(() -> silent.onRollout(applied(41), new ReplicaRef("cache-1"), RolloutStep.STAGED));
        assertFalse(silent.onConfigStateReached(tenant, instance, LadderRung.DESIRED, LadderRung.DESIRED, 1L));
    }
}
