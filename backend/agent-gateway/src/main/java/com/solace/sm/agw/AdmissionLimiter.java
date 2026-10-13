package com.solace.sm.agw;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Admission control for new sessions. It exists so that overload costs the Data Plane a refusal, not a database
 * transaction: a reconnect storm after a restart, a fleet-wide credential event, or two agents fighting over one
 * identity are all absorbed here, before the claim. Three independent limits, cheapest first:
 *
 * <ol>
 *   <li><b>Capacity</b> — at most {@code maxSessions} live sessions on this replica.</li>
 *   <li><b>Rate</b> — a token bucket on claims (sustained {@code ratePerSecond}, burst {@code burst}). The claim is the
 *       expensive step (a transaction plus a lease read), so this is what bounds database write load from reconnects.</li>
 *   <li><b>Per agent</b> — one agent may not claim more often than {@code perAgentMinInterval}. A healthy agent
 *       reconnects at most once per backoff; a faster one is a duplicate identity or a fault, and is told to wait.</li>
 * </ol>
 *
 * A refusal carries {@code retry_after} with jitter (between one and two times the computed wait) so that refused agents
 * do not all return at the same instant.
 */
final class AdmissionLimiter {
    enum Reason { CAPACITY, RATE, AGENT_INTERVAL }

    record Refusal(Reason reason, Duration retryAfter) { }

    private final int maxSessions;
    private final double ratePerSecond;
    private final double burst;
    private final long perAgentMinIntervalNanos;
    private final LongSupplier nanoTime;
    private final Map<String, Long> lastClaimNanos = new ConcurrentHashMap<>();
    private double tokens;
    private long refilledAt;

    AdmissionLimiter(int maxSessions, double ratePerSecond, int burst, Duration perAgentMinInterval, LongSupplier nanoTime) {
        this.maxSessions = maxSessions;
        this.ratePerSecond = ratePerSecond;
        this.burst = burst;
        this.perAgentMinIntervalNanos = perAgentMinInterval.toNanos();
        this.nanoTime = nanoTime;
        this.tokens = burst;
        this.refilledAt = nanoTime.getAsLong();
    }

    AdmissionLimiter(AgentGatewayProperties properties) {
        this(properties.getMaxSessions(), properties.getAdmissionRatePerSecond(), properties.getAdmissionBurst(),
                properties.getClaimMinInterval(), System::nanoTime);
    }

    /** The global checks, made before anything about the caller is looked up. */
    synchronized Optional<Refusal> admit(int liveSessions) {
        if (liveSessions >= maxSessions) {
            return Optional.of(new Refusal(Reason.CAPACITY, jitter(Duration.ofSeconds(5))));
        }
        long now = nanoTime.getAsLong();
        tokens = Math.min(burst, tokens + (now - refilledAt) / 1e9 * ratePerSecond);
        refilledAt = now;
        if (tokens < 1) {
            double secondsToNextToken = (1 - tokens) / ratePerSecond;
            return Optional.of(new Refusal(Reason.RATE, jitter(Duration.ofMillis((long) Math.max(500, secondsToNextToken * 1000)))));
        }
        tokens -= 1;
        return Optional.empty();
    }

    /** The per-agent check, made once the agent is known. A refusal does not count as a claim. */
    Optional<Refusal> admitAgent(String agentId) {
        long now = nanoTime.getAsLong();
        Long previous = lastClaimNanos.get(agentId);
        if (previous != null && now - previous < perAgentMinIntervalNanos) {
            return Optional.of(new Refusal(Reason.AGENT_INTERVAL,
                    jitter(Duration.ofNanos(perAgentMinIntervalNanos - (now - previous)).plusSeconds(1))));
        }
        lastClaimNanos.put(agentId, now);
        if (lastClaimNanos.size() > Math.max(1024, 2 * maxSessions)) {
            long cutoff = now - Duration.ofMinutes(10).toNanos();
            lastClaimNanos.values().removeIf(at -> at < cutoff);
        }
        return Optional.empty();
    }

    private static Duration jitter(Duration base) {
        long millis = base.toMillis();
        return Duration.ofMillis(millis + ThreadLocalRandom.current().nextLong(Math.max(1, millis)));
    }
}
