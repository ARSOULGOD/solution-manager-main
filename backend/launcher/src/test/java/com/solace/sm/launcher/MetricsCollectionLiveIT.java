package com.solace.sm.launcher;

import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 011 P11-4.4 (S-G, S-F): the live background collection against an OPERATOR-PROVIDED SSH-Docker host that runs the
 * Cache, with SM's own database and mesh events local to this run. The scenarios are
 * {@link AbstractMetricsCollectionWalkthrough}'s and run first, every run, against a local stub in
 * {@code MetricsCollectionRehearsalIT}.
 *
 * <p><b>Gated</b> exactly as {@link SkeletonWalkthroughIT} is, on the walkthrough's own target
 * ({@code -Dsm.walkthrough.ssh.host=...} and the rest, see {@link WalkthroughTarget}; {@code
 * backend/launcher/scripts/run-e2e-metrics-collection.sh} fills them from {@code SM_E2E_*}): without the property this class
 * is SKIPPED with the reason below and no host is looked for.
 *
 * <p><b>It READS.</b> The collector issues GETs (as a {@code curl} to the host's own loopback over SSH, or directly) and
 * {@code docker inspect}; no configuration file is written and no container touched. <b>Only with
 * {@code sm.walkthrough.metrics.disruptive=yes}</b> does it restart one Cache and stop one (and start it again, in
 * {@code @AfterAll}, whatever fails): the plan's Step 2 asks for both, and a stop of the reference host's Cache is not
 * something this class does unasked.
 */
@Tag("walkthrough")
@EnabledIfSystemProperty(named = WalkthroughTarget.GATE_PROPERTY, matches = ".+",
        disabledReason = "plan 011 P11-4.4 needs an operator-provided SSH-Docker host running the Cache: run with "
                + "-Dsm.walkthrough.ssh.host=<host> and the rest of the target "
                + "(backend/launcher/scripts/run-e2e-metrics-collection.sh); without it the live collection claim is not made")
class MetricsCollectionLiveIT extends AbstractMetricsCollectionWalkthrough {

    /** The ingest default: no property of this run changes it, so the live cadence is 30 s. */
    private static final Duration LIVE_INTERVAL = Duration.ofSeconds(30);

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        return E2eStack.live(WalkthroughTarget.fromSystem(), evidence);
    }

    @Override
    MetricsLiveTarget liveTarget() {
        return MetricsLiveTarget.fromSystem();
    }

    @Override
    String evidencePrefix() {
        return "MetricsCollectionLiveIT";
    }

    @Override
    Duration interval() {
        return LIVE_INTERVAL;
    }

    /** A plain TCP connect from this machine to the registered address at the Cache's host port: reads nothing. */
    @Override
    boolean directReachable(MetricsLiveTarget.Cache cache) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(stack.target.host(), cache.hostPort()), 3_000);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    void restart(Run run) {
        RuntimeAdapter adapter = app.adapter.delegate();
        adapter.startOrUpdate(replica(adapter, run), 0);   // a plain `docker restart`: no file is written
    }

    @Override
    void stop(Run run) {
        RuntimeAdapter adapter = app.adapter.delegate();
        adapter.stop(replica(adapter, run));
    }

    @Override
    void start(Run run) {
        RuntimeAdapter adapter = app.adapter.delegate();
        adapter.startOrUpdate(replica(adapter, run), 0);
    }

    private TargetReplica replica(RuntimeAdapter adapter, Run run) {
        return adapter.replicas(stack.placementRef(run.instance(), run.placement())).get(0);
    }
}
