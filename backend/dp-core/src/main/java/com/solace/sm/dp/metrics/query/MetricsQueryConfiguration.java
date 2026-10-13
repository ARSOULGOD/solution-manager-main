package com.solace.sm.dp.metrics.query;

import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import com.solace.sm.dp.metrics.rollup.MetricsDiskAlarm;
import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The metrics query service (plan 011 P11-6.1), built from interface types only: the {@code Jdbc*} read comes from
 * {@code QueryPersistenceConfiguration}, the shared {@link MetricsDbBudget} from {@code MetricsConfiguration} (this task
 * injects the one budget and builds none), and the runway keeper, the disk alarm and the rollup settings from
 * {@code MetricsRollupConfiguration}. The restart-observed generation and the active list are the collector's own ports
 * ({@code MetricsCollectConfiguration}). Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QueryProperties.class)
public class MetricsQueryConfiguration {

    @Bean
    MetricsQueryServiceImpl metricsQueryService(MetricsQueryRows rows, MetricsDbBudget budget, QueryProperties properties,
            RollupProperties rollupProperties, @Qualifier("smDataClock") Clock smDataClock,
            RestartObservedGenerations generations, CollectionRows collectionRows, PartitionRunway runway,
            MetricsDiskAlarm diskAlarm) {
        return new MetricsQueryServiceImpl(rows, budget, properties, rollupProperties, smDataClock, generations,
                collectionRows, runway, diskAlarm);
    }
}
