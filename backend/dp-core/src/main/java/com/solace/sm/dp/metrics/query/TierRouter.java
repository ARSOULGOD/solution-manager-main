package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeResult.Tier;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Which tier serves a range, how wide its buckets are, where its provisional edge starts and how far back it reaches
 * (plan 011 P11-6.1; discovery default D-6: at most 7 d raw, up to 90 d hourly, longer daily). Pure: no clock, no I/O.
 *
 * <p><b>The provisional edge mirrors {@code RollupService}'s window and must move with it.</b> A run at {@code now}
 * recomputes every hourly bucket whose END is after {@code now - lateWindow - 1 h}, and then every UTC day those buckets
 * touch. Everything older is final; everything from the edge on may still be rewritten (a late sample) or does not exist
 * yet (the open hour), so it is served from raw instead and declared provisional. {@code RollupService.window} is
 * package-private to another package, so the arithmetic is restated here and {@code MetricsQueryServiceIT} runs the real
 * service and compares.
 */
final class TierRouter {

    private static final Duration HOUR = Duration.ofHours(1);

    private final QueryProperties properties;
    private final Duration lateWindow;

    TierRouter(QueryProperties properties, Duration lateWindow) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.lateWindow = Objects.requireNonNull(lateWindow, "lateWindow");
    }

    Tier tier(Duration range, RangeQuery.Resolution requested) {
        if (requested == RangeQuery.Resolution.RAW || range.compareTo(properties.rawMaxRange()) <= 0) {
            return Tier.RAW;
        }
        return range.compareTo(properties.hourlyMaxRange()) <= 0 ? Tier.HOURLY : Tier.DAILY;
    }

    /**
     * The bucket width in seconds. The raw tier is bucketed on the fly: wide enough that an unaligned range touches at
     * most {@code maxPointsPerSeries} buckets ({@code ceil(range / (max - 1))}, because epoch-aligned buckets can touch
     * one more than {@code ceil(range / width)}), and never finer than the sources' interval.
     */
    int bucketSeconds(Tier tier, Duration range, int maxIntervalSeconds) {
        return switch (tier) {
            case HOURLY -> widened(3600, range);
            case DAILY -> widened(86400, range);
            case RAW -> {
                long forPoints = ceilDiv(Math.max(1, range.toSeconds()), properties.maxPointsPerSeries() - 1L);
                yield (int) Math.max(1, Math.max(forPoints, maxIntervalSeconds));
            }
        };
    }

    /**
     * W3-08: a rollup tier is never finer than its own width and never emits more than {@code maxPointsPerSeries} points per
     * series. When {@code range / width} buckets would exceed it, the bucket becomes a multiple of the width (2 h, 3 h, ...;
     * 2 d, ...) wide enough that an unaligned range touches at most that many (the raw tier's {@code max - 1} rule).
     */
    private int widened(int width, Duration range) {
        long buckets = ceilDiv(Math.max(1, range.toSeconds()), width);
        long factor = Math.max(1, ceilDiv(buckets, properties.maxPointsPerSeries() - 1L));
        return (int) (factor * width);
    }

    /** The start of the {@code widthSeconds}-wide bin, aligned to the 2001-01-01 origin the database bins from, containing {@code ts}. */
    Instant bin(Instant ts, long widthSeconds) {
        return Aggregator.bin(ts, widthSeconds);
    }

    /** The first instant of the first bucket the rollup may still rewrite; null for raw, which has no rollup. */
    Instant provisionalFrom(Tier tier, Instant now) {
        if (tier == Tier.RAW) {
            return null;
        }
        Instant lowerEnd = now.minus(lateWindow).minus(HOUR);
        Instant oldestStart = lowerEnd.truncatedTo(ChronoUnit.HOURS).plus(HOUR).minus(HOUR);
        if (!oldestStart.plus(HOUR).isAfter(lowerEnd)) {
            oldestStart = oldestStart.plus(HOUR);
        }
        return tier == Tier.HOURLY ? oldestStart : oldestStart.truncatedTo(ChronoUnit.DAYS);
    }

    /** The start of the UTC bucket that contains {@code instant}; raw has no bucket alignment of its own. */
    Instant floor(Tier tier, Instant instant) {
        return switch (tier) {
            case RAW -> instant;
            case HOURLY -> instant.truncatedTo(ChronoUnit.HOURS);
            case DAILY -> instant.truncatedTo(ChronoUnit.DAYS);
        };
    }

    /** The earliest instant the tier still holds: {@code from}, unless retention cut it (daily is kept indefinitely). */
    Instant servedFrom(Tier tier, Instant from, Instant now) {
        Duration retention = switch (tier) {
            case RAW -> properties.rawRetention();
            case HOURLY -> properties.hourlyRetention();
            case DAILY -> null;
        };
        if (retention == null) {
            return from;
        }
        Instant oldest = now.minus(retention);
        return from.isBefore(oldest) ? oldest : from;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
