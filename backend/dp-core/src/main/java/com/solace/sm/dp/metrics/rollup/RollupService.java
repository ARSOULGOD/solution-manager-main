package com.solace.sm.dp.metrics.rollup;

import com.solace.sm.dp.apply.model.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-aware hourly and daily rollups with a late window (plan 011 P11-3.2; M-Q13, M-Q24).
 *
 * <p>{@link #rollUp} is TENANT-SCOPED and is called per tenant by the metrics background loop from the one
 * {@code tenantsWithActiveInstances()} read it shares with collection (OQ-6 (a), {@code OR-92}; this widens M-Q3's named
 * caller to "the metrics background loop"). It is called on every tick and runs only when the tenant's previous run is at
 * least {@code interval} old: a recompute of the late window reads about 360 k raw rows at S-A, which is about 1.2 k
 * rows/s at five minutes and about 36 k rows/s at every 10 s tick on a burstable instance.
 *
 * <p>Each run recomputes every hourly bucket whose END lies in {@code (now - lateWindow - 1 h, now]}, then every UTC day
 * those buckets touch. Older buckets are final. The rewrite is idempotent, so a late sample inside the window corrects
 * its bucket and its day, and a bucket past the window is never touched again.
 *
 * <p>The type decides the shape: a gauge hour is {@code count/sum/min/max/last}; a counter hour is the sum of its
 * increments (NULL, never 0, when every increment is NULL) with the actual covered seconds. Averages are never averaged:
 * a mean is {@code sum / count} at query time.
 */
public final class RollupService {

    private static final Duration HOUR = Duration.ofHours(1);

    private final RollupRows rows;
    private final RollupProperties properties;
    private final Map<TenantId, Instant> lastRun = new ConcurrentHashMap<>();

    public RollupService(RollupRows rows, RollupProperties properties) {
        this.rows = Objects.requireNonNull(rows, "rows");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * @return true when the rollups ran, false when this tenant's last run is younger than the interval
     */
    public boolean rollUp(TenantId tenant, Instant now) {
        Instant previous = lastRun.get(tenant);
        if (previous != null && now.isBefore(previous.plus(properties.interval()))) {
            return false;
        }
        Window window = window(now, properties.lateWindow());
        rows.rollUpHours(tenant.value(), window.from(), window.to(), now);
        rows.rollUpDays(tenant.value(), window.from(), window.to(), now);
        lastRun.put(tenant, now);
        return true;
    }

    /** The hourly buckets a run at {@code now} recomputes: starts in {@code [from, to)}. Package-visible for the test. */
    record Window(Instant from, Instant to) {
    }

    static Window window(Instant now, Duration lateWindow) {
        // bucket [s, s+1h) is in scope when s+1h > now - late - 1h and s+1h <= now
        Instant newestStart = now.truncatedTo(ChronoUnit.HOURS).minus(HOUR);
        Instant lowerEnd = now.minus(lateWindow).minus(HOUR);
        Instant oldestStart = lowerEnd.truncatedTo(ChronoUnit.HOURS).plus(HOUR).minus(HOUR);
        if (!oldestStart.plus(HOUR).isAfter(lowerEnd)) {
            oldestStart = oldestStart.plus(HOUR);
        }
        return new Window(oldestStart, newestStart.plus(HOUR));
    }

    /** Midnight UTC of the day containing {@code instant}. */
    static Instant dayStart(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
