package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;

/**
 * The narrow port through which the engine learns which placements an instance HAS NOW (plan 008 S3, {@code BE-D46}):
 * what an instance-wide stop addresses. It answers from the placement registry ({@code sm_data.placement}, rows not
 * retired) and from nothing else. {@code BE-D26}'s first enumerator read {@code apply_request} history, which is the
 * set of placements SM has ever acted on: it includes retired ones (and so failed an instance-wide stop that had
 * stopped every live placement) and omits a placement added and never acted on.
 *
 * <p>The implementation lives in {@code dp.inventory}, beside the registry; this package states only what it needs. An
 * architecture rule ({@code PlacementEnumerationTest}) keeps every implementation off the apply history.
 *
 * <p>{@code I5}: tenant and instance are both arguments, so an id of another instance's placement can never be returned.
 */
public interface LivePlacements {

    /** The instance's live placements, oldest first; empty for an instance with none (or none this tenant has). */
    List<PlacementId> of(TenantId tenant, InstanceId instance);

    /**
     * The source of an engine built without one (the journal-only suites, whose stops name a placement): an instance-wide
     * stop through it fails LOUDLY rather than finding "no live placement" and landing a believable
     * {@code STOP_FAILED}. Production wiring supplies the registry's ({@code ProductionApplyConfiguration}).
     */
    LivePlacements UNAVAILABLE = (tenant, instance) -> {
        throw new IllegalStateException("this executor was built without a live-placement source, so it cannot "
                + "enumerate the placements of an instance-wide stop");
    };
}
