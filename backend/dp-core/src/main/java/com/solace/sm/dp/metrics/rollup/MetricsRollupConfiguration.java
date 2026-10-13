package com.solace.sm.dp.metrics.rollup;

import com.solace.sm.dp.metrics.RunwayDegradation;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The rollups, the runway keeper and the disk alarm (plan 011 P11-3.2), built from interface types only: the
 * {@code Jdbc*} beans come from {@code RollupPersistenceConfiguration} and {@code MetricsPersistenceConfiguration}. The
 * keeper and the alarm run on the one shared {@code metricsExecutor} ({@code MetricsConfiguration}); this class creates no
 * executor. Both start on bean initialisation, off the starting thread, and neither can fail the boot.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({RollupProperties.class, DiskProperties.class})
public class MetricsRollupConfiguration {

    @Bean
    RollupService rollupService(RollupRows rollupRows, RollupProperties properties) {
        return new RollupService(rollupRows, properties);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    PartitionRunway partitionRunway(PartitionRunway.Source runwaySource, ScheduledExecutorService metricsExecutor,
            RollupProperties properties, @Qualifier("smDataClock") Clock smDataClock, RunwayDegradation degradation,
            ObjectProvider<PartitionRunway.DailyJob> dailyJobs) {
        return new PartitionRunway(runwaySource, metricsExecutor, properties, smDataClock, degradation,
                dailyJobs.orderedStream().toList());
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    MetricsDiskAlarm metricsDiskAlarm(DiskRows diskRows, DiskProperties properties,
            ScheduledExecutorService metricsExecutor) {
        return new MetricsDiskAlarm(diskRows, properties, metricsExecutor);
    }
}
