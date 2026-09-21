package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Why {@code BE-D23} makes the v1 engine single-placement, measured against the shipped DDL rather
 * than argued.
 *
 * <p>These tests do not guard behaviour; they <b>pin the model's edge</b>. Each asserts the CURRENT
 * journal identity, and each is expected to go red when a persistence plan gives
 * {@code apply_step_intent} a placement — which is the point of writing them. A reviewer who sees
 * one fail should reach for `BE-D23`, not for a fix here.
 */
@Testcontainers(disabledWithoutDocker = true)
class InstanceWideIdentityLimitIT {

    private TenantId tenant;
    private ApplyId applyId;
    private StepIntentService intents;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        tenant = new TenantId(UUID.randomUUID());
        ScopeKey scope = new ScopeKey(tenant, new InstanceId(UUID.randomUUID()),
                new PlacementId(UUID.randomUUID()));
        AdmissionOutcome admitted = ApplyEngineHarness.admission().admit(
                new ApplyCommand(scope, Operation.APPLY, 7L, Strategy.MANUAL, null, "operator-1"));
        applyId = ((AdmissionOutcome.Admitted) admitted).applyId();
        intents = ApplyEngineHarness.stepIntents();
    }

    private static String hashOf(String yaml) {
        return com.solace.sm.dp.apply.runtime.RenderedConfig
                .of(yaml.getBytes(StandardCharsets.UTF_8)).hash();
    }

    /**
     * UNIQUE (tenant_id, apply_id, step, replica_ref) + placement_scoped_steps pinning replica_ref
     * to the literal '@placement' == at most ONE PREFLIGHT intent per apply_id. A fan-out would
     * record placement B's pre-flight as a RETRY of placement A's.
     */
    @Test
    void twoPlacementsCannotBothHoldAPreflightIntentForOneApply() {
        String key = ApplyExecutor.idempotencyKey(applyId, Step.PREFLIGHT, ReplicaRef.PLACEMENT);
        StepIntentRow a = intents.ensureIntent(tenant, applyId, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, key, null);
        StepIntentRow b = intents.ensureIntent(tenant, applyId, Step.PREFLIGHT,
                ReplicaRef.PLACEMENT, key, null);

        assertEquals(a.intentId(), b.intentId(),
                "the second placement silently receives the FIRST placement's intent -- no error, "
                        + "no mismatch, and from here its attempts are numbered as retries of "
                        + "another placement's pre-flight");
    }

    /**
     * replica_ref is ADAPTER vocabulary (BE-D19): a systemd unit name or a Kubernetes workload name
     * is identical on two hosts. Two placements sharing a label share an intent -- and where their
     * rendered configs differ, a legitimate apply is reported as a protocol error.
     */
    @Test
    void twoPlacementsWithTheSameReplicaLabelCollapseOntoOneIntent() {
        ReplicaRef shared = new ReplicaRef("cache.service");
        String key = ApplyExecutor.idempotencyKey(applyId, Step.MATERIALIZE, shared);
        String hashA = hashOf("placement: a\n");
        String hashB = hashOf("placement: b\n");

        StepIntentRow first =
                intents.ensureIntent(tenant, applyId, Step.MATERIALIZE, shared, key, hashA);
        assertEquals(first.intentId(),
                intents.ensureIntent(tenant, applyId, Step.MATERIALIZE, shared, key, hashA)
                        .intentId(),
                "same label, same hash: one intent covering two different hosts");

        assertThrows(StepIntentService.IntentMismatch.class,
                () -> intents.ensureIntent(tenant, applyId, Step.MATERIALIZE, shared, key, hashB),
                "and when the two placements render DIFFERENT configs -- the normal case -- the "
                        + "model reports a legitimate two-placement apply as a protocol error");
    }

    /**
     * The scenario the refusal prevents. An engine that fanned out would roll back only the
     * placement it noticed, leaving the other changed under a request whose journal claims one
     * pre-flight and one assessment — because there is nowhere else to put them.
     */
    @Test
    void thereIsNoSecondPlacementScopedJournalToRollBackTheOtherPlacementIn() {
        String key = ApplyExecutor.idempotencyKey(applyId, Step.ROLLBACK_ASSESS,
                ReplicaRef.PLACEMENT);
        intents.ensureIntent(tenant, applyId, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT, key, null);
        intents.ensureIntent(tenant, applyId, Step.ROLLBACK_ASSESS, ReplicaRef.PLACEMENT, key, null);

        assertEquals(1L, ApplyEngineHarness.intents().forRequest(tenant, applyId).stream()
                        .filter(i -> i.step() == Step.ROLLBACK_ASSESS).count(),
                "'we assessed both placements' is unrepresentable, and 'we restored A' has nowhere "
                        + "to be written at all");
    }
}
