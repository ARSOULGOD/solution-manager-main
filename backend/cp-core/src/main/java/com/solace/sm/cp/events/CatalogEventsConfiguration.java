package com.solace.sm.cp.events;

import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.ResourceTransactionManager;

/**
 * The control plane's side of the mesh publish path (plan 010 T3.3): ONE shared raw transport, TWO plane-local
 * after-commit wrappers.
 *
 * <ul>
 *   <li>The raw transport is the data plane's {@code meshEventPublisher} bean - the only broker session and the only
 *       drop counter. This class receives it as {@code @Qualifier("meshEventPublisher") EventPublisher}: typed by the
 *       {@code mesh-events} interface, so {@code cpNeverImportsDp} and {@code onlyMeshbridgeTouchesJcsmp} hold. The
 *       qualified injection of a {@code defaultCandidate = false} bean is the shape {@code MeshBridgeConfiguration}
 *       measured to resolve.</li>
 *   <li>The wrapper is this plane's own {@link AfterCommitEventPublisher} over a {@link CpTransactionBoundary}, so
 *       "after commit" means after the CONTROL plane's commit - not the data plane's wrapper, whose boundary the
 *       control plane does not own and cannot reason about.</li>
 * </ul>
 *
 * <p>The wrapper is {@code defaultCandidate = false}: it is explicitly qualified wherever it is wanted, and an
 * unqualified {@code List<EventPublisher>} must keep seeing exactly the data plane's primary composition (a second
 * default candidate would let a fan-out service double-publish).
 *
 * <p>The raw bean is REQUIRED: a context that imports the control plane without the data plane's mesh bridge has no
 * publisher and refuses to start naming {@code meshEventPublisher}, rather than quietly dropping catalog notices.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogEventsConfiguration {

    static final String CP_PUBLISHER = "cpEventPublisher";

    @Bean(name = CP_PUBLISHER, defaultCandidate = false)
    EventPublisher cpEventPublisher(@Qualifier("meshEventPublisher") EventPublisher meshEventPublisher,
            @Qualifier("smControlTransactionManager") ResourceTransactionManager smControlTransactionManager) {
        return new AfterCommitEventPublisher(meshEventPublisher, new CpTransactionBoundary(smControlTransactionManager));
    }

    @Bean
    CatalogEventEmitter catalogEventEmitter(@Qualifier(CP_PUBLISHER) EventPublisher cpEventPublisher,
            @Qualifier("smControlClock") Clock smControlClock) {
        return new CatalogEventEmitter(cpEventPublisher, smControlClock, UUID::randomUUID);
    }
}
