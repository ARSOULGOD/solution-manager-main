package com.solace.sm.dp.apply.worker;

/**
 * Whether Task 4b's startup recovery sweep actually ran in THIS process — the recovery half of
 * {@link ApplyWorkerStatus}, kept separate so a test (or an operator) can tell "the worker isn't
 * running" apart from "the one-shot startup sweep never ran", instead of one flag answering both.
 *
 * <p>Always registered, in every context, whether or not an
 * {@link com.solace.sm.dp.apply.engine.ApplyExecutor} bean exists.
 */
public final class ApplyRecoveryStatus {

    private volatile boolean ran;
    private volatile String reason = "not started";

    void markRan(String what) {
        ran = true;
        reason = what;
    }

    void markSkipped(String reason) {
        ran = false;
        this.reason = reason;
    }

    public boolean ran() {
        return ran;
    }

    public String reason() {
        return reason;
    }
}
