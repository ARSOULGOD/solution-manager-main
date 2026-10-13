package com.solace.sm.dp.metrics.query;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sm.dp.metrics.query.*} (plan 011 P11-6.1). The M-Q22 and M-Q24 defaults, all configurable.
 *
 * @param maxPointsPerSeries   the raw tier is bucketed on the fly to at most this many points per series (M-Q24 (10)
 *                             names ~1000, DM-D13)
 * @param seriesCap            rendered series per request (M-Q24 (10): default 10; DM-D7 allows 10 to 20)
 * @param statementTimeout     {@code statement_timeout} of every query transaction (M-Q22 (1): 30 s)
 * @param budgetWait           how long a query waits for one of the shared budget's permits (M-Q22 (6)) before answering
 *                             "busy"
 * @param rawMaxRange          ranges up to this are served raw (discovery default D-6: 7 days)
 * @param hourlyMaxRange       ranges up to this are served hourly, longer ones daily (D-6: 90 days)
 * @param rateSeconds          a counter is rendered as a rate per this many seconds (60: per minute)
 * @param rawRetention         how far back raw is kept (REQ-007: six months); a longer raw range is truncated, declared
 * @param hourlyRetention      how far back hourly rollups are kept (SM-D8: two years); daily is kept indefinitely
 * @param placementOverlapGrace two placements of one instance overlap only when they share MORE than this much of the
 *                             range, so a re-target that retires the old placement a moment after minting the new one is
 *                             one continuous line (M-Q20), not two
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.query")
public record QueryProperties(
        @DefaultValue("1000") int maxPointsPerSeries,
        @DefaultValue("10") int seriesCap,
        @DefaultValue("30s") Duration statementTimeout,
        @DefaultValue("10s") Duration budgetWait,
        @DefaultValue("7d") Duration rawMaxRange,
        @DefaultValue("90d") Duration hourlyMaxRange,
        @DefaultValue("60") int rateSeconds,
        @DefaultValue("183d") Duration rawRetention,
        @DefaultValue("730d") Duration hourlyRetention,
        @DefaultValue("1m") Duration placementOverlapGrace) {

    public QueryProperties {
        if (maxPointsPerSeries < 2) {
            throw new IllegalArgumentException("sm.dp.metrics.query.max-points-per-series must be at least 2, was " + maxPointsPerSeries);
        }
        if (seriesCap < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.query.series-cap must be at least 1, was " + seriesCap);
        }
        positive(statementTimeout, "statement-timeout");
        positive(budgetWait, "budget-wait");
        positive(rawMaxRange, "raw-max-range");
        positive(hourlyMaxRange, "hourly-max-range");
        positive(rawRetention, "raw-retention");
        positive(hourlyRetention, "hourly-retention");
        if (placementOverlapGrace == null || placementOverlapGrace.isNegative()) {
            throw new IllegalArgumentException("sm.dp.metrics.query.placement-overlap-grace must not be negative");
        }
        if (hourlyMaxRange.compareTo(rawMaxRange) <= 0) {
            throw new IllegalArgumentException("sm.dp.metrics.query.hourly-max-range must exceed raw-max-range");
        }
        if (rateSeconds < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.query.rate-seconds must be at least 1, was " + rateSeconds);
        }
    }

    public static QueryProperties defaults() {
        return new QueryProperties(1000, 10, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofDays(7),
                Duration.ofDays(90), 60, Duration.ofDays(183), Duration.ofDays(730), Duration.ofMinutes(1));
    }

    private static void positive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.query." + name + " must be a positive duration, was " + d);
        }
    }
}
