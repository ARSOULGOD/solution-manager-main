package com.solace.sm.dp.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The shared metrics settings, {@code sm.dp.metrics.*} (plan 011 P11-3.1). Each later area binds its own nested prefix
 * ({@code sm.dp.metrics.ingest}, {@code .rollup}, {@code .disk}); this record reads only its own two keys.
 *
 * <p><b>The pool arithmetic</b> (MF16: HikariCP 6.3.3 defaults {@code maximumPoolSize} to 10, read from the shipped
 * {@code HikariConfig} bytecode): pool 10, of which query and export together may hold at most
 * {@code db-budget} = 4, the ingest writer holds 1, and health, apply and everything else share the remaining 5.
 * {@code sm.data.pool.maximum-size} sets the pool; this budget must stay below it, and the margin is what keeps apply
 * answering while a dashboard is busy.
 *
 * @param dbBudget        permits shared by query and export; default 4
 * @param executorThreads threads of the shared {@code metricsExecutor} (runway keeper, disk alarm, rollups); default 2
 */
@ConfigurationProperties(prefix = "sm.dp.metrics")
public record MetricsDbProperties(
        @DefaultValue("4") int dbBudget,
        @DefaultValue("2") int executorThreads) {

    public MetricsDbProperties {
        if (dbBudget < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.db-budget must be at least 1, was " + dbBudget);
        }
        if (executorThreads < 1) {
            throw new IllegalArgumentException("sm.dp.metrics.executor-threads must be at least 1, was " + executorThreads);
        }
    }
}
