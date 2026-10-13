package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class AgentGatewayOperationalTest {
    @Test
    void disabledGatewayIsHealthyButReportsThatItIsDisabled() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        AgentGatewayServer server = new AgentGatewayServer(properties, null, null);

        assertEquals(Status.UP, server.health().getStatus());
        assertEquals(false, server.health().getDetails().get("enabled"));
    }

    @Test
    void enabledGatewayIsUnreadyUntilTlsListenerStarts() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setEnabled(true);
        AgentGatewayServer server = new AgentGatewayServer(properties, null, null);

        assertEquals(Status.DOWN, server.health().getStatus());
        assertEquals("not_started", server.health().getDetails().get("reason"));
    }

    @Test
    void invalidConfigurationProducesCodedUnreadyState() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setEnabled(true);
        properties.setReadinessPort(-1);
        AgentGatewayServer server = new AgentGatewayServer(properties, null, null);

        assertThrows(IllegalStateException.class, server::start);
        assertEquals(Status.DOWN, server.health().getStatus());
        assertEquals("configuration_invalid", server.health().getDetails().get("reason"));
    }

    @Test
    void propertyLimitsRejectUnboundedGatewaySettings() {
        AgentGatewayProperties properties = new AgentGatewayProperties();
        properties.setPollInterval(Duration.ZERO);
        properties.setCertificateFile("cert");
        properties.setPrivateKeyFile("key");
        properties.setTrustBundleFile("trust");

        assertThrows(IllegalArgumentException.class, properties::validate);
        assertEquals(16 * 1024 * 1024, properties.getMaxMessageBytes());
    }
}
