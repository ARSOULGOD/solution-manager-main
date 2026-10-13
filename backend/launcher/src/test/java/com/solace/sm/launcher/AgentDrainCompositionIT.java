package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.agw.AgentGatewayService;
import com.solace.sm.dp.api.agw.AgentDrainPort;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Plan 015 T4.3: the {@code AgentDrainPort} the console's {@code drainAgent} calls is the gateway's own service when the listener
 * is composed, and does not exist when it is not (the console then answers {@code AGENT_NOT_CONNECTED}, never a 500). Booted as
 * {@link AgentGatewayCompositionIT} boots, with no broker.
 */
class AgentDrainCompositionIT {

    private static Map<String, String> databaseProperties;

    @TempDir
    Path directory;

    @BeforeAll
    static void provisionTheDatabaseOnce() {
        databaseProperties = TwoSchemaTestDatabase.provisionStandalone().properties();
    }

    private ConfigurableApplicationContext boot(Map<String, String> gateway) {
        List<String> args = new ArrayList<>();
        gateway.forEach((key, value) -> args.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(SolutionManagerApplication.class).web(WebApplicationType.NONE)
                .properties(new LinkedHashMap<String, Object>(databaseProperties)).run(args.toArray(new String[0]));
    }

    @Test
    void thereIsNoDrainRequesterWithoutTheListener() {
        try (ConfigurableApplicationContext context = boot(Map.of())) {
            assertTrue(context.getBeansOfType(AgentDrainPort.class).isEmpty());
        }
    }

    @Test
    void withTheListenerTheRequesterIsTheGatewaysAndAnAgentWithNoSessionIsEmpty() throws Exception {
        AgentGatewayTestPki pki = new AgentGatewayTestPki(directory, true);
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sm.agent.gateway.enabled", "true");
        try (ServerSocket socket = new ServerSocket(0)) {
            p.put("sm.agent.gateway.port", String.valueOf(socket.getLocalPort()));
        }
        p.put("sm.agent.gateway.certificate-file", pki.serverCertificate.toString());
        p.put("sm.agent.gateway.private-key-file", pki.serverKey.toString());
        p.put("sm.agent.gateway.trust-bundle-file", pki.trustBundle.toString());
        p.put("sm.control.agent-gateway.ca.connect-endpoint", "agents.example.test:9443");
        p.put("sm.control.agent-gateway.ca.key-store-file", pki.keystore.toString());
        p.put("sm.control.agent-gateway.ca.key-alias", AgentGatewayTestPki.CA_ALIAS);
        p.put("sm.control.agent-gateway.ca.key-store-password", AgentGatewayTestPki.PASSWORD);
        try (ConfigurableApplicationContext context = boot(p)) {
            AgentDrainPort requester = context.getBean(AgentDrainPort.class);
            assertEquals(Optional.empty(), requester.drain(UUID.randomUUID(), "operator-drain"));
            assertEquals(0, context.getBean(AgentGatewayService.class).liveSessions());
        }
    }
}
