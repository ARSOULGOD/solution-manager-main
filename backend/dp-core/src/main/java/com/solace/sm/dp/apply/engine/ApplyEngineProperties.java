package com.solace.sm.dp.apply.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * The engine's own bounds.
 *
 * <p>Deliberately NOT the retry cap: that is plan 004's {@code AttemptPolicy}, bound from
 * {@code SmDataProperties.apply().maxAttempts()}, and a second copy would be a second answer to the
 * same question. Deliberately NOT {@code retainedGenerations} either — how many pre-images to keep
 * is a property of the placement's storage, and {@code DockerPlacement} already carries it.
 *
 * @param stepBudget         how long one attempt may take; becomes {@code apply_attempt.step_deadline}
 * @param healthGateDeadline how long {@link HealthGate} waits for one replica to come back healthy.
 *                           Separate from {@code stepBudget} because they bound different things: a
 *                           step budget is how long SM will wait for its OWN call to return, a gate
 *                           deadline is how long it will wait for somebody else's process to start
 * @param healthPollInterval how long the gate pauses between two observations. A gate that slept the
 *                           whole deadline and looked once would report a failure for a replica that
 *                           came back in a second
 */
public record ApplyEngineProperties(Duration stepBudget, Duration healthGateDeadline,
        Duration healthPollInterval) {

    public ApplyEngineProperties {
        positive(stepBudget, "a step budget");
        positive(healthGateDeadline, "a health gate deadline");
        positive(healthPollInterval, "a health poll interval");
        if (healthPollInterval.compareTo(healthGateDeadline) > 0) {
            throw new IllegalArgumentException("a poll interval longer than the deadline makes the "
                    + "gate a single observation with extra steps: " + healthPollInterval + " > "
                    + healthGateDeadline);
        }
    }

    private static void positive(Duration d, String what) {
        Objects.requireNonNull(d, what);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(what + " is a positive duration, was " + d);
        }
    }

    public static ApplyEngineProperties defaults() {
        return new ApplyEngineProperties(Duration.ofMinutes(5), Duration.ofMinutes(3),
                Duration.ofSeconds(2));
    }
}
