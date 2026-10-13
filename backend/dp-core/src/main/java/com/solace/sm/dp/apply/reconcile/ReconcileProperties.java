package com.solace.sm.dp.apply.reconcile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The indeterminate reconciler's bounds - {@code sm.dp.apply.reconcile.*} (plan 010 T6.8; owner rulings
 * {@code OR-59}, {@code OR-63}). Each default is the ruled value.
 *
 * @param maxAttempts     failed or undecided INSPECTIONS before the reconciler gives up ({@code reconcile.exhausted}).
 *                        A successful inspection is free; this does not count re-drives
 * @param initialBackoff  the spacing after the first failed inspection; doubles per failure
 * @param maxBackoff      the largest spacing
 * @param maxRedrives     RE-DRIVES (restart-only or re-apply) per request, separate from the inspection counter (Q-R3)
 * @param maxRedriveAge   how long after the request's first {@code APPLY_INDETERMINATE} sighting a re-drive may still
 *                        begin. Past it the reconciler may still CONFIRM (a read) but never restarts or re-applies
 */
@ConfigurationProperties(prefix = "sm.dp.apply.reconcile")
public record ReconcileProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("30s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("1") int maxRedrives,
        @DefaultValue("1h") Duration maxRedriveAge) {

    public ReconcileProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("sm.dp.apply.reconcile.max-attempts must be >= 1, was " + maxAttempts);
        }
        if (maxRedrives < 0) {
            throw new IllegalArgumentException("sm.dp.apply.reconcile.max-redrives must be >= 0, was " + maxRedrives);
        }
        positive(initialBackoff, "sm.dp.apply.reconcile.initial-backoff");
        positive(maxBackoff, "sm.dp.apply.reconcile.max-backoff");
        positive(maxRedriveAge, "sm.dp.apply.reconcile.max-redrive-age");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("sm.dp.apply.reconcile.max-backoff (" + maxBackoff
                    + ") is shorter than initial-backoff (" + initialBackoff + ")");
        }
    }

    /** The ruled defaults, spelt out. */
    public static ReconcileProperties defaults() {
        return new ReconcileProperties(5, Duration.ofSeconds(30), Duration.ofMinutes(5), 1, Duration.ofHours(1));
    }

    /** The spacing after the {@code failures}-th failed inspection: initial, doubling, capped. */
    Duration backoffAfter(int failures) {
        Duration d = initialBackoff;
        for (int i = 1; i < failures && d.compareTo(maxBackoff) < 0; i++) {
            d = d.multipliedBy(2);
        }
        return d.compareTo(maxBackoff) > 0 ? maxBackoff : d;
    }

    private static void positive(Duration d, String property) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(property + " must be a positive duration, was " + d);
        }
    }
}
