package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Objects;

/**
 * A real placement — {@link ScopeKey} without the instance-wide sentinel.
 *
 * <p>The engine addresses scopes, which may be instance-wide; an adapter is always addressed at one
 * placement. In v1 an instance-wide request never reaches this type at all: {@code BE-D23} makes
 * {@code ApplyExecutor} refuse it before admission, because {@code apply_step_intent}'s identity
 * cannot hold two placements' placement-scoped steps under one {@code apply_id}. This constructor is
 * the SECOND line — the first is the executor's own check, which fails gracefully; this one throws.
 */
public record PlacementRef(TenantId tenant, InstanceId instance, PlacementId placement) {

    public PlacementRef {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(placement, "placement");
        if (placement.isInstanceWide()) {
            throw new IllegalArgumentException("an adapter is addressed at a real placement, never "
                    + "at the instance-wide sentinel — BE-D23 refuses an instance-wide request "
                    + "before admission, so one reaching here is an engine defect, not input");
        }
    }

    public ScopeKey scope() {
        return new ScopeKey(tenant, instance, placement);
    }
}
