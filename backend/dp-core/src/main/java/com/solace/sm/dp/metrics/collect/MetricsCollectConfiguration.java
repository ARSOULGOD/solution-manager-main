package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.metrics.collect.fetch.DirectHttpFetcher;
import com.solace.sm.dp.metrics.collect.fetch.SshMetricFetcher;
import com.solace.sm.dp.metrics.ingest.IngestProperties;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import com.solace.sm.dp.metrics.rollup.RollupService;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * The metrics background loop and its parts (plan 011 P11-4.3), reached through the data-plane aggregator. Built from
 * interface types only: the {@code Jdbc*} beans come from {@code CollectPersistenceConfiguration}. This class creates
 * the collector's own worker pool (a fixed pool of daemon platform threads, discovery default D-4) and shares nothing
 * with {@code MetricsConfiguration}'s housekeeping executor.
 *
 * <p>The loop waits for the master-key canary ({@code kekCanaryCheck}) exactly as the health poller does, because
 * reading over SSH resolves a credential. {@code sm.dp.metrics.collect.enabled=false} withdraws it.
 *
 * <p>The SSH fetcher exists when the shared session pool does: health OR the collector enabled (W3-10), so switching health
 * off does not stop SSH collection. Without a fetcher an SSH instance is counted {@code SCRAPE_FAILED} and never read some
 * other way (M-Q2).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CollectorProperties.class)
public class MetricsCollectConfiguration {

    public static final String ENABLED = "sm.dp.metrics.collect.enabled";

    @Bean
    CollectionPlanBuilder collectionPlanBuilder(InstanceRows instanceRows, PlacementRows placementRows,
            CollectionRows collectionRows, RestartObservedGenerations restartObservedGenerations,
            IngestProperties ingestProperties, PlacementTransports placementTransports) {
        return new CollectionPlanBuilder(instanceRows, placementRows, collectionRows, restartObservedGenerations,
                ingestProperties.defaultIntervalSeconds(), placementTransports);
    }

    /** The per-target cap leaves health its own probe slot on the pool it shares (M-Q3); both numbers are configuration. */
    @Bean
    SourceScheduler sourceScheduler(CollectorProperties properties, SshSessionPoolProperties sshPool) {
        return new SourceScheduler(SourceScheduler.effectiveCap(properties, sshPool.maxProbesPerSession()),
                properties.jitter(), properties.maxBackoffMultiplier(), ThreadLocalRandom.current()::nextDouble);
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService metricsCollectExecutor(CollectorProperties properties) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(properties.workerThreads(), runnable -> {
            Thread thread = new Thread(runnable, "sm-metrics-collect-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    CollectorCounts collectorCounts(IngestRows.Counters counters, @Qualifier("smDataClock") Clock smDataClock) {
        return new CollectorCounts(counters, smDataClock);
    }

    @Bean
    MetricsCollector metricsCollector(SourceScheduler sourceScheduler, DirectHttpFetcher directHttpFetcher,
            ObjectProvider<SshMetricFetcher> sshMetricFetcher, TelemetryIngestPort telemetryIngest,
            CollectorCounts collectorCounts, @Qualifier("metricsCollectExecutor") ExecutorService metricsCollectExecutor,
            @Qualifier("smDataClock") Clock smDataClock) {
        return new MetricsCollector(sourceScheduler, directHttpFetcher, sshMetricFetcher.getIfAvailable(),
                telemetryIngest, collectorCounts, metricsCollectExecutor, smDataClock);
    }

    @Bean
    @DependsOn("kekCanaryCheck")
    @ConditionalOnProperty(name = ENABLED, havingValue = "true", matchIfMissing = true)
    MetricsBackgroundLoop metricsBackgroundLoop(TenantEnumerationRepository tenantEnumerationRepository,
            CollectionPlanBuilder collectionPlanBuilder, SourceScheduler sourceScheduler,
            MetricsCollector metricsCollector, RollupService rollupService, CollectorProperties properties,
            @Qualifier("smDataClock") Clock smDataClock, ObjectProvider<PushReconcile> pushReconcile) {
        return new MetricsBackgroundLoop(tenantEnumerationRepository, collectionPlanBuilder, sourceScheduler,
                metricsCollector, rollupService, properties, smDataClock, pushReconcile.getIfAvailable(() -> PushReconcile.NONE));
    }
}
