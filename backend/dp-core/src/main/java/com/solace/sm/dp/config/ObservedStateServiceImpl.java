package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.ObservedInstance;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.api.health.PlacementHealthView;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ObservedStateService} over the config of record (plan 010 T5.3). Reads only: it never writes, and never
 * touches a host (a stage does not, and a read must not).
 *
 * <p>{@code VERSION_UNVERIFIED} is not a placeholder: for a {@code dp-remote} instance SM reads no version from the
 * managed integration (I1 - nothing may be required of the MI), so "unverified" is the true state, and it does not
 * block editing.
 *
 * <p><b>Replicas are the persisted placement health</b> (plan 010 T7.2), one per polled live placement, and are empty
 * until the poller has visited the instance. {@code ReplicaHealth} has four values, and the projection reads any other
 * as GREEN, so the NOTFOUND sub-states must not be passed through: a placement that is unreachable, not exposed,
 * missing or still bootstrapping has given no answer, and "no response" is red in the projection's own rule (UI-D9).
 * {@code getHealth} keeps the full vocabulary; this is only the designer's rollup input.
 *
 * <p><b>A placement whose package declares no probe ({@code NOT_APPLICABLE}, owner ruling {@code OR-101}) is left out
 * of the replicas.</b> It is a property of the package, not an observation of the placement: counting it would make it
 * RED, and passing it as GREEN would claim a health nobody measured. The rollup's own "nothing declared" rule already
 * yields {@code NOT_APPLICABLE} for a package with no probe at all.
 */
public final class ObservedStateServiceImpl implements ObservedStateService {

    static final String UNVERIFIED = "VERSION_UNVERIFIED";

    private final ConfigLadderReader ladder;
    private final HealthService health;

    public ObservedStateServiceImpl(ConfigLadderReader ladder, HealthService health) {
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override
    public ObservedInstance observed(UUID organizationId, UUID instanceId) {
        ConfigLadderView view = ladder.read(organizationId, instanceId);
        return new ObservedInstance(UNVERIFIED, null, null,
                new ObservedInstance.Rungs(hashOf(view, LadderRung.DESIRED), hashOf(view, LadderRung.RENDERED),
                        hashOf(view, LadderRung.PERSISTED), hashOf(view, LadderRung.RESTART_OBSERVED),
                        hashOf(view, LadderRung.EFFECTIVE_VERIFIED)),
                health.health(organizationId, instanceId).placements().stream()
                        .filter(p -> p.state() != HealthState.NOT_APPLICABLE)
                        .map(p -> new ObservedInstance.Replica(p.placementId().toString(), replicaHealth(p)))
                        .toList());
    }

    private static String replicaHealth(PlacementHealthView placement) {
        HealthState state = placement.state();
        return switch (state) {
            case GREEN -> "GREEN";
            case RESTART_EXPECTED -> "RESTART_EXPECTED";
            case INACTIVE_REPLICA -> "INACTIVE_REPLICA";
            // NOT_APPLICABLE never reaches here: observed() drops it (OR-101); the arm only keeps the switch total
            case RED, NOT_APPLICABLE, UNREACHABLE, NOT_EXPOSED, MISSING, BOOTSTRAP -> "RED";
        };
    }

    @Override
    public ConfigLadderView ladder(UUID organizationId, UUID instanceId) {
        return ladder.read(organizationId, instanceId);
    }

    private static String hashOf(ConfigLadderView view, LadderRung rung) {
        ConfigLadderView.Rung entry = view.rungs().get(rung.ordinal());
        return entry.present() ? entry.configHash() : null;
    }
}
