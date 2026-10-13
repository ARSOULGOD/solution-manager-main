package com.solace.sm.dp.metrics.rollup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sm.dp.metrics.rollup.*} (plan 011 P11-3.2).
 *
 * @param interval     how often a tenant's rollups run (default 5 min; at S-A a recompute of the late window re-reads about
 *                     360 k raw rows, which is cheap every 5 minutes and not every 10 s tick)
 * @param lateWindow   how far back a sample may arrive and still correct its bucket (M-Q24 (9), default 2 h)
 * @param runwayEvery  how often the partition runway is re-extended (default daily)
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.rollup")
public record RollupProperties(
        @DefaultValue("5m") Duration interval,
        @DefaultValue("2h") Duration lateWindow,
        @DefaultValue("24h") Duration runwayEvery) {

    public RollupProperties {
        positive(interval, "interval");
        positive(runwayEvery, "runway-every");
        if (lateWindow == null || lateWindow.isNegative()) {
            throw new IllegalArgumentException("sm.dp.metrics.rollup.late-window must not be negative, was " + lateWindow);
        }
    }

    public static RollupProperties defaults() {
        return new RollupProperties(Duration.ofMinutes(5), Duration.ofHours(2), Duration.ofHours(24));
    }

    private static void positive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.rollup." + name + " must be a positive duration, was " + d);
        }
    }
}
