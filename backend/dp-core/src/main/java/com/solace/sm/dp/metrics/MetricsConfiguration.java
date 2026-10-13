package com.solace.sm.dp.metrics;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The two SHARED metrics beans and nothing else (plan 011 P11-3.1): {@link MetricsDbBudget} and the
 * {@code metricsExecutor} the runway keeper, the disk alarm and the rollups run on. Every other area registers its
 * beans in its own configuration, so no later task edits this file. Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsDbProperties.class)
public class MetricsConfiguration {

    @Bean
    MetricsDbBudget metricsDbBudget(MetricsDbProperties properties) {
        return new MetricsDbBudget(properties.dbBudget());
    }

    /** A small daemon pool: background metrics housekeeping must never keep the JVM alive or crowd the request threads. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService metricsExecutor(MetricsDbProperties properties) {
        AtomicInteger n = new AtomicInteger();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(properties.executorThreads(), runnable -> {
            Thread t = new Thread(runnable, "sm-metrics-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
