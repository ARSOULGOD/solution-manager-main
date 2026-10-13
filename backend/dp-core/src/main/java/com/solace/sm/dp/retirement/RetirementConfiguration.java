package com.solace.sm.dp.retirement;

import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.events.DeclarationsChangeSink;
import com.solace.sm.dp.apply.persistence.RetirementPersistenceConfiguration;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Undeclare's data-plane port as a bean (plan 012 T3). Imported by the data-plane aggregator with the persistence port it
 * needs ({@link RetirementPersistenceConfiguration}). The one bean is the {@code InstanceRetirementService} the gateway
 * composes in plan 012 T5; nothing consumes it before that, and nothing here starts a thread: the sweep is lazy and the
 * gateway's (a request-time call), so this configuration adds no loop.
 */
@Configuration(proxyBeanMethods = false)
@Import(RetirementPersistenceConfiguration.class)
public class RetirementConfiguration {

    @Bean
    InstanceRetirementServiceImpl instanceRetirementService(TransactionTemplate smDataTransactions,
            InstanceRows instanceRows, PlacementRows placementRows, RetirementRows retirementRows,
            @Qualifier("smDataClock") ObjectProvider<Clock> clock, ObjectProvider<ApplyEventEmitter> events) {
        ApplyEventEmitter emitter = events.getIfAvailable();
        // W4 lane D: a retirement transition changes what an agent placement declares; the emitter forwards to the declarations sink
        return new InstanceRetirementServiceImpl(smDataTransactions, instanceRows, placementRows, retirementRows,
                clock.getIfAvailable(Clock::systemUTC), java.util.UUID::randomUUID,
                emitter == null ? DeclarationsChangeSink.NONE : emitter::declarationsChanged);
    }
}
