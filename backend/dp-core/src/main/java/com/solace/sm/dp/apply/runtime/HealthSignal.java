package com.solace.sm.dp.apply.runtime;

/**
 * What the runtime says about the replica's health.
 *
 * <p>{@link #NOT_DECLARED} is the honest answer when the runtime publishes no health signal at all —
 * measured on Docker as the absence of the {@code State.Health} key when neither the image nor the
 * launch spec declares a healthcheck. It is <b>not</b> {@link #UNHEALTHY}, and it is not silently
 * treated as {@link #HEALTHY}: a health gate refuses to run against it, because a gate with no
 * signal is a guard that cannot fail ({@code L7}).
 */
public enum HealthSignal {
    HEALTHY,
    UNHEALTHY,
    STARTING,
    NOT_DECLARED
}
