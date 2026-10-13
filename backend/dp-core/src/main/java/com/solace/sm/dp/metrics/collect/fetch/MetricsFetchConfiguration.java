package com.solace.sm.dp.metrics.collect.fetch;

import com.solace.sm.dp.apply.runtime.ssh.SshSessionPool;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.health.SshPoolConfiguration;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.metrics.collect.CollectorProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The metric fetchers (plan 011 P11-4.2), reached through the data-plane aggregator. No JDBC here: the direct fetcher
 * reads the target's registered address through {@link TargetConnectionRows}, the same port the health poller uses.
 *
 * <p>The SSH fetcher shares the health poller's {@link SshSessionPool} (one connection per target, plan 010 T7.1) and so
 * exists exactly when that pool does: when health OR the collector is enabled ({@link SshPoolConfiguration#NEEDED}, W3-10),
 * so switching health off does not withdraw SSH collection. It builds its own
 * {@link TablePlacementCatalog}, as the health prober does, so it does not depend on the production apply configuration
 * being switched on. The per-target reservation that keeps health from being starved is the collector's (P11-4.3); the GLOBAL one (W3-02) is the fetcher's own bound on its in-flight runs, the pool's {@code maxConcurrentProbes} less {@code sm.dp.metrics.collect.health-reserve}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({FetchProperties.class, CollectorProperties.class, SshSessionPoolProperties.class})
@Import({SshPoolConfiguration.class, TargetAddressConfiguration.class})
public class MetricsFetchConfiguration {

    @Bean(destroyMethod = "close")
    DirectHttpFetcher directHttpFetcher(TargetConnectionRows targetConnectionRows, FetchProperties properties,
            @Qualifier("smDataClock") Clock smDataClock, TargetAddressPolicy targetAddressPolicy) {
        return new DirectHttpFetcher(targetConnectionRows, properties, smDataClock, targetAddressPolicy);
    }

    @Bean
    @ConditionalOnExpression(SshPoolConfiguration.NEEDED)
    SshMetricFetcher sshMetricFetcher(SshSessionPool healthSshSessionPool, PlacementRows placementRows,
            TargetConnectionRows targetConnectionRows, InstanceRows instanceRows, FetchProperties properties,
            CollectorProperties collector, SshSessionPoolProperties sshPool, @Qualifier("smDataClock") Clock smDataClock) {
        // W3-02: the collector never holds more than the pool's probe slots less the health reserve, across ALL targets
        int runs = Math.max(1, sshPool.maxConcurrentProbes() - collector.healthReserve());
        return new SshMetricFetcher(new TablePlacementCatalog(placementRows, targetConnectionRows, instanceRows),
                healthSshSessionPool, properties, smDataClock, runs, sshPool.acquireTimeout(), healthSshSessionPool::evictIdle);
    }
}
