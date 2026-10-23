package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

/** Plan 015 T3.2: what the configuration composes, and when. */
class AgentGatewayConfigurationTest {

    private static <T> T inert(Class<T> port) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (proxy, method, args) -> null));
    }

    @Configuration(proxyBeanMethods = false)
    static class EightPorts {
        @Bean AgentEnrollmentPort enrollment() { return inert(AgentEnrollmentPort.class); }
        @Bean AgentIdentityPort identity() { return inert(AgentIdentityPort.class); }
        @Bean AgentSessionPort sessions() { return inert(AgentSessionPort.class); }
        @Bean AgentLeasePort leases() { return inert(AgentLeasePort.class); }
        @Bean AgentCommandPort commands() { return inert(AgentCommandPort.class); }
        @Bean AgentAuditPort audit() { return inert(AgentAuditPort.class); }
        @Bean AgentDiscoveryPort discovery() { return inert(AgentDiscoveryPort.class); }
        @Bean AgentTelemetryDeclarationsPort declarations() { return inert(AgentTelemetryDeclarationsPort.class); }
    }

    @Configuration(proxyBeanMethods = false)
    static class RealReportAndTelemetryPorts {
        static final ActualStatePort REPORTS = inert(ActualStatePort.class);
        static final AgentTelemetryPort TELEMETRY = inert(AgentTelemetryPort.class);
        @Bean ActualStatePort reports() { return REPORTS; }
        @Bean AgentTelemetryPort telemetry() { return TELEMETRY; }
    }

    private static AnnotationConfigApplicationContext context(String enabled, Class<?>... extra) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                "sm.agent.gateway.enabled", enabled)));
        context.register(EightPorts.class, RealReportAndTelemetryPorts.class, AgentGatewayConfiguration.class);
        if (extra.length > 0) context.register(extra);
        return context;
    }

    @Test
    void notComposedAtAllWhenTheListenerIsNotEnabled() {
        try (AnnotationConfigApplicationContext context = context("false")) {
            context.refresh();
            assertEquals(0, context.getBeansOfType(AgentGatewayServer.class).size());
            assertEquals(0, context.getBeansOfType(AgentGatewayService.class).size());
        }
    }

    @Test
    void anEnabledListenerWithoutTlsMaterialFailsTheContextInsteadOfStartingUnsecured() {
        try (AnnotationConfigApplicationContext context = context("true")) {
            RuntimeException failure = assertThrows(org.springframework.context.ApplicationContextException.class, context::refresh);
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertTrue(root.getMessage().contains("TLS certificate, key and trust bundle"), root.getMessage());
        }
    }

    @Test
    void theReportAndTelemetryPortsAreRequiredAndPassedThrough() {
        AgentGatewayConfiguration configuration = new AgentGatewayConfiguration();
        var enrollment = inert(AgentEnrollmentPort.class);
        AgentGatewayPortSet ports = configuration.agentGatewayPortSet(enrollment, inert(AgentIdentityPort.class), inert(AgentSessionPort.class),
                inert(AgentLeasePort.class), inert(AgentCommandPort.class), RealReportAndTelemetryPorts.REPORTS,
                RealReportAndTelemetryPorts.TELEMETRY, inert(AgentAuditPort.class), inert(AgentDiscoveryPort.class),
                inert(AgentTelemetryDeclarationsPort.class),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.solace.sm.dp.api.agw.AgentLogRetentionSource.class));
        assertSame(RealReportAndTelemetryPorts.REPORTS, ports.reports());
        assertSame(RealReportAndTelemetryPorts.TELEMETRY, ports.telemetry());
        assertSame(enrollment, ports.enrollment());
    }

    @Test
    void aMissingReportPortFailsTheContextRatherThanComposingAStandIn() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                    "sm.agent.gateway.enabled", "true")));
            context.register(EightPorts.class, AgentGatewayConfiguration.class);
            RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);
            Throwable root = failure;
            while (root.getCause() != null) root = root.getCause();
            assertTrue(root instanceof org.springframework.beans.factory.NoSuchBeanDefinitionException, String.valueOf(root));
        }
    }
}
