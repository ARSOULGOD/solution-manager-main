package com.solace.sm.dp.fleet;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.GenerationBinding;
import com.solace.sm.dp.apply.persistence.FleetPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fleet apply as beans (plan 010 T11.2; {@code REQ-013}). Imported by the data-plane aggregator, with the persistence port
 * it needs ({@link FleetPersistenceConfiguration}). {@code FleetService} is the {@code FleetApplyService} the gateway
 * composes; {@code FleetScheduler} is the one advancer ({@code reachableInTheProductionBoot}: a scheduler that is not in the
 * boot leaves every fleet {@code RUNNING} forever).
 *
 * <p>The executor is looked up lazily, like {@code ApplyServiceConfiguration}'s: with
 * {@code sm.dp.apply.production.enabled=false} and none supplied there is none, a submit still records its fleet, and the
 * scheduler stays inert. The scheduler and the service depend on each other (the service wakes the scheduler, the scheduler
 * advances through the service); the wake is an {@link ObjectProvider} for that reason, resolved when it is called.
 */
@Configuration(proxyBeanMethods = false)
@Import(FleetPersistenceConfiguration.class)
@EnableConfigurationProperties(FleetProperties.class)
public class FleetConfiguration {

    @Bean
    FleetService fleetService(@Qualifier("smDataTransactions") TransactionTemplate smDataTransactions,
            FleetRows fleetRows, InstanceRows instanceRows, PlacementRows placementRows,
            ConfigGenerationRows configGenerationRows, ObjectProvider<ApplyExecutor> executor,
            ObjectProvider<FleetScheduler> scheduler, @Qualifier("smDataClock") Clock smDataClock,
            FleetProperties fleetProperties) {
        FleetService.Admitter admitter = (command, id) -> {
            ApplyExecutor resolved = executor.getIfAvailable();
            if (resolved == null) {
                throw new IllegalStateException(
                        "no ApplyExecutor is configured: an admitted request could never be claimed");
            }
            // bound to the config of record, like every API submission: a generation retired since the submit is a refusal
            // the fleet shows on its member, not a request nothing can render
            return resolved.admit(command, GenerationBinding.MUST_BE_STAGED, id);
        };
        return new FleetService(smDataTransactions, fleetRows, instanceRows, placementRows, configGenerationRows,
                admitter, smDataClock, fleetProperties.noValidation().settle(), () -> scheduler.ifAvailable(FleetScheduler::nudge));
    }

    @Bean
    FleetScheduler fleetScheduler(ObjectProvider<ApplyExecutor> executor,
            TenantEnumerationRepository tenantEnumerationRepository, FleetService fleetService,
            WorkerSingletonGuard workerSingletonGuard, FleetProperties fleetProperties) {
        return new FleetScheduler(() -> executor.getIfAvailable() != null, tenantEnumerationRepository, fleetService,
                workerSingletonGuard, fleetProperties);
    }
}
