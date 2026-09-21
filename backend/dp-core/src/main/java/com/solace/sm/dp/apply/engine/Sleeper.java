package com.solace.sm.dp.apply.engine;

import java.time.Duration;

/**
 * The pause between two polls of a {@link HealthGate}.
 *
 * <p>A seam with exactly one production implementation, and it exists so the gate's unit tests do
 * not sleep for real. Without it the suite's runtime <i>is</i> the deadline, which makes the one
 * property worth asserting — that the gate polls rather than sleeping the whole window — the most
 * expensive test in the module.
 */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration d);

    /**
     * {@link Thread#sleep(long)}, with an interrupt restoring the flag rather than swallowing it.
     *
     * <p>Milliseconds, not the {@code Duration} overload: that one arrived in Java 19 and this
     * reactor targets 17.
     */
    static Sleeper real() {
        return d -> {
            try {
                Thread.sleep(Math.max(1L, d.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting on a health gate", e);
            }
        };
    }
}
