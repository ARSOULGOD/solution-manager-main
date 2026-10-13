package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.AgentLeaseMaterial;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.agent.AgentRegistration;
import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The adapter configuration publishes exactly the three data-plane ports, each backed by its named adapter. */
class AgentGatewayControlPlaneAdapterConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    static class ControlPlane {
        @Bean
        ControlPlaneAgentGateway controlPlane() {
            return new ControlPlaneAgentGateway() {
                @Override public AgentEnrollmentMaterial enroll(AgentEnrollmentRequest request) { throw new AssertionError(); }
                @Override public AgentLeaseMaterial issueLease(AgentLeaseRequest request) { throw new AssertionError(); }
                @Override public Map<UUID, AgentRegistration> registrations(Collection<UUID> ids) { return Map.of(); }
            };
        }
    }

    @Test
    void exactlyTheThreePortsAreComposedFromTheNamedAdapters() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(ControlPlane.class,
                AgentGatewayControlPlaneAdapterConfiguration.class)) {
            assertInstanceOf(ControlPlaneEnrollmentCertificateIssuer.class, context.getBean(EnrollmentCertificateIssuer.class));
            assertInstanceOf(ControlPlaneLeaseIssuer.class, context.getBean(LeaseIssuer.class));
            assertInstanceOf(ControlPlaneRegistrationSource.class, context.getBean(AgentRegistrationSource.class));
            assertEquals(3, context.getBeansOfType(Object.class).values().stream()
                    .filter(bean -> bean.getClass().getName().startsWith("com.solace.sm.launcher.ControlPlane")
                            && !(bean instanceof ControlPlaneAgentGateway)).count());
        }
    }
}
