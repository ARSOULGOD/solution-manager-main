package com.solace.sm.dp.home;

import com.solace.sm.dp.api.health.HealthState;
import java.util.List;
import java.util.Optional;

/**
 * One instance's health, from its placements' states: {@code project.ts}'s {@code healthRollupOf} taken LITERALLY at
 * placement scope, with the four NOTFOUND sub-states inserted (owner ruling {@code OR-61}, Q-C1-rollup):
 *
 * <ol>
 *   <li>{@code NOT_APPLICABLE} when no placement declares a probe;</li>
 *   <li>otherwise the first of: any {@code RED}, {@code missing}, {@code unreachable}, {@code not_exposed},
 *       {@code RESTART_EXPECTED}, {@code bootstrap};</li>
 *   <li>every placement {@code INACTIVE_REPLICA} gives {@code INACTIVE_REPLICA};</li>
 *   <li>else {@code GREEN} (so a healthy active-standby pair, {@code GREEN} plus {@code INACTIVE_REPLICA}, is
 *       {@code GREEN}).</li>
 * </ol>
 *
 * <p>A placement recorded {@code NOT_APPLICABLE} ({@code OR-101}: its package declares no probe) is not an
 * observation of anything, so beside a probed placement it contributes nothing and is never RED.
 */
public final class HomeRollup {

    /** The inserted sub-states and RED, in the ruling's order. */
    private static final List<HealthState> FIRST_MATCH = List.of(HealthState.RED, HealthState.MISSING,
            HealthState.UNREACHABLE, HealthState.NOT_EXPOSED, HealthState.RESTART_EXPECTED, HealthState.BOOTSTRAP);

    private HomeRollup() {
    }

    /** @return empty when {@code placements} is empty: nothing has been polled, and no state is invented */
    public static Optional<HealthState> of(List<HealthState> placements) {
        if (placements.isEmpty()) {
            return Optional.empty();
        }
        List<HealthState> observed = placements.stream().filter(s -> s != HealthState.NOT_APPLICABLE).toList();
        if (observed.isEmpty()) {
            return Optional.of(HealthState.NOT_APPLICABLE);
        }
        for (HealthState candidate : FIRST_MATCH) {
            if (observed.contains(candidate)) {
                return Optional.of(candidate);
            }
        }
        if (observed.stream().allMatch(s -> s == HealthState.INACTIVE_REPLICA)) {
            return Optional.of(HealthState.INACTIVE_REPLICA);
        }
        return Optional.of(HealthState.GREEN);
    }
}
