package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReconciliationLedger;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.reconcile.ReconcileProperties;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.events.EventEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The reconciler's world for the engine ITs (plan 010 T6.8): one fresh tenant, instance and placement per fixture, an
 * in-memory host, and the real engine over PostgreSQL with the reconciliation ledger and an event capture wired in. Built by
 * hand, like {@link ApplyEngineHarness}, so a test asserts on behaviour and not on Spring.
 */
final class ReconcilerFixture {

    /** A clock a test moves: the reconciler's scheduling and age bound are the only things that read it. */
    static final class TestClock extends Clock {

        private volatile Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    final TenantId tenant = new TenantId(UUID.randomUUID());
    final InstanceId instance = new InstanceId(UUID.randomUUID());
    final PlacementId placementId = new PlacementId(UUID.randomUUID());
    final ScopeKey scope = new ScopeKey(tenant, instance, placementId);
    final PlacementRef placement = new PlacementRef(tenant, instance, placementId);
    final TestClock clock = new TestClock();
    final List<EventEnvelope> events = new CopyOnWriteArrayList<>();
    final JdbcReconciliationRepository ledger = new JdbcReconciliationRepository(ApplyEngineHarness.jdbc());
    final ApplyEventEmitter emitter = new ApplyEventEmitter(events::add, Clock.systemUTC(), UUID::randomUUID);

    /** Whether the landing writes its ledger row; a test switches it off to model a landing the DP did not witness. */
    volatile boolean ledgerWitnessesLandings = true;
    private final ReconciliationLedger landingLedger = new ReconciliationLedger() {
        @Override
        public void countRedrive(TenantId t, ApplyId id, Instant now) {
            ledger.countRedrive(t, id, now);
        }

        @Override
        public void close(TenantId t, ApplyId id, Instant now, ErrorClass lastClass) {
            ledger.close(t, id, now, lastClass);
        }

        @Override
        public java.util.Optional<RedriveBook> redriveBook(TenantId t, ApplyId id) {
            return ledger.redriveBook(t, id);
        }

        @Override
        public void recordIndeterminateLanding(TenantId t, ApplyId id, Instant now) {
            if (ledgerWitnessesLandings) {
                ledger.recordIndeterminateLanding(t, id, now);
            }
        }
    };

    OutcomeService outcomes;
    AdmissionService admission;
    ApplyExecutor executor;

    ReconcilerFixture() {
        SmDataPostgres.bootstrap();
        InventoryFixtures.registered(scope);
    }

    /** The engine over {@code adapter}, with the retry cap and gate bounds the ITs run under. */
    ApplyExecutor engine(RuntimeAdapter adapter, RenderedConfigSource configs) {
        return engine(adapter, configs, ApplyEngineHarness.maxAttempts());
    }

    ApplyExecutor engine(RuntimeAdapter adapter, RenderedConfigSource configs, int maxAttempts) {
        ApplyEngineProperties props = ApplyEngineHarness.engineProperties();
        outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), clock, emitter, LadderRecorder.none(), landingLedger);
        admission = new AdmissionService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), emitter);
        executor = new ApplyExecutor(admission, ApplyEngineHarness.stepIntents(),
                ApplyEngineHarness.attemptsService(maxAttempts), outcomes, adapter, configs,
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, emitter);
        return executor;
    }

    IndeterminateReconciler reconciler(ReconcileProperties props) {
        return reconciler(props, clock);
    }

    IndeterminateReconciler reconciler(ReconcileProperties props, Clock reconcilerClock) {
        return new IndeterminateReconciler(executor, outcomes, ledger, props, reconcilerClock);
    }

    ApplyCommand apply(long generation, Strategy strategy) {
        return new ApplyCommand(scope, Operation.APPLY, generation, strategy,
                strategy == Strategy.RESTART_ALL ? "operator-1" : null, "operator-1");
    }

    ApplyCommand stop() {
        return new ApplyCommand(scope, Operation.STOP, null, null, null, "operator-1");
    }

    /** The one open (non-terminal) request of this tenant. */
    ApplyId onlyOpenRequest() {
        return ApplyEngineHarness.requests().nonTerminal(tenant).get(0).applyId();
    }

    RequestState stateOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().state();
    }

    long stampOf(ApplyId id) {
        return ApplyEngineHarness.requests().find(tenant, id).orElseThrow().stateVersion();
    }

    String reconcileClassOf(ApplyId id) {
        return ApplyEngineHarness.jdbc().queryForObject(
                "SELECT reconcile_class FROM sm_data.apply_request WHERE tenant_id = :t AND apply_id = :a",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", tenant.value())
                        .addValue("a", id.value()), String.class);
    }

    /** The {@code instance.apply-outcome} notices published so far, as {@code state} strings in order. */
    List<String> outcomeNotices() {
        List<String> states = new ArrayList<>();
        for (EventEnvelope e : events) {
            if ("instance.apply-outcome".equals(e.event())) {
                states.add(String.valueOf(e.payload().get("state")));
            }
        }
        return states;
    }

    static InMemoryRuntimeAdapter host(PlacementRef placement, String... replicas) {
        return new InMemoryRuntimeAdapter(placement, true, replicas);
    }
}
