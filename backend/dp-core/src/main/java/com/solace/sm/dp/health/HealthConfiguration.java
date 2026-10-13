package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.ssh.DirectHttpProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPool;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.LadderRows;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The health read side and the poller (plan 010 T7.2), reached through the data-plane aggregator.
 *
 * <p>{@link HealthService} is always present (a read of persisted rows). The poller, its probe pool and its SSH session
 * pool exist unless {@code sm.dp.health.enabled=false}; the poller waits for the master-key canary
 * ({@code kekCanaryCheck}) exactly as the apply worker does, because probing over SSH resolves a credential. It builds
 * its own {@code SecretStoreCredentialSource}, so it does not depend on the production apply configuration being
 * switched on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({HealthPollerProperties.class, SshSessionPoolProperties.class})
@Import(SshPoolConfiguration.class)
public class HealthConfiguration {

    public static final String ENABLED = "sm.dp.health.enabled";

    @Bean
    HealthService healthService(PlacementRows placementRows, PlacementHealthRows placementHealthRows) {
        return new HealthServiceImpl(placementRows, placementHealthRows);
    }

    @Bean
    HealthRecorder healthRecorder(TransactionTemplate smDataTransactions, PlacementHealthRows placementHealthRows,
            ApplyEventEmitter applyEventEmitter, @Qualifier("smDataClock") Clock smDataClock) {
        return new HealthRecorder(smDataTransactions, placementHealthRows, applyEventEmitter, smDataClock);
    }

    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(name = ENABLED, havingValue = "true", matchIfMissing = true)
    ExecutorService healthProbeExecutor(HealthPollerProperties properties) {
        return Executors.newFixedThreadPool(properties.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "sm-health-probe");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The container-start observation the poller runs for placements waiting on one (OR-112 (1)). */
    @Bean
    @ConditionalOnProperty(name = ENABLED, havingValue = "true", matchIfMissing = true)
    StartObserver startObserver(SshSessionPool healthSshSessionPool, PlacementRows placementRows,
            TargetConnectionRows targetRows, InstanceRows instanceRows, LadderRows ladderRows,
            StartObservationRows startObservationRows, TransactionTemplate smDataTransactions,
            LadderRecorder ladderRecorder, ApplyEventEmitter applyEventEmitter, HealthPollerProperties properties,
            @Qualifier("smDataClock") Clock smDataClock) {
        return new StartObserver(healthSshSessionPool, new TablePlacementCatalog(placementRows, targetRows, instanceRows),
                ladderRows, startObservationRows, smDataTransactions, ladderRecorder, applyEventEmitter, smDataClock,
                properties.timeout());
    }

    @Bean
    @DependsOn("kekCanaryCheck")
    @ConditionalOnProperty(name = ENABLED, havingValue = "true", matchIfMissing = true)
    HealthPoller healthPoller(TenantEnumerationRepository tenantEnumerationRepository, InstanceRows instanceRows,
            PlacementRows placementRows, PlacementHealthRows placementHealthRows, TargetConnectionRows targetRows,
            SshSessionPool healthSshSessionPool, ExecutorService healthProbeExecutor, HealthRecorder healthRecorder,
            HealthPollerProperties properties, @Qualifier("smDataClock") Clock smDataClock, StartObserver startObserver,
            TargetAddressPolicy targetAddressPolicy, PlacementTransports placementTransports) {
        HealthProber prober = new HealthProber(healthSshSessionPool, new TablePlacementCatalog(placementRows, targetRows, instanceRows),
                targetRows, DirectHttpProbe.jdk(targetAddressPolicy), properties.timeout());
        return new HealthPoller(tenantEnumerationRepository, instanceRows, placementRows, placementHealthRows, prober,
                healthRecorder, properties, smDataClock, healthProbeExecutor, healthSshSessionPool::evictIdle,
                startObserver, placementTransports);
    }
}
