package com.solace.sm.launcher;

import com.solace.sm.agw.AgentGatewayMetrics;
import com.solace.sm.dp.agent.AgentTelemetryCounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the data plane's in-memory agent-telemetry counts ({@link AgentTelemetryCounts}) to the gateway's meter registry (plan 015
 * W4 lane D; the T3.4 owed item (5)). Measured first: no other dp-core count is bound to a meter, dp-core carries no metrics library
 * (HF13: no pom changes this wave), and the only registry in the reactor is the one {@link AgentGatewayMetrics} holds (the Spring
 * one when present, else a private simple one). The launcher is the composition root that may see both, so it does the binding; the
 * names are the closed constants of {@link AgentTelemetryCounts#NAMES} (dots to underscores, a {@code dp_} prefix and {@code _total}:
 * {@code dp_agent_telemetry_rejected_total} counts the DATA PLANE's permanent refusals of a batch, where the gateway's own
 * {@code agw_telemetry_rejected_total} counts streams it refused to open, so the two never share a name), so nothing an agent
 * sends ever becomes a meter name or tag (I6). Present only when the gateway is composed.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sm.agent.gateway", name = "enabled", havingValue = "true")
class AgentTelemetryMetersConfiguration {

    /** The meter a count is exposed as. */
    static String meterName(String countName) {
        return "dp_" + countName.replace('.', '_') + "_total";
    }

    @Bean
    AgentTelemetryMeters agentTelemetryMeters(AgentGatewayMetrics metrics, AgentTelemetryCounts counts) {
        for (String name : AgentTelemetryCounts.NAMES) {
            metrics.bindCount(meterName(name), counts, c -> c.get(name));   // the bean is the weakly held source
        }
        return new AgentTelemetryMeters();
    }

    /** The marker bean: the binding happened once, at composition. */
    static final class AgentTelemetryMeters {
    }
}
