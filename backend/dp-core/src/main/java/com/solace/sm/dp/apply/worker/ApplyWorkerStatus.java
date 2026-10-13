package com.solace.sm.dp.apply.worker;

/**
 * Whether the durable apply worker is actually running in THIS process — queryable so a startup
 * test can assert on it directly rather than scraping log output.
 *
 * <p><b>Always registered, in every context</b> (production included), regardless of whether an
 * {@link com.solace.sm.dp.apply.engine.ApplyExecutor} bean exists. When it does not, this is the
 * loud, structured half of the required absence signal — {@link ApplyWorker} and
 * {@link ApplyStartupRecovery} both write to it, and a caller reads {@link #running()} rather than
 * inferring the answer from whether a bean happens to exist.
 */
public final class ApplyWorkerStatus {

    private volatile boolean running;
    private volatile String reason = "not started";

    void markRunning(String what) {
        running = true;
        reason = what + " running";
    }

    void markNotRunning(String reason) {
        running = false;
        this.reason = reason;
    }

    public boolean running() {
        return running;
    }

    public String reason() {
        return reason;
    }
}
