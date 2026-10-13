package com.solace.sm.dp.apply.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ReplicaRef;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code BE-D44}'s key rule, as a pure function (no database): a PLACEMENT-scoped request keeps
 * {@code applyId:step:replica} — its {@code apply_id} already fixes the placement, and keeping the format is what lets a
 * request admitted before {@code V021} resume onto its own intent rows ({@code StepIntentService.compare} requires key
 * equality; {@code IntentIdentityUpgradeIT} proves the resume). Only an INSTANCE-WIDE request's key names the placement:
 * {@code applyId:placementId:step:replica}.
 */
class IdempotencyKeyFormatTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final InstanceId INSTANCE = new InstanceId(UUID.randomUUID());

    private final ApplyId id = new ApplyId(UUID.randomUUID());
    private final ReplicaRef replica = new ReplicaRef("sm-cache");
    private final PlacementId placement = new PlacementId(UUID.randomUUID());

    @Test
    void aPlacementScopedKeyIsUnchangedByteForByte() {
        ScopeKey scope = new ScopeKey(TENANT, INSTANCE, placement);

        assertEquals(id.value() + ":STOP:sm-cache", ApplyExecutor.idempotencyKey(id, Step.STOP, replica),
                "the format V020's engine wrote");
        assertEquals(id.value() + ":STOP:sm-cache",
                ApplyExecutor.idempotencyKey(id, scope, placement, Step.STOP, replica));
        assertEquals(id.value() + ":PREFLIGHT:@placement",
                ApplyExecutor.idempotencyKey(id, scope, placement, Step.PREFLIGHT, ReplicaRef.PLACEMENT));
    }

    @Test
    void anInstanceWideKeyNamesThePlacement() {
        ScopeKey wide = new ScopeKey(TENANT, INSTANCE, PlacementId.INSTANCE_WIDE);

        assertEquals(id.value() + ":" + placement.value() + ":STOP:sm-cache",
                ApplyExecutor.idempotencyKey(id, wide, placement, Step.STOP, replica));
        assertNotEquals(ApplyExecutor.idempotencyKey(id, wide, placement, Step.STOP, replica),
                ApplyExecutor.idempotencyKey(id, wide, new PlacementId(UUID.randomUUID()), Step.STOP, replica),
                "two placements of one instance-wide request get two keys, so the global UNIQUE holds for both");
    }
}
