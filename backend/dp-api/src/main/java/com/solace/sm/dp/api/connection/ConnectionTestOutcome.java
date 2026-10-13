package com.solace.sm.dp.api.connection;

import java.time.Instant;
import java.util.Objects;

/**
 * What a Connection's {@code testBeforeSave} probe found, as seen by {@code console-gateway} (plan 013 T4,
 * {@code BE-D66}). The dp-api mirror of dp-core's {@code ConnectionTestResult} — the gateway depends on
 * dp-api only ({@code I7}), so it never imports the dp-core type directly.
 *
 * <p>Every failure — an unreachable host, a credential the peer refused, a transport failure — is a result,
 * never an exception; {@code rejectionReason} is the peer's own text and never a secret ({@code I6}).
 *
 * <p>{@code reachable} (fix round 1, item 4) lets the gateway answer the contract's two documented failure
 * codes instead of collapsing both into one: {@code false} maps to {@code 503 SERVICE_UNAVAILABLE} ("could not
 * reach the target to test it; nothing was written, retry"), {@code true} (with {@code success} false) maps to
 * {@code 422 CONNECTION_TEST_FAILED}.
 *
 * @param success         the session or handshake completed and was then torn down
 * @param rejectionReason the peer's own reason, {@code null} on success
 * @param reachable       {@code true} when a peer accepted or rejected the attempt (meaningless when
 *                         {@code success} is true); {@code false} when the probe never got that far
 * @param checkedAt       when the test ran
 */
public record ConnectionTestOutcome(boolean success, String rejectionReason, boolean reachable, Instant checkedAt) {

    public ConnectionTestOutcome {
        Objects.requireNonNull(checkedAt, "checkedAt");
        if (success && rejectionReason != null) {
            throw new IllegalArgumentException("a successful test carries no rejection reason");
        }
    }
}
