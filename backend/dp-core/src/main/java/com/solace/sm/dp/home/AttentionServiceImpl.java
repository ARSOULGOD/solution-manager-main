package com.solace.sm.dp.home;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.home.AttentionCandidate;
import com.solace.sm.dp.api.home.AttentionReason;
import com.solace.sm.dp.api.home.AttentionService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.health.PlacementHealthRecord;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.inventory.InstanceRecord;
import com.solace.sm.dp.inventory.InstanceRows;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link AttentionService} over the persisted rows (plan 010 T10.5). Reads only, one tenant per call ({@code I5}).
 *
 * <ul>
 *   <li><b>health</b>: every live placement of an active instance whose persisted state is a problem. The states that
 *       are NOT: {@code GREEN}; {@code INACTIVE_REPLICA}, a healthy standby (the contract's own rollup counts a
 *       {@code GREEN} plus {@code INACTIVE_REPLICA} pair {@code GREEN}); and {@code NOT_APPLICABLE}, a package that
 *       declares no probe ({@code OR-101}). A placement nothing has polled yet is not listed: there is nothing to say
 *       about it.</li>
 *   <li><b>apply-indeterminate</b> and <b>config-diverged</b>: the placement's latest request when it is in that state
 *       ({@link ApplyAttentionRows}). An indeterminate request the reconciler gave up on is still
 *       {@code APPLY_INDETERMINATE}, so it is listed.</li>
 * </ul>
 *
 * Each candidate carries the OWN stamp of the row it came from ({@code OR-60}).
 */
public final class AttentionServiceImpl implements AttentionService {

    /** Health states that are not a problem; everything else in the vocabulary is. */
    private static final Set<HealthState> NOT_A_PROBLEM = Set.of(HealthState.GREEN, HealthState.INACTIVE_REPLICA,
            HealthState.NOT_APPLICABLE);

    private final InstanceRows instances;
    private final PlacementHealthRows health;
    private final ApplyAttentionRows apply;

    public AttentionServiceImpl(InstanceRows instances, PlacementHealthRows health, ApplyAttentionRows apply) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.health = Objects.requireNonNull(health, "health");
        this.apply = Objects.requireNonNull(apply, "apply");
    }

    @Override
    public List<AttentionCandidate> attention(UUID organizationId) {
        List<AttentionCandidate> out = new ArrayList<>();
        for (InstanceRecord instance : instances.list(organizationId, InstanceLifecycle.ACTIVE, Optional.empty())) {
            for (PlacementHealthRecord row : health.liveOf(organizationId, instance.instanceId())) {
                if (!NOT_A_PROBLEM.contains(row.state())) {
                    out.add(new AttentionCandidate(instance.instanceId(), Optional.of(row.placementId()),
                            AttentionReason.HEALTH, Optional.of(row.state()), row.stateVersion()));
                }
            }
        }
        for (ApplyAttentionRows.Row row : apply.needingAttention(organizationId)) {
            AttentionReason reason = switch (row.state()) {
                case "APPLY_INDETERMINATE" -> AttentionReason.APPLY_INDETERMINATE;
                case "CONFIG_DIVERGED" -> AttentionReason.CONFIG_DIVERGED;
                default -> throw new IllegalStateException("the attention read returned a state it does not list");
            };
            out.add(new AttentionCandidate(row.instanceId(), row.placementId(), reason, Optional.empty(),
                    row.stateVersion()));
        }
        return List.copyOf(out);
    }
}
