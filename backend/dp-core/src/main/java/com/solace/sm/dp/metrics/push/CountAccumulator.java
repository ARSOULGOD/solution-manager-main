package com.solace.sm.dp.metrics.push;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The cumulative value of a count extraction (plan 011 P11-5.1; OR-106): a counter the Data Plane synthesises, one per
 * (placement, metric), that starts at one for the first matching event and rises by one for each. It lives in memory only, so a
 * Data Plane restart starts it again; the ingest path then sees a value below the stored one with no restart signal, which is
 * M-Q9's INFERRED reset, exactly as OR-106 rules ("treated as an inferred reset when the DP restarts").
 *
 * <p>The known approximation, stated: if the Data Plane restarts and the stored series' last value happens to be equal to the
 * new first value (one), the first event after the restart reads as "no increase" rather than as a reset.
 */
public final class CountAccumulator {

    private record Key(UUID placement, String metric) {
    }

    private final Map<Key, AtomicLong> totals = new ConcurrentHashMap<>();

    /** The new cumulative value after one more event. */
    public double next(UUID placement, String metric) {
        return totals.computeIfAbsent(new Key(placement, metric), k -> new AtomicLong()).incrementAndGet();
    }

    /** Forgets every placement that is not in {@code keep}, so the map is bounded by the live plan, not by history. */
    public void retainPlacements(Set<UUID> keep) {
        totals.keySet().removeIf(key -> !keep.contains(key.placement()));
    }

    public int size() {
        return totals.size();
    }
}
