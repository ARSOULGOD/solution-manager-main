package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.solace.sm.agw.AgentGatewayMetrics;
import com.solace.sm.agw.AgentGatewayServer;
import com.solace.sm.agw.AgentGatewayService;
import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.agent.AgentReportIntake;
import com.solace.sm.dp.agent.AgentTelemetryBridge;
import com.solace.sm.dp.agent.AgentTelemetryCounts;
import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentRetentionRepository;
import com.solace.sm.dp.apply.runtime.agent.HandoffAgentCommandPayloadSource;
import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Plan 015 T3.2: the launcher composes the agent listener, and refuses to compose a half-configured one (T2.2 review note (a)).
 * One real PostgreSQL, the production {@link SolutionManagerApplication}, and a boot per configuration. The gateway properties
 * are command-line arguments because {@code SpringApplicationBuilder.properties} is the LOWEST precedence and
 * {@code application.properties} now maps these keys.
 */
class AgentGatewayCompositionIT {

    private static Map<String, String> databaseProperties;

    @TempDir
    Path directory;

    @BeforeAll
    static void provisionTheDatabaseOnce() {
        databaseProperties = TwoSchemaTestDatabase.provisionStandalone().properties();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** The properties of a correct composed listener; each test removes or blanks the one thing it is about. */
    private Map<String, String> composed(AgentGatewayTestPki pki) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("sm.agent.gateway.enabled", "true");
        p.put("sm.agent.gateway.port", String.valueOf(freePort()));
        p.put("sm.agent.gateway.certificate-file", pki.serverCertificate.toString());
        p.put("sm.agent.gateway.private-key-file", pki.serverKey.toString());
        p.put("sm.agent.gateway.trust-bundle-file", pki.trustBundle.toString());
        p.put("sm.control.agent-gateway.ca.connect-endpoint", "agents.example.test:9443");
        p.put("sm.control.agent-gateway.ca.key-store-file", pki.keystore.toString());
        p.put("sm.control.agent-gateway.ca.key-alias", AgentGatewayTestPki.CA_ALIAS);
        p.put("sm.control.agent-gateway.ca.key-store-password", AgentGatewayTestPki.PASSWORD);
        return p;
    }

    private ConfigurableApplicationContext boot(Map<String, String> gateway) {
        List<String> args = new ArrayList<>();
        gateway.forEach((key, value) -> args.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(SolutionManagerApplication.class).web(WebApplicationType.NONE)
                .properties(new LinkedHashMap<String, Object>(databaseProperties)).run(args.toArray(new String[0]));
    }

    /** The messages of the whole cause chain, which is what an operator reads. */
    private String refusal(Map<String, String> gateway) {
        try {
            boot(gateway).close();
        } catch (Exception refused) {
            StringBuilder chain = new StringBuilder();
            for (Throwable t = refused; t != null; t = t.getCause()) {
                chain.append(t.getMessage()).append('\n');
            }
            return chain.toString();
        }
        return fail("the context must not start");
    }

    @Test
    void aDpRemoteOnlyInstallComposesNoAgentListenerAndNeedsNoAgentMaterial() {
        try (ConfigurableApplicationContext context = boot(Map.of())) {
            assertTrue(context.getBeansOfType(AgentGatewayServer.class).isEmpty(), "no listener bean when the switch is off");
            assertTrue(context.getBeansOfType(AgentGatewayService.class).isEmpty());
            assertEquals("false", context.getEnvironment().getProperty("sm.control.agent-gateway.ca.required"),
                    "the CA is only required when the listener is composed");
        }
    }

    @Test
    void theAgentRetentionRidesTheDailyRunwayLoopOfEveryInstall() {
        try (ConfigurableApplicationContext context = boot(Map.of())) {
            assertEquals(1, context.getBean(PartitionRunway.class).dailyJobs().stream()
                    .filter(JdbcAgentRetentionRepository.class::isInstance).count(),
                    "T4.5: sm_data.agent_gateway_maintenance() is called by the loop that keeps the metrics partitions, composed once");
        }
    }

    @Test
    void aCorrectlyConfiguredListenerIsComposedAndRunsWithTheControlPlaneAdapters() throws Exception {
        try (ConfigurableApplicationContext context = boot(composed(new AgentGatewayTestPki(directory, true)))) {
            assertTrue(context.getBean(AgentGatewayServer.class).isRunning());
            assertNotNull(context.getBean(AgentGatewayService.class));
            assertEquals("true", context.getEnvironment().getProperty("sm.control.agent-gateway.ca.required"));
            // The data plane resolves these at call time and answers INTERNAL_UNAVAILABLE when one is absent (T2.1b fix-round (b)).
            assertNotNull(context.getBean(EnrollmentCertificateIssuer.class));
            assertNotNull(context.getBean(LeaseIssuer.class));
            assertNotNull(context.getBean(AgentRegistrationSource.class));
        }
    }

    @Test
    void theCommandPayloadSourceAndTheReportAndTelemetryPortsAreTheRealBeansWhenComposed() throws Exception {
        try (ConfigurableApplicationContext context = boot(composed(new AgentGatewayTestPki(directory, true)))) {
            assertInstanceOf(HandoffAgentCommandPayloadSource.class, context.getBean(AgentCommandPayloadSource.class),
                    "the composed listener serves pulls from T3.1's hand-off, not a stand-in");
            assertInstanceOf(AgentReportIntake.class, context.getBean(ActualStatePort.class), "T3.3's intake, not a stand-in");
            assertInstanceOf(AgentTelemetryBridge.class, context.getBean(AgentTelemetryPort.class), "T3.4's bridge, not a stand-in");
            // The arch rule theReportAndTelemetryPortsHaveOnlyTheirOwnedImplementations sees concrete CLASSES only; a lambda stand-in
            // has no class file. This is the half that closes that gap: the composed context holds exactly one bean of each type, and
            // it is the owned class (a lambda would be a second bean, or the only one and not an instance of the owned class).
            assertEquals(1, context.getBeansOfType(ActualStatePort.class).size(), "exactly one ActualStatePort in the composed context");
            assertEquals(1, context.getBeansOfType(AgentTelemetryPort.class).size(), "exactly one AgentTelemetryPort in the composed context");
        }
    }

    @Test
    void everyAgentTelemetryCountIsABoundMeterOnTheGatewaysRegistryThatReadsTheLiveCount() throws Exception {
        try (ConfigurableApplicationContext context = boot(composed(new AgentGatewayTestPki(directory, true)))) {
            var registry = context.getBean(AgentGatewayMetrics.class).registry();
            AgentTelemetryCounts counts = context.getBean(AgentTelemetryCounts.class);
            for (String name : AgentTelemetryCounts.NAMES) {
                String meter = "dp_" + name.replace('.', '_') + "_total";
                assertNotNull(registry.find(meter).functionCounter(), "no meter " + meter + " for the count " + name);
            }
            long before = (long) registry.get("dp_agent_telemetry_rejected_total").functionCounter().count();
            // Micrometer holds a function counter's source WEAKLY: a meter bound to a throwaway lambda reads zero after a collection
            // (found in the W4 lane D fix round). The bean is the source, so the live count must survive forced collections.
            counts.add(AgentTelemetryCounts.REJECTED, 3);
            for (int i = 0; i < 5; i++) {
                System.gc();
            }
            assertEquals(before + 3, (long) registry.get("dp_agent_telemetry_rejected_total").functionCounter().count(),
                    "the meter reads the live count at scrape time");
        }
    }

    @Test
    void aDpRemoteOnlyInstallBindsNoAgentTelemetryMeters() {
        try (ConfigurableApplicationContext context = boot(Map.of())) {
            assertTrue(context.getBeansOfType(AgentGatewayMetrics.class).isEmpty(), "no gateway, no gateway registry to bind to");
        }
    }

    @Test
    void aListenerWithNoCaKeystoreFailsTheContext() throws Exception {
        Map<String, String> p = composed(new AgentGatewayTestPki(directory, true));
        p.put("sm.control.agent-gateway.ca.key-store-file", "");
        assertTrue(refusal(p).contains("agent CA keystore is not configured"));
    }

    @Test
    void aListenerWhoseKeystoreHoldsNoLeaseNonceKeyFailsTheContext() throws Exception {
        String chain = refusal(composed(new AgentGatewayTestPki(directory, false)));
        assertTrue(chain.contains("lease nonce key entry is missing"), chain);
    }

    @Test
    void aListenerWithABlankConnectEndpointFailsTheContextBeforeAnyEnrolment() throws Exception {
        Map<String, String> p = composed(new AgentGatewayTestPki(directory, true));
        p.put("sm.control.agent-gateway.ca.connect-endpoint", " ");
        String chain = refusal(p);
        assertTrue(chain.contains("SM_AGENT_GATEWAY_ENDPOINT"), chain);
        assertFalse(chain.contains(AgentGatewayTestPki.PASSWORD), "the refusal names the property, never a secret");
    }

    @Test
    void anOverlayThatSwitchesTheCaRequirementOffUnderAnEnabledListenerIsRefused() throws Exception {
        Map<String, String> p = composed(new AgentGatewayTestPki(directory, true));
        p.put("sm.control.agent-gateway.ca.required", "false");
        String chain = refusal(p);
        assertTrue(chain.contains("sm.control.agent-gateway.ca.required must be true"), chain);
    }

    @Test
    void aListenerWithNoTlsCertificateFailsTheContextInsteadOfStartingUnsecured() throws Exception {
        Map<String, String> p = composed(new AgentGatewayTestPki(directory, true));
        p.put("sm.agent.gateway.certificate-file", "");
        String chain = refusal(p);
        assertTrue(chain.contains("TLS certificate, key and trust bundle are required"), chain);
    }
}
