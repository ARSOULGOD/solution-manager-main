package com.solace.sm.dp.apply.service;

import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.persistence.ApplyObservationPersistenceConfiguration;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.worker.ApplyWorker;
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.inventory.PlacementRows;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The apply API's data-plane service as a bean (plan 010 T6.3). Imported by the data-plane aggregator, with the
 * journal read port it needs ({@link ApplyObservationPersistenceConfiguration}).
 *
 * <p>The executor is looked up lazily: with {@code sm.dp.apply.production.enabled=false} and none supplied there is
 * none, and a submission then fails loudly instead of leaving a row nothing can claim. The worker's {@code nudge()} is
 * latency only; the poll is what makes a {@code 202} a promise.
 */
@Configuration(proxyBeanMethods = false)
@Import(ApplyObservationPersistenceConfiguration.class)
public class ApplyServiceConfiguration {

    @Bean
    ApplyService applyService(ObjectProvider<ApplyExecutor> executor, PlacementRows placementRows,
            ApplyRequestRepository applyRequestRepository, ApplyObservationRows applyObservationRows,
            ConfigRecordService configRecordService, RollbackTargetRows rollbackTargetRows,
            ObjectProvider<ApplyWorker> worker) {
        return new ApplyServiceImpl(executor::getIfAvailable, placementRows, applyRequestRepository,
                applyObservationRows, configRecordService, new RollbackTargets(rollbackTargetRows),
                () -> worker.ifAvailable(ApplyWorker::nudge));
    }
}
