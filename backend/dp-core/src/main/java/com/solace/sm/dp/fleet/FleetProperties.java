package com.solace.sm.dp.fleet;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The fleet scheduler's bounds - {@code sm.dp.fleet.*} (plan 010 T11.2).
 *
 * @param enabled         {@code false} withdraws the scheduler (a submitted fleet then sits {@code RUNNING}, visibly);
 *                        production sets nothing
 * @param advanceInterval time between two advance passes; default 1 s, the apply worker's own poll interval
 *                        ({@code fleet-apply.md} section 5.5, "Latency"): a settled batch opens the next one within one
 *                        tick, and the worker then claims within its own interval
 * @param noValidation    the {@code rolling-no-validation} fleet's pacing ({@code sm.dp.fleet.no-validation.*}; plan 012
 *                        T6, {@code OPS-Q8} (x), {@code BE-D50}); absent means the default
 */
@ConfigurationProperties(prefix = "sm.dp.fleet")
public record FleetProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration advanceInterval,
        @DefaultValue NoValidation noValidation) {

    public FleetProperties {
        if (advanceInterval == null || advanceInterval.isNegative() || advanceInterval.isZero()) {
            throw new IllegalArgumentException("sm.dp.fleet.advance-interval must be a positive duration, was "
                    + advanceInterval);
        }
        if (noValidation == null) {
            noValidation = new NoValidation(NoValidation.DEFAULT_SETTLE);
        }
    }

    /**
     * @param settle the least time between the LAST member of one batch reaching {@code apply_request.terminal_at} (or, for a member the reconciler gave up on,
     *               {@code apply_reconciliation.gave_up_at}) and the
     *               next batch of a {@code rolling-no-validation} fleet opening; default 30 s. It is applied between every
     *               pair of batches (uniform, {@code OPS-Q8} (x)), not only between two batches that share an instance.
     *               {@code 0} withdraws the delay (a lane addition no ruling covers - see plan 012 T6 Results, W4B-5); a negative value is refused at startup. {@code rolling-health-gated}
     *               (its gate is the wait), {@code restart-all} and {@code manual} never wait
     */
    public record NoValidation(@DefaultValue("30s") Duration settle) {

        static final Duration DEFAULT_SETTLE = Duration.ofSeconds(30);

        public NoValidation {
            if (settle == null || settle.isNegative()) {
                throw new IllegalArgumentException(
                        "sm.dp.fleet.no-validation.settle must be a non-negative duration, was " + settle);
            }
        }
    }
}
