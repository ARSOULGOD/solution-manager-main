package com.solace.sm.dp.apply.worker;

import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.persistence.ApplyClaimRepository;
import com.solace.sm.dp.apply.persistence.TenantEnumerationRepository;
import com.solace.sm.dp.apply.persistence.WorkerSingletonGuard;
import com.solace.sm.dp.apply.reconcile.IndeterminateReconciler;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.apply.runtime.agent.AgentRuntimeProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

/**
 * Wires Task 4a's durable worker and Task 4b's startup recovery sweep — dp-core's own
 * configuration, exactly like {@code ApplyPersistenceConfiguration} and
 * {@code ApplyEngineConfiguration}, and imported by the launcher the same way those two are.
 *
 * <p><b>Not in {@code com.solace.sm.launcher}</b>: see {@link ApplyWorker}'s javadoc. The launcher
 * imports this class by name; it never sees {@link ApplyExecutor} or a {@code DataSource} itself.
 *
 * <p>Both beans are created UNCONDITIONALLY, in every context — production included — and take an
 * {@link ObjectProvider}&lt;{@link ApplyExecutor}&gt; rather than requiring the bean directly.
 * Production supplies the {@code ApplyExecutor} since plan 010 T6.2
 * ({@code com.solace.sm.dp.apply.production.ProductionApplyConfiguration}, over SSH to Docker), which makes both
 * fully operational with no code change here. With no executor bean — a context that withdraws the production one
 * with {@code sm.dp.apply.production.enabled=false} and supplies none — both beans exist as inert
 * {@link org.springframework.context.SmartLifecycle}s whose {@code start()} logs a WARN and marks
 * the corresponding status bean, and do nothing else — no scheduler, no thread pool, no singleton
 * lock taken. A test configuration that supplies its own {@code ApplyExecutor} (over an in-memory
 * adapter) makes both fully operational the same way.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ApplyWorkerProperties.class, AgentRuntimeProperties.class})
public class ApplyWorkerConfiguration {

    @Bean
    ApplyWorkerStatus applyWorkerStatus() {
        return new ApplyWorkerStatus();
    }

    @Bean
    ApplyRecoveryStatus applyRecoveryStatus() {
        return new ApplyRecoveryStatus();
    }

    /**
     * No {@code initMethod}/{@code destroyMethod} here: {@link ApplyStartupRecovery} is a
     * {@link org.springframework.context.SmartLifecycle}, and Spring's own
     * {@code LifecycleProcessor} already calls {@code start()}/{@code stop()} once each during
     * context refresh/close — wiring BOTH would call {@code start()} twice.
     */
    @Bean
    @DependsOn("kekCanaryCheck")
    ApplyStartupRecovery applyStartupRecovery(ObjectProvider<ApplyExecutor> applyExecutor,
            TenantEnumerationRepository tenantEnumerationRepository,
            WorkerSingletonGuard workerSingletonGuard, ApplyRecoveryStatus applyRecoveryStatus,
            ObjectProvider<IndeterminateReconciler> indeterminateReconciler,
            ObjectProvider<AgentPlacementCatalog> agentPlacementCatalog, ObjectProvider<AgentSessionPort> agentSessionPort,
            ApplyWorkerProperties applyWorkerProperties) {
        // OR-146 (2): with the agent gateway's persistence present, an agent-target request waits (bounded) for its agent's
        // session. Without it nothing is on an agent target, and the sweep is exactly what it was.
        AgentPlacementCatalog catalog = agentPlacementCatalog.getIfAvailable();
        AgentSessionPort sessions = agentSessionPort.getIfAvailable();
        AgentSessionProbe probe = catalog == null || sessions == null ? null : AgentSessionProbe.over(catalog, sessions);
        return new ApplyStartupRecovery(applyExecutor, tenantEnumerationRepository,
                workerSingletonGuard, applyRecoveryStatus, indeterminateReconciler, probe,
                applyWorkerProperties.agentSessionGrace());
    }

    @Bean
    @DependsOn("kekCanaryCheck")
    ApplyWorker applyWorker(ObjectProvider<ApplyExecutor> applyExecutor,
            ApplyClaimRepository applyClaimRepository,
            TenantEnumerationRepository tenantEnumerationRepository,
            ApplyWorkerProperties applyWorkerProperties, WorkerSingletonGuard workerSingletonGuard,
            ApplyWorkerStatus applyWorkerStatus, ObjectProvider<IndeterminateReconciler> indeterminateReconciler,
            ObjectProvider<ApplyStartupRecovery> applyStartupRecovery, AgentRuntimeProperties agentRuntimeProperties,
            @Value("${sm.agent.gateway.enabled:false}") boolean agentGatewayEnabled) {
        // Boot refuses a cap that could not leave a thread for dp-remote (plan 015 T3.1, P15-6).
        int agentCap = applyWorkerProperties.agentCap(agentRuntimeProperties.maxConcurrentRequests(), agentGatewayEnabled);
        return new ApplyWorker(applyExecutor, applyClaimRepository, tenantEnumerationRepository,
                applyWorkerProperties, workerSingletonGuard, applyWorkerStatus, indeterminateReconciler, agentCap,
                applyStartupRecovery);
    }
}
