package com.solace.sm.dp.apply.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.HealthSignal;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.LifecycleResult;
import com.solace.sm.dp.apply.runtime.Liveness;
import com.solace.sm.dp.apply.runtime.MaterializeResult;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PreflightResult;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RestoreResult;
import com.solace.sm.dp.apply.runtime.RollbackAssessment;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.RuntimeStatus;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HealthGate} against a controllable clock, so the one property worth asserting about a
 * deadline — that the gate polls inside it rather than sleeping through it — costs the suite
 * nothing. Virtual time advances only when the injected {@link Sleeper} is called.
 */
class HealthGateTest {

    private static final Duration DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL = Duration.ofSeconds(2);

    private MutableClock clock;
    private List<Duration> sleeps;
    private ApplyEngineProperties props;
    private HealthGate gate;
    private PlacementRef placement;
    private InMemoryRuntimeAdapter adapter;
    private TargetReplica replica;
    private PreBaseline before;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-09-14T00:00:00Z"));
        sleeps = new ArrayList<>();
        props = new ApplyEngineProperties(Duration.ofMinutes(5), DEADLINE, POLL);
        gate = new HealthGate(clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        }, props);

        placement = new PlacementRef(new TenantId(UUID.randomUUID()),
                new InstanceId(UUID.randomUUID()), new PlacementId(UUID.randomUUID()));
        adapter = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        replica = adapter.replicas().get(0);
        before = adapter.status(replica).baseline();
    }

    @Test
    void theGatePassesWhenTheBaselineMovedAndHealthIsHealthy() {
        adapter.startOrUpdate(replica, 4);

        GateVerdict verdict = gate.await(adapter, replica, before);

        PreBaseline observed = assertInstanceOf(GateVerdict.Passed.class, verdict).observed();
        assertNotEquals(before.startedAt(), observed.startedAt(),
                "a pass reports the NEW baseline; reporting the old one would make the caller's "
                        + "next restart-observed comparison compare a value with itself");
        assertTrue(sleeps.isEmpty(), "a replica already back and healthy costs no poll interval");
    }

    @Test
    void theGateFailsWhenTheDeadlineExpiresWithHealthStillStarting() {
        adapter.startOrUpdate(replica, 4);
        adapter.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);

        GateVerdict verdict = gate.await(adapter, replica, before);

        assertEquals(new ErrorClass("gate.unhealthy"),
                assertInstanceOf(GateVerdict.Failed.class, verdict).why(),
                "the baseline DID move, so the missing half is health, and the classifier must say "
                        + "which half — an operator reading gate.no.restart here would look at the "
                        + "wrong thing");
        assertEquals(DEADLINE, Duration.between(Instant.parse("2026-09-14T00:00:00Z"),
                clock.instant()), "the gate uses its whole deadline before failing");
    }

    /**
     * {@code L7}, as a refusal rather than as a comment. A runtime that publishes no health signal
     * cannot be health-gated: treating {@code NOT_DECLARED} as a pass would make
     * {@code rolling-health-gated} indistinguishable from {@code rolling-no-validation} while
     * keeping its name — the worse of the two failures, because the operator chose the gated
     * strategy precisely to get a gate.
     */
    @Test
    void aRuntimeWithNoHealthSignalMakesTheGateUnavailableRatherThanPassing() {
        InMemoryRuntimeAdapter noSignal = new InMemoryRuntimeAdapter(placement, false, "replica-1");
        TargetReplica r = noSignal.replicas().get(0);
        PreBaseline baseline = noSignal.status(r).baseline();
        noSignal.startOrUpdate(r, 4);   // the baseline DOES move: only the signal is missing

        GateVerdict verdict = gate.await(noSignal, r, baseline);

        assertEquals(new ErrorClass("gate.signal.absent"),
                assertInstanceOf(GateVerdict.Unavailable.class, verdict).why());
        assertTrue(sleeps.isEmpty(),
                "an absent signal does not arrive later; polling for it would burn the deadline to "
                        + "reach the same answer");
    }

    @Test
    void theGateDoesNotPassOnHealthAloneWhenTheBaselineNeverMoved() {
        // A replica that was already healthy and was never restarted. Health alone is satisfied
        // from the first observation; restart-observed is not, and a gate that skipped the baseline
        // comparison would certify a restart that did not happen.
        GateVerdict verdict = gate.await(adapter, replica, before);

        assertEquals(new ErrorClass("gate.no.restart"),
                assertInstanceOf(GateVerdict.Failed.class, verdict).why());
        assertEquals(0, adapter.restarts(), "the gate observes; it never acts");
    }

    @Test
    void theGatePollsRatherThanSleepingTheWholeDeadline() {
        HealthyOnThirdObservation slow = new HealthyOnThirdObservation(replica, before);

        GateVerdict verdict = gate.await(slow, replica, before);

        assertInstanceOf(GateVerdict.Passed.class, verdict);
        assertEquals(3, slow.observations.get(),
                "the gate must observe repeatedly; one look at the end of the deadline is not a gate");
        assertEquals(List.of(POLL, POLL), sleeps, "one poll interval between observations");
        assertTrue(Duration.between(Instant.parse("2026-09-14T00:00:00Z"), clock.instant())
                        .compareTo(DEADLINE) < 0,
                "the gate returned well inside the deadline rather than sleeping through it");
    }

    /**
     * A replica that VANISHES mid-gate is not a runtime without a health signal. An adapter that
     * reads health out of the runtime's inspect output has nothing to report for a container that
     * no longer exists and says {@code NOT_DECLARED} for both cases; the gate tells them apart, so
     * an operator is not sent to look for a missing healthcheck when the container is gone.
     */
    @Test
    void aReplicaThatVanishedMidGateIsAFailureAndNotAnAbsentSignal() {
        GateVerdict verdict = gate.await(new Vanished(before), replica, before);

        assertEquals(new ErrorClass("gate.replica.absent"),
                assertInstanceOf(GateVerdict.Failed.class, verdict).why());
    }

    /**
     * A daemon that is briefly unreachable during a restart is the ordinary case; failing the gate
     * on the first hiccup would fail applies that were about to pass. The comment in
     * {@link HealthGate} says so — this asserts it, because an edit that returned {@code Failed}
     * on the first exception would otherwise go green.
     */
    @Test
    void aBrieflyUnreachableDaemonIsPolledThroughRatherThanFailingTheGate() {
        UnreachableThenHealthy flaky = new UnreachableThenHealthy(before, 2);

        GateVerdict verdict = gate.await(flaky, replica, before);

        assertInstanceOf(GateVerdict.Passed.class, verdict);
        assertEquals(3, flaky.observations.get(), "two refusals, then the observation that passes");
        assertEquals(List.of(POLL, POLL), sleeps,
                "and each refusal costs one poll interval, not a busy spin");
    }

    // ---------------------------------------------------------------------------------------

    /** Virtual time. Advances only when the injected sleeper is called. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Reports the replica gone. */
    private static final class Vanished extends ObservingAdapter {

        private final PreBaseline before;

        Vanished(PreBaseline before) {
            this.before = before;
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            return new RuntimeStatus(Liveness.ABSENT, HealthSignal.NOT_DECLARED, null, before,
                    RuntimeRef.of(RuntimeRef.Key.HOST, "scripted"));
        }
    }

    /** Throws for the first {@code refusals} observations, then reports a restarted, healthy one. */
    private static final class UnreachableThenHealthy extends ObservingAdapter {

        private final AtomicInteger observations = new AtomicInteger();
        private final PreBaseline before;
        private final int refusals;

        UnreachableThenHealthy(PreBaseline before, int refusals) {
            this.before = before;
            this.refusals = refusals;
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            if (observations.incrementAndGet() <= refusals) {
                throw new RuntimeAdapterException(new ErrorClass("docker.daemon.unreachable"));
            }
            return new RuntimeStatus(Liveness.RUNNING, HealthSignal.HEALTHY, null,
                    new PreBaseline(before.startedAt().plusSeconds(60), before.processRef()),
                    RuntimeRef.of(RuntimeRef.Key.HOST, "scripted"));
        }
    }

    /** The five operations a gate never calls, refused once instead of six times per fake. */
    private abstract static class ObservingAdapter implements RuntimeAdapter {

        @Override
        public List<TargetReplica> replicas(PlacementRef p) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public PreflightResult prepare(PlacementRef p, long generation) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public MaterializeResult materializeConfig(TargetReplica r, long g, RenderedConfig c) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public MaterializeResult rematerializeIfCurrent(TargetReplica r, long g, RenderedConfig c,
                String expectedCurrentHash) {
            throw new UnsupportedOperationException("not used by this test double");
        }

        @Override
        public LifecycleResult startOrUpdate(TargetReplica r, long generation) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public LifecycleResult stop(TargetReplica r) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public RollbackAssessment assessRollback(PlacementRef p, long g, String intendedHash) {
            throw new UnsupportedOperationException("the gate only observes");
        }

        @Override
        public RestoreResult restore(PlacementRef p, long g, String targetHash) {
            throw new UnsupportedOperationException("the gate only observes");
        }
    }

    /**
     * A replica that has restarted but reports {@code STARTING} until the third observation — the
     * ordinary case, and the only one in which "polls" and "sleeps once" give different answers.
     */
    private static final class HealthyOnThirdObservation extends ObservingAdapter {

        private final AtomicInteger observations = new AtomicInteger();
        private final TargetReplica replica;
        private final PreBaseline before;

        HealthyOnThirdObservation(TargetReplica replica, PreBaseline before) {
            this.replica = replica;
            this.before = before;
        }

        @Override
        public RuntimeStatus status(TargetReplica r) {
            int n = observations.incrementAndGet();
            PreBaseline moved =
                    new PreBaseline(before.startedAt().plusSeconds(60), before.processRef());
            return new RuntimeStatus(Liveness.RUNNING,
                    n < 3 ? HealthSignal.STARTING : HealthSignal.HEALTHY, null, moved,
                    RuntimeRef.of(RuntimeRef.Key.HOST, "scripted"));
        }

    }
}
