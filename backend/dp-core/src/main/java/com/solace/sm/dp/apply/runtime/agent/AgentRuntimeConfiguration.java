package com.solace.sm.dp.apply.runtime.agent;

import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent path's in-memory machinery (plan 015 T3.1), imported by {@code ProductionApplyConfiguration}: the payload hand-off,
 * the keyed-hash bridge and the {@link AgentCommandPayloadSource} the command repository pulls from. There is no switch of its
 * own (global constraint 5): it exists whenever the production apply path does, and an agent that is never enrolled costs three
 * small objects.
 *
 * <p>{@code AgentRuntimeAdapter} itself is built inside {@code ProductionApplyConfiguration}'s one {@code RuntimeAdapter} bean,
 * beside the SSH-Docker adapter and the router, so that the process has exactly one adapter bean and one {@code ApplyExecutor}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfiguration {

    @Bean
    AgentPayloadHandoff agentPayloadHandoff(AgentRuntimeProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("smDataClock") java.time.Clock smDataClock) {
        return new AgentPayloadHandoff(properties.handoff().maxEntries(), smDataClock);
    }

    @Bean
    AgentHashBridge agentHashBridge(RenderedConfigSource renderedConfigSource, ConfigHasher configHasher,
            AgentConfigGenerations agentConfigGenerations) {
        return new AgentHashBridge(renderedConfigSource, configHasher, agentConfigGenerations);
    }

    @Bean
    AgentCommandPayloadSource agentCommandPayloadSource(AgentPayloadHandoff handoff, RenderedConfigSource renderedConfigSource,
            ConfigHasher configHasher, AgentConfigGenerations agentConfigGenerations, AgentHashBridge bridge) {
        return new HandoffAgentCommandPayloadSource(handoff, renderedConfigSource, configHasher, agentConfigGenerations, bridge);
    }
}
