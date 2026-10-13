package com.solace.sm.dp.apply.production;

import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentObservedStatePort;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RoutingRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.apply.runtime.agent.AgentConfigGenerations;
import com.solace.sm.dp.apply.runtime.agent.AgentHashBridge;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.apply.runtime.agent.AgentPayloadHandoff;
import com.solace.sm.dp.apply.runtime.agent.AgentRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.agent.AgentRuntimeConfiguration;
import com.solace.sm.dp.apply.runtime.agent.AgentRuntimeProperties;
import com.solace.sm.dp.apply.runtime.ssh.HostKeyTypeRecorder;
import com.solace.sm.dp.apply.runtime.ssh.SshCredentialSource;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerRuntimeAdapter;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.metrics.collect.RestartObservedGenerations;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The production apply path (plan 010 T6.2): a {@link RuntimeAdapter} over SSH to Docker, a {@link RenderedConfigSource}
 * over the config of record, and the {@link ApplyExecutor} built from the engine beans {@code ApplyEngineConfiguration}
 * already publishes. With an executor present, {@code ApplyWorkerConfiguration}'s worker and startup sweep become live
 * with no change there; this is the one place that gives them something to run.
 *
 * <p>Everything is reached through {@code DataPlaneConfiguration}; the launcher constructs none of it
 * ({@code CompositionBoundaryTest}).
 *
 * <p><b>The switch.</b> {@code sm.dp.apply.production.enabled} defaults to {@code true}, so production sets nothing. The
 * seven launcher ITs that bring their own executor, adapter and config source over an in-memory adapter set it
 * {@code false}: two {@code ApplyExecutor} beans would make the worker's {@code ObjectProvider<ApplyExecutor>}
 * ambiguous. The condition is evaluated while this class is registered through {@code @Import}, before a test's own
 * {@code @TestConfiguration} beans exist (plan 010 M16, ii; measured by those ITs).
 *
 * <p><b>One adapter bean, one executor (plan 015 T3.1).</b> The adapter built here is {@link RoutingRuntimeAdapter}: it sends each
 * placement to {@link SshDockerRuntimeAdapter} or to {@link AgentRuntimeAdapter} by the transport of the placement's target,
 * read from the tables per call. There is no global agent switch and no second {@code ApplyExecutor}; the architecture inventory
 * certifies all four classes.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ProductionApplyConfiguration.ENABLED, havingValue = "true", matchIfMissing = true)
@Import({TargetAddressConfiguration.class, AgentRuntimeConfiguration.class})
public class ProductionApplyConfiguration {

    public static final String ENABLED = "sm.dp.apply.production.enabled";

    /** The SSH key comes from the secret store, for the placement's own tenant, for one use (T6.1's seam). */
    @Bean
    SshCredentialSource sshCredentialSource(SecretResolver secretResolver) {
        return new SecretStoreCredentialSource(secretResolver);
    }

    /** {@code (tenant, instance, placement)} to an SSH binding, read from the tables on every lookup. */
    @Bean
    SshDockerPlacementCatalog sshDockerPlacementCatalog(PlacementRows placementRows,
            TargetConnectionRows targetConnectionRows, InstanceRows instanceRows) {
        return new TablePlacementCatalog(placementRows, targetConnectionRows, instanceRows);
    }

    /**
     * The ONE {@link RuntimeAdapter} bean (plan 015 T3.1): a router that sends each placement to the adapter of the transport its
     * target uses, per call, from the tables. There is no global switch and no second {@code ApplyExecutor}: dp-remote and agent
     * placements run in one process. Both adapters are built here and are not beans of their own, so nothing else can inject
     * "the SSH adapter" or "the agent adapter" and bypass the routing.
     */
    @Bean
    RuntimeAdapter runtimeAdapter(SshDockerPlacementCatalog sshDockerPlacementCatalog,
            SshCredentialSource sshCredentialSource, ConfigHasher configHasher,
            TargetAddressPolicy targetAddressPolicy, TargetConnectionRows targetConnectionRows,
            AgentPlacementCatalog agentPlacementCatalog, PlacementTransports placementTransports, AgentCommandPort agentCommandPort,
            AgentSessionPort agentSessionPort, AgentObservedStatePort agentObservedStatePort,
            AgentPayloadHandoff agentPayloadHandoff, AgentHashBridge agentHashBridge,
            AgentRuntimeProperties agentRuntimeProperties, @Qualifier("smDataClock") Clock smDataClock) {
        RuntimeAdapter sshDocker = new SshDockerRuntimeAdapter(sshDockerPlacementCatalog, sshCredentialSource, configHasher,
                targetAddressPolicy, HostKeyTypeRecorder.over(targetConnectionRows));
        RuntimeAdapter agent = new AgentRuntimeAdapter(agentPlacementCatalog, agentCommandPort, agentSessionPort,
                agentObservedStatePort, configHasher, agentPayloadHandoff, agentHashBridge, smDataClock,
                AgentRuntimeAdapter.Settings.defaults(agentRuntimeProperties.readbackWait()));
        return new RoutingRuntimeAdapter(placementTransports, sshDocker, agent);
    }

    /** The two journal questions the agent path asks: see {@link ProductionAgentConfigGenerations}. */
    @Bean
    AgentConfigGenerations agentConfigGenerations(OutcomeService outcomeService, ConfigGenerationRows configGenerationRows,
            RestartObservedGenerations restartObservedGenerations, com.solace.sm.dp.config.LadderRows ladderRows) {
        return new ProductionAgentConfigGenerations(outcomeService, configGenerationRows, restartObservedGenerations, ladderRows);
    }

    /** Rebuilds generation {@code G}'s bytes from the generation row and the secret store alone. */
    @Bean
    RenderedConfigSource renderedConfigSource(ConfigGenerationRows configGenerationRows,
            SecretResolver secretResolver, ConfigHasher configHasher) {
        return new ConfigGenerationRenderedConfigSource(configGenerationRows, secretResolver, configHasher);
    }

    @Bean
    ApplyExecutor applyExecutor(AdmissionService admissionService, StepIntentService stepIntentService,
            AttemptService attemptService, OutcomeService outcomeService, RuntimeAdapter runtimeAdapter,
            RenderedConfigSource renderedConfigSource, HealthGate healthGate, ResumePlanner resumePlanner,
            ApplyEngineProperties applyEngineProperties, ApplyEventEmitter applyEventEmitter,
            LivePlacements livePlacements) {
        return new ApplyExecutor(admissionService, stepIntentService, attemptService, outcomeService,
                runtimeAdapter, renderedConfigSource, healthGate, resumePlanner, applyEngineProperties,
                applyEventEmitter, livePlacements);
    }
}
