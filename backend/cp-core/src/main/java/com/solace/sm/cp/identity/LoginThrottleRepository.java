package com.solace.sm.cp.identity;

import java.time.Instant;

/** Durable login-throttle port. Persistence failures are treated as authentication refusal. */
public interface LoginThrottleRepository {

    boolean isLocked(String subjectHash, Instant now);

    void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now);

    /** Clears accumulated failures after a successful local credential verification. */
    void clearFailures(String subjectHash, Instant now);
}
