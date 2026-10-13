package com.solace.sm.cp.identity;

import java.time.Instant;

/**
 * Durable login-throttle port. Persistence failures propagate as-is and become a {@code 500
 * INTERNAL_ERROR} — never {@code AUTHENTICATION_FAILED} — so a database outage is never reported
 * to a caller as "wrong credential" (see {@code LocalLoginServiceTest
 * .aLoginThrottleRepositoryInfrastructureFailurePropagatesAndIsNotConvertedToALoginRefusal}).
 */
public interface LoginThrottleRepository {

    boolean isLocked(String subjectHash, Instant now);

    void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now);

    /** Clears accumulated failures after a successful local credential verification. */
    void clearFailures(String subjectHash, Instant now);

    /**
     * A conditional write against an EXISTING, already-locked throttle row (an {@code UPDATE} that
     * records which lockout has already been probed) so the {@code OR-19} {@code AFTER UPDATE}
     * trigger fires the {@code LOGIN_REFUSED} / {@code CREDENTIAL_ROTATION_REFUSED} audit row for a
     * refusal against a locked subject — but, per {@code OR-24}, {@code AT MOST ONCE PER DISTINCT
     * LOCKOUT}, never once per refused request. A repeat probe against the SAME lockout (the same
     * {@code locked_until} value) is a true no-op: it neither writes an audit row nor extends the
     * lockout window, increments {@code failure_count}, or otherwise records an additional failure. A
     * probe against a NEW lockout (a different, later {@code locked_until}) fires the trigger again
     * exactly once. Only ever called for a {@code subjectHash} {@link #isLocked} has just reported
     * {@code true} for, so a row always exists to probe. Never called for the USERNAME dimension
     * ({@code OR-23} made it an alarm, not a gate — it is never independently checked or probed).
     *
     * <p>⚠️ Race with recovery: {@code isLocked} and this call are two separate statements, not one
     * atomic operation. If the lockout-recovery command ({@code SmControlLockoutRecoveryMain}) clears
     * this exact row between the two — i.e. the row stops existing, or its {@code locked_until}
     * moves into the past, in that narrow window — this {@code UPDATE} matches zero rows and writes
     * no audit row. The caller's refusal is still returned either way (the recovery race only ever
     * costs an audit row for that one borderline attempt, never a false ALLOW): correctness of the
     * refusal decision does not depend on this write landing.
     */
    void probe(String subjectHash, Instant now);
}
