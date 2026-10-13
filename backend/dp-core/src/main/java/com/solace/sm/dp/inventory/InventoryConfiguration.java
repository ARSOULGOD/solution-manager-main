package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The DP inventory service as a bean (plan 010 T2.5). Not auto-configured: {@code DataPlaneConfiguration} (T2.6)
 * imports it beside {@code InventoryPersistenceConfiguration}, which supplies the three persistence ports. The one
 * bean answers the {@code dp-api} ports ({@code InstanceInventoryService}, {@code TargetConnectionService} and, since
 * plan 013 T1.4, {@code TargetConnectionAdministration}, and since T1.5 {@code TargetConnectionManagement}); a consumer
 * injects the interface it needs. {@link TargetConnectionTestConfiguration} (T1.5) adds the connection test beside it.
 *
 * <p>It takes the {@code sm_data} {@link TransactionTemplate}, the {@link SecretStore} and the {@link SecretResolver}
 * (the same bean, {@code EnvelopeSecretStore}): the store is created only after the master-key canary has passed, so an
 * inventory write that stores a key can never run against a wrong master key, and the resolver opens a stored key for
 * the replay compare (plan 013 T1.1) inside this module and nowhere else.
 *
 * <p>{@link DeclareSweepProperties} ({@code sm.dp.declare.abandon-after}, T4.2) is bound here, beside the service whose
 * {@code settlePending} takes that window.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeclareSweepProperties.class)
@Import({TargetConnectionTestConfiguration.class, ConnectionCredentialConfiguration.class})
public class InventoryConfiguration {

    @Bean
    InventoryService inventoryService(TransactionTemplate smDataTransactions, InstanceRows instanceRows,
            PlacementRows placementRows, TargetConnectionRows targetConnectionRows, SecretStore secretStore,
            SecretResolver secretResolver, @Qualifier("smDataClock") ObjectProvider<Clock> clock,
            MetricDeclarationRows metricDeclarationRows, ObjectProvider<LadderRecorder> ladderRecorder,
            ObjectProvider<ApplyEventEmitter> applyEventEmitter, ObjectProvider<HostKeyScanService> hostKeyScanService) {
        // The ladder and the emitter are the landing path's own beans (plan 008 S3, OQ-8.10 (a)): a placement change
        // settles the rung through the same function. A context without them (a narrow IT) builds a service that settles
        // nothing, exactly as the apply engine does.
        return new InventoryService(smDataTransactions, instanceRows, placementRows, targetConnectionRows,
                secretStore, secretResolver, clock.getIfAvailable(Clock::systemUTC), metricDeclarationRows,
                ladderRecorder.getIfAvailable(LadderRecorder::none),
                applyEventEmitter.getIfAvailable(ApplyEventEmitter::silent),
                // The registration's pre-check scans the host (plan 013 T1.4). DataPlaneConfiguration imports the scan
                // service's configuration; a narrow context without it builds a service whose pre-check refuses to run.
                hostKeyScanService.getIfAvailable());
    }

    /** What an instance-wide stop addresses (plan 008 S3, BE-D46): the registry's live placements. */
    @Bean
    LivePlacements livePlacements(PlacementRows placementRows) {
        return new RegistryLivePlacements(placementRows);
    }
}
