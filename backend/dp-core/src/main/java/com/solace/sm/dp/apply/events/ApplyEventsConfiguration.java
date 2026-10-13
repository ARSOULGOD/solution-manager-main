package com.solace.sm.dp.apply.events;

import com.solace.sm.events.publisher.EventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The apply-side emitter as a bean (plan 010 T6.4). It is built over the data plane's primary {@link EventPublisher}
 * - after-commit ordering over the one raw mesh transport ({@code MeshBridgeConfiguration}) - injected by interface
 * type, so this package holds no broker type. {@code ApplyEngineConfiguration} hands the emitter to the engine
 * services; reached through {@code DataPlaneConfiguration}, never through the launcher.
 */
@Configuration(proxyBeanMethods = false)
public class ApplyEventsConfiguration {

    @Bean
    ApplyEventEmitter applyEventEmitter(EventPublisher eventPublisher,
            @Qualifier("smDataClock") Clock smDataClock) {
        return new ApplyEventEmitter(eventPublisher, smDataClock, UUID::randomUUID);
    }
}
