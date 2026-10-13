package com.solace.sm.dp.meshbridge.ingest;

import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The receive side of the broker push path (plan 011 P11-5.1; OR-107): the JCSMP subscriber factory and the subscription
 * manager, reached through the data-plane aggregator. JCSMP stays in {@code meshbridge}; the services that decide WHAT to
 * subscribe are {@code MetricsPushConfiguration}'s and see only {@link BrokerSubscriptions}. Registered whether or not push is
 * enabled: nothing here connects until a reconcile asks, and a Data Plane with no {@code broker_connection} row never asks.
 */
@Configuration(proxyBeanMethods = false)
public class MeshbridgeIngestConfiguration {

    @Bean
    SampleSubscriberFactory sampleSubscriberFactory(@Qualifier("smDataClock") Clock smDataClock) {
        return new JcsmpSampleSubscriberFactory(smDataClock);
    }

    @Bean(destroyMethod = "close")
    BrokerSubscriptionManager brokerSubscriptionManager(SecretResolver secretResolver,
            SampleSubscriberFactory sampleSubscriberFactory) {
        return new BrokerSubscriptionManager(secretResolver, sampleSubscriberFactory);
    }
}
