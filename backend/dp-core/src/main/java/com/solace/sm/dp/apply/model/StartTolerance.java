package com.solace.sm.dp.apply.model;

import java.time.Duration;

/**
 * How much later than a write's start baseline an observed container start must be to count as a different start (plan 015 W4
 * note (e); {@code OR-112}). Both values are the host's clock; this only absorbs the rounding between Docker's nanoseconds and the
 * database's microseconds. It lives in {@code dp.apply.model} so that the config ladder ({@code dp.config}), the SQL form of its rule
 * ({@code dp.apply.persistence}) and the agent runtime adapter ({@code dp.apply.runtime.agent}) read ONE constant without
 * {@code dp.apply.runtime.agent} depending on {@code dp.config} while {@code dp.config} depends on {@code dp.apply.runtime}: that was
 * a package cycle (no arch rule forbade it; one does now, {@code DpPackageCycleTest}).
 */
public final class StartTolerance {

    /** The tolerance; the SQL form of the rule ({@code JdbcRestartObservedGenerationRepository}) reads this, so the two cannot drift. */
    public static final Duration VALUE = Duration.ofMillis(1);

    private StartTolerance() {
    }
}
