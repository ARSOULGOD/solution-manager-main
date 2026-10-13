package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.agent.AgentEnrolmentSweep;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The sweep of an interrupted agent enrolment for ONE organization (plan 015 T3.5, T4.6, {@code BE-D71}; {@code BE-D32}'s
 * pattern). An enrolment writes the control plane's agent target and then the data plane's connection; if the agent never
 * retries after a crash between the two, the target is listed degraded forever and holds its name. This retires it once its
 * enrolment token expired and {@code sm.dp.declare.abandon-after} (the same window {@code BE-D32} uses; ten minutes) has passed
 * since: before that the agent may still be about to replay. The agent's own retry within the window completes the data plane half
 * and the target stops being a candidate.
 *
 * <p>Composed here because the sweep needs one answer from each plane ({@code I7}: the control plane lists the old agent
 * targets, the data plane says which hold a connection) and the gateway is the one module permitted both {@code -api}s.
 *
 * <p><b>Who calls it ({@code OR-140}).</b> Only {@link AgentEnrolmentSweepJob}, on a schedule, once per organization the control
 * plane names. It no longer runs in {@code listTargets} or any other request: a reach-level GET never triggers a revocation. Its
 * two control-plane writes are audited to the SYSTEM ACTOR (see {@link AgentEnrolmentSweep}), under a correlation id the pass mints
 * per abandoned target. A failure never fails the pass (best effort, logged by classifier only).
 */
@Component
public class AgentTargetSweep {

    private static final Logger LOG = LoggerFactory.getLogger(AgentTargetSweep.class);
    /** One bounded batch per request. */
    static final int MAX_BATCH = 20;
    /** A hard cap per request: 10 pages. */
    static final int MAX_PAGES = 10;

    private final AgentEnrolmentSweep controlPlane;
    private final TargetConnectionService dataPlane;
    private final Supplier<UUID> correlationIds;
    private final int batch;
    private final int maxPages;
    /** Per organization: where the previous pass stopped (keyset cursor), so a later pass continues past the healthy rows instead of re-reading them. */
    private final java.util.concurrent.ConcurrentMap<UUID, AgentEnrolmentSweep.Candidate> cursors =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Duration window;

    /**
     * @param window {@code sm.dp.declare.abandon-after}, the key the data plane documents; ⚠️ the ten-minute default is stated here
     *               too because the gateway may not import the data plane's properties.
     */
    @Autowired
    public AgentTargetSweep(AgentEnrolmentSweep controlPlane, TargetConnectionService dataPlane,
            @Value("${sm.dp.declare.abandon-after:10m}") Duration window) {
        this(controlPlane, dataPlane, UUID::randomUUID, window);
    }

    /** As above with the correlation ids supplied directly: one per abandoned target, shared by its revoke and its retire. */
    AgentTargetSweep(AgentEnrolmentSweep controlPlane, TargetConnectionService dataPlane, Supplier<UUID> correlationIds,
            Duration window) {
        this(controlPlane, dataPlane, correlationIds, window, MAX_BATCH, MAX_PAGES);
    }

    /** As above with the page limits injectable (a test drives a small limit over a larger population). */
    AgentTargetSweep(AgentEnrolmentSweep controlPlane, TargetConnectionService dataPlane, Supplier<UUID> correlationIds,
            Duration window, int batch, int maxPages) {
        if (batch < 1 || maxPages < 1) {
            throw new IllegalArgumentException("batch and maxPages must be positive");
        }
        this.batch = batch;
        this.maxPages = maxPages;
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.dataPlane = Objects.requireNonNull(dataPlane, "dataPlane");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
        this.window = Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("sm.dp.declare.abandon-after must be positive");
        }
    }

    /** The abandon window: a target is a candidate once its token expired this long ago. */
    Duration window() {
        return window;
    }

    /**
     * Pages through the control plane's old agent targets (a healthy enrolment matches too, because the control plane cannot see the
     * data plane, so no SQL predicate can narrow the list to degraded ones), asks the data plane which hold a connection, and abandons
     * the rest. At most {@code maxPages} pages of {@code batch} per organization per pass: bounded work. <b>The position is kept
     * between passes</b> (a keyset cursor per organization): the next pass continues after the last row this one read, and starts again
     * from the beginning when a page comes back short. So a degraded target sorted behind any number of healthy ones is reached within
     * {@code ceil(candidates / (batch * maxPages))} passes, never starved by the rows in front of it. Per abandoned target: revoke,
     * look at the data plane AGAIN, and retire only if it is still absent (a replay may have landed in between).
     */
    public void sweep(UUID organization) {
        try {
            AgentEnrolmentSweep.Candidate after = cursors.get(organization);
            for (int page = 0; page < maxPages; page++) {
                List<AgentEnrolmentSweep.Candidate> old = controlPlane.candidates(organization, window, after, batch);
                if (old.isEmpty()) {
                    cursors.remove(organization);
                    return;
                }
                Map<UUID, TargetEnvelope> connected = dataPlane.liveEnvelopes(organization,
                        old.stream().map(AgentEnrolmentSweep.Candidate::targetId).toList());
                for (AgentEnrolmentSweep.Candidate candidate : old) {
                    if (!connected.containsKey(candidate.targetId())) {
                        abandon(organization, candidate);
                    }
                }
                if (old.size() < batch) {
                    cursors.remove(organization);
                    return;
                }
                after = old.get(old.size() - 1);
                cursors.put(organization, after);
            }
        } catch (RuntimeException e) {
            LOG.warn("agent enrolment sweep of one organization did not finish ({})", e.getClass().getSimpleName());
        }
    }

    /** Drops the cursor of every organization not in {@code live}: the job calls it with the organizations it just listed. */
    void retainCursorsFor(java.util.Collection<UUID> live) {
        cursors.keySet().retainAll(live);
    }

    private void abandon(UUID organization, AgentEnrolmentSweep.Candidate candidate) {
        UUID correlation = correlationIds.get();
        controlPlane.revoke(organization, candidate, correlation);
        // the revoke closed the door to a LATER replay; one that landed before it may have written the data plane meanwhile
        if (dataPlane.liveEnvelopes(organization, List.of(candidate.targetId())).containsKey(candidate.targetId())) {
            return;
        }
        controlPlane.retire(organization, candidate, correlation);
    }
}
