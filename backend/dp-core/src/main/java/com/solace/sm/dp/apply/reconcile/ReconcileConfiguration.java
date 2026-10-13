package com.solace.sm.dp.apply.reconcile;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.persistence.JdbcReconciliationRepository;
import com.solace.sm.dp.apply.persistence.ReconcilePersistenceConfiguration;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Wires verify-then-retry (plan 010 T6.8): the reconciler's bounds ({@code sm.dp.apply.reconcile.*}), its persistence, and
 * the reconciler itself. Imported by {@code DataPlaneConfiguration} - one line there - and used by {@code ApplyWorker} (the
 * poll runs due reconciliations) and {@code ApplyStartupRecovery} (boot enqueues, never blocks).
 *
 * <p><b>The reconciler exists exactly when an {@code ApplyExecutor} does.</b> It reaches a host only through the executor.
 * With no executor bean - a context that withdraws the production one and supplies none - the {@code @Bean} method below
 * returns {@code null}, which Spring registers as an absent bean: {@code ObjectProvider.getIfAvailable()} then yields
 * {@code null}, and the worker and the sweep skip reconciliation, exactly as they skip everything else without an executor.
 */
@Configuration(proxyBeanMethods = false)
@Import(ReconcilePersistenceConfiguration.class)
@EnableConfigurationProperties(ReconcileProperties.class)
public class ReconcileConfiguration {

    @Bean
    IndeterminateReconciler indeterminateReconciler(ObjectProvider<ApplyExecutor> applyExecutor,
            OutcomeService outcomeService, JdbcReconciliationRepository reconciliationRepository,
            ReconcileProperties reconcileProperties, Clock smDataClock) {
        ApplyExecutor executor = applyExecutor.getIfAvailable();
        if (executor == null) {
            return null;
        }
        return new IndeterminateReconciler(executor, outcomeService, reconciliationRepository, reconcileProperties,
                smDataClock);
    }
}
