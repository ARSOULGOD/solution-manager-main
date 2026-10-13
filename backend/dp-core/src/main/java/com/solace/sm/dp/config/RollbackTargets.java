package com.solace.sm.dp.config;

import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException.Reason;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.Objects;
import java.util.UUID;

/**
 * Which generation an operator rollback copies forward (plan 010 T8.1, OR-42, verbatim): a generation <b>actually
 * written to that placement</b> - the placement's request for it reached {@code STAGED}, {@code APPLIED}, or
 * {@code SUPERSEDED} after staging, or resolved {@code INDETERMINATE_RESOLVED} with outcome {@code applied} (it was
 * confirmed on the host; {@code OR-108} (7) extends {@code OR-42}'s three states by that one). Resolved
 * {@code not_applied} is excluded: nothing was written.
 *
 * <p>The target is named one of two ways, as the contract's {@code oneOf} says:
 * <ul>
 *   <li>by {@code applyId}: the highest eligible generation <b>below</b> that apply's generation on the placement
 *       ("undo this apply"); the apply must be an {@code apply} request of THIS instance and THIS placement, else {@code NOT_FOUND}
 *       (indistinguishable from an absent one); no eligible generation below it is
 *       {@link Reason#ROLLBACK_TARGET_NOT_ELIGIBLE};</li>
 *   <li>by {@code generation}: that generation itself, which must be eligible.</li>
 * </ul>
 *
 * <p>Pure rule over a read port. It takes no lock and writes nothing; the copy-forward that follows is its own
 * transaction.
 */
public final class RollbackTargets {

    private final RollbackTargetRows rows;

    public RollbackTargets(RollbackTargetRows rows) {
        this.rows = Objects.requireNonNull(rows, "rows");
    }

    /**
     * @param applyId    exactly one of this and {@code generation} is non-null
     * @param generation see {@code applyId}
     * @return the generation to copy forward
     * @throws ApplyRefusedException {@code NOT_FOUND} or {@code ROLLBACK_TARGET_NOT_ELIGIBLE}
     */
    public long resolve(TenantId tenant, InstanceId instance, PlacementId placement, UUID applyId, Long generation) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(placement, "placement");
        if ((applyId == null) == (generation == null)) {
            throw new IllegalArgumentException("exactly one of applyId and generation names the rollback target");
        }
        if (generation != null) {
            if (!rows.isEligible(tenant, instance, placement, generation)) {
                throw new ApplyRefusedException(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE);
            }
            return generation;
        }
        long anchor = rows.applyGeneration(tenant, instance, placement, new ApplyId(applyId))
                .orElseThrow(() -> new ApplyRefusedException(Reason.NOT_FOUND));
        return rows.highestEligibleBelow(tenant, instance, placement, anchor)
                .orElseThrow(() -> new ApplyRefusedException(Reason.ROLLBACK_TARGET_NOT_ELIGIBLE));
    }
}
