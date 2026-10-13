package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptionManager;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The services of the broker push path (plan 011 P11-5.1; OR-107), built from interface types only: the {@code Jdbc*} beans come
 * from {@code BrokerConnectionPersistenceConfiguration} and {@code CollectPersistenceConfiguration}, the broker session from
 * {@code MeshbridgeIngestConfiguration}. Imported by {@code DataPlaneConfiguration}; the metrics loop picks the
 * {@link PushIngestService} up as its {@code PushReconcile} step.
 *
 * <p>{@code sm.dp.metrics.push.enabled=false} withdraws it. With no {@code broker_connection} row, or no instance declaring a
 * {@code solace_event} metric, an enabled push path subscribes to nothing and holds no session.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties.class)
@ConditionalOnProperty(name = "sm.dp.metrics.push.enabled", havingValue = "true", matchIfMissing = true)
public class MetricsPushConfiguration {

    @Bean
    PushSubscriptionPlan pushSubscriptionPlan(InstanceRows instanceRows, CollectionRows collectionRows,
            RestartObservedGenerations restartObservedGenerations, ConfigGenerationRows configGenerationRows,
            PlacementRows placementRows, BrokerConnectionRows brokerConnectionRows) {
        return new PushSubscriptionPlan(new PushRowsAdapter(instanceRows, collectionRows, restartObservedGenerations,
                configGenerationRows, placementRows, brokerConnectionRows));
    }

    @Bean
    PushAttribution pushAttribution(PushProperties properties) {
        return new PushAttribution(new BrokerSampleDecoder(properties.maxBodyBytes()));
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    PushIngestService pushIngestService(PushSubscriptionPlan plan, BrokerSubscriptionManager subscriptions,
            PushAttribution attribution, TelemetryIngestPort telemetryIngest, BrokerCounterRows brokerCounterRows,
            PushProperties properties, @Qualifier("smDataClock") Clock smDataClock) {
        return new PushIngestService(plan, subscriptions, attribution, telemetryIngest, brokerCounterRows, properties,
                smDataClock);
    }
}
