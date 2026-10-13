package com.solace.sm.dp.metrics.rollup;

import java.time.Instant;
import java.util.UUID;

/**
 * The persistence port of the rollups (plan 011 P11-3.2). Every method names the tenant (I5) and is idempotent: a
 * rollup is {@code INSERT ... ON CONFLICT DO UPDATE}, recomputed from raw (hourly) or from the hourly rows (daily), so
 * running it twice, or late, only ever rewrites a bucket with what is now true. Implemented in
 * {@code dp.apply.persistence}.
 */
public interface RollupRows {

    /**
     * Recomputes every hourly bucket whose start lies in {@code [from, to)} (both on the hour) for the tenant's series.
     *
     * @return rows written
     */
    int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt);

    /**
     * Recomputes every UTC day that contains an hourly bucket starting in {@code [from, to)}, from the hourly rows.
     *
     * @return rows written
     */
    int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt);
}
