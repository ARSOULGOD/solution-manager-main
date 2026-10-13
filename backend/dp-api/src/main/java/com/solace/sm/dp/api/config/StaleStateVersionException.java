package com.solace.sm.dp.api.config;

/**
 * The operator staged from a {@code baseStateVersion} that is no longer the instance's. Maps to {@code 409
 * CONFLICT}: the second of two operators staging from one base loses, and the first's generation is untouched.
 * Carries the current version (a number, not configuration) so the caller can say what to re-read.
 */
public final class StaleStateVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long currentStateVersion;

    public StaleStateVersionException(long currentStateVersion) {
        super("config.stage.stale-state-version");
        this.currentStateVersion = currentStateVersion;
    }

    public long currentStateVersion() {
        return currentStateVersion;
    }
}
