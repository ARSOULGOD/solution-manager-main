package com.solace.sm.dp.inventory;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The declare sweep's one knob (BE-D32, plan 010 T4.2): how long an UNREGISTERED {@code pending} instance may sit
 * before the sweep abandons it. A fresh unregistered row may be a declare between steps 3 and 4, so the window must
 * outlast the slowest honest declare; a registered row is activated at any age and ignores it.
 *
 * <p>Read by the caller of {@code InstanceInventoryService.settlePending} (the gateway's declare orchestrator,
 * T4.4), which owns the composition; the service itself takes the window as an argument.
 *
 * @param abandonAfter {@code sm.dp.declare.abandon-after}; null means {@link #DEFAULT_ABANDON_AFTER}
 */
@ConfigurationProperties(prefix = "sm.dp.declare")
public record DeclareSweepProperties(Duration abandonAfter) {

    /** Plan 010 T4.2: ten minutes. */
    public static final Duration DEFAULT_ABANDON_AFTER = Duration.ofMinutes(10);

    public DeclareSweepProperties {
        if (abandonAfter == null) {
            abandonAfter = DEFAULT_ABANDON_AFTER;
        }
        if (abandonAfter.isZero() || abandonAfter.isNegative()) {
            throw new IllegalArgumentException("sm.dp.declare.abandon-after must be positive");
        }
    }
}
