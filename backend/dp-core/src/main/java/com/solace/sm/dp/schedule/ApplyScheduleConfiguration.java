package com.solace.sm.dp.schedule;

import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplySchedulePersistenceConfiguration;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled applies' data-plane port as a bean (plan 012 T7). Imported by the data-plane aggregator with the persistence
 * port it needs ({@link ApplySchedulePersistenceConfiguration}). The one bean is the {@link ApplySchedulePort} the gateway
 * composes; nothing here starts a thread - the loop is the gateway's {@code ScheduleFirer} ({@code OPS-Q10} (c)), which
 * is why this plane gains no cross-tenant caller: the firer is the one caller of the enumeration.
 */
@Configuration(proxyBeanMethods = false)
@Import(ApplySchedulePersistenceConfiguration.class)
@EnableConfigurationProperties(ApplyScheduleProperties.class)
public class ApplyScheduleConfiguration {

    @Bean
    ApplySchedulePort applySchedulePort(@Qualifier("smDataTransactions") TransactionTemplate smDataTransactions,
            ScheduleRows scheduleRows, ScheduleTargets scheduleTargets, ApplyService applyService,
            RollbackTargetRows rollbackTargetRows, ApplyScheduleProperties properties,
            @Qualifier("smDataClock") Clock smDataClock) {
        return new ApplyScheduleServiceImpl(smDataTransactions, scheduleRows, scheduleTargets, applyService,
                new RollbackTargets(rollbackTargetRows), properties, smDataClock, UUID::randomUUID);
    }

    /** The questions a submit asks and a fire reconciles with, answered from the registry, the config of record and the journal. */
    @Bean
    ScheduleTargets scheduleTargets(InstanceRows instanceRows, PlacementRows placementRows,
            ConfigGenerationRows configGenerationRows, ApplyRequestRepository applyRequests,
            com.solace.sm.dp.apply.persistence.ApplyScopeRepository scopes) {
        return new RowScheduleTargets(instanceRows, placementRows, configGenerationRows, applyRequests, scopes);
    }
}
