package com.solace.sm.dp.apply.model;

import java.util.Objects;

/**
 * The three columns every fence in design §3.1 is keyed by: {@code (tenant_id, instance_id,
 * placement_id)}.
 *
 * <p>{@link #overlaps} is the cross-scope rule the partial unique indexes CANNOT express — an
 * instance-wide request excludes every placement-scoped one and vice versa — and which §3.3's
 * admission transaction therefore has to evaluate in code while holding the scope row.
 */
public record ScopeKey(TenantId tenant, InstanceId instance, PlacementId placement) {

    public ScopeKey {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(placement, "placement");
    }

    /** Same tenant and instance, and placements that are equal or either of them instance-wide. */
    public boolean overlaps(ScopeKey other) {
        return tenant.equals(other.tenant)
                && instance.equals(other.instance)
                && (placement.isInstanceWide() || other.placement.isInstanceWide()
                        || placement.equals(other.placement));
    }
}
