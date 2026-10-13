package com.solace.sm.dp.api.metrics.ingest;

/**
 * How a source detects a restart (M-Q9 (d')). <b>Derived by the data plane from its own rows, never taken from the
 * batch:</b> {@code DP_POLL} over {@code via-ssh} is {@link #DOCKER}; {@code DP_POLL} over {@code direct-http} with a
 * restart signal declared for the metric's component is {@link #DECLARED}; everything else ({@code direct-http} with
 * none, {@code BROKER_PUSH}, {@code AGENT} until the agent plan) is {@link #INFERRED}. The catalog's
 * {@code restartDetection} (P11-1.2) reports the same value.
 */
public enum RestartDetection {
    DECLARED, DOCKER, INFERRED;

    /** The detections whose reset evidence is a token read from the source (so a decrease alone proves nothing). */
    public boolean hasSignal() {
        return this != INFERRED;
    }
}
