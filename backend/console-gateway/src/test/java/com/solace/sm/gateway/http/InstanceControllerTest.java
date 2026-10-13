package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.CatalogEntry;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.PackageLoad;
import com.solace.sm.cp.api.catalog.PackageVersionId;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceNameTakenException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * {@code declareInstance}, {@code listInstances} and {@code getInstance} (plan 010 T4.4) over the real filter,
 * interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports FAKED. Every fake
 * appends to ONE ordered call log, so the tests can say not only "reserve ran" but "reserve ran before register, and
 * the sweep ran before either" - the order is the protocol (BE-D32), and the crash-window guarantees hold only
 * because of it.
 */
class InstanceControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOLUTION = "cache";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeInventory inventory;
    private FakeConnections connections;
    private FakeGovernance governance;
    private FakeCatalog catalog;
    private FakeConnectionDirectory connectionDirectory;
    private UUID organization;
    private UUID environment;
    private UUID target;
    private UUID catalogSolution;

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
        calls = context.getBean(Calls.class);
        inventory = context.getBean(FakeInventory.class);
        connections = context.getBean(FakeConnections.class);
        governance = context.getBean(FakeGovernance.class);
        catalog = context.getBean(FakeCatalog.class);
        connectionDirectory = context.getBean(FakeConnectionDirectory.class);
        organization = UUID.randomUUID();
        environment = UUID.randomUUID();
        target = UUID.randomUUID();
        catalogSolution = UUID.randomUUID();
        connections.live.add(target);
        catalog.serve(catalogSolution, "1", packageDocument(probe("actuator-health", "/actuator/health", 8081,
                "liveness")));
    }

    // -------------------------------------------------------------------------------------------------- rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        Calls calls() {
            return new Calls();
        }

        @Bean
        FakeInventory inventory(Calls calls) {
            return new FakeInventory(calls);
        }

        @Bean
        FakeConnections connections() {
            return new FakeConnections();
        }

        @Bean
        FakeGovernance governance(Calls calls) {
            return new FakeGovernance(calls);
        }

        @Bean
        FakeCatalog catalog() {
            return new FakeCatalog();
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
        EnvironmentResolver environmentResolver() {
            return new EnvironmentResolver();
        }

        @Bean
        InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean
        FakeConnectionDirectory connectionDirectory() {
            return new FakeConnectionDirectory();
        }

        @Bean
        DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
                InstanceGovernance governance, SolutionCatalog catalog,
                com.solace.sm.cp.api.authz.AuthorizationService authorization, ReachAuthorization reach,
                com.solace.sm.cp.api.designer.DesignerCodec codec,
                com.solace.sm.cp.api.designer.MetricApplicability applicability,
                FakeConnectionDirectory connectionDirectory) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10), connectionDirectory);
        }

        @Bean
        InstanceController instanceController(DeclareOrchestrator orchestrator, RequestOriginFactory origins) {
            return new InstanceController(orchestrator, origins);
        }
    }

    /** One ordered log shared by every fake. */
    static final class Calls {
        final List<String> log = new ArrayList<>();

        void add(String call) {
            log.add(call);
        }

        boolean has(String call) {
            return log.contains(call);
        }

        int indexOf(String call) {
            return log.indexOf(call);
        }
    }

    static final class FakeInventory implements InstanceInventoryService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final Map<UUID, InstanceView> rows = new LinkedHashMap<>();
        final List<DeclareInstanceCommand> reserved = new ArrayList<>();
        final List<UUID> abandoned = new ArrayList<>();
        List<UUID> pending = List.of();
        Set<UUID> settledRegistered;
        Duration settledWindow;
        RuntimeException reserveFailure;
        RuntimeException activateFailure;
        UUID lastListCatalog;

        FakeInventory(Calls calls) {
            this.calls = calls;
        }

        @Override
        public InstanceView reserve(UUID organizationId, DeclareInstanceCommand command) {
            calls.add("dp.reserve");
            organizationsSeen.add(organizationId);
            if (reserveFailure != null) {
                throw reserveFailure;
            }
            reserved.add(command);
            UUID id = UUID.randomUUID();
            InstanceView view = new InstanceView(id, command.name(), command.environmentId(),
                    command.catalogSolutionId(), command.declaredPackageVersion(), InstanceLifecycle.PENDING,
                    command.acquisition(),
                    List.of(new PlacementView(UUID.randomUUID(), command.targetId(),
                            command.placement().containerName(),
                            Optional.ofNullable(command.placement().probe()).map(HealthProbeSpec::strength),
                            command.connectionId())),
                    11L);
            rows.put(id, view);
            return view;
        }

        @Override
        public InstanceView activate(UUID organizationId, UUID instanceId) {
            calls.add("dp.activate");
            organizationsSeen.add(organizationId);
            if (activateFailure != null) {
                throw activateFailure;
            }
            InstanceView p = rows.get(instanceId);
            InstanceView active = new InstanceView(p.instanceId(), p.name(), p.environmentId(), p.catalogSolutionId(),
                    p.declaredPackageVersion(), InstanceLifecycle.ACTIVE, p.acquisition(), p.placements(), 12L);
            rows.put(instanceId, active);
            return active;
        }

        @Override
        public void abandon(UUID organizationId, UUID instanceId) {
            calls.add("dp.abandon");
            organizationsSeen.add(organizationId);
            abandoned.add(instanceId);
        }

        @Override
        public Optional<InstanceView> findActive(UUID organizationId, UUID instanceId) {
            calls.add("dp.findActive");
            organizationsSeen.add(organizationId);
            return Optional.ofNullable(rows.get(instanceId)).filter(v -> v.lifecycle() == InstanceLifecycle.ACTIVE);
        }

        @Override
        public List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId) {
            calls.add("dp.listActive");
            organizationsSeen.add(organizationId);
            lastListCatalog = catalogSolutionId;
            return rows.values().stream().filter(v -> v.lifecycle() == InstanceLifecycle.ACTIVE)
                    .filter(v -> v.catalogSolutionId().equals(catalogSolutionId)).toList();
        }

        @Override
        public List<UUID> pendingOlderThan(UUID organizationId, Duration age) {
            throw new AssertionError("the gateway sweeps with pending/settlePending");
        }

        @Override
        public List<UUID> pending(UUID organizationId, int limit) {
            calls.add("dp.pending");
            organizationsSeen.add(organizationId);
            return pending;
        }

        @Override
        public void settlePending(UUID organizationId, Set<UUID> registered, Duration abandonAfter) {
            calls.add("dp.settlePending");
            organizationsSeen.add(organizationId);
            settledRegistered = registered;
            settledWindow = abandonAfter;
        }

        void put(InstanceView view) {
            rows.put(view.instanceId(), view);
        }
    }

    /** Plan 014: the control plane's Connection registry, as {@code cp-api} shows it to the gateway. */
    static final class FakeConnectionDirectory implements com.solace.sm.cp.api.connection.ConnectionDirectory {
        final Map<UUID, com.solace.sm.cp.api.connection.ConnectionView> rows = new LinkedHashMap<>();
        final List<UUID> organizationsSeen = new ArrayList<>();

        void put(UUID organization, UUID id, String type, boolean active) {
            java.time.Instant now = java.time.Instant.now();
            rows.put(id, new com.solace.sm.cp.api.connection.ConnectionView(id, "conn-" + id, type, 1,
                    com.solace.sm.cp.api.authz.ScopePath.organization(organization), Map.of("host", "vm.example"),
                    Map.of(), active, 0, 1L, now, now));
        }

        @Override
        public List<com.solace.sm.cp.api.connection.ConnectionView> list(UUID organizationId) {
            organizationsSeen.add(organizationId);
            return List.copyOf(rows.values());
        }
    }

    static final class FakeConnections implements TargetConnectionService {
        final Set<UUID> live = new HashSet<>();
        /** Plan 014: connections whose DP runtime projection is live. */
        final Set<UUID> projected = new HashSet<>();

        @Override
        public boolean connectionRuntimeLive(UUID organizationId, UUID connectionId) {
            return projected.contains(connectionId);
        }

        @Override
        public void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey,
                UUID actor) {
            throw new AssertionError("a declare never registers a target");
        }

        @Override
        public void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor) {
            throw new AssertionError("a declare never rotates a credential");
        }

        @Override
        public Map<UUID, TargetEnvelope> liveEnvelopes(UUID organizationId, Collection<UUID> targetIds) {
            Map<UUID, TargetEnvelope> out = new HashMap<>();
            for (UUID id : targetIds) {
                if (live.contains(id)) {
                    out.put(id, new TargetEnvelope("/srv/sm", "sm-"));
                }
            }
            return out;
        }
    }

    static final class FakeGovernance implements InstanceGovernance {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<UUID> registeredEnvironments = new ArrayList<>();
        final List<UUID> registeredInstances = new ArrayList<>();
        final List<PrincipalRef> actors = new ArrayList<>();
        final List<RequestOrigin> origins = new ArrayList<>();
        final List<String> quotaAsked = new ArrayList<>();
        Set<UUID> registered = Set.of();
        boolean withinQuota = true;
        RuntimeException registerFailure;

        FakeGovernance(Calls calls) {
            this.calls = calls;
        }

        @Override
        public GovernanceRegistration register(PrincipalRef actor, RequestOrigin origin, UUID organizationId,
                UUID environmentId, UUID catalogSolutionId, UUID instanceId) {
            calls.add("cp.register");
            organizationsSeen.add(organizationId);
            actors.add(actor);
            origins.add(origin);
            if (registerFailure != null) {
                throw registerFailure;
            }
            registeredEnvironments.add(environmentId);
            registeredInstances.add(instanceId);
            return new GovernanceRegistration(UUID.randomUUID(), false, instanceId);
        }

        @Override
        public Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds) {
            calls.add("cp.registered");
            organizationsSeen.add(organizationId);
            Set<UUID> answer = new HashSet<>(instanceIds);
            answer.retainAll(registered);
            return answer;
        }

        @Override
        public boolean withinQuota(UUID organizationId, String solution) {
            calls.add("cp.withinQuota");
            organizationsSeen.add(organizationId);
            quotaAsked.add(solution);
            return withinQuota;
        }
    }

    static final class FakeCatalog implements SolutionCatalog {
        final Map<String, String> documents = new HashMap<>();
        final Map<String, LoadedPackage> packages = new HashMap<>();

        void serve(UUID catalogSolution, String version, String document) {
            String key = catalogSolution + "|" + version;
            documents.put(key, document);
            packages.put(key, new LoadedPackage(catalogSolution, SOLUTION, List.of(PackageVersionId.of(1)),
                    "sha256:" + "a".repeat(64), "unsigned", 3));
        }

        @Override
        public PackageLoad load(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String packageJson) {
            throw new AssertionError("a declare never loads a package");
        }

        @Override
        public List<CatalogEntry> list(UUID organizationId) {
            throw new AssertionError("a declare never lists the catalog");
        }

        @Override
        public Optional<LoadedPackage> find(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            return Optional.ofNullable(packages.get(catalogSolutionId + "|" + packageVersion));
        }

        @Override
        public Optional<String> documentOf(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
            return Optional.ofNullable(documents.get(catalogSolutionId + "|" + packageVersion));
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private String declarePath() {
        return "/api/dp/environments/" + environment + "/instances";
    }

    private ObjectNode body() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("solutionId", catalogSolution.toString());
        body.put("name", "orders-cache");
        body.put("targetId", target.toString());
        body.put("declaredPackageVersion", 1);
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", "sm-orders");
        placement.put("configDirectory", "/srv/sm/orders");
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", 9090);
        body.putObject("acquisition").put("mode", "direct-http");
        return body;
    }

    private MvcResult declare(String token, ObjectNode body) throws Exception {
        return mvc.perform(post(declarePath()).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String code(MvcResult result) throws Exception {
        return json(result).get("code").asText();
    }

    private static String probe(String kind, String path, int port, String strength) {
        ObjectNode probe = MAPPER.createObjectNode().put("kind", kind);
        if (path != null) {
            probe.put("path", path);
        }
        if (port > 0) {
            probe.put("port", port);
        }
        probe.put("strength", strength);
        return probe.toString();
    }

    private static String packageDocument(String probeJson) {
        return "{\"solution\":\"cache\",\"packageVersion\":[1],\"components\":[{\"health\":{\"probe\":" + probeJson
                + "}}]}";
    }

    private InstanceView activeInstance(UUID id, String name, UUID env, UUID catalogId, long stateVersion) {
        return new InstanceView(id, name, env, catalogId, "1", InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(UUID.randomUUID(), target, "sm-" + name, Optional.of(ProbeStrength.LIVENESS))),
                stateVersion);
    }

    private void assertNothingWritten() {
        assertFalse(calls.has("dp.reserve"), "the data plane was written: " + calls.log);
        assertFalse(calls.has("cp.register"), "the control plane was written: " + calls.log);
        assertFalse(calls.has("dp.activate"), calls.log.toString());
    }

    // ------------------------------------------------------------------------------------ authorization

    @Test
    void declareRequiresInstanceCreateAtTheEnvironmentNamedByThePath() throws Exception {
        Session session = login("tok-create");

        declare("tok-create", body());

        assertEquals(Permission.INSTANCE_CREATE, authorization.permissions.get(0));
        assertEquals(ResourceKind.ENVIRONMENT, authorization.resources.get(0).kind());
        assertEquals(environment, authorization.resources.get(0).id());
        assertEquals(session.organizationId(), authorization.resources.get(0).tenant().value(), "I5");
    }

    @Test
    void aCallerWithoutInstanceCreateIs403BeforeTheBodyIsReadAndNothingIsWritten() throws Exception {
        login("tok-deny");
        authorization.outcomeByPermission.put(Permission.INSTANCE_CREATE, Outcome.DENY);

        MvcResult result = declare("tok-deny", body());

        assertEquals(403, result.getResponse().getStatus());
        assertEquals(List.of(Permission.INSTANCE_CREATE), authorization.permissions,
                "the secondary verbs are not even asked once the primary one fails");
        assertTrue(calls.log.isEmpty(), "no port was touched: " + calls.log);
    }

    @Test
    void declareAlsoRequiresBindTargetAndTargetUseAndNamesWhichIsMissing() throws Exception {
        login("tok-two-sided");
        authorization.outcomeByPermission.put(Permission.INSTANCE_BIND_TARGET, Outcome.DENY);

        MvcResult noBind = declare("tok-two-sided", body());

        assertEquals(403, noBind.getResponse().getStatus());
        assertEquals("FORBIDDEN", code(noBind));
        assertTrue(json(noBind).get("detail").asText().contains("instance.bindTarget"), json(noBind).toString());
        assertFalse(json(noBind).get("detail").asText().contains("target.use"), json(noBind).toString());
        assertTrue(calls.log.isEmpty(), "nothing was written, swept or read: " + calls.log);

        authorization.outcomeByPermission.clear();
        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.DENY);
        MvcResult noUse = declare("tok-two-sided", body());

        assertEquals(403, noUse.getResponse().getStatus());
        assertTrue(json(noUse).get("detail").asText().contains("target.use"), json(noUse).toString());
        assertTrue(calls.log.isEmpty(), calls.log.toString());
        // the three checks, at the right resources
        assertEquals(Permission.INSTANCE_BIND_TARGET, authorization.permissions.get(1));
        assertEquals(ResourceKind.ENVIRONMENT, authorization.resources.get(1).kind());
        assertEquals(environment, authorization.resources.get(1).id());
        int lastUse = authorization.permissions.lastIndexOf(Permission.TARGET_USE);
        assertEquals(ResourceKind.DEPLOYMENT_TARGET, authorization.resources.get(lastUse).kind());
        assertEquals(target, authorization.resources.get(lastUse).id(), "target.use is checked AT THE TARGET");
    }

    @Test
    void aTargetTheCallerCannotSeeIsRefusedTargetUseAs404AndNothingIsWritten() throws Exception {
        login("tok-sibling");
        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.NOT_FOUND);

        MvcResult result = declare("tok-sibling", body());

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", code(result));
        assertNothingWritten();
        assertTrue(calls.log.isEmpty(), calls.log.toString());
    }

    @Test
    void aDeclareIntoAnotherOrganizationsEnvironmentIs404AndWritesNothing() throws Exception {
        login("tok-foreign-env");
        authorization.outcomeByPermission.put(Permission.INSTANCE_CREATE, Outcome.NOT_FOUND);

        MvcResult result = declare("tok-foreign-env", body());

        assertEquals(404, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), "neither plane was touched: " + calls.log);
    }

    @Test
    void bothPlanesAreWrittenForTheSessionsOrganizationNeverARequestField() throws Exception {
        Session session = login("tok-i5");
        UUID other = UUID.randomUUID();

        MvcResult result = mvc.perform(post(declarePath() + "?organizationId=" + other)
                .cookie(new Cookie(COOKIE, "tok-i5")).header("X-Organization", other.toString())
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-tok-i5")
                .contentType(MediaType.APPLICATION_JSON).content(body().toString())).andReturn();

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertFalse(inventory.organizationsSeen.isEmpty());
        assertTrue(inventory.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                "DP tenant: " + inventory.organizationsSeen);
        assertTrue(governance.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                "CP organization: " + governance.organizationsSeen);
        assertEquals(List.of(session.principal()), governance.actors);
        assertEquals(session.principal().id(), inventory.reserved.get(0).requestedBy());
    }

    // ----------------------------------------------------------------------------------- the protocol

    @Test
    void aDeclareRunsTheSweepThenReserveThenRegisterThenActivateAndReturns201() throws Exception {
        login("tok-happy");
        UUID stale = UUID.randomUUID();
        inventory.pending = List.of(stale);
        governance.registered = Set.of(stale);

        MvcResult result = declare("tok-happy", body());

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of("dp.pending", "cp.registered", "dp.settlePending", "cp.withinQuota", "dp.reserve",
                "cp.register", "dp.activate"), calls.log, "BE-D32: sweep, pre-check, reserve, register, activate");
        assertEquals(Set.of(stale), inventory.settledRegistered);
        assertEquals(Duration.ofMinutes(10), inventory.settledWindow);
        UUID minted = inventory.reserved.isEmpty() ? null : governance.registeredInstances.get(0);
        assertNotNull(minted);
        JsonNode item = json(result);
        assertEquals(minted.toString(), item.get("instanceId").asText(), "the CP node carries the id the DP minted");
        assertEquals(environment, governance.registeredEnvironments.get(0));
        assertEquals(12, item.get("stateVersion").asLong(), "the ACTIVATED row's version, not the pending one");
        assertEquals(catalogSolution.toString(), item.get("solutionId").asText());
        assertEquals(environment.toString(), item.get("environmentId").asText());
        assertEquals(1, item.get("declaredPackageVersion").asInt());
        assertEquals("sm-orders", item.at("/placements/0/containerName").asText());
        assertEquals("liveness", item.at("/placements/0/probeStrength").asText());
    }

    @Test
    void aSweepWithNothingPendingCostsOneReadAndNoCpCall() throws Exception {
        login("tok-quiet");

        declare("tok-quiet", body());

        assertEquals("dp.pending", calls.log.get(0));
        assertFalse(calls.has("cp.registered"));
        assertFalse(calls.has("dp.settlePending"));
    }

    @Test
    void theDpRowIsReservedBeforeTheCpNodeAndNeverTheOtherWayRound() throws Exception {
        login("tok-order");

        declare("tok-order", body());

        assertTrue(calls.indexOf("dp.reserve") >= 0 && calls.indexOf("dp.reserve") < calls.indexOf("cp.register"),
                calls.log.toString());
        assertTrue(calls.indexOf("cp.register") < calls.indexOf("dp.activate"), calls.log.toString());
    }

    @Test
    void aPlacementCollisionIs409ConflictNamingTheClassAndWritesNothingOnEitherPlane() throws Exception {
        login("tok-collision");
        inventory.reserveFailure = new PlacementCollisionException(PlacementCollisionException.Kind.CONTAINER_NAME);

        MvcResult result = declare("tok-collision", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", code(result));
        assertTrue(json(result).get("detail").asText().contains("container name"), json(result).toString());
        assertFalse(json(result).toString().contains("sm-orders"), "the value is never echoed");
        assertFalse(calls.has("cp.register"), "the CP was not written: " + calls.log);
        assertFalse(calls.has("dp.activate"));
    }

    @Test
    void aDuplicateLiveNameIs409ConflictAndWritesNothingOnTheControlPlane() throws Exception {
        login("tok-name");
        inventory.reserveFailure = new InstanceNameTakenException();

        MvcResult result = declare("tok-name", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", code(result));
        assertFalse(json(result).toString().contains("orders-cache"), "the name is never echoed");
        assertFalse(calls.has("cp.register"));
    }

    @Test
    void aConfigDirectoryOutsideTheTargetRootIs422NamingTheFieldAndWritesNothingOnEitherPlane() throws Exception {
        login("tok-dir");
        inventory.reserveFailure = new PlacementOutsideEnvelopeException("configDirectory");
        ObjectNode sshDir = body();
        ((ObjectNode) sshDir.get("placement")).put("configDirectory", "/home/ec2-user/.ssh");

        MvcResult result = declare("tok-dir", sshDir);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("configDirectory"), json(result).toString());
        assertFalse(json(result).toString().contains(".ssh"), "the value is never echoed");
        assertFalse(calls.has("cp.register"));
        assertFalse(calls.has("dp.activate"));
    }

    @Test
    void aContainerOutsideTheTargetPrefixIs422NamingTheField() throws Exception {
        login("tok-prefix");
        inventory.reserveFailure = new PlacementOutsideEnvelopeException("containerName");

        MvcResult result = declare("tok-prefix", body());

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("containerName"), json(result).toString());
        assertFalse(calls.has("cp.register"));
    }

    @Test
    void aTargetWithNoLiveConnectionRecordIs422NamingTargetIdAndWritesNothing() throws Exception {
        login("tok-noconn");
        connections.live.clear();

        MvcResult result = declare("tok-noconn", body());

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("targetId"), json(result).toString());
        assertNothingWritten();
    }

    @Test
    void overQuotaAtTheCpIs409EntitlementRefusedAndTheDpRowIsAbandonedSynchronously() throws Exception {
        login("tok-quota");
        governance.registerFailure = new QuotaExceededException();

        MvcResult result = declare("tok-quota", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("ENTITLEMENT_REFUSED", code(result));
        assertEquals(1, inventory.abandoned.size(), "BE-D32 rule 3: the just-reserved row is abandoned now");
        assertEquals(inventory.rows.keySet().iterator().next(), inventory.abandoned.get(0));
        assertFalse(calls.has("dp.activate"));
        assertTrue(calls.indexOf("dp.abandon") > calls.indexOf("cp.register"), calls.log.toString());
    }

    @Test
    void anEntitlementRefusalAtTheCpAlsoAbandonsTheReservedRow() throws Exception {
        login("tok-unentitled");
        governance.registerFailure = new EntitlementRefusedException();

        MvcResult result = declare("tok-unentitled", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("ENTITLEMENT_REFUSED", code(result));
        assertEquals(1, inventory.abandoned.size());
    }

    @Test
    void theQuotaPreCheckSparesADoomedReserve() throws Exception {
        login("tok-precheck");
        governance.withinQuota = false;

        MvcResult result = declare("tok-precheck", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("ENTITLEMENT_REFUSED", code(result));
        assertEquals(List.of(SOLUTION), governance.quotaAsked, "the package's declared solution string");
        assertNothingWritten();
        assertTrue(inventory.abandoned.isEmpty(), "nothing was reserved, so nothing is abandoned");
    }

    @Test
    void aDuplicateInstanceIdFromTheCpIsALoudDefectNotAnAbandonAndTheSweepFinishesIt() throws Exception {
        login("tok-dup");
        // cp-core throws Spring's DuplicateKeyException naming instance_id_unique (GovernanceServiceIT); the gateway
        // cannot name it, and BE-D32 rule 1 makes the duplicate a defect of the minting side, not a client error.
        governance.registerFailure = new IllegalStateException("duplicate key value violates unique constraint");

        MvcResult result = declare("tok-dup", body());

        assertEquals(500, result.getResponse().getStatus());
        assertEquals("INTERNAL_ERROR", code(result));
        assertTrue(inventory.abandoned.isEmpty(),
                "an unknown outcome is NOT abandoned here: the lazy sweep abandons an unregistered row after the "
                        + "window and activates a registered one, so it is right for either outcome");
        assertFalse(calls.has("dp.activate"));
    }

    @Test
    void aRegistrationThatLandedAfterTheSweepAbandonedTheRowIsALoud500NotASilentSuccess() throws Exception {
        login("tok-late");
        inventory.activateFailure = new IllegalStateException("an abandoned instance cannot be activated");

        MvcResult result = declare("tok-late", body());

        assertEquals(500, result.getResponse().getStatus());
        assertEquals(1, governance.registeredInstances.size(), "the CP node exists: BE-D32 is silent on this window");
    }

    // ---------------------------------------------------------------------------------- the probe (M8)

    @Test
    void theProbeKindPathAndStrengthComeFromThePackageNeverTheBody() throws Exception {
        login("tok-probe");
        catalog.serve(catalogSolution, "1", packageDocument(probe("http-status", "/status", 8081, "readiness")));

        MvcResult result = declare("tok-probe", body());   // body healthPort 9090

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        HealthProbeSpec probe = inventory.reserved.get(0).placement().probe();
        assertEquals(new HealthProbeSpec("http-status", "/status", 9090, ProbeStrength.READINESS), probe,
                "kind, path and strength from the package; the host port from the body, not the package's 8081");
        assertEquals("readiness", json(result).at("/placements/0/probeStrength").asText());
    }

    @Test
    void aProbeKeyAnywhereInTheBodyIs400() throws Exception {
        login("tok-bodyprobe");
        ObjectNode withProbe = body();
        ((ObjectNode) withProbe.get("placement")).putObject("probe").put("path", "/evil");
        ObjectNode withHealthPath = body();
        ((ObjectNode) withHealthPath.get("placement")).put("healthPath", "/evil");
        ObjectNode topLevel = body();
        topLevel.put("healthPath", "/evil");

        assertEquals(400, declare("tok-bodyprobe", withProbe).getResponse().getStatus());
        assertEquals(400, declare("tok-bodyprobe", withHealthPath).getResponse().getStatus());
        assertEquals(400, declare("tok-bodyprobe", topLevel).getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void aBrokerHeartbeatOnlyPackageDeclaresNoHttpProbeSoThePlacementCarriesNone() throws Exception {
        login("tok-heartbeat");
        catalog.serve(catalogSolution, "1", packageDocument(probe("broker-heartbeat", null, 0, "liveness")));

        MvcResult result = declare("tok-heartbeat", body());

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertNull(inventory.reserved.get(0).placement().probe());
        assertEquals("none", json(result).at("/placements/0/probeStrength").asText());
    }

    @Test
    void aProbePathOutsideTheGrammarIs422AndNothingIsWritten() throws Exception {
        login("tok-badpath");
        catalog.serve(catalogSolution, "1",
                packageDocument(probe("actuator-health", "/actuator/health?x=$(id)", 8081, "liveness")));

        MvcResult result = declare("tok-badpath", body());

        assertEquals(422, result.getResponse().getStatus());
        assertFalse(json(result).toString().contains("$(id)"), "the value is never echoed");
        assertNothingWritten();
    }

    @Test
    void anHttpProbeKindWithNoPathIs422() throws Exception {
        login("tok-nopath");
        catalog.serve(catalogSolution, "1", packageDocument(probe("actuator-health", null, 8081, "liveness")));

        assertEquals(422, declare("tok-nopath", body()).getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void anUnknownPackageVersionIs422NamingTheField() throws Exception {
        login("tok-version");
        ObjectNode unknown = body();
        unknown.put("declaredPackageVersion", 7);

        MvcResult result = declare("tok-version", unknown);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("declaredPackageVersion"), json(result).toString());
        assertNothingWritten();
    }

    @Test
    void aStringPackageVersionIsMatchedAsItsCanonicalJsonText() throws Exception {
        login("tok-strver");
        catalog.serve(catalogSolution, "\"v2\"", packageDocument(probe("actuator-health", "/h", 8081, "liveness")));
        ObjectNode named = body();
        named.put("declaredPackageVersion", "v2");

        MvcResult result = declare("tok-strver", named);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("\"v2\"", inventory.reserved.get(0).declaredPackageVersion());
        assertEquals("v2", json(result).get("declaredPackageVersion").asText(), "a string id stays a string");
    }

    // --------------------------------------------------------------------------- acquisition (OR-71, OR-82)

    @Test
    void theDeclareBodyCarriesTheAcquisitionModeAndItIsReturnedOnTheInstance() throws Exception {
        login("tok-mode");
        ObjectNode viaSsh = body();
        ((ObjectNode) viaSsh.get("acquisition")).put("mode", "via-ssh");

        MvcResult direct = declare("tok-mode", body());
        MvcResult ssh = declare("tok-mode", viaSsh);

        assertEquals(201, direct.getResponse().getStatus(), direct.getResponse().getContentAsString());
        assertEquals("direct-http", json(direct).at("/acquisition/mode").asText());
        assertEquals(AcquisitionMode.DIRECT_HTTP, inventory.reserved.get(0).acquisition());
        assertEquals("via-ssh", json(ssh).at("/acquisition/mode").asText());
        assertEquals(AcquisitionMode.VIA_SSH, inventory.reserved.get(1).acquisition());
        assertEquals(Set.of("mode"), fieldNames(json(direct).get("acquisition")), "the mode only: no host (OR-82)");
    }

    @Test
    void declareBodyWithAnAcquisitionHostIsRefused422() throws Exception {
        login("tok-host");
        for (String mode : new String[] {"direct-http", "via-ssh"}) {
            ObjectNode withHost = body();
            ObjectNode acquisition = (ObjectNode) withHost.get("acquisition");
            acquisition.put("mode", mode);
            acquisition.put("host", "169.254.169.254");

            MvcResult result = declare("tok-host", withHost);

            assertEquals(422, result.getResponse().getStatus(), mode);
            assertEquals("VALIDATION_FAILED", code(result));
            assertTrue(json(result).get("detail").asText().contains("acquisition"), json(result).toString());
            assertFalse(json(result).toString().contains("169.254"), "the value is never echoed");
        }
        assertNothingWritten();
        assertTrue(calls.log.isEmpty(), "refused before any port: " + calls.log);
    }

    @Test
    void anAcquisitionWithNoModeAnUnknownModeOrANonObjectIs422() throws Exception {
        login("tok-badmode");
        ObjectNode noMode = body();
        ((ObjectNode) noMode.get("acquisition")).removeAll();
        ObjectNode unknown = body();
        ((ObjectNode) unknown.get("acquisition")).put("mode", "auto-fallback");
        ObjectNode notObject = body();
        notObject.put("acquisition", "direct-http");
        ObjectNode agentMode = body();       // V028's third value is an enrolled agent's, never an operator's choice (plan 015 T3.5)
        ((ObjectNode) agentMode.get("acquisition")).put("mode", "agent");

        for (ObjectNode bad : new ObjectNode[] {noMode, unknown, notObject, agentMode}) {
            MvcResult result = declare("tok-badmode", bad);
            assertEquals(422, result.getResponse().getStatus(), bad.toString());
            assertTrue(json(result).get("detail").asText().contains("acquisition"));
        }
        assertNothingWritten();
    }

    // ------------------------------------------------------------------------------ structural 400s

    @Test
    void aBodyNamingAnOrganizationOrMissingARequiredFieldIs400() throws Exception {
        login("tok-400");
        ObjectNode named = body();
        named.put("organizationId", UUID.randomUUID().toString());
        ObjectNode noName = body();
        noName.remove("name");
        ObjectNode noAcquisition = body();
        noAcquisition.remove("acquisition");
        ObjectNode badPort = body();
        ((ObjectNode) badPort.get("placement")).put("healthPort", 70000);
        ObjectNode badVersion = body();
        badVersion.put("declaredPackageVersion", 0);

        for (ObjectNode bad : new ObjectNode[] {named, noName, noAcquisition, badPort, badVersion}) {
            assertEquals(400, declare("tok-400", bad).getResponse().getStatus(), bad.toString());
        }
        assertTrue(calls.log.isEmpty(), calls.log.toString());
    }

    @Test
    void placementFieldGrammarIsDeferredDuringInstanceCreation() throws Exception {
        login("tok-grammar");
        ObjectNode spaced = body();
        ((ObjectNode) spaced.get("placement")).put("containerName", "sm orders; rm -rf");
        ObjectNode traversal = body();
        ((ObjectNode) traversal.get("placement")).put("configDirectory", "/srv/sm/../../etc");

        MvcResult a = declare("tok-grammar", spaced);
        MvcResult b = declare("tok-grammar", traversal);

        assertEquals(201, a.getResponse().getStatus());
        assertEquals(201, b.getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------ listInstances

    @Test
    void anEnvironmentBoundOperatorListsItsOwnEnvironmentsInstancesOnly() throws Exception {
        Session session = login("tok-list");
        UUID mine = UUID.randomUUID();
        UUID sibling = UUID.randomUUID();
        inventory.put(activeInstance(mine, "mine", environment, catalogSolution, 21));
        inventory.put(activeInstance(sibling, "sibling", UUID.randomUUID(), catalogSolution, 22));
        authorization.authorizedIds.add(mine);

        MvcResult result = mvc.perform(get("/api/dp/solutions/" + catalogSolution + "/instances")
                .cookie(new Cookie(COOKIE, "tok-list"))).andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode items = json(result).get("items");
        assertEquals(1, items.size());
        assertEquals(mine.toString(), items.get(0).get("instanceId").asText());
        assertFalse(result.getResponse().getContentAsString().contains(sibling.toString()),
                "an unauthorized id is ABSENT, not merely hidden");
        assertEquals(1, authorization.subsetCalls.size(), "the handler must call authorizedSubset exactly once");
        FakeAuthorizationService.SubsetCall call = authorization.subsetCalls.get(0);
        assertEquals(Permission.INSTANCE_VIEW, call.permission(), "the operation's OWN verb");
        assertEquals(ResourceKind.INSTANCE, call.kind());
        assertEquals(Set.of(mine, sibling), call.ids());
        assertEquals(session.principal(), call.principal());
        assertEquals(List.of(Permission.INSTANCE_VIEW), authorization.reachChecks, "the pre-check uses the same verb");
        assertEquals(catalogSolution, inventory.lastListCatalog, "the path's id is the CATALOG id (OR-41)");
        assertTrue(authorization.resources.isEmpty(), "a reach operation resolves no single resource");
    }

    @Test
    void everyInstanceItemCarriesItsOwnStateVersionAndTheListCarriesNone() throws Exception {
        login("tok-versions");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        inventory.put(activeInstance(a, "a", environment, catalogSolution, 31));
        inventory.put(activeInstance(b, "b", environment, catalogSolution, 32));
        authorization.authorizedIds.addAll(List.of(a, b));

        JsonNode list = json(mvc.perform(get("/api/dp/solutions/" + catalogSolution + "/instances")
                .cookie(new Cookie(COOKIE, "tok-versions"))).andReturn());

        assertNull(list.get("stateVersion"), "§6.1: a collection carries versions per item");
        Set<Long> versions = new HashSet<>();
        list.get("items").forEach(item -> versions.add(item.get("stateVersion").asLong()));
        assertEquals(Set.of(31L, 32L), versions);
        assertEquals("via-ssh", list.at("/items/0/acquisition/mode").asText());
    }

    @Test
    void aListSweepsThisTenantFirstSoARegisteredPendingInstanceBecomesVisible() throws Exception {
        login("tok-list-sweep");
        UUID registeredPending = UUID.randomUUID();
        inventory.pending = List.of(registeredPending);
        governance.registered = Set.of(registeredPending);

        mvc.perform(get("/api/dp/solutions/" + catalogSolution + "/instances")
                .cookie(new Cookie(COOKIE, "tok-list-sweep"))).andReturn();

        assertTrue(calls.indexOf("dp.settlePending") >= 0 && calls.indexOf("dp.settlePending")
                < calls.indexOf("dp.listActive"), "settle precedes the read: " + calls.log);
    }

    @Test
    void aCallerHoldingInstanceViewNowhereIs403AndNothingIsRead() throws Exception {
        login("tok-list-deny");
        authorization.outcome = Outcome.DENY;

        MvcResult result = mvc.perform(get("/api/dp/solutions/" + catalogSolution + "/instances")
                .cookie(new Cookie(COOKIE, "tok-list-deny"))).andReturn();

        assertEquals(403, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), calls.log.toString());
    }

    // ------------------------------------------------------------------------------------- getInstance

    @Test
    void getInstanceRequiresInstanceViewAtTheInstanceAndReturnsItWithItsPlacement() throws Exception {
        Session session = login("tok-get");
        UUID id = UUID.randomUUID();
        inventory.put(activeInstance(id, "orders", environment, catalogSolution, 41));

        MvcResult result = mvc.perform(get("/api/dp/instances/" + id).cookie(new Cookie(COOKIE, "tok-get")))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(Permission.INSTANCE_VIEW), authorization.permissions);
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(id, authorization.resources.get(0).id());
        assertEquals(session.organizationId(), authorization.resources.get(0).tenant().value());
        JsonNode item = json(result);
        assertEquals(41, item.get("stateVersion").asLong());
        assertEquals("orders", item.get("name").asText());
        assertEquals(1, item.get("placements").size());
        assertEquals("sm-orders", item.at("/placements/0/containerName").asText());
    }

    @Test
    void aSiblingEnvironmentsInstanceIs404OnGetAndTheDataPlaneIsNeverRead() throws Exception {
        login("tok-get-sibling");
        authorization.outcome = Outcome.NOT_FOUND;
        UUID id = UUID.randomUUID();
        inventory.put(activeInstance(id, "orders", UUID.randomUUID(), catalogSolution, 51));

        MvcResult result = mvc.perform(get("/api/dp/instances/" + id).cookie(new Cookie(COOKIE, "tok-get-sibling")))
                .andReturn();

        assertEquals(404, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), calls.log.toString());
    }

    @Test
    void aPendingInstanceIs404UntilTheSweepActivatesIt() throws Exception {
        login("tok-get-pending");
        UUID id = UUID.randomUUID();
        InstanceView pending = new InstanceView(id, "orders", environment, catalogSolution, "1",
                InstanceLifecycle.PENDING, AcquisitionMode.VIA_SSH, List.of(), 61);
        inventory.put(pending);

        MvcResult before = mvc.perform(get("/api/dp/instances/" + id).cookie(new Cookie(COOKIE, "tok-get-pending")))
                .andReturn();
        assertEquals(404, before.getResponse().getStatus(), "pending rows are excluded from every read");

        inventory.pending = List.of(id);
        governance.registered = Set.of(id);
        inventory.put(activeInstance(id, "orders", environment, catalogSolution, 62));   // what settle would do
        calls.log.clear();
        MvcResult after = mvc.perform(get("/api/dp/instances/" + id).cookie(new Cookie(COOKIE, "tok-get-pending")))
                .andReturn();

        assertEquals(200, after.getResponse().getStatus());
        assertTrue(calls.indexOf("dp.settlePending") >= 0 && calls.indexOf("dp.settlePending")
                < calls.indexOf("dp.findActive"), "getInstance sweeps before it reads: " + calls.log);
    }

    @Test
    void aMalformedInstanceIdIsNotFoundBeforeAnyPortIsAsked() throws Exception {
        login("tok-get-bad");

        MvcResult result = mvc.perform(get("/api/dp/instances/not-a-uuid").cookie(new Cookie(COOKIE, "tok-get-bad")))
                .andReturn();

        assertEquals(404, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty());
    }


    // ---- plan 011 P11-4.1 (M-Q5, M-Q14, M-Q15): declare carries the package's metrics declaration to the data plane

    private static ObjectNode http(String path, int port) {
        return MAPPER.createObjectNode().put("kind", "http_json").put("path", path).put("port", port)
                .put("pointer", "/v").put("onUnavailable", "degrade");
    }

    /** One exactly-one component: two metrics on 8081 and 8082, a restart signal on 9091 that no metric uses. */
    private String metricsPackage() {
        ObjectNode component = MAPPER.createObjectNode().put("type", "cache-engine").put("cardinality", "exactly-one")
                .put("configPath", "cache-engine");
        component.putObject("health").set("probe", MAPPER.createObjectNode().put("kind", "actuator-health")
                .put("path", "/actuator/health").put("strength", "liveness"));
        var metrics = component.putArray("metrics");
        metrics.addObject().put("name", "cache.hit_rate").put("metricType", "gauge").put("unit", "ratio")
                .set("source", http("/api/cache/stats", 8081));
        metrics.addObject().put("name", "cache.size").put("metricType", "gauge").put("unit", "entries")
                .set("source", http("/api/cache/size", 8082));
        component.set("restartSignal", http("/actuator/info", 9091));
        ObjectNode pkg = MAPPER.createObjectNode().put("solution", "cache");
        pkg.putArray("packageVersion").add(1);
        pkg.putArray("components").add(component);
        return pkg.toString();
    }

    private com.solace.sm.dp.api.metrics.declare.MetricsDeclaration reservedMetrics() {
        return inventory.reserved.get(0).metrics();
    }

    @Test
    void declareHandsTheDataPlaneTheDefinitionsTheEndpointsAndTheCpsStoredDigest() throws Exception {
        login("tok-metrics");
        catalog.serve(catalogSolution, "1", metricsPackage());
        ObjectNode body = body();
        ((ObjectNode) body.get("placement")).putObject("metricsPorts").put("8081", 28081);

        MvcResult result = declare("tok-metrics", body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        var metrics = reservedMetrics();
        assertEquals(List.of("cache.hit_rate", "cache.size"),
                metrics.definitions().stream().map(d -> d.metricName()).toList());
        assertEquals("cache-engine", metrics.definitions().get(0).componentType());
        assertEquals(List.of("cache-engine"), metrics.restartSignals().stream().map(r -> r.componentType()).toList());
        // 8081 is mapped; 8082 (a metric source with no mapping) defaults to itself; 9091 is declared ONLY as the
        // restart signal's port and still gets its own row, or a direct-mode signal read would have no host port
        assertEquals(Map.of(8081, 28081, 8082, 8082, 9091, 9091), metrics.metricsPorts());
        assertFalse(metrics.isNone());
    }

    @Test
    void theDigestPassedToTheDpIsTheCpStoredDigestAndTheGatewayHashesNothing() throws Exception {
        login("tok-digest");
        catalog.serve(catalogSolution, "1", metricsPackage());
        String stored = catalog.find(organization, catalogSolution, "1").orElseThrow().digest();

        declare("tok-digest", body());

        assertEquals(stored, reservedMetrics().packageDigest());
        assertEquals("sha256:" + "a".repeat(64), stored, "the fake's stored digest is not the hash of any document");
    }

    @Test
    void generationZeroIsTheControlPlanesEvaluatorOverTheEmptyDraftAndTheComponentsADeclareAlwaysHas()
            throws Exception {
        login("tok-gen0");
        catalog.serve(catalogSolution, "1", metricsPackage());
        var applicability = context.getBean(MetricsFakes.RecordingApplicability.class);
        applicability.next = List.of("cache.hit_rate");

        declare("tok-gen0", body());

        assertEquals(List.of("cache.hit_rate"), reservedMetrics().activeMetricNames());
        assertEquals(1, applicability.asks.size());
        var ask = applicability.last();
        assertEquals(1L, ask.packageVersion());
        assertTrue(ask.draft().isEmpty(), "package defaults: there is no draft at declare");
        assertEquals(List.of("cache-engine"), ask.selection().instances().stream().map(i -> i.type()).toList(),
                "one instance of each exactly-one component");
    }

    @Test
    void aPackageWithNoMetricsStillRecordsItsDigestAndAnEmptyDeclaration() throws Exception {
        login("tok-nometrics");

        MvcResult result = declare("tok-nometrics", body());

        assertEquals(201, result.getResponse().getStatus());
        assertFalse(reservedMetrics().isNone(), "a package with no metrics is a recorded declaration, not 'none'");
        assertTrue(reservedMetrics().definitions().isEmpty());
        assertTrue(reservedMetrics().metricsPorts().isEmpty());
    }

    @Test
    void aMetricsPortCollisionIs409WithItsOwnCodeNamingTheClassAndNeitherPlaneIsWrittenAfterIt() throws Exception {
        login("tok-collision");
        catalog.serve(catalogSolution, "1", metricsPackage());
        for (var kind : com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException.Kind.values()) {
            calls.log.clear();
            inventory.reserveFailure = new com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException(kind);
            ObjectNode body = body();
            ((ObjectNode) body.get("placement")).putObject("metricsPorts").put("8081", 28081);

            MvcResult result = declare("tok-collision", body);

            assertEquals(409, result.getResponse().getStatus(), kind.toString());
            assertEquals("METRICS_PORT_CONFLICT", code(result), kind.toString());
            // as a token, not a substring: the body carries a random correlation id whose hex digits can contain "28081" by chance
            assertFalse(java.util.regex.Pattern.compile("(?<![0-9A-Za-z-])28081(?![0-9A-Za-z-])")
                    .matcher(result.getResponse().getContentAsString()).find(), "the port is never echoed");
            assertFalse(calls.has("cp.register"), "the control plane was not written: " + calls.log);
            assertFalse(calls.has("dp.activate"), calls.log.toString());
            assertTrue(inventory.abandoned.isEmpty(), "nothing was reserved, so nothing is abandoned");
        }
    }

    @Test
    void aPackageVersionMismatchIs409WithItsOwnCodeAndNothingIsWrittenAfterIt() throws Exception {
        login("tok-mismatch");
        inventory.reserveFailure = new com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException();

        MvcResult result = declare("tok-mismatch", body());

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("PACKAGE_VERSION_MISMATCH", code(result));
        assertFalse(calls.has("cp.register"), calls.log.toString());
        assertFalse(result.getResponse().getContentAsString().contains("sha256"), "no digest is echoed");
    }

    @Test
    void aMetricsPortKeyNoSourceUsesIs422NamingTheFieldAndNothingIsWritten() throws Exception {
        login("tok-unknownport");
        catalog.serve(catalogSolution, "1", metricsPackage());
        ObjectNode body = body();
        ((ObjectNode) body.get("placement")).putObject("metricsPorts").put("8083", 28083);

        MvcResult result = declare("tok-unknownport", body);

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("placement.metricsPorts"), json(result).toString());
        assertFalse(json(result).toString().contains("28083"));
        assertNothingWritten();
    }

    @Test
    void aMetricThatBreaksTheGrammarIs422NamingTheVersionFieldAndNothingIsWritten() throws Exception {
        login("tok-badmetric");
        catalog.serve(catalogSolution, "1", metricsPackage().replace("cache.hit_rate", "Cache-Hit-Rate"));

        MvcResult result = declare("tok-badmetric", body());

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(json(result).get("detail").asText().contains("declaredPackageVersion"), json(result).toString());
        assertFalse(json(result).toString().contains("Cache-Hit-Rate"), "the value is never echoed");
        assertNothingWritten();
    }

    @Test
    void aStoredDocumentThatIsNotAPackageIs422AndNeverQuotedBack() throws Exception {
        login("tok-notapackage");
        catalog.serve(catalogSolution, "1", "[\"sentinel-not-a-package\"]");

        MvcResult result = declare("tok-notapackage", body());

        assertEquals(422, result.getResponse().getStatus());
        assertFalse(json(result).toString().contains("sentinel"));
        assertNothingWritten();
    }

    // ------------------------------------------------------------- plan 014: connection-backed declare

    /** The default body, rebound from the target to a remote-server Connection. */
    private ObjectNode connectionBody(UUID connection) {
        ObjectNode body = body();
        body.remove("targetId");
        body.put("connectionId", connection.toString());
        body.put("deploymentMode", "dp-remote");
        return body;
    }

    /** An active remote-server Connection of the organization with a live DP projection: the declarable kind. */
    private UUID usableConnection() {
        UUID connection = UUID.randomUUID();
        connectionDirectory.put(organization, connection, "remote-server", true);
        connections.projected.add(connection);
        return connection;
    }

    @Test
    void aDpRemoteDeclareBindsTheConnectionAndAuthorizesTargetUseAtTheConnectionsScope() throws Exception {
        login("tok-conn");
        UUID connection = usableConnection();

        MvcResult result = declare("tok-conn", connectionBody(connection));

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        DeclareInstanceCommand command = inventory.reserved.get(0);
        assertEquals(connection, command.connectionId());
        assertNull(command.targetId(), "a connection-backed declare names no target");
        assertEquals(com.solace.sm.dp.api.inventory.DeploymentMode.DP_REMOTE, command.deploymentMode());
        int use = authorization.permissions.lastIndexOf(Permission.TARGET_USE);
        assertEquals(ResourceKind.CONNECTION, authorization.resources.get(use).kind(),
                "target.use is asked at the CONNECTION (plan 014: the same checks, mapped to the Connection)");
        assertEquals(connection, authorization.resources.get(use).id());
        assertTrue(authorization.permissions.contains(Permission.INSTANCE_BIND_TARGET));
        JsonNode placement = json(result).at("/placements/0");
        assertEquals(connection.toString(), placement.get("connectionId").asText());
        assertFalse(placement.has("targetId"), "the absent binding is omitted, never null: " + placement);
        assertTrue(calls.indexOf("dp.reserve") < calls.indexOf("cp.register"));
    }

    @Test
    void anAbsentDeploymentModeIsDpRemote() throws Exception {
        login("tok-conn-default");
        UUID connection = usableConnection();
        ObjectNode body = connectionBody(connection);
        body.remove("deploymentMode");

        MvcResult result = declare("tok-conn-default", body);

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(com.solace.sm.dp.api.inventory.DeploymentMode.DP_REMOTE, inventory.reserved.get(0).deploymentMode());
    }

    @Test
    void theLegacyTargetDeclareStillBindsTheTargetAndIsDpRemote() throws Exception {
        login("tok-legacy");

        MvcResult result = declare("tok-legacy", body());

        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(target, inventory.reserved.get(0).targetId());
        assertNull(inventory.reserved.get(0).connectionId());
        assertEquals(com.solace.sm.dp.api.inventory.DeploymentMode.DP_REMOTE, inventory.reserved.get(0).deploymentMode());
        assertFalse(json(result).at("/placements/0").has("connectionId"));
    }

    @Test
    void theReservedDeploymentModesAre422NamingTheFieldAndWriteNothing() throws Exception {
        login("tok-mode");
        UUID connection = usableConnection();
        for (String reserved : new String[] {"host-local", "federated"}) {
            ObjectNode body = connectionBody(connection);
            body.put("deploymentMode", reserved);

            MvcResult result = declare("tok-mode", body);

            assertEquals(422, result.getResponse().getStatus(), reserved);
            assertEquals("VALIDATION_FAILED", code(result));
            assertTrue(json(result).get("detail").asText().contains("deploymentMode"), json(result).toString());
        }
        assertNothingWritten();
    }

    @Test
    void aDeploymentModeOutsideTheVocabularyIs400() throws Exception {
        login("tok-mode-unknown");
        ObjectNode body = connectionBody(usableConnection());
        body.put("deploymentMode", "kubernetes");

        MvcResult result = declare("tok-mode-unknown", body);

        assertEquals(400, result.getResponse().getStatus());
        assertNothingWritten();
    }

    @Test
    void bothBindingsOrNeitherIs422NamingConnectionIdAndWritesNothing() throws Exception {
        login("tok-xor");
        UUID connection = usableConnection();
        ObjectNode both = connectionBody(connection);
        both.put("targetId", target.toString());
        ObjectNode neither = body();
        neither.remove("targetId");

        for (ObjectNode body : List.of(both, neither)) {
            MvcResult result = declare("tok-xor", body);

            assertEquals(422, result.getResponse().getStatus(), body.toString());
            assertTrue(json(result).get("detail").asText().contains("connectionId"), json(result).toString());
        }
        assertNothingWritten();
        assertFalse(authorization.permissions.contains(Permission.TARGET_USE),
                "no resource to ask target.use at: refused before the secondary check");
    }

    @Test
    void aConnectionThatIsNotAnActiveRemoteServerIs422ButAUsableControlConnectionNeedsNoProjection() throws Exception {
        login("tok-unusable");
        UUID inactive = UUID.randomUUID();
        connectionDirectory.put(organization, inactive, "remote-server", false);
        connections.projected.add(inactive);
        UUID broker = UUID.randomUUID();
        connectionDirectory.put(organization, broker, "solace-broker", true);
        connections.projected.add(broker);
        UUID unprojected = UUID.randomUUID();
        connectionDirectory.put(organization, unprojected, "remote-server", true);
        UUID unknown = UUID.randomUUID();
        connections.projected.add(unknown);

        for (UUID connection : List.of(inactive, broker, unknown)) {
            MvcResult result = declare("tok-unusable", connectionBody(connection));

            assertEquals(422, result.getResponse().getStatus(), connection.toString());
            assertTrue(json(result).get("detail").asText().contains("connectionId"), json(result).toString());
            assertFalse(result.getResponse().getContentAsString().contains(connection.toString()),
                    "the value is never echoed");
        }

        assertEquals(201, declare("tok-unusable", connectionBody(unprojected)).getResponse().getStatus(),
                "an active remote-server connection is sufficient while runtime projection validation is disabled");
    }

    @Test
    void targetUseDeniedOrNotFoundAtTheConnectionIs403Or404AndWritesNothing() throws Exception {
        login("tok-conn-authz");
        UUID connection = usableConnection();
        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.DENY);

        MvcResult denied = declare("tok-conn-authz", connectionBody(connection));

        assertEquals(403, denied.getResponse().getStatus());
        assertTrue(json(denied).get("detail").asText().contains("target.use"), json(denied).toString());

        authorization.outcomeByPermission.put(Permission.TARGET_USE, Outcome.NOT_FOUND);
        MvcResult hidden = declare("tok-conn-authz", connectionBody(connection));

        assertEquals(404, hidden.getResponse().getStatus());
        assertNothingWritten();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
