package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.governance.AgentInstanceGovernance;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.agent.AgentDiscoveryService;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.apply.persistence.agent.AgentAuthority;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentDiscoveryRepository;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 015 T4.2 ({@code OR-27}, {@code OR-128} (15)) on the real application against PostgreSQL 14, both planes composed: an enrolled
 * agent's discovery report reaches the data plane's intake, which calls the console gateway's {@code AgentRegistrationCompleter}
 * after its own commit; the completer writes the control plane's governance node under {@code AGENT_INSTANCE_REGISTERED} with the
 * AGENT as actor, and the data plane confirms. Nothing here enumerates tenants: every call is for one agent, in one RPC.
 *
 * <p>The crash windows are driven by a SECOND intake built from the same beans but with no completion sink: it is "the process that
 * died before it called the completer". The real {@code AgentDiscoveryPort} is then the next {@code Connect}'s report.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AgentGatewayControlPlaneAdapterConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentRegistrationCompleterIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final int QUOTA = 3;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID admin;
    private static UUID solutionId;
    private static final AtomicInteger PORT = new AtomicInteger(18100);

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AgentEnrolment enrolment;
    @Autowired
    private AgentEnrollmentPort enrollments;
    @Autowired
    private AgentSessionPort sessions;
    @Autowired
    private AgentLeasePort leases;
    @Autowired
    private AgentDiscoveryPort discovery;
    @Autowired
    private AgentInstanceGovernance agentGovernance;
    @Autowired
    private AgentAuthority authority;
    @Autowired
    private JdbcAgentDiscoveryRepository discoveryRepository;
    @Autowired
    private InventoryService inventory;
    @Autowired
    private AgentAuditPort audits;
    @Autowired
    private PackageDeclarationSource packages;
    @Autowired
    @Qualifier("smDataClock")
    private Clock clock;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws Exception {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.declare.abandon-after", () -> "6s");
        Path keystore = AgentGatewayTestPki.keystore();
        registry.add("sm.control.agent-gateway.ca.key-store-file", keystore::toString);
        registry.add("sm.control.agent-gateway.ca.key-store-password", () -> AgentGatewayTestPki.PASSWORD);
        registry.add("sm.control.agent-gateway.ca.key-alias", () -> AgentGatewayTestPki.CA_ALIAS);
        registry.add("sm.control.agent-gateway.ca.connect-endpoint", () -> "agents.example.test:8443");
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        admin = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o AND username = :u",
                Map.of("o", world.organization(), "u", DeclareFixtures.ADMIN), UUID.class);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", QUOTA,
                DeclareFixtures.ADMIN);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------------

    private UUID solution() throws Exception {
        if (solutionId == null) {
            Client http = new Client(port);
            ObjectNode load = MAPPER.createObjectNode();
            load.set("package", DeclareFixtures.cachePackage());
            HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
            assertEquals(201, loaded.statusCode(), loaded.body());
            solutionId = UUID.fromString(MAPPER.readTree(loaded.body()).get("solutionId").asText());
        }
        return solutionId;
    }

    /** An agent enrolled for real (token, CSR, certificate, both planes' target), with a session and a lease. */
    private record Enrolled(UUID agentId, UUID targetId, String thumbprint, AgentSessionRef session, LeaseGrant lease) {
    }

    private Enrolled enrol(String name) throws Exception {
        AgentEnrolment.IssuedToken issued = enrolment.issue(new PrincipalRef(admin), world.organization(),
                new AgentEnrolment.Request(ScopePath.environment(world.organization(), world.unit(), world.envOne()), solution(),
                        "1", name, "/opt/sm/config", "sm-", null), new RequestOrigin("203.0.113.40", UUID.randomUUID()));
        AgentRequestOrigin origin = new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.77");
        EnrollmentResult result = enrollments.enroll(new EnrollmentRequest("enroll-" + UUID.randomUUID(), issued.token(),
                AgentGatewayTestPki.csr(), "0.1.0", List.of(1), "linux", "arm64", "docker", origin));
        String thumbprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.certificateChainDer().get(0)));
        AgentSessionRef session = sessions.claim(thumbprint, new SessionOpening(1, "0.1.0", Set.of(), Instant.now()), origin);
        LeaseGrant lease = leases.issue("lease-" + UUID.randomUUID(), session, Instant.now(), origin);
        return new Enrolled(issued.agentId(), result.targetId(), thumbprint, session, lease);
    }

    private static DiscoveredContainer container(String suffix) {
        return new DiscoveredContainer(String.format("%012x", Math.abs((long) suffix.hashCode())),
                "sm-" + suffix,
                "registry.example/cache:2.4", Map.of("com.solace.sm.instance", "cache-" + suffix,
                        "com.solace.sm.config-file", "/app/external/spring/config/application.yml"),
                List.of(new DiscoveredContainer.Mount("/opt/sm/config/" + suffix, "/app/external/spring/config")),
                List.of(new DiscoveredContainer.PublishedPort(8081, PORT.incrementAndGet())));
    }

    private AgentRequestOrigin origin() {
        return new AgentRequestOrigin(UUID.randomUUID(), "198.51.100.88");
    }

    private DiscoveryResult report(AgentDiscoveryPort port, Enrolled agent, DiscoveredContainer item, AgentRequestOrigin origin) {
        return port.report(agent.session(), "disc-" + UUID.randomUUID(), List.of(item), origin).get(0);
    }

    /** The process that died before calling the completer: the same intake, no completion sink. */
    private AgentDiscoveryPort intakeThatDiesBeforeCompletion() {
        return new AgentDiscoveryService(authority, discoveryRepository, inventory, audits, () -> packages, () -> null, clock, 16,
                java.time.Duration.ofMinutes(10), 256);
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private UUID instanceOf(Enrolled agent, String suffix) {
        return ownerJdbc.queryForObject("SELECT instance_id FROM sm_data.agent_discovery WHERE tenant_id = :t AND agent_id = :a"
                + " AND instance_label = :l", Map.of("t", world.organization(), "a", agent.agentId(), "l", "cache-" + suffix), UUID.class);
    }

    private String discoveryState(Enrolled agent, String suffix) {
        return ownerJdbc.queryForObject("SELECT state FROM sm_data.agent_discovery WHERE tenant_id = :t AND agent_id = :a"
                + " AND instance_label = :l", Map.of("t", world.organization(), "a", agent.agentId(), "l", "cache-" + suffix), String.class);
    }

    private long cpNodes(UUID instance) {
        return count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o AND instance_id = :i",
                Map.of("o", world.organization(), "i", instance));
    }

    // ---- the happy path ---------------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void aDiscoveredContainerBecomesAGovernanceNodeRegisteredByTheAgentAndTheNextLeaseHoldsItsPlacement() throws Exception {
        Enrolled agent = enrol("edge-happy");
        AgentRequestOrigin origin = origin();

        DiscoveryResult result = report(discovery, agent, container("happy"), origin);

        assertEquals(DiscoveryResult.State.REGISTERED, result.state());
        UUID placement = result.placementId();
        assertNotNull(placement);
        UUID instance = instanceOf(agent, "happy");
        assertEquals(1, cpNodes(instance), "the control plane holds the node");
        assertEquals("active", ownerJdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), String.class));
        assertEquals("REGISTERED", discoveryState(agent, "happy"));

        // the change_audit row of the node: the AGENT is the actor, no principal, the RPC's correlation id and address
        List<Map<String, Object>> audit = ownerJdbc.queryForList("SELECT audit_operation, actor_agent_id, actor_principal_id,"
                + " client_address FROM sm_control.change_audit WHERE table_name = 'instance' AND correlation_id = :c",
                Map.of("c", origin.correlationId()));
        assertEquals(1, audit.size(), audit.toString());
        assertEquals("AGENT_INSTANCE_REGISTERED", audit.get(0).get("audit_operation"));
        assertEquals(agent.agentId(), audit.get(0).get("actor_agent_id"));
        assertNull(audit.get(0).get("actor_principal_id"));
        assertEquals("198.51.100.88", audit.get(0).get("client_address"));
        assertEquals(1, count("SELECT count(*) FROM sm_data.agent_event WHERE tenant_id = :t AND agent_id = :a"
                + " AND code = 'agent.discovery.registered' AND correlation_id = :c",
                Map.of("t", world.organization(), "a", agent.agentId(), "c", origin.correlationId())));

        // the placement's probe and metrics endpoints come from the ENROLLED package (the data plane reads none), mapped to the ports Docker published
        Map<String, Object> placementRow = ownerJdbc.queryForMap("SELECT health_kind, health_path, health_strength, health_port, created_by,"
                + " container_name FROM sm_data.placement WHERE tenant_id = :t AND placement_id = :p",
                Map.of("t", world.organization(), "p", placement));
        assertEquals("actuator-health", placementRow.get("health_kind"));
        assertEquals("/actuator/health", placementRow.get("health_path"));
        assertEquals("liveness", placementRow.get("health_strength"));
        assertEquals(agent.agentId(), placementRow.get("created_by"));
        assertEquals(placementRow.get("health_port"), ownerJdbc.queryForObject("SELECT host_port FROM sm_data.placement_metrics_endpoint"
                + " WHERE tenant_id = :t AND placement_id = :p AND source_port = 8081", Map.of("t", world.organization(), "p", placement),
                Integer.class), "the cache fixture probes and serves metrics on the same container port 8081");

        // the new placement reaches the agent only at its next lease RENEWAL (the lease snapshot is not pushed): drive one explicitly
        LeaseGrant renewed = leases.renew("renew-" + UUID.randomUUID(), agent.session(), agent.lease().nonce(), Instant.now(), origin());
        assertTrue(renewed.placementIds().contains(placement), renewed.placementIds().toString());

        // and the environment/solution are the ENROLMENT's, in both planes
        assertEquals(world.envOne(), ownerJdbc.queryForObject("SELECT environment_id FROM sm_control.instance WHERE instance_id = :i",
                Map.of("i", instance), UUID.class));
        assertEquals(solutionId, ownerJdbc.queryForObject("SELECT s.catalog_solution_id FROM sm_control.instance i JOIN"
                + " sm_control.solution s ON s.solution_id = i.solution_id WHERE i.instance_id = :i", Map.of("i", instance), UUID.class));
    }

    // ---- the crash windows ---------------------------------------------------------------------------------------------------

    @Test
    @Order(2)
    void killedBetweenTheControlPlaneWriteAndTheDataPlaneConfirmTheNextConnectsReportCompletesItWithoutASecondNode() throws Exception {
        Enrolled agent = enrol("edge-crash");
        assertEquals(DiscoveryResult.State.PENDING, report(intakeThatDiesBeforeCompletion(), agent, container("crash"), origin()).state());
        UUID instance = instanceOf(agent, "crash");
        // "the completer wrote the CP node, then the process died": the same call the completer makes, alone
        agentGovernance.registerForAgent(agent.agentId(), new RequestOrigin("198.51.100.88", UUID.randomUUID()),
                world.organization(), world.envOne(), solutionId, instance);
        assertEquals("PENDING", discoveryState(agent, "crash"));
        assertEquals(1, cpNodes(instance));

        DiscoveryResult next = report(discovery, agent, container("crash"), origin());   // the same container, the next Connect

        assertEquals(DiscoveryResult.State.REGISTERED, next.state());
        assertEquals("REGISTERED", discoveryState(agent, "crash"));
        assertEquals(1, cpNodes(instance), "the node is confirmed, never inserted twice");
        assertEquals("active", ownerJdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), String.class));
    }

    @Test
    @Order(3)
    void killedBeforeTheControlPlaneWriteTheSweepPastTheWindowTombstonesItAndTheNextConnectDoesNotReMintIt() throws Exception {
        Enrolled agent = enrol("edge-sweep");
        assertEquals(DiscoveryResult.State.PENDING, report(intakeThatDiesBeforeCompletion(), agent, container("sweep"), origin()).state());
        UUID instance = instanceOf(agent, "sweep");
        Thread.sleep(6_500);                                  // past sm.dp.declare.abandon-after (6s in this test)

        Client http = new Client(port);
        HttpResponse<String> listed = http.get("/api/dp/solutions/" + solutionId + "/instances", http.login(DeclareFixtures.ADMIN));
        assertEquals(200, listed.statusCode(), listed.body());       // any request of this tenant that sweeps

        assertEquals("ABANDONED", discoveryState(agent, "sweep"), "the sweep's abandonment tombstones the key in its transaction");
        assertEquals(0, cpNodes(instance), "the control plane never held it");
        DiscoveryResult next = report(discovery, agent, container("sweep"), origin());
        assertEquals(DiscoveryResult.State.ABANDONED, next.state());
        assertEquals(1, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'cache-sweep'",
                Map.of("t", world.organization())), "nothing was minted again");
    }

    // ---- the quota ---------------------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void overTheQuotaTheDataPlaneRowIsRefusedEntitlementQuotaAuditedAndNoNodeIsWritten() throws Exception {
        Enrolled agent = enrol("edge-quota");
        long before = count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o", Map.of("o", world.organization()));
        DiscoveryResult refused = null;
        String refusedSuffix = null;
        List<String> registered = new ArrayList<>();
        for (int i = 0; i < QUOTA + 1 && refused == null; i++) {
            String suffix = "quota" + i;
            DiscoveryResult result = report(discovery, agent, container(suffix), origin());
            if (result.state() == DiscoveryResult.State.REFUSED) {
                refused = result;
                refusedSuffix = suffix;
            } else {
                assertEquals(DiscoveryResult.State.REGISTERED, result.state());
                registered.add(suffix);
            }
        }
        assertNotNull(refused, "the quota (" + QUOTA + " instances of the solution) must stop a registration");
        assertEquals(GatewayCode.ENTITLEMENT_QUOTA, refused.code());
        assertEquals("entitlement.quota", refused.code().wire());
        assertEquals(QUOTA, count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o",
                Map.of("o", world.organization())) , "the quota is the organization's, not the agent's");
        assertTrue(before < QUOTA);
        UUID instance = instanceOf(agent, refusedSuffix);
        assertEquals(0, cpNodes(instance), "no control-plane node for the refused instance");
        assertEquals("REFUSED", discoveryState(agent, refusedSuffix));
        assertEquals("abandoned", ownerJdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), String.class));
        assertEquals(1, count("SELECT count(*) FROM sm_data.agent_event WHERE tenant_id = :t AND agent_id = :a"
                + " AND code = 'agent.discovery.refused' AND detail_code = 'entitlement.quota'",
                Map.of("t", world.organization(), "a", agent.agentId())));
        // and a later Connect does not retry it
        assertEquals(DiscoveryResult.State.REFUSED, report(discovery, agent, container(refusedSuffix), origin()).state());
        assertEquals(QUOTA, count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :o",
                Map.of("o", world.organization())));
    }
}
