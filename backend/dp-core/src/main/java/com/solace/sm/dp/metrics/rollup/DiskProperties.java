package com.solace.sm.dp.metrics.rollup;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code sm.dp.metrics.disk.*} (plan 011 P11-3.2, OQ-16 (a)).
 *
 * @param budgetBytes the operator's disk budget for the database; null or 0 means none, which reads as {@code unconfigured}
 *                    and never as a silent {@code ok}
 * @param every       how often the two sizes are read (default hourly)
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.disk")
public record DiskProperties(Long budgetBytes, @DefaultValue("1h") Duration every) {

    public DiskProperties {
        if (budgetBytes != null && budgetBytes < 0) {
            throw new IllegalArgumentException("sm.dp.metrics.disk.budget-bytes must not be negative, was " + budgetBytes);
        }
        if (every == null || every.isNegative() || every.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.disk.every must be a positive duration, was " + every);
        }
    }

    public boolean configured() {
        return budgetBytes != null && budgetBytes > 0;
    }
}
