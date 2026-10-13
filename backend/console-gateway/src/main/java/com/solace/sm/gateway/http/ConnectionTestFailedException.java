package com.solace.sm.gateway.http;

import java.util.Map;
import java.util.Objects;

/**
 * {@code createConnection}/{@code updateConnection}'s {@code testBeforeSave: true} ran the connectivity test and it
 * failed (plan 013 T4, {@code BE-D66}). Nothing was written to {@code cp-core} ({@link ConnectionOrchestrator}
 * raises this BEFORE the cp write); the dp secret seal that preceded the test may be left behind, orphaned and
 * referenced by nothing — the same shape as a refused registration's leftover probe-audit row.
 *
 * <p>⛔ {@code I6}: {@link #echoedAttributes()} holds only the non-secret attributes of the submission — secret-typed
 * keys are stripped before this exception is ever constructed ({@link ConnectionAttributeCodec#validateCreate}/
 * {@code validateUpdate} already separated them out).
 */
public final class ConnectionTestFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String rejectionReason;
    private final String echoedName;
    private final Map<String, Object> echoedAttributes;

    public ConnectionTestFailedException(String rejectionReason, String echoedName, Map<String, Object> echoedAttributes) {
        super(Objects.requireNonNull(rejectionReason, "rejectionReason"), null, false, false);
        this.rejectionReason = rejectionReason;
        this.echoedName = echoedName;
        this.echoedAttributes = Map.copyOf(Objects.requireNonNull(echoedAttributes, "echoedAttributes"));
    }

    /** The peer's own rejection text (never a secret value, {@code I6}: the tester never put one there). */
    public String rejectionReason() {
        return rejectionReason;
    }

    /** {@code null} for {@code updateConnection} when the request did not change the name. */
    public String echoedName() {
        return echoedName;
    }

    public Map<String, Object> echoedAttributes() {
        return echoedAttributes;
    }
}
