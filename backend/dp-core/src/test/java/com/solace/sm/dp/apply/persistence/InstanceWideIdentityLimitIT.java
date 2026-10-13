package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Formerly the proof that {@code BE-D23} made the v1 journal single-placement; <b>inverted by plan 008 S2</b>
 * ({@code BE-D44}, {@code sm_data} V021), which is what its original javadoc said would happen: <i>"each is expected to
 * go red when a persistence plan gives {@code apply_step_intent} a placement — which is the point of writing them."</i>
 * Each test below is the same scenario as before and asserts the opposite: the limit is lifted, in the DDL.
 *
 * <p>The journal can now hold two placements' placement-scoped steps, and two placements whose adapters name a replica
 * alike, under one {@code apply_id}. The ENGINE still refuses an instance-wide APPLY before admission, by policy
 * ({@code BE-D45}: a rollout across placements is a fleet of placement-scoped applies), which {@code StopApplyIT}
 * pins; these tests drive {@link StepIntentService} directly over an instance-wide request, which admission itself does
 * not refuse, to show what the table now admits.
 */
@Testcontainers(disabledWithoutDocker = true)
class InstanceWideIdentityLimitIT {

    private TenantId tenant;
    private ScopeKey wide;
    private PlacementId placementA;
    private PlacementId placementB;
    private ApplyId applyId;
    private StepIntentService intents;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        InstanceId instance = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, instance);
        // two real placements of the instance: an instance-wide request's intents must name one (V021's trigger)
        placementA = new PlacementId(InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value())));
        placementB = new PlacementId(InventoryFixtures.placement(tenant.value(), instance.value(),
                InventoryFixtures.target(tenant.value())));
        wide = new ScopeKey(tenant, instance, PlacementId.INSTANCE_WIDE);
        AdmissionOutcome admitted = ApplyEngineHarness.admission().admit(
                new ApplyCommand(wide, Operation.APPLY, 7L, Strategy.MANUAL, null, "operator-1"));
        applyId = ((AdmissionOutcome.Admitted) admitted).applyId();
        intents = ApplyEngineHarness.stepIntents();
    }

    private String key(PlacementId placement, Step step, ReplicaRef replica) {
        return ApplyExecutor.idempotencyKey(applyId, wide, placement, step, replica);
    }

    private static String hashOf(String yaml) {
        return com.solace.sm.dp.apply.runtime.TestHashers.rendered(yaml.getBytes(StandardCharsets.UTF_8)).hash();
    }

    /**
     * Was {@code twoPlacementsCannotBothHoldAPreflightIntentForOneApply}: clause 1. {@code placement_scoped_steps} still
     * pins {@code replica_ref} to {@code '@placement'}, but the identity now includes the placement, so each placement
     * holds its own pre-flight under one {@code apply_id} and neither is recorded as a retry of the other's.
     */
    @Test
    void twoPlacementsCanEachHoldAPreflightIntentForOneApply() {
        StepIntentRow a = intents.ensureIntent(tenant, applyId, placementA, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                key(placementA, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);
        StepIntentRow b = intents.ensureIntent(tenant, applyId, placementB, Step.PREFLIGHT, ReplicaRef.PLACEMENT,
                key(placementB, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null);

        assertNotEquals(a.intentId(), b.intentId(),
                "each placement receives ITS OWN intent: its attempts are numbered as its own");
        assertEquals(placementA, a.placement());
        assertEquals(placementB, b.placement());
        // …and asking again in the same placement is still the same row (the retry path is unchanged)
        assertEquals(a.intentId(), intents.ensureIntent(tenant, applyId, placementA, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, key(placementA, Step.PREFLIGHT, ReplicaRef.PLACEMENT), null).intentId());
    }

    /**
     * Was {@code twoPlacementsWithTheSameReplicaLabelCollapseOntoOneIntent}: clause 2. {@code replica_ref} is ADAPTER
     * vocabulary ({@code BE-D19}): a systemd unit name or a Kubernetes workload name is identical on two hosts. Two
     * placements sharing a label now have separate intents, and where their rendered configs differ — the normal case —
     * a legitimate two-placement request is no longer reported as a protocol error.
     */
    @Test
    void twoPlacementsWithTheSameReplicaLabelHaveSeparateIntentsAndTheirOwnHashes() {
        ReplicaRef shared = new ReplicaRef("cache.service");
        String hashA = hashOf("placement: a\n");
        String hashB = hashOf("placement: b\n");

        StepIntentRow first = intents.ensureIntent(tenant, applyId, placementA, Step.MATERIALIZE, shared,
                key(placementA, Step.MATERIALIZE, shared), hashA);
        StepIntentRow second = intents.ensureIntent(tenant, applyId, placementB, Step.MATERIALIZE, shared,
                key(placementB, Step.MATERIALIZE, shared), hashB);

        assertNotEquals(first.intentId(), second.intentId());
        assertEquals(hashA, first.configHash());
        assertEquals(hashB, second.configHash());
        // the OTHER half of the old assertion still holds: within ONE placement a different hash is a mismatch
        assertThrows(StepIntentService.IntentMismatch.class,
                () -> intents.ensureIntent(tenant, applyId, placementA, Step.MATERIALIZE, shared,
                        key(placementA, Step.MATERIALIZE, shared), hashB),
                "narrowed, not removed: the same replica in the same placement is one logical step");
    }

    /**
     * Was {@code thereIsNoSecondPlacementScopedJournalToRollBackTheOtherPlacementIn}. An engine that fanned out would
     * now have somewhere to write 'we assessed both placements' and 'we restored A': one assessment per placement.
     */
    @Test
    void eachPlacementHasItsOwnPlacementScopedJournalToRollBackIn() {
        intents.ensureIntent(tenant, applyId, placementA, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT,
                key(placementA, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);
        intents.ensureIntent(tenant, applyId, placementB, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT,
                key(placementB, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT), null);

        assertEquals(2L, ApplyEngineHarness.intents().forRequest(tenant, applyId).stream()
                        .filter(i -> i.step() == Step.ROLLBACK_ASSESS).count(),
                "'we assessed both placements' is representable, and 'we restored A' has somewhere to be written");
    }
}
