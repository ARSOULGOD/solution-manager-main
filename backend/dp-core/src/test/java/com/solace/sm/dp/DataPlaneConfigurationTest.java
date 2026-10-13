package com.solace.sm.dp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.events.ApplyEventsConfiguration;
import com.solace.sm.dp.apply.persistence.ApplyPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.ConfigPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.ExportPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.HealthPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.HomePersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.IngestPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.InventoryPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.MetricDefinitionPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.MetricsPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.BrokerConnectionPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.CollectPersistenceConfiguration;
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
import com.solace.sm.dp.retirement.RetirementConfiguration;
import com.solace.sm.dp.schedule.ApplyScheduleConfiguration;
import com.solace.sm.dp.secret.SecretConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;

/** Plan 010 T2.6: the data plane as ONE aggregator, and the one ordering edge the aggregator makes necessary. */
class DataPlaneConfigurationTest {

    @Test
    void theAggregatorImportsEveryDataPlaneConfiguration() {
        Import imported = DataPlaneConfiguration.class.getAnnotation(Import.class);

        assertNotNull(imported);
        assertEquals(Set.of(ApplyPersistenceConfiguration.class, SecretPersistenceConfiguration.class,
                        InventoryPersistenceConfiguration.class, ApplyEngineConfiguration.class,
                        ApplyWorkerConfiguration.class, SecretConfiguration.class, InventoryConfiguration.class,
                        ConfigPersistenceConfiguration.class, ConfigRecordConfiguration.class,
                        ObservedStateConfiguration.class, MeshBridgeConfiguration.class,
                        MetricsPersistenceConfiguration.class, ProductionApplyConfiguration.class,
                        ApplyServiceConfiguration.class, ApplyEventsConfiguration.class, ReconcileConfiguration.class,
                        HealthPersistenceConfiguration.class, HealthConfiguration.class,
                        ConfigSecretConfiguration.class, LogConfiguration.class,
                        HomePersistenceConfiguration.class, HomeConfiguration.class, LogLevelConfiguration.class,
                        RuntimeReadBackConfiguration.class, FleetConfiguration.class,
                        MetricDefinitionPersistenceConfiguration.class, MetricsConfiguration.class,
                        IngestPersistenceConfiguration.class, MetricsIngestConfiguration.class, RollupPersistenceConfiguration.class,
                        MetricsRollupConfiguration.class, MetricsFetchConfiguration.class,
                        ExportPersistenceConfiguration.class, MetricsExportConfiguration.class,
                        CollectPersistenceConfiguration.class, MetricsCollectConfiguration.class,
                        QueryPersistenceConfiguration.class, MetricsQueryConfiguration.class,
                        BrokerConnectionPersistenceConfiguration.class, MeshbridgeIngestConfiguration.class,
                        MetricsPushConfiguration.class, TargetAddressConfiguration.class,
                        RetirementConfiguration.class, ApplyScheduleConfiguration.class,
                        com.solace.sm.dp.agent.AgentGatewayPortsConfiguration.class),
                Set.copyOf(Arrays.asList(imported.value())));
    }

    /**
     * T2.4 left these two beans without the edge ("the launcher's to add"). Importing SecretConfiguration makes the
     * master-key canary a precondition of the process; the durable worker and the startup sweep, which act on hosts,
     * must not exist before it has passed.
     */
    @Test
    void theWorkerAndTheStartupSweepDependOnTheMasterKeyCanaryCheck() {
        for (String bean : List.of("applyWorker", "applyStartupRecovery")) {
            Method method = Arrays.stream(ApplyWorkerConfiguration.class.getDeclaredMethods())
                    .filter(m -> m.getName().equals(bean)).findFirst().orElseThrow();
            DependsOn dependsOn = method.getAnnotation(DependsOn.class);
            assertNotNull(dependsOn, bean + " must declare @DependsOn(\"kekCanaryCheck\")");
            assertTrue(Arrays.asList(dependsOn.value()).contains("kekCanaryCheck"), bean);
        }
    }

    /**
     * The launcher holds two {@code Clock} beans; an unqualified {@code ObjectProvider<Clock>} in any data-plane
     * configuration fails the boot with NoUniqueBeanDefinitionException. T2.6 qualified the clocks that existed;
     * T5.1 and T5.2 added two more on a branch that had not seen the ambiguity. (ApplyEngineConfiguration defines
     * the bean and takes it by parameter name.)
     */
    @Test
    void everyDataPlaneClockLookupIsQualifiedToSmDataClock() {
        for (Class<?> config : List.of(SecretConfiguration.class, InventoryConfiguration.class,
                ConfigRecordConfiguration.class, ApplyEventsConfiguration.class, HealthConfiguration.class,
                ConfigSecretConfiguration.class, MetricsFetchConfiguration.class, MetricsCollectConfiguration.class, MetricsQueryConfiguration.class,
                com.solace.sm.dp.apply.persistence.agent.AgentGatewayPersistenceConfiguration.class)) {
            for (Method method : config.getDeclaredMethods()) {
                if (method.getAnnotation(Bean.class) == null) {
                    continue;
                }
                for (Parameter parameter : method.getParameters()) {
                    boolean clock = parameter.getType() == Clock.class
                            || (parameter.getType() == ObjectProvider.class
                            && parameter.getParameterizedType().getTypeName().contains(Clock.class.getName()));
                    if (!clock) {
                        continue;
                    }
                    Qualifier qualifier = parameter.getAnnotation(Qualifier.class);
                    assertNotNull(qualifier, config.getSimpleName() + "." + method.getName()
                            + " takes an unqualified Clock");
                    assertEquals("smDataClock", qualifier.value(), config.getSimpleName() + "." + method.getName());
                }
            }
        }
    }
}
