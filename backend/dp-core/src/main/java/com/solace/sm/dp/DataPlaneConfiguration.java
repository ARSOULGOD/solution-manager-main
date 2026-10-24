package com.solace.sm.dp;

import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.events.ApplyEventsConfiguration;
import com.solace.sm.dp.apply.persistence.ApplyPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.BrokerConnectionPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.CollectPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.ConfigPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.ExportPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.HealthPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.HomePersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.IngestPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.InventoryPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.MetricDefinitionPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.MetricsPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.QueryPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.RollupPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.SecretPersistenceConfiguration;
import com.solace.sm.dp.apply.production.ProductionApplyConfiguration;
import com.solace.sm.dp.apply.reconcile.ReconcileConfiguration;
import com.solace.sm.dp.apply.runtime.readback.RuntimeReadBackConfiguration;
import com.solace.sm.dp.apply.service.ApplyServiceConfiguration;
import com.solace.sm.dp.apply.worker.ApplyWorkerConfiguration;
import com.solace.sm.dp.config.ConfigRecordConfiguration;
import com.solace.sm.dp.config.ConfigSecretConfiguration;
import com.solace.sm.dp.config.ObservedStateConfiguration;
import com.solace.sm.dp.fleet.FleetConfiguration;
import com.solace.sm.dp.health.HealthConfiguration;
import com.solace.sm.dp.home.HomeConfiguration;
import com.solace.sm.dp.inventory.InventoryConfiguration;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.configview.ConfigViewConfiguration;
import com.solace.sm.dp.logs.LogConfiguration;
import com.solace.sm.dp.logs.LogLevelConfiguration;
import com.solace.sm.dp.meshbridge.MeshBridgeConfiguration;
import com.solace.sm.dp.meshbridge.ingest.MeshbridgeIngestConfiguration;
import com.solace.sm.dp.metrics.MetricsConfiguration;
import com.solace.sm.dp.metrics.collect.MetricsCollectConfiguration;
import com.solace.sm.dp.metrics.collect.fetch.MetricsFetchConfiguration;
import com.solace.sm.dp.metrics.export.MetricsExportConfiguration;
import com.solace.sm.dp.metrics.ingest.MetricsIngestConfiguration;
import com.solace.sm.dp.metrics.push.MetricsPushConfiguration;
import com.solace.sm.dp.metrics.query.MetricsQueryConfiguration;
import com.solace.sm.dp.metrics.rollup.MetricsRollupConfiguration;
import com.solace.sm.dp.agent.AgentGatewayPortsConfiguration;
import com.solace.sm.dp.retirement.RetirementConfiguration;
import com.solace.sm.dp.schedule.ApplyScheduleConfiguration;
import com.solace.sm.dp.secret.SecretConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The data plane as ONE aggregator (plan 010 T2.6): the only thing the launcher imports for it, so a bean added to
 * the data plane reaches production through a line HERE — never through the launcher
 * ({@code SolutionManagerConfiguration} is single-owner, and {@code CompositionBoundaryTest} keeps it constructing
 * nothing). Mirrors {@code ControlPlaneConfiguration} on the other plane.
 *
 * <p>Each later DP task adds exactly one {@code @Import} line; a merge conflict here is a one-line rebase.
 *
 * <p><b>{@link MeshBridgeConfiguration} is imported with no default broker</b> (T3.3): {@code sm.mesh.enabled}
 * defaults to {@code false}, so a boot without {@code sm.mesh.*} is a declared degraded state (counted drops, one
 * WARN), never a refusal; enabled with a partial configuration still refuses.
 *
 * <p><b>{@link ProductionApplyConfiguration} is what makes the worker live</b> (T6.2): it supplies the one
 * {@code ApplyExecutor}, over SSH to Docker. {@code sm.dp.apply.production.enabled=false} withdraws it for a test that
 * brings its own; production sets nothing.
 *
 * <p><b>Importing {@link SecretConfiguration} makes the master key a precondition of the process.</b> The key is
 * read, and a canary row proves it, inside the {@code kekCanaryCheck} bean method, so a missing, malformed or wrong
 * key aborts context refresh naming the variable — before the HTTP connector opens and before the apply worker or the
 * startup sweep exists ({@link ApplyWorkerConfiguration} declares that ordering edge explicitly).
 */
@Configuration(proxyBeanMethods = false)
@Import({ApplyPersistenceConfiguration.class, SecretPersistenceConfiguration.class,
        InventoryPersistenceConfiguration.class, ApplyEngineConfiguration.class, ApplyWorkerConfiguration.class,
        SecretConfiguration.class, InventoryConfiguration.class, ConfigPersistenceConfiguration.class,
        ConfigRecordConfiguration.class, ObservedStateConfiguration.class, MeshBridgeConfiguration.class,
        MetricsPersistenceConfiguration.class, ProductionApplyConfiguration.class, ApplyServiceConfiguration.class,
        ApplyEventsConfiguration.class, ReconcileConfiguration.class, HealthPersistenceConfiguration.class,
        HealthConfiguration.class, ConfigSecretConfiguration.class, LogConfiguration.class,
        HomePersistenceConfiguration.class, HomeConfiguration.class, LogLevelConfiguration.class,
        RuntimeReadBackConfiguration.class, FleetConfiguration.class,
        MetricDefinitionPersistenceConfiguration.class, MetricsConfiguration.class,
        IngestPersistenceConfiguration.class, MetricsIngestConfiguration.class, RollupPersistenceConfiguration.class,
        MetricsRollupConfiguration.class, MetricsFetchConfiguration.class,
        ExportPersistenceConfiguration.class, MetricsExportConfiguration.class,
        CollectPersistenceConfiguration.class, MetricsCollectConfiguration.class,
        QueryPersistenceConfiguration.class, MetricsQueryConfiguration.class,
        BrokerConnectionPersistenceConfiguration.class, MeshbridgeIngestConfiguration.class,
        MetricsPushConfiguration.class, TargetAddressConfiguration.class, RetirementConfiguration.class,
        ApplyScheduleConfiguration.class, AgentGatewayPortsConfiguration.class, ConfigViewConfiguration.class})
public class DataPlaneConfiguration {
}
