package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The lazy undeclare sweep for ONE organization (plan 012 T5; {@code ops-501.md} section 4.4), the reverse of
 * {@link DeclareOrchestrator#settle}'s declare sweep. It runs inside the requests a stale {@code retiring} row affects
 * ({@code declareInstance}, {@code listInstances}, {@code getInstance}, and a retry of the {@code DELETE} whose own record has
 * outlived the whole window, W5 review W5B-4), for the request's organization only, with no loop and
 * no tenant enumeration ({@code OR-44}, {@code OR-49} are untouched).
 *
 * <ul>
 *   <li><b>Control plane released, data plane {@code retiring}</b> - complete step 5 (a data-plane write). At any age: the
 *       control plane's audit row was written by the original {@code DELETE}'s session.</li>
 *   <li><b>Control plane NOT released, older than the window</b> ({@code sm.dp.undeclare.rollback-after}, default ten
 *       minutes, as {@code sm.dp.declare.abandon-after}) - roll the row back to {@code active}. Nothing on the control plane
 *       changes, so nothing there needs an audit context.</li>
 *   <li><b>Control plane NOT released, younger than the window</b> - left alone: a {@code DELETE} may be between steps 3 and 4.</li>
 * </ul>
 *
 * <p>⛔ <b>This class never writes the Control Plane.</b> It runs inside <i>some other user's</i> request, possibly a Viewer's,
 * so a {@code sm_control} write would be audited under the wrong session or refused ({@code OR-25}). It holds the one port
 * that carries the write ({@link InstanceRetirementGovernance#deregister}) only because {@code retired} lives beside it, and
 * {@code UndeclareBoundaryTest} (arch-tests) fails the build if this class ever calls it.
 */
@Component
public class UndeclareSweep {

    private final InstanceRetirementService retirements;
    private final InstanceRetirementGovernance governance;
    private final Clock clock;
    private final Duration rollbackAfter;

    /**
     * @param rollbackAfter {@code sm.dp.undeclare.rollback-after}. ⚠️ The ten-minute default is stated here and in
     *                      {@link UndeclareOrchestrator} (whose half-window guard reads the same key), the gateway not
     *                      being allowed to import the data plane's properties.
     */
    public UndeclareSweep(InstanceRetirementService retirements, InstanceRetirementGovernance governance,
            @Qualifier("smDataClock") Clock clock, @Value("${sm.dp.undeclare.rollback-after:10m}") Duration rollbackAfter) {
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.rollbackAfter = Objects.requireNonNull(rollbackAfter, "rollbackAfter");
        if (rollbackAfter.isZero() || rollbackAfter.isNegative()) {
            throw new IllegalArgumentException("sm.dp.undeclare.rollback-after must be positive");
        }
    }

    /** One bounded read when nothing is {@code retiring}; otherwise one control-plane read per batch of ids. */
    public void sweep(UUID organization) {
        List<RetiringInstance> retiring = retirements.retiring(organization);
        if (retiring.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Set<UUID> released = released(organization, retiring);
        for (RetiringInstance row : retiring) {
            try {
                if (released.contains(row.instanceId())) {
                    retirements.completeRetire(organization, row.instanceId());
                } else if (Duration.between(row.requestedAt(), now).compareTo(rollbackAfter) > 0) {
                    retirements.rollBackRetire(organization, row.instanceId());
                }
            } catch (RetirementRefusedException finishedByAnother) {
                // The loser of a race: a retry or another request's sweep finished (or rolled back) this undeclare first.
                // Not an error, and not a reason to stop the rest.
            }
        }
    }

    private Set<UUID> released(UUID organization, List<RetiringInstance> retiring) {
        Set<UUID> released = new java.util.HashSet<>();
        List<UUID> ids = new ArrayList<>(retiring.size());
        for (RetiringInstance row : retiring) {
            ids.add(row.instanceId());
        }
        for (int from = 0; from < ids.size(); from += InstanceRetirementGovernance.MAX_BATCH) {
            int to = Math.min(ids.size(), from + InstanceRetirementGovernance.MAX_BATCH);
            released.addAll(governance.retired(organization, ids.subList(from, to)));
        }
        return released;
    }
}
