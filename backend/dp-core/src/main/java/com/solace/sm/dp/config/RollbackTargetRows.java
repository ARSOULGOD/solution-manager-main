package com.solace.sm.dp.config;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.OptionalLong;

/**
 * The journal read behind rollback eligibility (plan 010 T8.1, OR-42). A port: the JDBC lives in
 * {@code dp.apply.persistence} (JDBC is confined there), this package holds the rule. Read-only, and it takes no lock:
 * a generation's eligibility only ever GAINS reasons (a request reaching {@code STAGED}, {@code APPLIED} or
 * {@code SUPERSEDED} is never undone), so a stale read refuses at worst a target that became eligible a moment ago.
 *
 * <p>{@code I5}: every method names the tenant and binds it.
 */
public interface RollbackTargetRows {

    /**
     * The generation of an {@code apply} request of THIS instance AND THIS placement, or empty (no such request, not an
     * apply, or an apply of another placement: that one is not "this apply" for a rollback of this placement).
     */
    OptionalLong applyGeneration(TenantId tenant, InstanceId instance, PlacementId placement, ApplyId apply);

    /**
     * The highest generation strictly below {@code belowExclusive} that an {@code apply} request for THIS placement
     * took to {@code STAGED}, {@code APPLIED} or {@code SUPERSEDED}; empty when there is none.
     */
    OptionalLong highestEligibleBelow(TenantId tenant, InstanceId instance, PlacementId placement,
            long belowExclusive);

    /** Whether an {@code apply} request for THIS placement and {@code generation} reached one of those three states. */
    boolean isEligible(TenantId tenant, InstanceId instance, PlacementId placement, long generation);
}
