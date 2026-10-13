package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code undeclareInstance} across the two planes (plan 012 T5; {@code OR-121} {@code OPS-Q2} U-C; {@code ops-501.md}
 * section 4.4), composed here because the gateway is the one module permitted both {@code -api}s ({@code I7}). The mirror of
 * {@link DeclareOrchestrator} (BE-D32), in reverse.
 *
 * <p><b>The order is the protocol.</b> {@code instance.undeclare} at the instance has already passed (the interceptor, which
 * is also why a control-plane node already released is a {@code 404} before this runs). Then:
 * <ol>
 *   <li>{@code beginRetire} (DP) commits FIRST: under the instance's admission lock it refuses while anything is in flight
 *       ({@code 409}) and records the intent ({@code retiring}, an open {@code instance_retirement}). A crash here leaves
 *       a row an authorized retry resumes or the sweep rolls back; it never leaves a control-plane write with no data-plane
 *       intent, which nothing could recover.</li>
 *   <li>the window guard: a record older than <b>half</b> {@code sm.dp.undeclare.rollback-after} is not continued - the
 *       sweep may roll it back at any moment, and a control-plane release landing after that would leave a retired node over
 *       an {@code active} instance. {@code 503}; nothing on the control plane was written. A record older than the <i>whole</i>
 *       window is rolled back first, by this request running {@link UndeclareSweep#sweep} for its own organization (W5 review
 *       W5B-4), and the begin is repeated once on the clean row: without that, a retry of the DELETE alone answered
 *       {@code 503} until some other request ran the sweep.</li>
 *   <li>{@code deregister} (CP): the node is retired and its quota slot freed, audited {@code INSTANCE_UNDECLARED} under
 *       <i>this</i> session (so a retry's CP row names the retrying principal while the DP record keeps the original
 *       requester). Idempotent.</li>
 *   <li>{@code completeRetire} (DP): {@code retired}, the placements retired, the record closed.</li>
 * </ol>
 * It touches no host ({@code U-C}) and reads no target: the answer is built from the closed record.
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()} in every call on both planes.
 */
@Component
public class UndeclareOrchestrator {

    private final InstanceRetirementService retirements;
    private final InstanceRetirementGovernance governance;
    private final Clock clock;
    private final Duration rollbackAfter;
    private final Duration halfWindow;
    private final UndeclareSweep sweep;

    /**
     * @param rollbackAfter {@code sm.dp.undeclare.rollback-after}, the same key the sweep reads ({@link UndeclareSweep}).
     */
    public UndeclareOrchestrator(InstanceRetirementService retirements, InstanceRetirementGovernance governance,
            @Qualifier("smDataClock") Clock clock, @Value("${sm.dp.undeclare.rollback-after:10m}") Duration rollbackAfter,
            UndeclareSweep sweep) {
        this.retirements = Objects.requireNonNull(retirements, "retirements");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(rollbackAfter, "rollbackAfter");
        if (rollbackAfter.isZero() || rollbackAfter.isNegative()) {
            throw new IllegalArgumentException("sm.dp.undeclare.rollback-after must be positive");
        }
        this.rollbackAfter = rollbackAfter;
        this.halfWindow = rollbackAfter.dividedBy(2);
        this.sweep = Objects.requireNonNull(sweep, "sweep");
    }

    public InstanceRetirement undeclare(Session session, RequestOrigin origin, UUID instanceId) {
        UUID organization = session.organizationId();

        // Step 3: DP intent. Refusals (404, 409) leave both planes as they were.
        RetirementBegun begun = retirements.beginRetire(organization, instanceId, session.principal().id(),
                origin.correlationId(), WorkloadDisposition.LEAVE_RUNNING);

        // The window guard. Strictly older than half: a record exactly half old still proceeds.
        if (olderThan(begun, halfWindow)) {
            // W5 review W5B-4: a record that has outlived the WHOLE window is the sweep's to roll back, and a bare retry of
            // this DELETE would otherwise answer 503 until some other request in the organization ran the sweep. So the
            // retry runs it, once, for this organization, then begins again on the clean row. Inside the window (half to
            // whole) nothing is rolled back: a slow step 4 of the original may still be running, so this stays a 503.
            if (olderThan(begun, rollbackAfter)) {
                sweep.sweep(organization);
                begun = retirements.beginRetire(organization, instanceId, session.principal().id(),
                        origin.correlationId(), WorkloadDisposition.LEAVE_RUNNING);
            }
            if (olderThan(begun, halfWindow)) {
                throw new UndeclareWindowException();
            }
        }

        // Step 4: CP release, under THIS session's audit context. Whether it wrote or found the node already released
        // does not change what happens next.
        governance.deregister(session.principal(), origin, organization, instanceId);

        // Step 5: DP finish.
        return toWire(retirements.completeRetire(organization, instanceId));
    }

    private boolean olderThan(RetirementBegun begun, Duration limit) {
        return Duration.between(begun.retirement().requestedAt(), clock.instant()).compareTo(limit) > 0;
    }

    private static InstanceRetirement toWire(RetirementView closed) {
        return new InstanceRetirement(closed.stateVersion(), null, closed.instanceId(),
                closed.completedAt().orElseThrow(() -> new IllegalStateException("a completed undeclare has no completion time"))
                        .toString(),
                InstanceRetirement.LEFT_RUNNING,
                closed.placements().stream()
                        .map(p -> new InstanceRetirement.RetiredPlacement(p.placementId(), p.lastHealth().orElse(null)))
                        .toList());
    }
}
