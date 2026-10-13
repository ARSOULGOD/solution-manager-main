package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentRegistrationCompletion;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.persistence.agent.AgentAuthority;
import com.solace.sm.dp.apply.persistence.agent.AgentGatewayPersistenceConfiguration;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.AckStartSettler;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentDiscoveryRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentTelemetryWatermarkRepository;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.health.StartObservationRows;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The agent-gateway ports of the data plane (plan 015 T2.1b, HF4), imported by {@code DataPlaneConfiguration}: the JDBC-backed
 * ports ({@link AgentGatewayPersistenceConfiguration}), the report intake, the observed state, the telemetry bridge and the
 * telemetry declarations (T3.3, T3.4) and the discovery intake (T4.1, which filled in the last contracted stub where it stood: no bean was
 * ever swapped and no task compiled against something a later task creates, Global constraint 11).
 *
 * <p>Property location, when a switch is needed: {@code dp.apply.persistence.agent}; there is no global agent switch (Global
 * constraint 5).
 */
@Configuration(proxyBeanMethods = false)
@Import(AgentGatewayPersistenceConfiguration.class)
public class AgentGatewayPortsConfiguration {

    /** The read side of {@code status} (T3.3): the latest accepted report, liveness read fresh. Implements {@code AgentObservedStatePort}. */
    @Bean
    AgentObservedState agentObservedStatePort(JdbcAgentReportRepository agentReportWatermarks) {
        return new AgentObservedState(agentReportWatermarks);
    }

    /** The {@code ActualStatePort} bean (T3.3): the watermark helper, the health and start tables and the ladder, in one transaction. */
    @Bean
    ActualStatePort agentReportIntake(JdbcAgentReportRepository agentReportWatermarks, AgentAuditPort agentAuditPort,
            HealthRecorder healthRecorder, PlacementHealthRows placementHealthRows, StartObservationRows startObservationRows,
            LadderRecorder ladderRecorder, ApplyEventEmitter applyEventEmitter, AgentObservedState agentObservedStatePort,
            TransactionTemplate smDataTransactions, @Qualifier("smDataClock") Clock smDataClock) {
        return new AgentReportIntake(agentReportWatermarks, agentAuditPort, healthRecorder, placementHealthRows,
                startObservationRows, ladderRecorder, applyEventEmitter, agentObservedStatePort, smDataTransactions, smDataClock);
    }

    /** AGW-D28 item 2: an acknowledged container start that moves the start observation settles the instance's ladder, in the ack's transaction. */
    @Bean
    AckStartSettler agentAckStartSettler(LadderRecorder ladderRecorder, ApplyEventEmitter applyEventEmitter) {
        return (tenantId, instanceId) -> applyEventEmitter.settleLadder(ladderRecorder,
                new TenantId(tenantId), new InstanceId(instanceId));
    }

    /** AGW-D28 item 1, the per-instance step of {@link AgentStalenessJob}; the window is {@code sm.dp.agent.report-staleness} (default 5 minutes). */
    @Bean
    AgentHealthStaleness agentHealthStaleness(JdbcAgentReportRepository agentReportWatermarks, HealthRecorder healthRecorder,
            @Qualifier("smDataClock") Clock smDataClock,
            @org.springframework.beans.factory.annotation.Value("${sm.dp.agent.report-staleness:5m}") java.time.Duration window) {
        return new AgentHealthStaleness(agentReportWatermarks, healthRecorder, smDataClock, window);
    }

    /**
     * Plan 015 W4 integration, review F1: the agent-target retire gate applies the control plane's revocation (an offline agent's
     * {@code revokeAgent} reaches only the control plane) before it answers. Set once on the inventory service; the bean is returned
     * only so the wiring is visible in the context.
     */
    @Bean
    InventoryService.AgentRevocation agentTargetRevocation(InventoryService inventoryService,
            com.solace.sm.dp.apply.persistence.agent.JdbcAgentIdentityRepository agentIdentityRepository) {
        InventoryService.AgentRevocation check = agentIdentityRepository::revokedApplyingTheControlPlane;
        inventoryService.agentRevocation(check);
        return check;
    }

    /**
     * OR-140 (plan 015 T4.6): the scheduled staleness verdict that replaced the lazy one on {@code getHealth}. Lives in this existing
     * configuration (no new {@code @Import}, HF4). {@code sm.dp.agent.staleness-job.enabled} (default true) and
     * {@code sm.dp.agent.staleness-job.poll-interval} (default 30 s).
     */
    @Bean
    AgentStalenessJob agentStalenessJob(JdbcAgentReportRepository agentReportWatermarks, AgentHealthStaleness agentHealthStaleness,
            @org.springframework.beans.factory.annotation.Value("${sm.dp.agent.staleness-job.enabled:true}") boolean enabled,
            @org.springframework.beans.factory.annotation.Value("${sm.dp.agent.staleness-job.poll-interval:PT30S}") java.time.Duration interval) {
        return new AgentStalenessJob(agentReportWatermarks, agentHealthStaleness, enabled, interval);
    }

    /**
     * T4.1 ({@code OR-27}): the discovery intake, filled in where the contracted stub stood. The gateway's two collaborators - the
     * package reader and the T4.2 completion sink - are optional here (a context with no console gateway builds an intake that
     * refuses an item needing the package {@code agent.discovery.unavailable}, audited and with no row, so it is retried, or leaves
     * items PENDING); the cap is
     * {@code sm.dp.agent.max-placements-per-target} (default 16, OR-128 (11)).
     */
    @Bean
    AgentDiscoveryPort agentDiscoveryPort(AgentAuthority agentAuthority, JdbcAgentDiscoveryRepository agentDiscoveryRepository,
            InventoryService inventoryService, AgentAuditPort agentAuditPort,
            ObjectProvider<PackageDeclarationSource> packageDeclarations, ObjectProvider<AgentRegistrationCompletion> completion,
            @Qualifier("smDataClock") Clock smDataClock,
            @Value("${sm.dp.agent.max-placements-per-target:16}") int maxPlacementsPerTarget,
            @Value("${sm.dp.declare.abandon-after:10m}") java.time.Duration abandonAfter,
            @Value("${sm.dp.agent.max-discovery-items-per-report:256}") int maxItemsPerReport) {
        return new AgentDiscoveryService(agentAuthority, agentDiscoveryRepository, inventoryService, agentAuditPort,
                packageDeclarations::getIfAvailable, completion::getIfAvailable, smDataClock, maxPlacementsPerTarget, abandonAfter,
                maxItemsPerReport);
    }

    /** T3.4: the placement's package metrics and probe, read from DP rows (it fills in what T2.1b's stub answered empty). */
    @Bean
    AgentTelemetryDeclarationsPort agentTelemetryDeclarationsPort(JdbcAgentTelemetryWatermarkRepository agentTelemetryWatermarks,
            IngestRows.Placements ingestPlacements, CollectionRows collectionRows,
            RestartObservedGenerations restartObservedGenerations, PlacementRows placementRows,
            ApplyEventEmitter applyEventEmitter) {
        AgentTelemetryDeclarationsService service = new AgentTelemetryDeclarationsService(agentTelemetryWatermarks,
                ingestPlacements, collectionRows, restartObservedGenerations, placementRows, new SpringTransactionBoundary());
        // W4 lane D (HF4, additive; HF18's signal): the ladder settle (landing, placement add/retire, agent start) tells the service,
        // which tells the gateway's subscription once the change committed.
        applyEventEmitter.declarationsSink(service);
        return service;
    }

    @Bean
    AgentTelemetryCounts agentTelemetryCounts() {
        return new AgentTelemetryCounts();
    }

    /** T3.4: the one {@code AgentTelemetryPort} bean; the watermark repository stays a persistence helper behind it. */
    @Bean
    AgentTelemetryPort agentTelemetryPort(JdbcAgentTelemetryWatermarkRepository agentTelemetryWatermarks,
            TelemetryIngestPort telemetryIngest, IngestRows.Counters ingestCounters, AgentTelemetryCounts agentTelemetryCounts,
            @Qualifier("smDataClock") Clock smDataClock) {
        return new AgentTelemetryBridge(agentTelemetryWatermarks, telemetryIngest, ingestCounters, agentTelemetryCounts,
                smDataClock);
    }
}
