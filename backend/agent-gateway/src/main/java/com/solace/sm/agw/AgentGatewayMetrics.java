package com.solace.sm.agw;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

/** Bounded gateway metrics. Payloads and secret material are never metric values or labels. */
public final class AgentGatewayMetrics {
    private final Counter sessionsClaimed;
    private final Counter claimRefused;
    private final Counter noticesSent;
    private final Counter pulls;
    private final Counter commandsAcked;
    private final Counter telemetryBatches;
    private final Counter telemetryRejected;
    private final Counter sessionsRevoked;
    private final Counter leasesReissued;
    private final Counter drainSessions;
    private final Counter drainForced;
    private final Counter drainAbandonedCommands;
    private final Counter reconcileFailures;
    private final Counter creditsRegranted;
    private final Counter logRepliesIgnored;
    private final Counter logRequestTimeouts;
    private final MeterRegistry registry;
    private final AtomicInteger activeSessions = new AtomicInteger();
    private final AtomicInteger creditsOutstanding = new AtomicInteger();

    public AgentGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        sessionsClaimed = registry.counter("agw_sessions_claimed_total");
        claimRefused = registry.counter("agw_claim_refused_total");
        noticesSent = registry.counter("agw_notices_sent_total");
        pulls = registry.counter("agw_pulls_total");
        commandsAcked = registry.counter("agw_commands_acked_total");
        telemetryBatches = registry.counter("agw_telemetry_batches_total");
        telemetryRejected = registry.counter("agw_telemetry_rejected_total");
        sessionsRevoked = registry.counter("agw_sessions_revoked_total");
        leasesReissued = registry.counter("agw_leases_reissued_total");
        drainSessions = registry.counter("agw_drain_total");
        drainForced = registry.counter("agw_drain_forced_total");
        drainAbandonedCommands = registry.counter("agw_drain_abandoned_commands_total");
        reconcileFailures = registry.counter("agw_reconcile_failures_total");
        creditsRegranted = registry.counter("agw_credits_unsolicited_total");
        logRepliesIgnored = registry.counter("agw_log_replies_ignored_total");
        logRequestTimeouts = registry.counter("agw_log_request_timeouts_total");
        Gauge.builder("agw_sessions_active", activeSessions, AtomicInteger::get).register(registry);
        Gauge.builder("agw_credits_outstanding", creditsOutstanding, AtomicInteger::get).register(registry);
    }

    /**
     * Registers a monotonically increasing count that lives outside the gateway (the data plane's agent-telemetry counts) as a meter
     * of this registry, read at scrape time. {@code name} is a closed constant of the caller's, never agent-supplied text.
     *
     * <p>Micrometer holds {@code source} WEAKLY: a lambda or any object nothing else references is collected and the meter then reads
     * zero. The caller must therefore pass the long-lived object that owns the count (a Spring bean), and {@code reading} must not
     * capture anything else it needs to keep.
     */
    public <T> void bindCount(String name, T source, ToDoubleFunction<T> reading) {
        FunctionCounter.builder(name, source, reading).register(registry);
    }

    /** The registry the meters live in, for the management endpoint's exposition. */
    public MeterRegistry registry() { return registry; }
    public void sessionClaimed() { sessionsClaimed.increment(); activeSessions.incrementAndGet(); }
    public void sessionReleased() { activeSessions.updateAndGet(value -> Math.max(0, value - 1)); }
    public void claimRefused() { claimRefused.increment(); }
    public void noticeSent() { noticesSent.increment(); }
    public void pull() { pulls.increment(); }
    public void commandAcked() { commandsAcked.increment(); }
    public void telemetryBatch() { telemetryBatches.increment(); }
    public void telemetryRejected() { telemetryRejected.increment(); }
    public void sessionRevoked() { sessionsRevoked.increment(); }
    public void leaseReissued() { leasesReissued.increment(); }
    public void drainStarted(int sessions) { drainSessions.increment(sessions); }
    public void drainForced(int sessions) { drainForced.increment(sessions); }
    public void drainAbandoned(long commands) { drainAbandonedCommands.increment(commands); }
    public void reconcileFailed() { reconcileFailures.increment(); }
    public void admissionRejected(String reason) { registry.counter("agw_admission_rejected_total", "reason", reason).increment(); }
    /** A log reply or chunk for a request id this gateway does not hold for that agent (late, duplicate, or another agent's). */
    public void logReplyIgnored() { logRepliesIgnored.increment(); }
    public void logRequestTimedOut() { logRequestTimeouts.increment(); }
    public void creditsRegranted() { creditsRegranted.increment(); }
    public void creditsChanged(int delta) {
        creditsOutstanding.updateAndGet(value -> Math.max(0, value + delta));
    }
}
