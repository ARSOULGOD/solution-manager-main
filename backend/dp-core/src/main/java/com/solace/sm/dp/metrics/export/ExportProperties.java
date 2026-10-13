package com.solace.sm.dp.metrics.export;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The export's bounds, {@code sm.dp.metrics.export.*} (plan 011 P11-6.2). Every default is an owner ruling (M-Q22 {@code OR-79}),
 * every one is configurable, and none may be raised by a request.
 *
 * @param rowLimit                 rows an export may write (M-Q22 (4)); default 20 M. A larger projection is the typed
 *                                 {@code 422}; the 105 M-row measurement raises it for that labelled run only
 * @param perOrganization          concurrent exports per organization (M-Q22 (3)); default 1
 * @param global                   concurrent exports in the install (M-Q22 (3)); default 2
 * @param statementTimeout         the database statement timeout for an export (M-Q22 (2)); default 30 min
 * @param budgetWait               how long an export (or its plan read) waits for a connection permit from the one shared
 *                                 budget before it is refused as busy; default 30 s
 * @param fetchSize                rows PgJDBC pulls per round trip from the server-side cursor; must be above 0, because 0
 *                                 restores "cache every row" (DMR-20); default 10 000
 * @param unknownIntervalSeconds   RETIRED by W3-12 and kept so existing configuration still binds: a series with no declared
 *                                 interval (the push path) is now projected from a bound count of its raw rows, not from a
 *                                 guessed interval, which misjudged File Mesh's 5 s heartbeat fivefold
 */
@ConfigurationProperties(prefix = "sm.dp.metrics.export")
public record ExportProperties(
        @DefaultValue("20000000") long rowLimit,
        @DefaultValue("1") int perOrganization,
        @DefaultValue("2") int global,
        @DefaultValue("30m") Duration statementTimeout,
        @DefaultValue("30s") Duration budgetWait,
        @DefaultValue("10000") int fetchSize,
        @DefaultValue("1") int unknownIntervalSeconds) {

    public ExportProperties {
        if (rowLimit < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.export.row-limit must be at least 1, was " + rowLimit);
        }
        if (perOrganization < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.export.per-organization must be at least 1, was " + perOrganization);
        }
        if (global < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.export.global must be at least 1, was " + global);
        }
        positive(statementTimeout, "statement-timeout");
        positive(budgetWait, "budget-wait");
        if (fetchSize < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.export.fetch-size must be above 0 (0 buffers the whole result), was "
                    + fetchSize);
        }
        if (unknownIntervalSeconds < 1 || unknownIntervalSeconds > 86400) {
            throw new IllegalArgumentException("sm.dp.metrics.export.unknown-interval-seconds must be 1..86400, was "
                    + unknownIntervalSeconds);
        }
    }

    /** The ruled defaults, for code that is not Spring-bound. */
    public static ExportProperties defaults() {
        return new ExportProperties(20_000_000L, 1, 2, Duration.ofMinutes(30), Duration.ofSeconds(30), 10_000, 1);
    }

    private static void positive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException("sm.dp.metrics.export." + name + " must be a positive duration, was " + d);
        }
    }
}
