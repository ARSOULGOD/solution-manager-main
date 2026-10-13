package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRefusalReason;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.ObservedInstance;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeCatalog;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeConnections;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeGovernance;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * {@code getDesigner}, {@code stageConfig}, {@code getConfigHistory} and {@code getConfigLadder} (plan 010 T5.3) over
 * the real filter, interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports
 * FAKED. The projection itself is {@code cp-core}'s (33 fixtures) and the persistence is {@code dp-core}'s
 * ({@code ConfigRecordServiceIT}); what is pinned here is the ORCHESTRATION: who may call, in what order the planes are
 * read, that the server's projection - never the client's claim - is what is stored, and that no refusal and no
 * response ever carries a value.
 */
class DesignerControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIGEST = "hmacsha256v1:" + "b".repeat(64);
    private static final String LITERAL = "hunter2-sentinel";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeInventory inventory;
    private FakeCatalog catalog;
    private FakeConfig config;
    private FakeObserved observed;
    private FakeSecrets secrets;
    private FakeProjector projector;
    private FakeValidator validator;
    private FakeCodec codec;
    private MetricsFakes.RecordingApplicability applicability;
    private UUID organization;
    private UUID catalogSolution;
    private UUID instanceId;

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
        catalog = context.getBean(FakeCatalog.class);
        config = context.getBean(FakeConfig.class);
        observed = context.getBean(FakeObserved.class);
        secrets = context.getBean(FakeSecrets.class);
        projector = context.getBean(FakeProjector.class);
        validator = context.getBean(FakeValidator.class);
        codec = context.getBean(FakeCodec.class);
        applicability = context.getBean(MetricsFakes.RecordingApplicability.class);
        organization = UUID.randomUUID();
        catalogSolution = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        catalog.serve(catalogSolution, "1", "{\"solution\":\"cache\",\"packageVersion\":[1]}");
        inventory.put(new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), catalogSolution, "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(UUID.randomUUID(), UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))), 41L));
    }

    // -------------------------------------------------------------------------------------------------- rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean Calls calls() {
            return new Calls();
        }

        @Bean FakeInventory inventory(Calls calls) {
            return new FakeInventory(calls);
        }

        @Bean FakeConnections connections() {
            return new FakeConnections();
        }

        @Bean FakeGovernance governance(Calls calls) {
            return new FakeGovernance(calls);
        }

        @Bean FakeCatalog catalog() {
            return new FakeCatalog();
        }

        @Bean FakeConfig config(Calls calls) {
            return new FakeConfig(calls);
        }

        @Bean FakeObserved observed(Calls calls) {
            return new FakeObserved(calls);
        }

        @Bean FakeSecrets secrets() {
            return new FakeSecrets();
        }

        @Bean FakeProjector projector() {
            return new FakeProjector();
        }

        @Bean FakeValidator validator() {
            return new FakeValidator();
        }

        @Bean FakeCodec codec() {
            return new FakeCodec();
        }

        @Bean ConnectionDirectory connectionDirectory() {
            return organization -> List.of();
        }

        @Bean MetricsFakes.RecordingApplicability applicability() {
            return new MetricsFakes.RecordingApplicability();
        }

        @Bean InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean
        DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
                InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
                ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10));
        }

        @Bean
        DesignerOrchestrator designerOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog,
                ConfigRecordService config, ObservedStateService observed, SecretReferences secrets,
                DesignerProjector projector, DesignerStageValidator validator, DesignerCodec codec,
                MetricApplicability applicability) {
            return new DesignerOrchestrator(instances, catalog, config, observed, secrets, projector, validator,
                    codec, applicability);
        }

        @Bean DesignerController designerController(DesignerOrchestrator orchestrator) {
            return new DesignerController(orchestrator);
        }

        @Bean GovernanceController governanceController(DesignerOrchestrator orchestrator) {
            return new GovernanceController(orchestrator);
        }
    }

    static final class FakeConfig implements ConfigRecordService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<StageCommand> staged = new ArrayList<>();
        Optional<ConfigDraft> draft = Optional.empty();
        List<GenerationView> history = List.of();
        RuntimeException stageFailure;
        boolean unchanged;
        long generationAfter = 7;
        long stateVersionAfter = 42;
        int historyLimit;

        FakeConfig(Calls calls) {
            this.calls = calls;
        }

        @Override
        public StageResult stage(UUID organizationId, UUID instanceId, StageCommand command) {
            calls.add("dp.stage");
            organizationsSeen.add(organizationId);
            if (stageFailure != null) {
                throw stageFailure;
            }
            staged.add(command);
            return new StageResult(new GenerationView(generationAfter, command.packageSolution(),
                    command.packageVersion(), DIGEST, command.origin(), null, command.actor(), Instant.EPOCH,
                    stateVersionAfter), unchanged, stateVersionAfter);
        }

        @Override
        public StageResult copyForward(UUID organizationId, UUID instanceId, long fromGeneration,
                GenerationOrigin origin, UUID actor) {
            throw new AssertionError("the designer never copies a generation forward");
        }

        @Override
        public Optional<StageResult> copyLatestForward(UUID organizationId, UUID instanceId,
                GenerationOrigin origin, UUID actor) {
            throw new AssertionError("the designer never copies a generation forward");
        }

        @Override
        public Optional<StageResult> copyLatestWithLogLevel(UUID organizationId, UUID instanceId, UUID placementId, String loggerPrefix,
                com.solace.sm.dp.api.logs.LoggerLevel level, UUID actor) {
            throw new AssertionError("the designer never changes a log level");
        }

        @Override
        public java.util.Map<String, com.solace.sm.dp.api.logs.LoggerLevel> configuredLogLevels(UUID organizationId,
                UUID instanceId) {
            throw new AssertionError("the designer never reads a log level");
        }

        @Override
        public Optional<ConfigDraft> latestDraft(UUID organizationId, UUID instanceId) {
            calls.add("dp.latestDraft");
            organizationsSeen.add(organizationId);
            return draft;
        }

        @Override
        public List<GenerationView> history(UUID organizationId, UUID instanceId, int limit) {
            calls.add("dp.history");
            organizationsSeen.add(organizationId);
            historyLimit = limit;
            return history;
        }

        @Override
        public Optional<GenerationView> generation(UUID organizationId, UUID instanceId, long generation) {
            throw new AssertionError("the designer never reads one generation");
        }
    }

    static final class FakeObserved implements ObservedStateService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        ConfigLadderView ladder = new ConfigLadderView(
                List.of(new ConfigLadderView.Rung(LadderRung.DESIRED, true, 3L, DIGEST),
                        new ConfigLadderView.Rung(LadderRung.RENDERED, true, 3L, DIGEST),
                        new ConfigLadderView.Rung(LadderRung.PERSISTED, false, null, null),
                        new ConfigLadderView.Rung(LadderRung.RESTART_OBSERVED, false, null, null),
                        new ConfigLadderView.Rung(LadderRung.EFFECTIVE_VERIFIED, false, null, null)),
                LadderRung.RENDERED, "apply is not wired yet");

        FakeObserved(Calls calls) {
            this.calls = calls;
        }

        @Override
        public ObservedInstance observed(UUID organizationId, UUID instanceId) {
            calls.add("dp.observed");
            organizationsSeen.add(organizationId);
            return new ObservedInstance("VERSION_UNVERIFIED", null, null,
                    new ObservedInstance.Rungs(DIGEST, DIGEST, null, null, null), List.of());
        }

        @Override
        public ConfigLadderView ladder(UUID organizationId, UUID instanceId) {
            calls.add("dp.ladder");
            organizationsSeen.add(organizationId);
            return ladder;
        }
    }

    static final class FakeSecrets implements SecretReferences {
        final List<UUID> asked = new ArrayList<>();
        final List<UUID> organizationsSeen = new ArrayList<>();
        boolean resolves = true;

        @Override
        public boolean exists(UUID organizationId, UUID secretRef) {
            asked.add(secretRef);
            organizationsSeen.add(organizationId);
            return resolves;
        }
    }

    /** Returns whatever the test programmed and records what it was asked to project. */
    static final class FakeProjector implements DesignerProjector {
        ViewModel next = viewModel(List.of(), List.of(), List.of());
        PackageModel.SolutionPackage pkg;
        StateModel.ComponentSelection selection;
        Map<String, Map<String, Object>> draft;
        StateModel.ObservedState observed;
        Object requestedVersion;
        int invocations;

        @Override
        public ViewModel project(PackageModel.SolutionPackage pkg, StateModel.ComponentSelection selection,
                Map<String, Map<String, Object>> draft, StateModel.ObservedState observed,
                Map<String, List<Object>> catalog, Object requestedPackageVersion) {
            invocations++;
            this.pkg = pkg;
            this.selection = selection;
            this.draft = draft;
            this.observed = observed;
            this.requestedVersion = requestedPackageVersion;
            return next;
        }
    }

    static final class FakeValidator implements DesignerStageValidator {
        StageOutcome next;
        StageRequest request;

        @Override
        public StageOutcome validateStage(StageRequest request) {
            this.request = request;
            return next;
        }
    }

    /** Writes a tree in a deliberately odd key order, so a test can tell the port's tree from a re-serialization. */
    static final class FakeCodec implements DesignerCodec {
        final List<String> documents = new ArrayList<>();
        /** The package the next read returns; null is the empty one (OR-103's log-level tests set their own). */
        PackageModel.SolutionPackage next;

        @Override
        public PackageModel.SolutionPackage readPackage(String packageDocumentJson) {
            documents.add(packageDocumentJson);
            return next != null ? next
                    : new PackageModel.SolutionPackage("cache", List.of(1), null, null, List.of());
        }

        @Override
        public Map<String, Object> viewModelTree(ViewModel viewModel) {
            Map<String, Object> inner = new LinkedHashMap<>();
            inner.put("b", 1);
            inner.put("a", 2);
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("zeta", viewModel.solution());
            tree.put("alpha", inner);
            tree.put("engineVersion", viewModel.engineVersion());
            return tree;
        }
    }

    // ------------------------------------------------------------------------------------ view-model builders

    static ViewModel viewModel(List<ViewModel.ComponentView> components, List<ViewModel.RenderedEntry> rendered,
            List<ViewModel.Diagnostic> diagnostics) {
        return new ViewModel(ViewModel.ENGINE_VERSION, "cache", 1, components, rendered, List.of(), List.of(),
                new ViewModel.StatusView(new ViewModel.VersionStatus("VERSION_UNVERIFIED", null, null, false, null),
                        StateModel.ConfigState.EMPTY, List.of(), "NOT_APPLICABLE", "not-declared"),
                diagnostics);
    }

    static ViewModel.FieldView field(String key, String path, boolean masked, ViewModel.FieldValue value,
            List<ViewModel.Validation> validations) {
        return new ViewModel.FieldView(key, path, masked ? "secret" : "text", value, null, true, true, false, false,
                masked, false, null, null, validations, List.of());
    }

    static ViewModel.ComponentView component(String instanceKey, ViewModel.FieldView... fields) {
        return new ViewModel.ComponentView(instanceKey, "cache-engine", "none",
                List.of(new ViewModel.SectionView("General", 0, List.of(fields), List.of(), false)));
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private String path(String suffix) {
        return "/api/dp/instances/" + instanceId + suffix;
    }

    private MvcResult read(String token, String suffix) throws Exception {
        return mvc.perform(get(path(suffix)).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private MvcResult stage(String token, ObjectNode body) throws Exception {
        return mvc.perform(post(path("/config")).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String code(MvcResult result) throws Exception {
        return json(result).get("code").asText();
    }

    /** A valid stage body: one instance {@code i1} with one plain draft value, and the matching rendered claim. */
    private static ObjectNode stageBody(long base) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseStateVersion", base);
        ObjectNode instance = body.putObject("componentSelection").putArray("instances").addObject();
        instance.put("instanceKey", "i1").put("type", "cache-engine");
        instance.putObject("layout").put("x", 10).put("y", 20);
        body.putObject("draftValues").putObject("i1").put("namespace", "orders");
        ObjectNode claim = body.putArray("rendered").addObject();
        claim.put("instanceKey", "i1").put("renderedPath", "cache-engine.namespace").put("value", "orders");
        return body;
    }

    /** The server's projection of {@link #stageBody}: one plain rendered entry, no validation. */
    private static ViewModel serverProjection() {
        return viewModel(
                List.of(component("i1", field("namespace", "cache-engine.namespace", false,
                        new ViewModel.FieldValue.Value("orders", "draft"), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "orders")), List.of());
    }

    private void serverAccepts(ViewModel serverResult) {
        validator.next = new StageOutcome.Accepted(serverResult);
    }

    private void assertNothingStaged() {
        assertTrue(config.staged.isEmpty(), "a generation was written: " + calls.log);
        assertFalse(calls.has("dp.stage"), calls.log.toString());
    }

    // ----------------------------------------------------------------------------------------- authorization

    @Test
    void theThreeReadsRequireConfigViewAndStageRequiresConfigEditAtThePathsInstance() throws Exception {
        Session session = login("tok-verbs");
        serverAccepts(serverProjection());

        read("tok-verbs", "/designer");
        read("tok-verbs", "/config/history");
        read("tok-verbs", "/config-ladder");
        stage("tok-verbs", stageBody(41));

        assertEquals(List.of(Permission.CONFIG_VIEW, Permission.CONFIG_VIEW, Permission.CONFIG_VIEW,
                Permission.CONFIG_EDIT), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNoPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        for (String suffix : List.of("/designer", "/config/history", "/config-ladder")) {
            assertEquals(403, read("tok-deny", suffix).getResponse().getStatus(), suffix);
        }
        assertEquals(403, stage("tok-deny", stageBody(41)).getResponse().getStatus());

        assertTrue(calls.log.isEmpty(), "no plane was read or written: " + calls.log);
        assertEquals(0, projector.invocations);
    }

    @Test
    void anInstanceThatIsNotActiveIsTheSame404OnAllFourAndNothingElseIsRead() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();   // no such instance on the data plane

        for (String suffix : List.of("/designer", "/config/history", "/config-ladder")) {
            MvcResult result = read("tok-missing", suffix);
            assertEquals(404, result.getResponse().getStatus(), suffix);
            assertEquals("NOT_FOUND", code(result));
        }
        assertEquals(404, stage("tok-missing", stageBody(41)).getResponse().getStatus());
        assertFalse(calls.has("dp.latestDraft"), calls.log.toString());
        assertFalse(calls.has("dp.history"), calls.log.toString());
        assertFalse(calls.has("dp.ladder"), calls.log.toString());
        assertNothingStaged();
        assertEquals(0, projector.invocations);
    }

    @Test
    void everyCallUsesTheSessionsOrganizationNeverARequestField() throws Exception {
        Session session = login("tok-i5");
        serverAccepts(serverProjection());
        UUID other = UUID.randomUUID();

        mvc.perform(get(path("/designer") + "?organizationId=" + other).cookie(new Cookie(COOKIE, "tok-i5"))
                .header("X-Organization", other.toString())).andReturn();
        read("tok-i5", "/config/history");
        read("tok-i5", "/config-ladder");
        stage("tok-i5", stageBody(41));

        for (List<UUID> seen : List.of(inventory.organizationsSeen, config.organizationsSeen,
                observed.organizationsSeen)) {
            assertFalse(seen.isEmpty());
            assertTrue(seen.stream().allMatch(session.organizationId()::equals), seen.toString());
        }
        assertEquals(session.principal().id(), config.staged.get(0).actor(), "the actor is the session's principal");
    }

    // -------------------------------------------------------------------------------------------- getDesigner

    @Test
    void theDesignerIsTheServersProjectionAndCarriesTheInstanceStateVersion() throws Exception {
        login("tok-designer");
        projector.next = viewModel(List.of(), List.of(), List.of());

        MvcResult result = read("tok-designer", "/designer");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(41, body.get("stateVersion").asLong(), "the INSTANCE's version, the base for the next stage");
        assertTrue(body.has("generation") && body.get("generation").isNull(), "null before any generation: " + body);
        assertEquals("cache", body.at("/viewModel/zeta").asText(), "the tree the codec wrote from the projector's model");
        assertEquals(1, projector.invocations);
        assertEquals(List.of("{\"solution\":\"cache\",\"packageVersion\":[1]}"), codec.documents,
                "the package the catalog holds for the declared version");
        assertEquals(1L, projector.requestedVersion, "projected against the instance's declared package version");
        assertEquals("VERSION_UNVERIFIED", projector.observed.version().state());
        assertEquals(DIGEST, projector.observed.config().desired());
        assertTrue(projector.selection.instances().isEmpty(), "an empty draft before generation 1");
        assertTrue(projector.draft.isEmpty());
    }

    @Test
    void theDesignerAfterAReloadRebuildsTheSameBranchesFromTheStoredDraft() throws Exception {
        login("tok-reload");
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1",
                "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}",
                "{\"i1\":{\"provider\":\"redis\",\"namespace\":\"orders\"}}", DIGEST,
                "{\"i1\":{\"x\":10.5,\"y\":-20}}"));

        MvcResult result = read("tok-reload", "/designer");

        assertEquals(5, json(result).get("generation").asLong());
        assertEquals(List.of(new StateModel.ComponentInstance("i1", "cache-engine", null)),
                projector.selection.instances(), "the projector gets the selection, and layout is not its input");
        assertEquals(Map.of("i1", Map.of("provider", "redis", "namespace", "orders")), projector.draft,
                "the branch the operator switched to is rebuilt from the STORED draft, not from defaults");
    }

    /** OR-96: the designer returns the layout the operator last saved, from the presentation record. */
    @Test
    void theDesignerReturnsTheStoredLayoutBesideTheViewModelAsTheClientSentIt() throws Exception {
        login("tok-layout");
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1",
                "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}",
                "{\"i1\":{\"namespace\":\"orders\"}}", DIGEST,
                "{\"i1\":{\"x\":10.5,\"y\":-20}}"));

        MvcResult result = read("tok-layout", "/designer");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":10.5,\"y\":-20}}"), json(result).get("layout"));
        assertTrue(raw(result).contains("\"layout\":{\"i1\":{\"x\":10.5,\"y\":-20}}"),
                "fractional numbers are not truncated: " + raw(result));
    }

    @Test
    void theDesignerAlwaysCarriesALayoutObjectEvenBeforeAnyGenerationOrLayoutExists() throws Exception {
        login("tok-nolayout");

        JsonNode before = json(read("tok-nolayout", "/designer"));
        assertTrue(before.get("layout").isObject() && before.get("layout").isEmpty(), "before any generation: " + before);

        config.draft = Optional.of(new ConfigDraft(5, "cache", "1", "{\"instances\":[]}", "{}", DIGEST));
        JsonNode after = json(read("tok-nolayout", "/designer"));
        assertTrue(after.get("layout").isObject() && after.get("layout").isEmpty(), "a draft with no layout: " + after);
    }

    @Test
    void theVersionIsReadBeforeTheDraftSoACommitBetweenThemCanOnlyCauseAStaleBase() throws Exception {
        login("tok-order");

        read("tok-order", "/designer");

        assertTrue(calls.indexOf("dp.findActive") >= 0, calls.log.toString());
        assertTrue(calls.indexOf("dp.findActive") < calls.indexOf("dp.latestDraft"), calls.log.toString());
    }

    @Test
    void theViewModelIsServedThroughTheCodecsTreeInItsOwnKeyOrderNeverReSerializedFromTheRecords() throws Exception {
        login("tok-bytes");

        String body = raw(read("tok-bytes", "/designer"));

        assertTrue(body.contains("\"viewModel\":{\"zeta\":\"cache\",\"alpha\":{\"b\":1,\"a\":2},\"engineVersion\":2}"),
                "the codec's ordered tree, verbatim: " + body);
    }

    @Test
    void aPackageTheCatalogNoLongerHoldsStillProjectsAsADeclaredEmptyDesignerNotAnError() throws Exception {
        login("tok-nopkg");
        catalog.documents.clear();

        MvcResult result = read("tok-nopkg", "/designer");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertNull(projector.pkg, "the projector declares VERSION_UNKNOWN for a null package");
    }

    // -------------------------------------------------------------------------------------------- stageConfig

    @Test
    void aValidStageStoresTheServersOwnRenderedSetNotTheClients() throws Exception {
        login("tok-stage");
        serverAccepts(viewModel(
                List.of(component("i1", field("namespace", "cache-engine.namespace", false,
                        new ViewModel.FieldValue.Value("server-value", "draft"), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "server-value")), List.of()));

        MvcResult result = stage("tok-stage", stageBody(41));

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        StageCommand command = config.staged.get(0);
        assertEquals(List.of(RenderedEntryValue.plain("i1", "cache-engine.namespace", "\"server-value\"")),
                command.rendered(), "OR-34: what is stored is the SERVER's, even though the client claimed 'orders'");
        assertEquals(41, command.baseStateVersion());
        assertEquals(GenerationOrigin.EDIT, command.origin());
        assertEquals("cache", command.packageSolution());
        assertEquals("1", command.packageVersion());
        JsonNode body = json(result);
        assertEquals(7, body.get("generation").asLong());
        assertEquals(42, body.get("stateVersion").asLong(), "the instance's version AFTER the stage");
        assertFalse(body.get("unchanged").asBoolean());
    }

    // ---- plan 011 P11-4.1, M-Q15: the generation is born with the metric names its DRAFT makes collectable

    @Test
    void aStageCarriesTheListTheEvaluatorReturnsForTheDraftItStoresOverTheSelectionItStores() throws Exception {
        login("tok-active");
        serverAccepts(serverProjection());
        PackageModel.SolutionPackage pkg = cachePackage("cache-engine", Map.of());
        codec.next = pkg;
        applicability.next = List.of("cache.hit_rate", "cache.size");

        MvcResult result = stage("tok-active", stageBody(41));

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of("cache.hit_rate", "cache.size"), config.staged.get(0).activeMetricNames());
        assertEquals(1, applicability.asks.size(), "one evaluation, by the control plane's evaluator, never by the data plane");
        MetricsFakes.Ask ask = applicability.last();
        assertSame(pkg, ask.pkg());
        assertEquals(1L, ask.packageVersion(), "the instance's declared version, as project() takes it");
        assertEquals(List.of("i1"), ask.selection().instances().stream().map(i -> i.instanceKey()).toList());
        assertEquals(Map.of("i1", Map.of("namespace", "orders")), ask.draft(),
                "the draft the stage STORES (the request's), not the running values and not the previous generation's");
    }

    @Test
    void aStageTheServerRefusesIsNeverEvaluatedSoNoListIsComputedForADraftThatIsNotStored() throws Exception {
        login("tok-refused");
        validator.next = new StageOutcome.Refused(List.of(
                new StageRefusalReason.ValueMismatch("i1", "cache-engine.namespace", "a", "b")), serverProjection());

        MvcResult result = stage("tok-refused", stageBody(41));

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertTrue(applicability.asks.isEmpty(), "evaluated nothing: " + applicability.asks);
        assertNothingStaged();
    }

    // ---- OR-103 / BE-D37: the package's logger declaration travels with the stage; the DP writes the overlay

    private static PackageModel.SolutionPackage cachePackage(String type, Map<String, Object> logs) {
        return new PackageModel.SolutionPackage("cache", List.of(1), null, null, List.of(
                new PackageModel.PackageComponent(type, "exactly-one", null, "none", "cache-engine", null,
                        "com.solace.solutions.cache", List.of(), List.of(), List.of(), null, logs, null)));
    }

    @Test
    void aPackageThatDeclaresLoggersStagesWithItsBaselineAsLoggerSupportAndNoOverlayKeyFromTheGateway() throws Exception {
        login("tok-logger");
        serverAccepts(serverProjection());
        codec.next = cachePackage("cache-engine", Map.of("loggers", Map.of(), "baselineExposure",
                List.of("metrics", "health")));

        stage("tok-logger", stageBody(41));

        StageCommand command = config.staged.get(0);
        assertEquals(new com.solace.sm.dp.api.config.LoggerSupport(List.of("health", "metrics")),
                command.loggerSupport());
        assertEquals(List.of(RenderedEntryValue.plain("i1", "cache-engine.namespace", "\"orders\"")),
                command.rendered(), "the gateway never mints an overlay key: the data plane does (BE-D37)");
    }

    @Test
    void aPackageThatDeclaresNoLoggersStagesWithNoLoggerSupport() throws Exception {
        login("tok-nologger");
        serverAccepts(serverProjection());
        codec.next = cachePackage("cache-engine", Map.of("baselineExposure", List.of("health")));

        stage("tok-nologger", stageBody(41));

        assertNull(config.staged.get(0).loggerSupport(), "nothing is inferred from loggerPrefix (BE-D38)");
    }

    @Test
    void loggersWithNoBaselineAddNoOverlayBecauseTheWrittenListWouldWithdrawWhatTheMiExposes() throws Exception {
        login("tok-nobaseline");
        serverAccepts(serverProjection());
        codec.next = cachePackage("cache-engine", Map.of("loggers", Map.of()));

        stage("tok-nobaseline", stageBody(41));

        assertNull(config.staged.get(0).loggerSupport());
    }

    @Test
    void aComponentTheSelectionDoesNotUseContributesNothing() throws Exception {
        login("tok-unselected");
        serverAccepts(serverProjection());
        codec.next = cachePackage("some-other-component", Map.of("loggers", Map.of(), "baselineExposure",
                List.of("health")));

        stage("tok-unselected", stageBody(41));

        assertNull(config.staged.get(0).loggerSupport());
    }

    @Test
    void theStagedSelectionCarriesNoLayoutAndTheLayoutTravelsSeparatelyAndTheClaimGoesToTheValidatorUntouched()
            throws Exception {
        login("tok-claim");
        serverAccepts(serverProjection());

        stage("tok-claim", stageBody(41));

        StageCommand command = config.staged.get(0);
        assertEquals(MAPPER.readTree("{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}"),
                MAPPER.readTree(command.componentSelectionJson()),
                "OR-96: the config of record holds no presentation state");
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":10,\"y\":20}}"), MAPPER.readTree(command.layoutJson()),
                "the layout goes to the presentation record, keyed by instance key");
        assertEquals(MAPPER.readTree("{\"i1\":{\"namespace\":\"orders\"}}"),
                MAPPER.readTree(command.draftValuesJson()));
        assertEquals(List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "orders")),
                validator.request.claimedRendered());
        assertEquals(Map.of("i1", Map.of("namespace", "orders")), validator.request.draft());
        assertEquals(1L, validator.request.requestedPackageVersion());
    }

    @Test
    void aFractionalLayoutPositionIsStoredAsTheClientSentItNotTruncated() throws Exception {
        login("tok-fraction");
        serverAccepts(serverProjection());
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/componentSelection/instances/0/layout")).put("x", 10.7).put("y", -3.25);

        stage("tok-fraction", body);

        JsonNode layout = MAPPER.readTree(config.staged.get(0).layoutJson()).get("i1");
        assertEquals(10.7, layout.get("x").asDouble());
        assertEquals(-3.25, layout.get("y").asDouble());
    }

    @Test
    void aSelectionWithNoLayoutStagesAnEmptyLayoutSoTheRecordIsReplacedByWhatWasSent() throws Exception {
        login("tok-bare");
        serverAccepts(serverProjection());
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/componentSelection/instances/0")).remove("layout");

        stage("tok-bare", body);

        assertEquals(MAPPER.readTree("{}"), MAPPER.readTree(config.staged.get(0).layoutJson()));
    }

    @Test
    void aLayoutOnlySaveIsAnUnchangedStageThatStillCarriesTheLayoutToTheRecord() throws Exception {
        login("tok-drag");
        serverAccepts(serverProjection());
        config.unchanged = true;

        MvcResult result = stage("tok-drag", stageBody(41));

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertTrue(json(result).get("unchanged").asBoolean());
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":10,\"y\":20}}"),
                MAPPER.readTree(config.staged.get(0).layoutJson()));
    }

    @Test
    void aRenderedMismatchIs422AndWritesNothing() throws Exception {
        login("tok-mismatch");
        validator.next = new StageOutcome.Refused(List.of(
                new StageRefusalReason.ValueMismatch("i1", "cache-engine.namespace", "server-secret-sentinel",
                        "claimed-sentinel")), serverProjection());

        MvcResult result = stage("tok-mismatch", stageBody(41));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", code(result));
        assertEquals(StageRefusedException.Reason.RENDERED_MISMATCH.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains("sentinel"), "no value is echoed: " + raw(result));
        assertNothingStaged();
    }

    @Test
    void anUndeclaredKeyIs422NamingTheRuleAndNotTheKey() throws Exception {
        login("tok-undeclared");
        validator.next = new StageOutcome.Refused(List.of(
                new StageRefusalReason.UndeclaredDraftKey("i1", "not-in-the-package-key")), serverProjection());

        MvcResult result = stage("tok-undeclared", stageBody(41));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.UNDECLARED_KEY.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains("not-in-the-package-key"), raw(result));
        assertNothingStaged();
    }

    @Test
    void aBlockingValidationIs422AndWritesNothing() throws Exception {
        login("tok-block");
        serverAccepts(viewModel(List.of(component("i1", field("namespace", "cache-engine.namespace", false,
                new ViewModel.FieldValue.Unset(),
                List.of(new ViewModel.Validation("block", "REQUIRED", "This field is required."))))),
                List.of(), List.of()));

        MvcResult result = stage("tok-block", stageBody(41));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.BLOCKING_VALIDATION.detail(), json(result).get("detail").asText());
        assertNothingStaged();
    }

    @Test
    void aWarningDoesNotBlockAStage() throws Exception {
        login("tok-warn");
        serverAccepts(viewModel(List.of(component("i1", field("namespace", "cache-engine.namespace", false,
                new ViewModel.FieldValue.Value("orders", "draft"),
                List.of(new ViewModel.Validation("warn", "SEMANTIC", "Looks odd."))))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "orders")), List.of()));

        assertEquals(200, stage("tok-warn", stageBody(41)).getResponse().getStatus());
    }

    @Test
    void aSecretLiteralIs422AndIsNeverEchoedNorStored() throws Exception {
        login("tok-literal");
        ViewModel server = viewModel(List.of(component("i1", field("password", "cache-engine.password", true,
                new ViewModel.FieldValue.SecretUnset(),
                List.of(new ViewModel.Validation("block", "SECRET_NOT_A_REFERENCE", "x"))))), List.of(), List.of());
        serverAccepts(server);
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", LITERAL);
        ((ObjectNode) body.get("rendered").get(0)).put("value", LITERAL);

        MvcResult result = stage("tok-literal", body);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.SECRET_LITERAL.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(LITERAL), "I6: " + raw(result));
        assertNothingStaged();
    }

    @Test
    void aSecretLiteralInAHiddenSecretFieldIsRefusedToo() throws Exception {
        // A hidden field carries no validation and is excluded from the rendered set, so only the draft check sees
        // it; storing the draft verbatim would persist the literal in config_generation.draft_values (I6).
        login("tok-hidden");
        ViewModel.FieldView hidden = new ViewModel.FieldView("password", "cache-engine.password", "secret",
                new ViewModel.FieldValue.SecretUnset(), null, false, true, false, false, true, false, null, null,
                List.of(), List.of("provider"));
        serverAccepts(viewModel(List.of(component("i1", hidden)), List.of(), List.of()));
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", LITERAL);
        body.putArray("rendered");

        MvcResult result = stage("tok-hidden", body);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.SECRET_LITERAL.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(LITERAL));
        assertNothingStaged();
    }

    @Test
    void aSecretRefPrefixWithoutAUuidInAHiddenSecretFieldIsRefusedAndNothingIsStored() throws Exception {
        // The prefix alone is not a reference: 'secretRef://hunter2' would be stored verbatim in draft_values (I6).
        login("tok-hidden-prefix");
        ViewModel.FieldView hidden = new ViewModel.FieldView("password", "cache-engine.password", "secret",
                new ViewModel.FieldValue.SecretUnset(), null, false, true, false, false, true, false, null, null,
                List.of(), List.of("provider"));
        serverAccepts(viewModel(List.of(component("i1", hidden)), List.of(), List.of()));
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", "secretRef://hunter2");
        body.putArray("rendered");

        MvcResult result = stage("tok-hidden-prefix", body);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertFalse(raw(result).contains("hunter2"));
        assertTrue(secrets.asked.isEmpty(), "a malformed reference is refused without asking the store");
        assertNothingStaged();
    }

    @Test
    void aHiddenSecretFieldHoldingAUuidThatResolvesToNothingIs422AndNothingIsStaged() throws Exception {
        login("tok-hidden-unresolved");
        ViewModel.FieldView hidden = new ViewModel.FieldView("password", "cache-engine.password", "secret",
                new ViewModel.FieldValue.SecretUnset(), null, false, true, false, false, true, false, null, null,
                List.of(), List.of("provider"));
        serverAccepts(viewModel(List.of(component("i1", hidden)), List.of(), List.of()));
        secrets.resolves = false;
        UUID reference = UUID.randomUUID();
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", "secretRef://" + reference);
        body.putArray("rendered");

        MvcResult result = stage("tok-hidden-unresolved", body);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED.detail(),
                json(result).get("detail").asText());
        assertEquals(List.of(reference), secrets.asked, "a hidden field's reference is resolved like a visible one");
        assertEquals(organization, secrets.organizationsSeen.get(0));
        assertNothingStaged();
    }

    @Test
    void aVirtualSecretFieldHoldingAUuidThatResolvesToNothingIs422AndNothingIsStaged() throws Exception {
        login("tok-virtual-unresolved");
        ViewModel.FieldView virtual = new ViewModel.FieldView("password", null, "secret",
                new ViewModel.FieldValue.SecretUnset(), null, true, true, true, false, true, false, null, null,
                List.of(), List.of());
        serverAccepts(viewModel(List.of(component("i1", virtual)), List.of(), List.of()));
        secrets.resolves = false;
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", "secretRef://" + UUID.randomUUID());
        body.putArray("rendered");

        MvcResult result = stage("tok-virtual-unresolved", body);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(1, secrets.asked.size());
        assertNothingStaged();
    }

    @Test
    void aSecretLiteralOutranksARenderedMismatchSoTheMistakenValueIsTheOneNamed() throws Exception {
        login("tok-outrank");
        ViewModel server = viewModel(List.of(component("i1", field("password", "cache-engine.password", true,
                new ViewModel.FieldValue.SecretUnset(), List.of()))), List.of(), List.of());
        validator.next = new StageOutcome.Refused(List.of(
                new StageRefusalReason.UnexpectedInClaim("i1", "cache-engine.password", LITERAL)), server);
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", LITERAL);

        MvcResult result = stage("tok-outrank", body);

        assertEquals(StageRefusedException.Reason.SECRET_LITERAL.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(LITERAL));
    }

    @Test
    void aSecretReferenceIsStoredAsAReferenceNeverAValueAndMustResolve() throws Exception {
        login("tok-ref");
        UUID reference = UUID.randomUUID();
        String ref = "secretRef://" + reference;
        serverAccepts(viewModel(List.of(component("i1",
                field("namespace", "cache-engine.namespace", false, new ViewModel.FieldValue.Value("orders", "draft"),
                        List.of()),
                field("password", "cache-engine.password", true, new ViewModel.FieldValue.SecretRef(ref), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "orders"),
                        new ViewModel.RenderedEntry("i1", "cache-engine.password", ref)),
                List.of()));
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", ref);

        MvcResult result = stage("tok-ref", body);

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(RenderedEntryValue.plain("i1", "cache-engine.namespace", "\"orders\""),
                RenderedEntryValue.secret("i1", "cache-engine.password", reference)), config.staged.get(0).rendered());
        assertEquals(List.of(reference), secrets.asked);
        assertEquals(organization, secrets.organizationsSeen.get(0), "I5: the reference is resolved in the session's organization");
    }

    @Test
    void aSecretReferenceThatDoesNotResolveIs422AndWritesNothing() throws Exception {
        login("tok-unresolved");
        String ref = "secretRef://" + UUID.randomUUID();
        serverAccepts(viewModel(List.of(component("i1", field("password", "cache-engine.password", true,
                new ViewModel.FieldValue.SecretRef(ref), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.password", ref)), List.of()));
        secrets.resolves = false;
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", ref);

        MvcResult result = stage("tok-unresolved", body);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED.detail(),
                json(result).get("detail").asText());
        assertFalse(raw(result).contains(ref));
        assertNothingStaged();
    }

    @Test
    void aSecretReferenceThatIsNotAUuidIs422WithoutAskingTheStore() throws Exception {
        login("tok-notuuid");
        String ref = "secretRef://not-a-uuid";
        serverAccepts(viewModel(List.of(component("i1", field("password", "cache-engine.password", true,
                new ViewModel.FieldValue.SecretRef(ref), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.password", ref)), List.of()));
        ObjectNode body = stageBody(41);
        ((ObjectNode) body.at("/draftValues/i1")).put("password", ref);

        MvcResult result = stage("tok-notuuid", body);

        assertEquals(422, result.getResponse().getStatus());
        assertTrue(secrets.asked.isEmpty());
        assertNothingStaged();
    }

    @Test
    void aStaleBaseStateVersionIs409AndTheCurrentVersionIsNotEchoed() throws Exception {
        login("tok-stale");
        serverAccepts(serverProjection());
        config.stageFailure = new StaleStateVersionException(987654);

        MvcResult result = stage("tok-stale", stageBody(40));

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", code(result));
        assertFalse(raw(result).contains("987654"), raw(result));
    }

    @Test
    void reStagingIdenticalContentReturnsUnchangedTrueAndTheSameGeneration() throws Exception {
        login("tok-unchanged");
        serverAccepts(serverProjection());
        config.unchanged = true;
        config.generationAfter = 4;
        config.stateVersionAfter = 41;

        JsonNode body = json(stage("tok-unchanged", stageBody(41)));

        assertTrue(body.get("unchanged").asBoolean());
        assertEquals(4, body.get("generation").asLong());
        assertEquals(41, body.get("stateVersion").asLong());
    }

    @Test
    void aSelectionNamingOneInstanceKeyTwiceIs422() throws Exception {
        login("tok-dupe");
        serverAccepts(serverProjection());
        ObjectNode body = stageBody(41);
        ((com.fasterxml.jackson.databind.node.ArrayNode) body.at("/componentSelection/instances"))
                .addObject().put("instanceKey", "i1").put("type", "cache-engine");

        MvcResult result = stage("tok-dupe", body);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.SELECTION_UNRESOLVED.detail(), json(result).get("detail").asText());
        assertNothingStaged();
    }

    @Test
    void aSelectionThePackageCannotProjectIs422() throws Exception {
        login("tok-unknown-type");
        serverAccepts(viewModel(List.of(), List.of(), List.of(new ViewModel.Diagnostic("UNKNOWN_COMPONENT",
                "Selection names component type x which the package does not declare.", "i1", null))));

        MvcResult result = stage("tok-unknown-type", stageBody(41));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.SELECTION_UNRESOLVED.detail(), json(result).get("detail").asText());
        assertNothingStaged();
    }

    @Test
    void aPackageTheCatalogNoLongerHoldsRefusesTheStage() throws Exception {
        login("tok-stage-nopkg");
        catalog.documents.clear();

        MvcResult result = stage("tok-stage-nopkg", stageBody(41));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals(StageRefusedException.Reason.PACKAGE_UNAVAILABLE.detail(), json(result).get("detail").asText());
        assertNull(validator.request, "nothing is validated against a package that is not there");
        assertNothingStaged();
    }

    // ------------------------------------------------------------------------------------ the request shape

    @Test
    void aBodyThatBreaksTheContractsShapeIs400BeforeAnyPlaneIsRead() throws Exception {
        login("tok-shape");

        ObjectNode withOrganization = stageBody(41);
        withOrganization.put("organizationId", UUID.randomUUID().toString());
        ObjectNode withoutBase = stageBody(41);
        withoutBase.remove("baseStateVersion");
        ObjectNode negativeBase = stageBody(-1);
        ObjectNode strayLayoutKey = stageBody(41);
        ((ObjectNode) strayLayoutKey.at("/componentSelection/instances/0/layout")).put("z", 1);
        ObjectNode withoutRendered = stageBody(41);
        withoutRendered.remove("rendered");
        ObjectNode draftNotObjects = stageBody(41);
        draftNotObjects.putObject("draftValues").put("i1", "not-an-object");

        for (ObjectNode bad : List.of(withOrganization, withoutBase, negativeBase, strayLayoutKey, withoutRendered,
                draftNotObjects)) {
            MvcResult result = stage("tok-shape", bad);
            assertEquals(400, result.getResponse().getStatus(), bad.toString());
            assertEquals("BAD_REQUEST", code(result));
        }
        assertEquals(0, validator.request == null ? 0 : 1);
        assertNothingStaged();
    }

    @Test
    void theRequestsToStringNeverPrintsADraftValue() {
        StageConfigRequest request = new StageConfigRequest(1L,
                new StageConfigRequest.ComponentSelection(List.of()),
                Map.of("i1", Map.of("password", LITERAL)),
                List.of(new StageConfigRequest.Rendered("i1", "p", LITERAL)));

        assertFalse(request.toString().contains(LITERAL), request.toString());
        assertFalse(request.rendered().get(0).toString().contains(LITERAL));
    }

    @Test
    void aNullDraftValueIsAcceptedAndKeepsTheOrderTheBrowserSent() throws Exception {
        login("tok-null");
        serverAccepts(serverProjection());
        ObjectNode body = stageBody(41);
        ObjectNode draft = (ObjectNode) body.at("/draftValues/i1");
        draft.removeAll();
        draft.put("zz", "last").putNull("aa").put("mm", "middle");

        assertEquals(200, stage("tok-null", body).getResponse().getStatus());

        assertEquals(List.of("zz", "aa", "mm"), new ArrayList<>(validator.request.draft().get("i1").keySet()));
    }

    // -------------------------------------------------------------------------------------- getConfigHistory

    @Test
    void historyIsRedactedServerSide() throws Exception {
        login("tok-history");
        UUID actor = UUID.randomUUID();
        config.history = List.of(
                new GenerationView(2, "cache", "1", DIGEST, GenerationOrigin.EDIT, null, actor,
                        Instant.parse("2026-10-05T10:00:00Z"), 40),
                new GenerationView(1, "cache", "1", "hmacsha256v1:" + "c".repeat(64), GenerationOrigin.EDIT, null,
                        actor, Instant.parse("2026-10-05T09:00:00Z"), 39));

        MvcResult result = read("tok-history", "/config/history");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(41, body.get("stateVersion").asLong(), "the instance's version");
        assertEquals(2, body.get("entries").size());
        JsonNode first = body.get("entries").get(0);
        assertEquals(java.util.Set.of("generation", "at", "by", "configHash"), fieldNames(first),
                "identity, time, actor and the keyed digest - no value, no draft, no rendered set");
        assertEquals(2, first.get("generation").asLong());
        assertEquals("2026-10-05T10:00:00Z", first.get("at").asText());
        assertEquals(actor.toString(), first.get("by").asText());
        assertEquals(DIGEST, first.get("configHash").asText());
        assertEquals(DesignerOrchestrator.HISTORY_LIMIT, config.historyLimit);
    }

    // ---------------------------------------------------------------------------------------- getConfigLadder

    @Test
    void theLadderDeclaresPersistedAndRestartObservedDegradedUntilApplyIsWired() throws Exception {
        login("tok-ladder");

        MvcResult result = read("tok-ladder", "/config-ladder");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(41, body.get("stateVersion").asLong());
        assertEquals("degraded", body.at("/availability/state").asText());
        assertEquals("apply is not wired yet", body.at("/availability/reason").asText());
        assertEquals(List.of("desired", "rendered", "persisted", "restart-observed", "effective-verified"),
                texts(body.get("rungs"), "rung"));
        assertEquals(List.of(true, true, false, false, false),
                body.get("rungs").findValues("present").stream().map(JsonNode::asBoolean).toList());
        assertEquals(3, body.at("/rungs/0/generation").asLong());
        assertEquals(DIGEST, body.at("/rungs/1/configHash").asText());
        assertFalse(body.at("/rungs/2").has("generation"), "an absent rung carries no generation");
        assertFalse(body.at("/rungs/2").has("configHash"));
        assertEquals("rendered", body.get("reached").asText());
        assertFalse(body.has("placements"), "no per-placement rung is claimed that nobody observed");
    }

    @Test
    void aFullyReportedLadderIsNotMarkedDegraded() throws Exception {
        login("tok-ladder-live");
        observed.ladder = new ConfigLadderView(observed.ladder.rungs(), LadderRung.RENDERED, null);

        JsonNode body = json(read("tok-ladder-live", "/config-ladder"));

        assertFalse(body.has("availability"), body.toString());
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
