package com.solace.sm.dp.api.agw;

import java.time.Duration;

/**
 * The outcome of one batch. {@code disposition} says whether the gateway may acknowledge it (only after the built
 * port reports Committed), must not (RetryAfter / Pending; then {@code retryAfter} says when to try again), or that it
 * was rejected and counted ({@code rejection} says why) (AGW-D27).
 *
 * @param retryAfter {@code null} unless the disposition is DO_NOT_ACK with a known wait
 * @param rejection  {@code null} unless the disposition is REJECTED_COUNTED
 */
public record AgentTelemetryResult(Disposition disposition, long highWatermark, int creditsGranted,
        Duration retryAfter, GatewayCode rejection) {
    public enum Disposition { ACK, DO_NOT_ACK, REJECTED_COUNTED }

    public AgentTelemetryResult {
        if (disposition == null) {
            throw new IllegalArgumentException("disposition is required");
        }
    }

    /** The common case: committed, acknowledge. */
    public static AgentTelemetryResult acked(long highWatermark, int creditsGranted) {
        return new AgentTelemetryResult(Disposition.ACK, highWatermark, creditsGranted, null, null);
    }
}
