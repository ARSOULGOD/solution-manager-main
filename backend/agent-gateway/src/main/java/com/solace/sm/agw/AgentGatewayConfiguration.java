package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent-facing gateway as beans (plan 015 T3.2), imported by the launcher alone. It is composed only when
 * {@code sm.agent.gateway.enabled=true}: an installation that manages hosts over {@code dp-remote} only opens no port, builds no
 * session pool and needs no certificate, keystore or agent port set. When it is composed, {@link AgentGatewayServer} fails the
 * context at start if the TLS material is missing (it never starts a listener it cannot secure).
 *
 * <p>Every port is required, the report and telemetry ports included (T3.3's {@code AgentReportIntake} and T3.4's
 * {@code AgentTelemetryBridge} since the W3 integration): a missing port fails the context, never a silent stand-in.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "sm.agent.gateway", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentGatewayProperties.class)
public class AgentGatewayConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService agentGatewayScheduler() {
        return Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "agent-gateway");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    AgentGatewayMetrics agentGatewayMetrics(ObjectProvider<MeterRegistry> registries) {
        return new AgentGatewayMetrics(registries.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    AgentGatewayPortSet agentGatewayPortSet(AgentEnrollmentPort enrollment, AgentIdentityPort identity,
            AgentSessionPort sessions, AgentLeasePort leases, AgentCommandPort commands,
            ActualStatePort reports, AgentTelemetryPort telemetry, AgentAuditPort audit,
            AgentDiscoveryPort discovery, AgentTelemetryDeclarationsPort declarations,
            ObjectProvider<AgentLogRetentionSource> logRetention) {
        // The data plane supplies the bean (JdbcPlacementLogRetentionRepository); a context without one has no stored cap, so nothing is
        // pushed at session open. This is the one port that is not required, because it carries no safety property: a missing push
        // leaves the agent's default.
        return new AgentGatewayPortSet(enrollment, identity, sessions, leases, commands, reports, telemetry, audit, discovery,
                declarations, logRetention.getIfAvailable(() -> AgentLogRetentionSource.NONE));
    }

    @Bean
    AgentGatewayService agentGatewayService(AgentGatewayPortSet ports, AgentGatewayProperties properties,
            ScheduledExecutorService agentGatewayScheduler, AgentGatewayMetrics metrics) {
        return new AgentGatewayService(ports, properties, agentGatewayScheduler, metrics);
    }

    @Bean
    AgentGatewayManagementServer agentGatewayManagementServer(AgentGatewayProperties properties,
            AgentGatewayServer server, AgentGatewayService service, AgentGatewayMetrics metrics) {
        return new AgentGatewayManagementServer(properties, server, service, metrics);
    }

    @Bean
    AgentGatewayServer agentGatewayServer(AgentGatewayProperties properties, AgentGatewayService service,
            ScheduledExecutorService agentGatewayScheduler) {
        return new AgentGatewayServer(properties, service, agentGatewayScheduler);
    }

    /** What the data plane's log reads call (plan 016 task 7): the gateway implements the port, the live sessions are this process's. */
    @Bean
    AgentLogPort agentLogPort(AgentGatewayService service) {
        return service.logPort();
    }

    /** What the console's {@code drainAgent} calls (plan 015 T4.3): the live session map is this process's, so the seam is a port. */
    @Bean
    com.solace.sm.dp.api.agw.AgentDrainPort agentDrainRequester(AgentGatewayService service) {
        return service::drainAgent;
    }
}
