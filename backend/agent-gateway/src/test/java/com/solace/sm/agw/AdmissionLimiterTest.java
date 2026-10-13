package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Overload costs a refusal, not a database transaction: the three limits, their order and their retry-after. */
class AdmissionLimiterTest {
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    private AdmissionLimiter limiter(int maxSessions, double rate, int burst, Duration perAgent) {
        return new AdmissionLimiter(maxSessions, rate, burst, perAgent, nanos::get);
    }

    @Test
    void capacityIsRefusedBeforeAnythingIsSpent() {
        AdmissionLimiter limiter = limiter(2, 1000, 1000, Duration.ZERO);
        assertTrue(limiter.admit(1).isEmpty());
        var refusal = limiter.admit(2).orElseThrow();
        assertEquals(AdmissionLimiter.Reason.CAPACITY, refusal.reason());
        assertTrue(refusal.retryAfter().compareTo(Duration.ofSeconds(5)) >= 0
                && refusal.retryAfter().compareTo(Duration.ofSeconds(10)) < 0, "5-10 s with jitter: " + refusal.retryAfter());
    }

    @Test
    void theClaimRateIsATokenBucketThatRefillsWithTime() {
        AdmissionLimiter limiter = limiter(1000, 10, 3, Duration.ZERO); // 10/s, burst 3
        for (int i = 0; i < 3; i++) assertTrue(limiter.admit(0).isEmpty(), "burst " + i);
        var refusal = limiter.admit(0).orElseThrow();
        assertEquals(AdmissionLimiter.Reason.RATE, refusal.reason());
        assertTrue(refusal.retryAfter().toMillis() >= 500, "never tells an agent to retry in under half a second");
        nanos.addAndGet(Duration.ofMillis(250).toNanos()); // 2.5 tokens
        assertTrue(limiter.admit(0).isEmpty());
        assertTrue(limiter.admit(0).isEmpty());
        assertEquals(AdmissionLimiter.Reason.RATE, limiter.admit(0).orElseThrow().reason());
    }

    @Test
    void anAgentThatReclaimsFasterThanTheMinimumIntervalIsToldToWait() {
        AdmissionLimiter limiter = limiter(1000, 1000, 1000, Duration.ofSeconds(1));
        assertTrue(limiter.admitAgent("a1").isEmpty());
        var refusal = limiter.admitAgent("a1").orElseThrow();
        assertEquals(AdmissionLimiter.Reason.AGENT_INTERVAL, refusal.reason());
        assertTrue(limiter.admitAgent("a2").isEmpty(), "another agent is unaffected");
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertTrue(limiter.admitAgent("a1").isEmpty(), "after the interval the agent is admitted again");
    }

    @Test
    void aRefusalDoesNotCountAsAClaimSoAPersistentOffenderIsNotLockedOutForever() {
        AdmissionLimiter limiter = limiter(1000, 1000, 1000, Duration.ofSeconds(1));
        assertTrue(limiter.admitAgent("a1").isEmpty());
        for (int i = 0; i < 5; i++) {
            nanos.addAndGet(Duration.ofMillis(100).toNanos());
            limiter.admitAgent("a1"); // refused: must not push the window forward
        }
        nanos.addAndGet(Duration.ofMillis(600).toNanos());
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(limiter.admitAgent("a1").isEmpty());
    }

    @Test
    void retryAfterIsJitteredSoRefusedAgentsDoNotReturnTogether() {
        AdmissionLimiter limiter = limiter(1, 1000, 1000, Duration.ZERO);
        java.util.Set<Long> distinct = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) distinct.add(limiter.admit(1).orElseThrow().retryAfter().toMillis());
        assertTrue(distinct.size() > 10, "retry-after spread over " + distinct.size() + " distinct values");
    }
}
