package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceRequestInFlightException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.LastLivePlacementException;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementInFlightException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.PlacementSchedulePendingException;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.PushMetricsPlacementRefusedException;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code addPlacement} and {@code retirePlacement} (plan 008 S3, {@code BE-D47}, {@code BE-D53}) over the real filter,
 * interceptor, argument-resolver, controller and advice pipeline, with the cp-api and dp-api ports FAKED and one ordered
 * call log. What this pins is the gateway's half: WHICH verb is asked at WHICH resource and in what order, that the probe
 * and the metrics come from the instance's declared package and never the body, and how each data-plane refusal is
 * answered. The data plane's half is {@code AddPlacementIT} and {@code RetirePlacementIT}; the two over real HTTP and a
 * real database are {@code AddPlacementEndpointIT}.
 */
class PlacementControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private InstanceControllerTest.Calls calls;
    private Inventory inventory;
    private InstanceControllerTest.FakeConnections connections;
    private InstanceControllerTest.FakeCatalog catalog;
    private UUID organization;
    private UUID instance;
    private UUID firstTarget;
    private UUID secondTarget;
    private UUID catalogSolution;
    private UUID firstPlacement;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true",
                "sm.gateway.auth-rate.capacity=10", "sm.gateway.auth-rate.refill-per-minute=10",
                "sm.gateway.auth-rate.max-tracked-keys=100000");
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        authorization = context.getBean(FakeAuthorizationService.class);
        calls = context.getBean(InstanceControllerTest.Calls.class);
        inventory = context.getBean(Inventory.class);
        connections = context.getBean(InstanceControllerTest.FakeConnections.class);
        catalog = context.getBean(InstanceControllerTest.FakeCatalog.class);
        organization = UUID.randomUUID();
        instance = UUID.randomUUID();
        firstTarget = UUID.randomUUID();
        secondTarget = UUID.randomUUID();
        catalogSolution = UUID.randomUUID();
        firstPlacement = UUID.randomUUID();
        connections.live.add(firstTarget);
        connections.live.add(secondTarget);
        catalog.serve(catalogSolution, "1", pullPackage());
        inventory.rows.put(instance, view(List.of(placement(firstPlacement, firstTarget, "sm-orders"))));
    }

    // -------------------------------------------------------------------------------------------------- rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        InstanceControllerTest.Calls calls() {
            return new InstanceControllerTest.Calls();
        }

        @Bean
        Inventory inventory(InstanceControllerTest.Calls calls) {
            return new Inventory(calls);
        }

        @Bean
        InstanceControllerTest.FakeConnections connections() {
            return new InstanceControllerTest.FakeConnections();
        }

        @Bean
        InstanceControllerTest.FakeGovernance governance(InstanceControllerTest.Calls calls) {
            return new InstanceControllerTest.FakeGovernance(calls);
        }

        @Bean
        InstanceControllerTest.FakeCatalog catalog() {
            return new InstanceControllerTest.FakeCatalog();
        }

        @Bean
        MetricsFakes.JacksonPackageCodec codec() {
            return new MetricsFakes.JacksonPackageCodec();
        }

        @Bean
        MetricsFakes.RecordingApplicability applicability() {
            return new MetricsFakes.RecordingApplicability();
        }

        @Bean
        InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean
        DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
                InstanceGovernance governance, SolutionCatalog catalog,
                com.solace.sm.cp.api.authz.AuthorizationService authorization, ReachAuthorization reach,
                com.solace.sm.cp.api.designer.DesignerCodec codec,
                com.solace.sm.cp.api.designer.MetricApplicability applicability) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10));
        }

        @Bean
        PlacementOrchestrator placementOrchestrator(DeclareOrchestrator declare, InstanceInventoryService inventory,
                TargetConnectionService connections, SolutionCatalog catalog,
                com.solace.sm.cp.api.authz.AuthorizationService authorization,
                com.solace.sm.cp.api.designer.DesignerCodec codec) {
            return new PlacementOrchestrator(declare, inventory, connections, catalog, authorization, codec);
        }

        @Bean
        PlacementController placementController(PlacementOrchestrator orchestrator, RequestOriginFactory origins) {
            return new PlacementController(orchestrator, origins);
        }
    }

    /** The data plane's inventory as far as these two operations see it: findActive, add, retire and an empty sweep. */
    static final class Inventory implements InstanceInventoryService {
        final InstanceControllerTest.Calls calls;
        final Map<UUID, InstanceView> rows = new LinkedHashMap<>();
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<AddPlacementCommand> added = new ArrayList<>();
        final List<UUID> retired = new ArrayList<>();
        final List<UUID> retiredBy = new ArrayList<>();
        final List<UUID> retiredCorrelations = new ArrayList<>();
        RuntimeException addFailure;
        RuntimeException retireFailure;

        Inventory(InstanceControllerTest.Calls calls) {
            this.calls = calls;
        }

        @Override
        public InstanceView reserve(UUID organizationId, DeclareInstanceCommand command) {
            throw new AssertionError("a placement operation never reserves an instance");
        }

        @Override
        public InstanceView activate(UUID organizationId, UUID instanceId) {
            throw new AssertionError();
        }

        @Override
        public void abandon(UUID organizationId, UUID instanceId) {
            throw new AssertionError();
        }

        @Override
        public Optional<InstanceView> findActive(UUID organizationId, UUID instanceId) {
            calls.add("dp.findActive");
            organizationsSeen.add(organizationId);
            return Optional.ofNullable(rows.get(instanceId)).filter(v -> v.lifecycle() == InstanceLifecycle.ACTIVE);
        }

        @Override
        public List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId) {
            throw new AssertionError();
        }

        @Override
        public List<UUID> pendingOlderThan(UUID organizationId, Duration age) {
            throw new AssertionError();
        }

        @Override
        public List<UUID> pending(UUID organizationId, int limit) {
            calls.add("dp.pending");
            return List.of();
        }

        @Override
        public void settlePending(UUID organizationId, Set<UUID> registered, Duration abandonAfter) {
            throw new AssertionError("nothing is pending, so nothing is settled");
        }

        @Override
        public InstanceView addPlacement(UUID organizationId, UUID instanceId, AddPlacementCommand command) {
            calls.add("dp.addPlacement");
            organizationsSeen.add(organizationId);
            if (addFailure != null) {
                throw addFailure;
            }
            added.add(command);
            InstanceView before = rows.get(instanceId);
            List<PlacementView> placements = new ArrayList<>(before.placements());
            placements.add(new PlacementView(UUID.randomUUID(), command.targetId(),
                    command.placement().containerName(),
                    Optional.ofNullable(command.placement().probe()).map(p -> p.strength())));
            InstanceView after = new InstanceView(before.instanceId(), before.name(), before.environmentId(),
                    before.catalogSolutionId(), before.declaredPackageVersion(), before.lifecycle(),
                    before.acquisition(), placements, before.stateVersion() + 1);
            rows.put(instanceId, after);
            return after;
        }

        @Override
        public InstanceView retirePlacement(UUID organizationId, UUID instanceId, UUID placementId, UUID requestedBy,
                UUID correlationId) {
            calls.add("dp.retirePlacement");
            organizationsSeen.add(organizationId);
            if (retireFailure != null) {
                throw retireFailure;
            }
            retired.add(placementId);
            retiredBy.add(requestedBy);
            retiredCorrelations.add(correlationId);
            InstanceView before = rows.get(instanceId);
            InstanceView after = new InstanceView(before.instanceId(), before.name(), before.environmentId(),
                    before.catalogSolutionId(), before.declaredPackageVersion(), before.lifecycle(),
                    before.acquisition(), before.placements().stream()
                            .filter(p -> !p.placementId().equals(placementId)).toList(), before.stateVersion() + 1);
            rows.put(instanceId, after);
            return after;
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private PlacementView placement(UUID id, UUID target, String name) {
        return new PlacementView(id, target, name, Optional.of(ProbeStrength.LIVENESS));
    }

    private InstanceView view(List<PlacementView> placements) {
        return new InstanceView(instance, "orders", UUID.randomUUID(), catalogSolution, "1", InstanceLifecycle.ACTIVE,
                AcquisitionMode.DIRECT_HTTP, placements, 20L);
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private static ObjectNode http(String path, int port) {
        return MAPPER.createObjectNode().put("kind", "http_json").put("path", path).put("port", port)
                .put("pointer", "/v").put("onUnavailable", "degrade");
    }

    /** One component declaring a probe (liveness, /actuator/health) and one pulled metric on 8081. */
    private static String pullPackage() {
        ObjectNode component = MAPPER.createObjectNode().put("type", "cache-engine").put("cardinality", "exactly-one")
                .put("configPath", "cache-engine");
        component.putObject("health").set("probe", MAPPER.createObjectNode().put("kind", "actuator-health")
                .put("path", "/actuator/health").put("strength", "liveness"));
        component.putArray("metrics").addObject().put("name", "cache.hit_rate").put("metricType", "gauge")
                .put("unit", "ratio").set("source", http("/api/cache/stats", 8081));
        ObjectNode pkg = MAPPER.createObjectNode().put("solution", "cache");
        pkg.putArray("packageVersion").add(1);
        pkg.putArray("components").add(component);
        return pkg.toString();
    }

    /** The same component with a second, PUSHED metric (a {@code solace_event} source). */
    private static String pushPackage() {
        try {
            ObjectNode pkg = (ObjectNode) MAPPER.readTree(pullPackage());
            ((ObjectNode) pkg.get("components").get(0)).withArray("metrics").addObject().put("name", "cache.evictions")
                    .put("metricType", "counter").put("unit", "1").set("source",
                            MAPPER.createObjectNode().put("kind", "solace_event").put("topic", "x/y"));
            return pkg.toString();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private String addPath() {
        return "/api/dp/instances/" + instance + "/placements";
    }

    private ObjectNode addBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("targetId", secondTarget.toString());
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", "sm-orders");
        placement.put("configDirectory", "/srv/sm/orders-2");
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", 9090);
        return body;
    }

    private MvcResult add(String token, ObjectNode body) throws Exception {
        return mvc.perform(post(addPath()).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    private MvcResult retire(String token, UUID placement, String query) throws Exception {
        return mvc.perform(delete("/api/dp/instances/" + instance + "/placements/" + placement + query)
                .cookie(new Cookie(COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-" + token)).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private void assertNothingWritten() {
        assertFalse(calls.has("dp.addPlacement") || calls.has("dp.retirePlacement"),
                "the data plane was written: " + calls.log);
    }

    // ------------------------------------------------------------------------------------ authorization

    @Test
    void addPlacementRequiresBindTargetAtTheInstanceNamedByThePathAndThenTargetUseAtTheBodysTarget() throws Exception {
        Session session = login("tok-add");

        MvcResult result = add("tok-add", addBody());

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(Permission.INSTANCE_BIND_TARGET, Permission.TARGET_USE), authorization.permissions,
                "OQ-8.3s (i): the first verb at the instance the path names, the second at the body's target");
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(instance, authorization.resources.get(0).id());
        assertEquals(ResourceKind.DEPLOYMENT_TARGET, authorization.resources.get(1).kind());
        assertEquals(secondTarget, authorization.resources.get(1).id());
        assertEquals(session.organizationId(), authorization.resources.get(1).tenant().value(), "I5");
    }

    @Test
    void aCallerWithoutBindTargetAtTheInstanceIs403BeforeAnythingElseIsAsked() throws Exception {
        login("tok-deny");
        authorization.outcomeByPermission.put(Permission.INSTANCE_BIND_TARGET, Outcome.DENY);

        MvcResult result = add("tok-deny", addBody());

        assertEquals(403, result.getResponse().getStatus());
        assertEquals(List.of(Permission.INSTANCE_BIND_TARGET), authorization.permissions);
        assertTrue(calls.log.isEmpty(), "no port was touched: " + calls.log);
    }

    @Test
    void theSecondDenialIs403NamingTargetUseAndNothingIsWritten() throws Exception {
        login("tok-second");
        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.DENY);

        MvcResult result = add("tok-second", addBody());

        assertEquals(403, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("target.use"), json(result).toString());
        assertNothingWritten();
    }

    @Test
    void aTargetThatIsNotThisOrganizationsIs404NotADenial() throws Exception {
        login("tok-foreign");
        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.NOT_FOUND);

        MvcResult result = add("tok-foreign", addBody());

        assertEquals(404, result.getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void anInstanceThatIsNotActiveIs404AndNothingIsWritten() throws Exception {
        login("tok-pending");
        inventory.rows.put(instance, new InstanceView(instance, "orders", UUID.randomUUID(), catalogSolution, "1",
                InstanceLifecycle.PENDING, AcquisitionMode.DIRECT_HTTP, List.of(), 1L));

        MvcResult result = add("tok-pending", addBody());

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(List.of(Permission.INSTANCE_BIND_TARGET), authorization.permissions,
                "the second verb is not even asked of an instance that is not there");
        assertNothingWritten();
    }

    @Test
    void theOrganizationIsTheSessionsOnEveryCall() throws Exception {
        Session session = login("tok-org");

        add("tok-org", addBody());
        retire("tok-org", firstPlacement, "?workload=leave-running");

        assertFalse(inventory.organizationsSeen.isEmpty());
        assertTrue(inventory.organizationsSeen.stream().allMatch(session.organizationId()::equals), "I5");
    }

    // --------------------------------------------------------------------------- what comes from where

    @Test
    void theProbeAndTheMetricsComeFromTheInstancesDeclaredPackageAndOnlyThePortsFromTheBody() throws Exception {
        login("tok-source");
        ObjectNode body = addBody();
        ((ObjectNode) body.get("placement")).putObject("metricsPorts").put("8081", 28081);

        MvcResult result = add("tok-source", body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        AddPlacementCommand command = inventory.added.get(0);
        assertEquals("actuator-health", command.placement().probe().kind());
        assertEquals("/actuator/health", command.placement().probe().path());
        assertEquals(ProbeStrength.LIVENESS, command.placement().probe().strength());
        assertEquals(9090, command.placement().probe().hostPort(), "the host-side port is the body's");
        assertEquals(List.of("cache.hit_rate"),
                command.metrics().definitions().stream().map(d -> d.metricName()).toList());
        assertEquals(Map.of(8081, 28081), command.metrics().metricsPorts());
        assertEquals("sha256:" + "a".repeat(64), command.metrics().packageDigest(), "the catalog's stored digest");
        assertEquals(List.of(), command.metrics().activeMetricNames(),
                "the generation-0 list is the instance's, written once at its declare");
        assertEquals(secondTarget, command.targetId());
    }

    @Test
    void aBodyThatNamesAProbeAVersionAHostOrAnOrganizationIsRefusedNotIgnored() throws Exception {
        login("tok-strict");
        for (String extra : List.of("healthPath", "kind", "host", "declaredPackageVersion")) {
            ObjectNode body = addBody();
            ((ObjectNode) body.get("placement")).put(extra, "x");
            assertEquals(400, add("tok-strict", body).getResponse().getStatus(), "placement." + extra);
        }
        for (String extra : List.of("organizationId", "acquisition", "solutionId", "declaredPackageVersion")) {
            ObjectNode body = addBody();
            body.put(extra, "x");
            assertEquals(400, add("tok-strict", body).getResponse().getStatus(), extra);
        }
        assertNothingWritten();
    }

    @Test
    void aMissingTargetOrPlacementIs400() throws Exception {
        login("tok-missing");
        ObjectNode noTarget = addBody();
        noTarget.remove("targetId");
        ObjectNode noPlacement = addBody();
        noPlacement.remove("placement");

        assertEquals(400, add("tok-missing", noTarget).getResponse().getStatus());
        assertEquals(400, add("tok-missing", noPlacement).getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void aTargetThatIsNotLiveIs422NamingTargetIdAndNothingIsWritten() throws Exception {
        login("tok-dead");
        connections.live.remove(secondTarget);

        MvcResult result = add("tok-dead", addBody());

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("targetId"));
        assertNothingWritten();
    }

    @Test
    void aFieldOutsideTheDatabasesGrammarIs422NamingTheFieldAndNothingIsWritten() throws Exception {
        login("tok-grammar");
        ObjectNode body = addBody();
        ((ObjectNode) body.get("placement")).put("configDirectory", "/srv/sm/../etc");

        MvcResult result = add("tok-grammar", body);

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("placement.configDirectory"));
        assertFalse(json(result).toString().contains("/etc"), "never the value");
        assertNothingWritten();
    }

    // ------------------------------------------------------------------------------- data-plane refusals

    @Test
    void aPackageThatDeclaresPushMetricsIsRefusedByTheDataPlaneAs409AndNamesNoMetric() throws Exception {
        login("tok-push");
        catalog.serve(catalogSolution, "1", pushPackage());
        inventory.addFailure = new PushMetricsPlacementRefusedException();

        MvcResult result = add("tok-push", addBody());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertFalse(json(result).toString().contains("cache.evictions"), "names no metric");
        assertTrue(inventory.added.isEmpty());
    }

    @Test
    void thePushSourceReachesTheDataPlaneInTheDeclarationSoTheDataPlaneCanRefuseIt() throws Exception {
        login("tok-push-carried");
        catalog.serve(catalogSolution, "1", pushPackage());

        add("tok-push-carried", addBody());

        assertTrue(inventory.added.get(0).metrics().definitions().stream()
                        .anyMatch(d -> d.sourceKind().equals("solace_event")),
                "the gateway does not decide the refusal; it hands the data plane what it needs to");
    }

    @Test
    void anInstanceWideRequestInFlightIs409AndNamesNoRequest() throws Exception {
        login("tok-wide");
        inventory.addFailure = new InstanceRequestInFlightException();

        MvcResult result = add("tok-wide", addBody());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("instance-wide request is in flight"));
        assertFalse(json(result).get("detail").asText().contains("retire"), "this is the add's refusal, not retire's");
    }

    @Test
    void aPlacementCollisionIs409NamingTheClassOnly() throws Exception {
        login("tok-collide");
        inventory.addFailure = new PlacementCollisionException(PlacementCollisionException.Kind.CONFIG_DIRECTORY);

        MvcResult result = add("tok-collide", addBody());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("config directory"));
    }

    @Test
    void anOutsideEnvelopeFieldIs422NamingTheField() throws Exception {
        login("tok-envelope");
        inventory.addFailure = new PlacementOutsideEnvelopeException("containerName");

        MvcResult result = add("tok-envelope", addBody());

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("placement.containerName"));
    }

    @Test
    void anInstanceThatLeftActiveBetweenTheReadAndTheLockIs404() throws Exception {
        login("tok-race");
        inventory.addFailure = new InstanceNotActiveException();

        assertEquals(404, add("tok-race", addBody()).getResponse().getStatus());
    }

    @Test
    void aTargetRetiredBetweenTheCheckAndTheLockIs422NamingTargetId() throws Exception {
        login("tok-target-race");
        inventory.addFailure = new NoSuchElementException("the target has no live connection record");

        MvcResult result = add("tok-target-race", addBody());

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("targetId"));
    }

    @Test
    void theCreatedInstanceCarriesEveryLivePlacementAndAFreshStateVersion() throws Exception {
        login("tok-created");

        MvcResult result = add("tok-created", addBody());

        JsonNode body = json(result);
        assertEquals(21, body.get("stateVersion").asLong());
        assertEquals(2, body.get("placements").size());
        assertEquals(secondTarget.toString(), body.get("placements").get(1).get("targetId").asText());
        assertEquals("liveness", body.get("placements").get(1).get("probeStrength").asText());
        assertFalse(body.has("acquisition") && body.get("acquisition").has("host"), "OR-82");
    }

    // -------------------------------------------------------------------------------------- retirePlacement

    private UUID secondPlacement() {
        UUID second = UUID.randomUUID();
        inventory.rows.put(instance, view(List.of(placement(firstPlacement, firstTarget, "sm-orders"),
                placement(second, secondTarget, "sm-orders"))));
        return second;
    }

    @Test
    void retireRequiresBindTargetAtTheInstanceAndNoSecondVerb() throws Exception {
        login("tok-retire");
        UUID second = secondPlacement();

        MvcResult result = retire("tok-retire", second, "?workload=leave-running");

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(Permission.INSTANCE_BIND_TARGET), authorization.permissions,
                "removal touches no target, so target.use is not asked (OPS-Q12 R-1)");
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(instance, authorization.resources.get(0).id());
    }

    @Test
    void theWorkloadParameterMustBeLeaveRunningAndAnythingElseIs400BeforeAnythingIsWritten() throws Exception {
        login("tok-workload");
        UUID second = secondPlacement();

        for (String query : List.of("", "?workload=", "?workload=stop", "?workload=remove", "?workload=LEAVE-RUNNING",
                "?other=leave-running")) {
            assertEquals(400, retire("tok-workload", second, query).getResponse().getStatus(), query);
        }

        assertNothingWritten();
        assertEquals(2, inventory.rows.get(instance).placements().size());
    }

    @Test
    void theResponseSaysTheWorkloadWasLeftRunningAndShowsTheRemainingPlacements() throws Exception {
        Session session = login("tok-says");
        UUID second = secondPlacement();

        MvcResult result = retire("tok-says", second, "?workload=leave-running");

        JsonNode body = json(result);
        assertEquals(second.toString(), body.get("placementId").asText());
        assertEquals("left-running", body.get("workload").asText());
        assertEquals(1, body.get("instance").get("placements").size());
        assertEquals(firstPlacement.toString(), body.get("instance").get("placements").get(0).get("placementId").asText());
        assertEquals(List.of(second), inventory.retired);
        assertEquals(List.of(session.principal().id()), inventory.retiredBy);
        assertEquals(1, inventory.retiredCorrelations.size(), "plan 012 T5: the request's correlation id reaches the record");
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"),
                inventory.retiredCorrelations.get(0).toString(), "the id the response carries is the one recorded");
    }

    @Test
    void aPlacementThatIsNotALivePlacementOfThisInstanceIs404AndNothingIsWritten() throws Exception {
        login("tok-unknown");
        secondPlacement();

        assertEquals(404, retire("tok-unknown", UUID.randomUUID(), "?workload=leave-running").getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void anInstanceThatIsNotActiveIs404ForRetireToo() throws Exception {
        login("tok-retire-pending");
        UUID second = secondPlacement();
        inventory.rows.put(instance, new InstanceView(instance, "orders", UUID.randomUUID(), catalogSolution, "1",
                InstanceLifecycle.ABANDONED, AcquisitionMode.DIRECT_HTTP, List.of(), 1L));

        assertEquals(404, retire("tok-retire-pending", second, "?workload=leave-running").getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void theLastLivePlacementAndWorkInFlightAreBothA409WithDifferentDetails() throws Exception {
        login("tok-409");
        UUID second = secondPlacement();

        inventory.retireFailure = new LastLivePlacementException();
        MvcResult last = retire("tok-409", second, "?workload=leave-running");
        inventory.retireFailure = new PlacementInFlightException();
        MvcResult busy = retire("tok-409", second, "?workload=leave-running");

        assertEquals(409, last.getResponse().getStatus());
        assertEquals(409, busy.getResponse().getStatus());
        assertEquals("CONFLICT", json(last).get("code").asText());
        assertEquals("CONFLICT", json(busy).get("code").asText());
        assertNotEquals(json(last).get("detail").asText(), json(busy).get("detail").asText());
        assertTrue(json(last).get("detail").asText().contains("undeclare"));
    }

    @Test
    void aPendingScheduleOnThePlacementIsAFixedTextConflictThatNamesNoSchedule() throws Exception {
        login("tok-sched");
        UUID second = secondPlacement();
        inventory.retireFailure = new PlacementSchedulePendingException();
        MvcResult pending = retire("tok-sched", second, "?workload=leave-running");

        assertEquals(409, pending.getResponse().getStatus());
        assertEquals("CONFLICT", json(pending).get("code").asText());
        assertTrue(json(pending).get("detail").asText().contains("scheduled apply"));
    }

    @Test
    void aPlacementRetiredAfterTheReadIs404() throws Exception {
        login("tok-retire-race");
        UUID second = secondPlacement();
        inventory.retireFailure = new com.solace.sm.dp.api.inventory.PlacementNotLiveException();

        assertEquals(404, retire("tok-retire-race", second, "?workload=leave-running").getResponse().getStatus());
    }
}
