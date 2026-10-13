package com.solace.sm.dp.api.home;

import com.solace.sm.dp.api.health.HealthState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One reason an instance needs attention.
 *
 * @param placementId absent for a reason that belongs to the instance as a whole
 * @param state       present exactly when {@code reason} is {@link AttentionReason#HEALTH}: the placement's state in
 *                    the notify vocabulary
 * @param stateVersion the source row's OWN stamp ({@code OR-60}): the placement health row's, or the apply request's.
 *                    Never one version for the set ({@code OR-55})
 */
public record AttentionCandidate(UUID instanceId, Optional<UUID> placementId, AttentionReason reason,
        Optional<HealthState> state, long stateVersion) {

    public AttentionCandidate {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        if ((reason == AttentionReason.HEALTH) != state.isPresent()) {
            throw new IllegalArgumentException("a health state is carried by a health reason, and by no other");
        }
    }
}
