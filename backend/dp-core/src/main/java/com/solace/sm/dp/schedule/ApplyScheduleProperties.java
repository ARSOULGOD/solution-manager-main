package com.solace.sm.dp.schedule;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Scheduled applies' bounds - {@code sm.dp.apply.schedule.*} (plan 012 T7, {@code OPS-Q10}).
 *
 * @param maxLead how far ahead the window may reach - {@code notBefore} AND {@code notAfter}: default 7 days, and 7 days is
 *                the CEILING ({@code OR-122} (2): "at most 7 days ahead"), so configuration may only lower it; a larger
 *                value refuses to boot. A longer lead is refused at submit ({@code 422}); the bound exists because authority
 *                staleness grows with it, and the schedule is re-authorized at fire regardless
 * @param dueBatch the most schedules one tenant's pass reads; a backlog is read on the next tick
 */
@ConfigurationProperties(prefix = "sm.dp.apply.schedule")
public record ApplyScheduleProperties(
        @DefaultValue("7d") Duration maxLead,
        @DefaultValue("100") int dueBatch) {

    /** The owner's ceiling ({@code OR-122} (2)): a schedule is accepted at most this far ahead, whatever is configured. */
    public static final Duration MAX_LEAD_CEILING = Duration.ofDays(7);

    public ApplyScheduleProperties {
        if (maxLead == null || maxLead.isNegative() || maxLead.isZero()) {
            throw new IllegalArgumentException("sm.dp.apply.schedule.max-lead must be a positive duration, was " + maxLead);
        }
        if (maxLead.compareTo(MAX_LEAD_CEILING) > 0) {
            // refuse to boot rather than clamp: a silent clamp would let an operator believe a longer lead is in force
            throw new IllegalArgumentException("sm.dp.apply.schedule.max-lead may be at most " + MAX_LEAD_CEILING
                    + " (OR-122 (2)), was " + maxLead);
        }
        if (dueBatch < 1 || dueBatch > 1000) {
            throw new IllegalArgumentException("sm.dp.apply.schedule.due-batch must be between 1 and 1000, was " + dueBatch);
        }
    }

    /** The defaults, for a test or a caller that configures nothing. */
    public static ApplyScheduleProperties defaults() {
        return new ApplyScheduleProperties(Duration.ofDays(7), 100);
    }
}
