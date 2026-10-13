package com.solace.sm.launcher;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Plan 015 T2.2 review note (a): a composed agent listener must not start to find out that it cannot enrol anyone.
 *
 * <p>When {@code sm.agent.gateway.enabled=true} this refuses the boot unless
 * <ul>
 *   <li>{@code sm.control.agent-gateway.ca.required} is true: with it, the control plane's CA and its lease-nonce HMAC key are verified
 *       at startup, so a missing or unreadable keystore, or a keystore without the nonce key entry, fails the context (the property
 *       file derives it from the listener switch; an overlay that sets it false is refused here, not honoured);</li>
 *   <li>{@code sm.control.agent-gateway.ca.connect-endpoint} is not blank: the control plane's {@code enroll} only throws on a
 *       blank endpoint at the first enrolment, which is far too late.</li>
 * </ul>
 * It reads two property names and holds no value; the messages name the property, never a value.
 */
@Configuration(proxyBeanMethods = false)
final class AgentGatewayBootGuard implements InitializingBean {
    static final String ENABLED = "sm.agent.gateway.enabled";
    static final String REQUIRED = "sm.control.agent-gateway.ca.required";
    static final String ENDPOINT = "sm.control.agent-gateway.ca.connect-endpoint";

    private final Environment environment;

    AgentGatewayBootGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        // Exactly "true", as @ConditionalOnProperty compares it: "yes", "on" or "1" neither arm this guard nor compose the gateway.
        if (!"true".equals(environment.getProperty(ENABLED))) {
            return;
        }
        if (!environment.getProperty(REQUIRED, Boolean.class, false)) {
            throw new IllegalStateException(REQUIRED + " must be true when " + ENABLED + " is true: the agent CA keystore and the"
                    + " lease-nonce key are verified at startup, not at the first enrolment");
        }
        String endpoint = environment.getProperty(ENDPOINT, "");
        if (endpoint.isBlank()) {
            throw new IllegalStateException(ENDPOINT + " (SM_AGENT_GATEWAY_ENDPOINT) must be set when " + ENABLED
                    + " is true: it is the address agents are told to connect to");
        }
    }
}
