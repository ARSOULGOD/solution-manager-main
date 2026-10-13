package com.solace.sm.dp.apply.runtime.connection;

import java.time.Instant;
import java.util.Objects;

/**
 * What a {@code createConnection}/{@code updateConnection} {@code testBeforeSave} probe found (plan 013 T3/T4,
 * {@code BE-D66}). This is a result, never an exception: a broker that rejects the credential, a host that
 * refuses the connection, and a transport failure are all a failed result with a reason, not a {@code 5xx} by
 * itself — {@code console-gateway} (Task 4) maps a failed, UNREACHABLE result to {@code 503} and a failed,
 * reachable one to {@code 422 CONNECTION_TEST_FAILED} (fix round 1, item 4), per the contract's own two codes.
 *
 * <p>{@code I6}: {@code rejectionReason} is the broker's or host's own rejection text — never a secret value,
 * because nothing upstream of this record ever had one to put there (the transport classifiers below hand back
 * only what the peer said, not what was sent).
 *
 * @param success         the session or handshake completed and was then torn down
 * @param rejectionReason the peer's own reason, {@code null} on success
 * @param reachable       {@code true} when the probe reached a peer that accepted or rejected it (meaningless
 *                         when {@code success} is true); {@code false} when it never got that far — an address
 *                         the policy refused, a name that did not resolve, or a transport-level failure
 * @param checkedAt       when the test ran
 */
public record ConnectionTestResult(boolean success, String rejectionReason, boolean reachable, Instant checkedAt) {

    public ConnectionTestResult {
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (success && rejectionReason != null) {
            throw new IllegalArgumentException("a successful test carries no rejection reason");
        }
    }
}
