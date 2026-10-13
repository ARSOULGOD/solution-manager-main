package com.solace.sm.cp.events;

import com.solace.sm.events.publisher.EventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stands in for the data plane's raw {@code meshEventPublisher} in a context that composes the control plane ALONE
 * (cp-core cannot see dp-core). The control plane REQUIRES that bean - a context without it refuses to start rather
 * than quietly dropping catalog notices - so every cp-core test that boots {@code ControlPlaneConfiguration} adds
 * this beside it. Discards everything; the launcher's ITs use the real bridge.
 */
@Configuration(proxyBeanMethods = false)
public class StubMeshPublisherConfiguration {

    @Bean(name = "meshEventPublisher", defaultCandidate = false)
    EventPublisher meshEventPublisher() {
        return event -> { };
    }
}
