package com.solace.sm.launcher;

import com.solace.sm.cp.api.agent.ControlPlaneAgentGateway;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Maps the CP authority into the DP gateway ports at the composition root: exactly three adapters (BE-D69), each a
 * one-way translation of one cp-api call into one dp-api port. A fourth would be a new cross-plane seam and is refused by
 * {@code CompositionBoundaryTest}, which names these three classes.
 */
@Configuration(proxyBeanMethods = false)
public class AgentGatewayControlPlaneAdapterConfiguration {
    @Bean
    EnrollmentCertificateIssuer enrollmentCertificateIssuer(ControlPlaneAgentGateway controlPlane) {
        return new ControlPlaneEnrollmentCertificateIssuer(controlPlane);
    }

    @Bean
    AgentRegistrationSource agentRegistrationSource(ControlPlaneAgentGateway controlPlane) {
        return new ControlPlaneRegistrationSource(controlPlane);
    }

    @Bean
    LeaseIssuer leaseIssuer(ControlPlaneAgentGateway controlPlane) {
        return new ControlPlaneLeaseIssuer(controlPlane);
    }
}
