package com.solace.sm.dp.apply.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyStepView;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T6.9 point 4: {@code observeApply} reports which strength a passed gate proved - and only that. It is read
 * from the immutable placement row's probe columns, never from a declaration alone: a placement with no executable probe
 * has no strength to read, and a step that is not a passed gated restart proves nothing.
 */
class ApplyServiceGateProvedTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();

    private static PlacementRecord placement(Optional<ProbeStrength> strength) {
        Optional<String> kind = strength.map(s -> "actuator-health");
        return new PlacementRecord(TENANT, PLACEMENT, INSTANCE, UUID.randomUUID(), "sm-cache", "/srv/sm/p",
                "/app/external/spring/config", "application.yml", "ADDITIONAL_LOCATION", 10, kind,
                kind.map(k -> "/actuator/health"), kind.map(k -> 8081), strength, Instant.now());
    }

    private static List<ApplyStepView> observe(Strategy strategy, Optional<PlacementRecord> placement,
            Step step, AttemptOutcome outcome) {
        ScopeKey scope = new ScopeKey(new TenantId(TENANT), new InstanceId(INSTANCE), new PlacementId(PLACEMENT));
        ApplyRequestRow row = new ApplyRequestRow(new ApplyId(UUID.randomUUID()), scope, Operation.APPLY, 3L,
                strategy, null, "u", Instant.now(), RequestState.APPLIED, Instant.now(), 9L);
        ApplyObservationRows rows = (tenant, instance, applyId, since, limit) -> List.of(
                new ApplyObservationRows.ObservedRequest(row,
                        List.of(new ApplyObservationRows.ObservedStep(step, true, outcome, null))));
        // findLive is always empty: the placement may be retired by the time an old request is observed, and the
        // gate's strength must still be reported from the immutable row, so the service has to use find().
        PlacementRows placements = new PlacementRows() {
            @Override public void insert(PlacementRecord r) {
                throw new AssertionError();
            }

            @Override public Optional<PlacementRecord> findLive(UUID t, UUID i, UUID p) {
                return Optional.empty();
            }

            @Override public Optional<PlacementRecord> find(UUID t, UUID i, UUID p) {
                return placement;
            }

            @Override public List<PlacementRecord> live(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public int retireAll(UUID t, UUID i, Instant at) {
                throw new AssertionError();
            }

            @Override public int retire(UUID t, UUID i, UUID p, Instant at, UUID by) {
                throw new AssertionError();
            }

            @Override public boolean lockApplyScope(UUID t, UUID i) {
                throw new AssertionError();
            }

            @Override public boolean inFlightOn(UUID t, UUID i, UUID p) {
                throw new AssertionError();
            }

            @Override public boolean instanceWideInFlight(UUID t, UUID i) {
                throw new AssertionError();
            }
        };
        ApplyServiceImpl service = new ApplyServiceImpl(() -> null, placements, new ApplyRequestRepository(null), rows,
                unused(com.solace.sm.dp.api.config.ConfigRecordService.class),
                new com.solace.sm.dp.config.RollbackTargets(
                        unused(com.solace.sm.dp.config.RollbackTargetRows.class)),
                () -> { });
        List<ApplyObservationView> views = service.observeApply(TENANT, INSTANCE, new ObserveQuery(null, null, 10));
        return views.get(0).steps();
    }

    /** Observation reaches neither collaborator; a call to one is a failure of the test, not a null pointer. */
    private static <T> T unused(Class<T> type) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    throw new AssertionError(type.getSimpleName() + " is not reached by observeApply");
                }));
    }

    /** OR-108 (1): the journal's recorded change reaches the view on its own step; a step with none carries none. */
    @Test
    void aRecordedPreImageChangeReachesTheViewOnItsStepAndNoOtherStepCarriesOne() {
        com.solace.sm.dp.apply.model.ChangedContent change = com.solace.sm.dp.apply.model.ChangedContent
                .of(List.of("cache-engine.ttl"), true);
        ScopeKey scope = new ScopeKey(new TenantId(TENANT), new InstanceId(INSTANCE), new PlacementId(PLACEMENT));
        ApplyRequestRow row = new ApplyRequestRow(new ApplyId(UUID.randomUUID()), scope, Operation.APPLY, 3L,
                Strategy.MANUAL, null, "u", Instant.now(), RequestState.APPLY_INDETERMINATE, null, 9L);
        ApplyObservationRows rows = (tenant, instance, applyId, since, limit) -> List.of(
                new ApplyObservationRows.ObservedRequest(row, List.of(
                        new ApplyObservationRows.ObservedStep(Step.ROLLBACK_ASSESS, true, AttemptOutcome.SUCCEEDED, null,
                                change),
                        new ApplyObservationRows.ObservedStep(Step.MATERIALIZE, true, AttemptOutcome.SUCCEEDED, null))));
        ApplyServiceImpl service = new ApplyServiceImpl(() -> null, unused(PlacementRows.class),
                new ApplyRequestRepository(null), rows, unused(com.solace.sm.dp.api.config.ConfigRecordService.class),
                new com.solace.sm.dp.config.RollbackTargets(unused(com.solace.sm.dp.config.RollbackTargetRows.class)),
                () -> { });

        List<ApplyStepView> steps = service.observeApply(TENANT, INSTANCE, new ObserveQuery(null, null, 10)).get(0).steps();

        assertEquals(List.of("cache-engine.ttl"), steps.get(0).changedPaths());
        assertEquals(Boolean.FALSE, steps.get(0).changedPathsTruncated());
        assertEquals(Boolean.TRUE, steps.get(0).otherContentChanged());
        assertNull(steps.get(1).changedPaths());
        assertNull(steps.get(1).otherContentChanged());
    }

    @Test
    void aPassedGateOnALivenessDeclaredProbeReportsLiveness() {
        List<ApplyStepView> steps = observe(Strategy.ROLLING_HEALTH_GATED,
                Optional.of(placement(Optional.of(ProbeStrength.LIVENESS))), Step.LIFECYCLE, AttemptOutcome.SUCCEEDED);

        assertEquals("liveness", steps.get(0).gateProved());
    }

    @Test
    void aPassedGateOnAReadinessDeclaredProbeOfAnyOtherPackageReportsReadiness() {
        List<ApplyStepView> steps = observe(Strategy.ROLLING_HEALTH_GATED,
                Optional.of(placement(Optional.of(ProbeStrength.READINESS))), Step.LIFECYCLE, AttemptOutcome.SUCCEEDED);

        assertEquals("readiness", steps.get(0).gateProved(), "the same code as Cache's, another declaration");
    }

    @Test
    void aPlacementWithNoExecutableProbeProvedNothing() {
        List<ApplyStepView> steps = observe(Strategy.ROLLING_HEALTH_GATED, Optional.of(placement(Optional.empty())),
                Step.LIFECYCLE, AttemptOutcome.SUCCEEDED);

        assertNull(steps.get(0).gateProved(), "a broker-heartbeat-only package has no strength to report (M-6)");
    }

    @Test
    void onlyAPassedLifecycleStepOfAGatedRequestCarriesIt() {
        Optional<PlacementRecord> liveness = Optional.of(placement(Optional.of(ProbeStrength.LIVENESS)));
        for (Step other : List.of(Step.PREFLIGHT, Step.MATERIALIZE, Step.VERIFY, Step.ROLLBACK_ASSESS)) {
            assertNull(observe(Strategy.ROLLING_HEALTH_GATED, liveness, other, AttemptOutcome.SUCCEEDED).get(0)
                    .gateProved(), other.name());
        }
        for (AttemptOutcome outcome : List.of(AttemptOutcome.FAILED, AttemptOutcome.TIMED_OUT)) {
            assertNull(observe(Strategy.ROLLING_HEALTH_GATED, liveness, Step.LIFECYCLE, outcome).get(0).gateProved(),
                    "a gate that did not pass proved nothing: " + outcome);
        }
        assertNull(observe(Strategy.ROLLING_HEALTH_GATED, liveness, Step.LIFECYCLE, null).get(0).gateProved(),
                "an open attempt has proved nothing yet");
        for (Strategy strategy : List.of(Strategy.MANUAL, Strategy.ROLLING_NO_VALIDATION, Strategy.RESTART_ALL)) {
            assertNull(observe(strategy, liveness, Step.LIFECYCLE, AttemptOutcome.SUCCEEDED).get(0).gateProved(),
                    strategy + " has no gate");
        }
    }

    @Test
    void aMissingPlacementRowReportsNothingRatherThanGuessing() {
        assertNull(observe(Strategy.ROLLING_HEALTH_GATED, Optional.empty(), Step.LIFECYCLE, AttemptOutcome.SUCCEEDED)
                .get(0).gateProved());
    }
}
