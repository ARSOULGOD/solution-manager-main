package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.ConfigSecretService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.SecretValueRejectedException;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * {@code setConfigSecret} (plan 010 T10.1; OR-30, OR-45, OR-60) over the real filter, interceptor, argument-resolver,
 * controller and advice pipeline with the cp-api and dp-api ports FAKED. The transaction that pairs the secret row with
 * the generation is {@code dp-core}'s ({@code ConfigSecretServiceIT}); what is pinned here is the ORCHESTRATION: who
 * may call, which field is the target, that the staged content is the draft with ONE reference replaced and the
 * server's own re-projection, that a refusal stores nothing, and that the value is in no response, no log line and is
 * zeroised.
 */
class ConfigSecretControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DIGEST = "hmacsha256v1:" + "b".repeat(64);
    /** Letters and digits only, so it is also a valid identifier for the mutation that echoes it. */
    private static final String VALUE = "Hunter2SentinelT101";
    /** A value the (faked) secret store refuses: it is not empty, so a 422 that echoed it would be visible. */
    private static final String REJECTED_PREFIX = "Rejected";
    private static final UUID REF = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String REF_TEXT = "secretRef://" + REF;
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}";
    private static final String TWO_SELECTION = "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"},"
            + "{\"instanceKey\":\"i2\",\"type\":\"cache-engine\"}]}";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeInventory inventory;
    private FakeCatalog catalog;
    private DesignerControllerTest.FakeConfig config;
    private DesignerControllerTest.FakeObserved observed;
    private DesignerControllerTest.FakeSecrets secrets;
    private ScriptedProjector projector;
    private DesignerControllerTest.FakeValidator validator;
    private FakeConfigSecrets configSecrets;
    private MetricsFakes.RecordingApplicability applicability;
    private UUID organization;
    private UUID instanceId;
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private ch.qos.logback.classic.Logger root;
    private Level rootLevel;

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
        config = context.getBean(DesignerControllerTest.FakeConfig.class);
        observed = context.getBean(DesignerControllerTest.FakeObserved.class);
        secrets = context.getBean(DesignerControllerTest.FakeSecrets.class);
        projector = context.getBean(ScriptedProjector.class);
        validator = context.getBean(DesignerControllerTest.FakeValidator.class);
        configSecrets = context.getBean(FakeConfigSecrets.class);
        applicability = context.getBean(MetricsFakes.RecordingApplicability.class);
        organization = UUID.randomUUID();
        UUID catalogSolution = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        catalog.serve(catalogSolution, "1", "{\"solution\":\"cache\",\"packageVersion\":[1]}");
        inventory.put(new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), catalogSolution, "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(UUID.randomUUID(), UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))), 41L));
        // The stored draft: one component, a plain value, and a secret field not yet set.
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1", SELECTION, "{\"i1\":{\"namespace\":\"orders\"}}",
                DIGEST, "{}"));
        projector.before = unsetProjection();
        projector.after = setProjection();
        serverAccepts(setProjection());

        root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLevel = root.getLevel();
        log.start();
        root.addAppender(log);
        root.setLevel(Level.TRACE);
    }

    @AfterEach
    void detachLog() {
        root.detachAppender(log);
        root.setLevel(rootLevel);
        log.stop();
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

        @Bean DesignerControllerTest.FakeConfig config(Calls calls) {
            return new DesignerControllerTest.FakeConfig(calls);
        }

        @Bean DesignerControllerTest.FakeObserved observed(Calls calls) {
            return new DesignerControllerTest.FakeObserved(calls);
        }

        @Bean DesignerControllerTest.FakeSecrets secrets() {
            return new DesignerControllerTest.FakeSecrets();
        }

        @Bean ScriptedProjector projector() {
            return new ScriptedProjector();
        }

        @Bean DesignerControllerTest.FakeValidator validator() {
            return new DesignerControllerTest.FakeValidator();
        }

        @Bean DesignerControllerTest.FakeCodec codec() {
            return new DesignerControllerTest.FakeCodec();
        }

        @Bean MetricsFakes.RecordingApplicability applicability() {
            return new MetricsFakes.RecordingApplicability();
        }

        @Bean FakeConfigSecrets configSecrets(Calls calls) {
            return new FakeConfigSecrets(calls);
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
        ConfigSecretOrchestrator configSecretOrchestrator(DeclareOrchestrator instances, SolutionCatalog catalog,
                ConfigRecordService config, ConfigSecretService configSecrets, ObservedStateService observed,
                SecretReferences secrets, DesignerProjector projector, DesignerStageValidator validator,
                DesignerCodec codec, MetricApplicability applicability) {
            return new ConfigSecretOrchestrator(instances, catalog, config, configSecrets, observed, secrets,
                    projector, validator, codec, applicability);
        }

        @Bean ConfigSecretController configSecretController(ConfigSecretOrchestrator orchestrator) {
            return new ConfigSecretController(orchestrator);
        }
    }

    /**
     * The server's projection, scripted: {@link #before} for the stored draft, {@link #after} for a draft that carries
     * the new reference (what the real projector would compute from it), and it records every draft it was given.
     */
    static final class ScriptedProjector implements DesignerProjector {
        ViewModel before;
        ViewModel after;
        int invocations;
        final List<Map<String, Map<String, Object>>> drafts = new ArrayList<>();

        @Override
        public ViewModel project(com.solace.sm.cp.api.designer.PackageModel.SolutionPackage pkg,
                com.solace.sm.cp.api.designer.StateModel.ComponentSelection selection,
                Map<String, Map<String, Object>> draft, com.solace.sm.cp.api.designer.StateModel.ObservedState observed,
                Map<String, List<Object>> catalog, Object requestedPackageVersion) {
            invocations++;
            drafts.add(draft);
            boolean carriesTheNewReference = draft.values().stream()
                    .anyMatch(fields -> fields.containsValue(REF_TEXT));
            return carriesTheNewReference ? after : before;
        }
    }

    /**
     * Runs the builder the way the data plane does (after "storing" the value under {@link #REF}) and commits NOTHING
     * unless the builder returns: {@link #committed} is what a refusal must leave empty.
     */
    static final class FakeConfigSecrets implements ConfigSecretService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<UUID> actorsSeen = new ArrayList<>();
        final List<StageCommand> committed = new ArrayList<>();
        final List<Long> basesSeen = new ArrayList<>();
        char[] valueHandedOver;
        String valueAtCall;
        RuntimeException failure;

        FakeConfigSecrets(Calls calls) {
            this.calls = calls;
        }

        @Override
        public StageResult storeAndStage(UUID organizationId, UUID instanceId, long baseStateVersion, char[] value,
                UUID actor, StageBuilder stage) {
            calls.add("dp.storeAndStage");
            organizationsSeen.add(organizationId);
            actorsSeen.add(actor);
            basesSeen.add(baseStateVersion);
            valueHandedOver = value;
            valueAtCall = new String(value);
            if (failure != null) {
                throw failure;
            }
            if (value.length == 0 || valueAtCall.startsWith(REJECTED_PREFIX)) {
                throw new SecretValueRejectedException();
            }
            StageCommand command = stage.build(REF);
            committed.add(command);
            return new StageResult(new GenerationView(6, command.packageSolution(), command.packageVersion(), DIGEST,
                    command.origin(), null, command.actor(), Instant.EPOCH, 90), false, 91);
        }
    }

    // ------------------------------------------------------------------------------------ view-model builders

    /** The latest draft, projected: a plain field and a secret field with no value yet. */
    private static ViewModel unsetProjection() {
        return DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("namespace", "cache-engine.namespace", false,
                        new ViewModel.FieldValue.Value("orders", "draft"), List.of()),
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretUnset(), List.of()))), List.of(), List.of());
    }

    /** The server's projection of the draft AFTER the secret is set: the password is a reference. */
    private static ViewModel setProjection() {
        return DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("namespace", "cache-engine.namespace", false,
                        new ViewModel.FieldValue.Value("orders", "draft"), List.of()),
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretRef(REF_TEXT), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.namespace", "orders"),
                        new ViewModel.RenderedEntry("i1", "cache-engine.solace.password", REF_TEXT)),
                List.of());
    }

    private void serverAccepts(ViewModel serverResult) {
        validator.next = new StageOutcome.Accepted(serverResult);
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult set(String token, String fieldKey, String body) throws Exception {
        return mvc.perform(post("/api/dp/instances/" + instanceId + "/config/secrets/" + fieldKey)
                .cookie(new Cookie(COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-" + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult set(String token, String fieldKey, String value, long base) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("value", value).put("baseStateVersion", base);
        return set(token, fieldKey, body.toString());
    }

    private MvcResult set(String token) throws Exception {
        return set(token, "solace.password", VALUE, 41);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private void assertNothingStoredOrStaged() {
        assertTrue(configSecrets.committed.isEmpty(), "a generation was committed: " + calls.log);
        assertTrue(config.staged.isEmpty(), "the config-record stage path was not the one used: " + calls.log);
    }

    // ----------------------------------------------------------------------------------------- authorization

    @Test
    void requiresConfigEditAtThePathsInstanceAndNothingElse() throws Exception {
        Session session = login("tok-verb");

        MvcResult result = set("tok-verb");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(Permission.CONFIG_EDIT), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNeitherTheValueNorAnyPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        MvcResult result = set("tok-deny");

        assertEquals(403, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), "no plane was read or written: " + calls.log);
        assertEquals(0, projector.invocations);
        assertFalse(raw(result).contains(VALUE));
    }

    @Test
    void anInstanceThatIsNotActiveIsThe404AndNothingElseIsRead() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();

        MvcResult result = set("tok-missing");

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertFalse(calls.has("dp.latestDraft"), calls.log.toString());
        assertFalse(calls.has("dp.storeAndStage"), calls.log.toString());
        assertEquals(0, projector.invocations);
    }

    @Test
    void everyCallUsesTheSessionsOrganizationAndPrincipalNeverARequestField() throws Exception {
        Session session = login("tok-i5");
        UUID other = UUID.randomUUID();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("value", VALUE).put("baseStateVersion", 41).put("organizationId", other.toString());

        MvcResult smuggled = set("tok-i5", "solace.password", body.toString());
        MvcResult honest = set("tok-i5");

        assertEquals(400, smuggled.getResponse().getStatus(), "a body naming an organization is refused, not ignored");
        assertEquals(200, honest.getResponse().getStatus(), raw(honest));
        for (List<UUID> seen : List.of(configSecrets.organizationsSeen, config.organizationsSeen,
                observed.organizationsSeen, secrets.organizationsSeen)) {
            assertTrue(seen.stream().allMatch(session.organizationId()::equals), seen.toString());
        }
        assertEquals(List.of(session.principal().id()), configSecrets.actorsSeen);
        assertEquals(session.principal().id(), configSecrets.committed.get(0).actor());
    }

    // --------------------------------------------------------------------------------------- the target field

    @Test
    void aNonSecretFieldIs404AndNothingIsStored() throws Exception {
        login("tok-plain");

        MvcResult result = set("tok-plain", "namespace", VALUE, 41);

        assertEquals(404, result.getResponse().getStatus(), raw(result));
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertFalse(calls.has("dp.storeAndStage"), "the store was never asked: " + calls.log);
        assertFalse(raw(result).contains(VALUE));
        assertNothingStoredOrStaged();
    }

    @Test
    void aFieldThePackageDoesNotDeclareIs404TheSameWay() throws Exception {
        login("tok-unknown");

        MvcResult result = set("tok-unknown", "no.such.field", VALUE, 41);

        assertEquals(404, result.getResponse().getStatus());
        assertFalse(calls.has("dp.storeAndStage"));
    }

    @Test
    void anInstanceWithNoStagedDraftHasNoSecretFieldAndIs404() throws Exception {
        login("tok-nodraft");
        config.draft = Optional.empty();

        MvcResult result = set("tok-nodraft");

        assertEquals(404, result.getResponse().getStatus());
        assertFalse(calls.has("dp.storeAndStage"));
    }

    @Test
    void aFieldKeyTwoSelectedComponentsBothDeclareIs422AndNeverGuessed() throws Exception {
        login("tok-ambiguous");
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1", TWO_SELECTION, "{}", DIGEST, "{}"));
        ViewModel.FieldView password = DesignerControllerTest.field("solace.password",
                "cache-engine.solace.password", true, new ViewModel.FieldValue.SecretUnset(), List.of());
        projector.before = DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1", password),
                DesignerControllerTest.component("i2", password)), List.of(), List.of());

        MvcResult result = set("tok-ambiguous");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SECRET_FIELD_AMBIGUOUS.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains("i1") || raw(result).contains("i2"), "no instance key is echoed");
        assertFalse(calls.has("dp.storeAndStage"));
    }

    @Test
    void anUnprojectableSelectionIs422BeforeAnythingIsStored() throws Exception {
        login("tok-unprojectable");
        projector.before = DesignerControllerTest.viewModel(List.of(), List.of(), List.of(
                new ViewModel.Diagnostic("VERSION_UNKNOWN", "unknown version", null, null)));

        MvcResult result = set("tok-unprojectable");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SELECTION_UNRESOLVED.detail(), json(result).get("detail").asText());
        assertFalse(calls.has("dp.storeAndStage"));
    }

    // ---------------------------------------------------------------------------- what is stored and staged

    @Test
    void theStagedGenerationIsTheLatestDraftWithOneReferenceReplacedAndTheServersOwnRenderedSet() throws Exception {
        login("tok-stage");

        MvcResult result = set("tok-stage");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(1, configSecrets.committed.size());
        StageCommand command = configSecrets.committed.get(0);
        assertEquals(41, command.baseStateVersion());
        assertEquals(GenerationOrigin.EDIT, command.origin());
        assertEquals(null, command.derivedFromGeneration(), "a secret is an edit, not a copy of another generation");
        assertEquals("cache", command.packageSolution());
        assertEquals("1", command.packageVersion());
        assertEquals(SELECTION, command.componentSelectionJson(), "the stored selection, verbatim");
        assertEquals(MAPPER.readTree("{\"i1\":{\"namespace\":\"orders\",\"solace.password\":\"" + REF_TEXT + "\"}}"),
                MAPPER.readTree(command.draftValuesJson()), "the draft with that ONE field replaced by the reference");
        assertEquals(List.of(RenderedEntryValue.plain("i1", "cache-engine.namespace", "\"orders\""),
                RenderedEntryValue.secret("i1", "cache-engine.solace.password", REF)), command.rendered(),
                "the server's rendered set: a plain value as JSON, the secret as its reference");
        assertEquals(null, command.layoutJson(), "a copy-forward leaves the presentation record alone (OR-96)");
        assertEquals(organization, configSecrets.organizationsSeen.get(0));
        assertEquals(List.of(41L), configSecrets.basesSeen);
    }

    @Test
    void theGenerationItMintsCarriesTheListTheEvaluatorReturnsForTheDraftWithTheNewReference() throws Exception {
        // plan 011 P11-4.1, M-Q15: a console secret is the second path through which a generation is born, and it
        // re-projects an edited draft, so the gateway evaluates that draft like a stage does
        login("tok-active");
        applicability.next = List.of("cache.hit_rate");

        MvcResult result = set("tok-active");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of("cache.hit_rate"), configSecrets.committed.get(0).activeMetricNames());
        MetricsFakes.Ask ask = applicability.last();
        assertEquals(1L, ask.packageVersion());
        assertEquals(MAPPER.readTree("{\"i1\":{\"namespace\":\"orders\",\"solace.password\":\"" + REF_TEXT + "\"}}"),
                MAPPER.valueToTree(ask.draft()), "the draft the generation stores, with the new reference in it");
    }

    @Test
    void theNewDraftIsReProjectedAndCheckedByTheSameValidatorStageUses() throws Exception {
        login("tok-validated");

        set("tok-validated");

        assertEquals(2, projector.invocations, "once to find the field, once for the draft the secret produces");
        assertNotNull(validator.request, "OR-34: the stage rules ran");
        assertEquals("secretRef://" + REF, ((Map<?, ?>) validator.request.draft().get("i1")).get("solace.password"));
        assertEquals(setProjection().rendered(), validator.request.claimedRendered(),
                "the claim is the server's own projection of the new draft");
        assertTrue(secrets.asked.isEmpty(), "the reference just minted is not asked of the store; no other exists");
    }

    @Test
    void aReferenceAlreadyInTheDraftIsStillResolvedButTheNewOneIsNot() throws Exception {
        login("tok-other-ref");
        UUID other = UUID.randomUUID();
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1", SELECTION,
                "{\"i1\":{\"solace.token\":\"secretRef://" + other + "\"}}", DIGEST, "{}"));
        projector.before = DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretUnset(), List.of()),
                DesignerControllerTest.field("solace.token", "cache-engine.solace.token", true,
                        new ViewModel.FieldValue.SecretRef("secretRef://" + other), List.of()))), List.of(), List.of());
        serverAccepts(DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretRef(REF_TEXT), List.of()),
                DesignerControllerTest.field("solace.token", "cache-engine.solace.token", true,
                        new ViewModel.FieldValue.SecretRef("secretRef://" + other), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.solace.password", REF_TEXT),
                        new ViewModel.RenderedEntry("i1", "cache-engine.solace.token", "secretRef://" + other)),
                List.of()));

        MvcResult result = set("tok-other-ref");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(other), secrets.asked, "a reference the operator did not just mint is resolved");
        assertEquals(organization, secrets.organizationsSeen.get(0));
        assertEquals(List.of(RenderedEntryValue.secret("i1", "cache-engine.solace.password", REF),
                RenderedEntryValue.secret("i1", "cache-engine.solace.token", other)),
                configSecrets.committed.get(0).rendered(), "the other secret is carried forward untouched");
    }

    @Test
    void anOtherReferenceThatNoLongerResolvesIs422AndStoresNothing() throws Exception {
        login("tok-dangling");
        UUID other = UUID.randomUUID();
        config.draft = Optional.of(new ConfigDraft(5, "cache", "1", SELECTION,
                "{\"i1\":{\"solace.token\":\"secretRef://" + other + "\"}}", DIGEST, "{}"));
        projector.before = DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretUnset(), List.of()),
                DesignerControllerTest.field("solace.token", "cache-engine.solace.token", true,
                        new ViewModel.FieldValue.SecretRef("secretRef://" + other), List.of()))), List.of(), List.of());
        serverAccepts(DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretRef(REF_TEXT), List.of()),
                DesignerControllerTest.field("solace.token", "cache-engine.solace.token", true,
                        new ViewModel.FieldValue.SecretRef("secretRef://" + other), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.solace.password", REF_TEXT),
                        new ViewModel.RenderedEntry("i1", "cache-engine.solace.token", "secretRef://" + other)),
                List.of()));
        secrets.resolves = false;

        MvcResult result = set("tok-dangling");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SECRET_REFERENCE_UNRESOLVED.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(other.toString()));
        assertNothingStoredOrStaged();
    }

    @Test
    void theResponseIsAStagedConfigWithNoUnchangedAndNeverTheValueOrAReference() throws Exception {
        login("tok-response");

        MvcResult result = set("tok-response");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(List.of("generation", "stateVersion"), sortedNames(body), raw(result));
        assertEquals(91, body.get("stateVersion").asLong(), "the instance's version AFTER the call: the next base");
        assertEquals(6, body.get("generation").asLong());
        assertFalse(raw(result).contains(VALUE));
        assertFalse(raw(result).contains(REF.toString()), "not even the reference: refetch the designer");
    }

    // ------------------------------------------------------------------------------------------- refusals

    @Test
    void aStaleBaseStateVersionIs409AndTheCurrentVersionAndTheValueAreNotEchoed() throws Exception {
        login("tok-stale");
        configSecrets.failure = new StaleStateVersionException(987654);

        MvcResult result = set("tok-stale");

        assertEquals(409, result.getResponse().getStatus(), raw(result));
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertFalse(raw(result).contains("987654"));
        assertFalse(raw(result).contains(VALUE));
        assertNothingStoredOrStaged();
    }

    @Test
    void aBlockingValidationIs422AndTheSecretIsNotStored() throws Exception {
        login("tok-blocking");
        serverAccepts(DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("namespace", "cache-engine.namespace", false,
                        new ViewModel.FieldValue.Unset(), List.of(new ViewModel.Validation("block", "REQUIRED", "x"))),
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretRef(REF_TEXT), List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.solace.password", REF_TEXT)), List.of()));

        MvcResult result = set("tok-blocking");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.BLOCKING_VALIDATION.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(VALUE));
        assertNothingStoredOrStaged();
    }

    @Test
    void aRefusedProjectionIs422AndTheSecretIsNotStored() throws Exception {
        login("tok-refused");
        validator.next = new StageOutcome.Refused(List.of(
                new DesignerStageValidator.StageRefusalReason.ValueMismatch("i1", "cache-engine.namespace", "a", "b")),
                setProjection());

        MvcResult result = set("tok-refused");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.RENDERED_MISMATCH.detail(), json(result).get("detail").asText());
        assertNothingStoredOrStaged();
    }

    @Test
    void anEmptyValueTheStoreRefusesIs422WithFixedTextAndNoValue() throws Exception {
        login("tok-empty");

        MvcResult result = set("tok-empty", "solace.password", "", 41);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SECRET_VALUE_REJECTED.detail(), json(result).get("detail").asText());
        assertNothingStoredOrStaged();
    }

    @Test
    void aValueTheStoreRefusesIs422WithFixedTextAndTheValueIsNeverEchoed() throws Exception {
        login("tok-rejected");
        String refused = REJECTED_PREFIX + VALUE;

        MvcResult result = set("tok-rejected", "solace.password", refused, 41);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals(StageRefusedException.Reason.SECRET_VALUE_REJECTED.detail(), json(result).get("detail").asText());
        assertFalse(raw(result).contains(refused), "I6: the 422's detail is fixed text, never the value");
        assertFalse(raw(result).contains(VALUE));
        assertNothingStoredOrStaged();
    }

    @Test
    void aMalformedBodyIs400AndNothingIsRead() throws Exception {
        login("tok-bad");

        for (String bad : List.of("{}", "{\"value\":\"x\"}", "{\"baseStateVersion\":1}",
                "{\"value\":\"x\",\"baseStateVersion\":-1}", "{\"value\":null,\"baseStateVersion\":1}",
                "{\"value\":\"x\",\"baseStateVersion\":1,\"extra\":true}", "not json")) {
            MvcResult result = set("tok-bad", "solace.password", bad);
            assertEquals(400, result.getResponse().getStatus(), bad);
            assertEquals("BAD_REQUEST", json(result).get("code").asText());
        }
        assertFalse(calls.has("dp.storeAndStage"));
        assertEquals(0, projector.invocations);
    }

    // ------------------------------------------------------------------------------- I6: the value goes nowhere

    @Test
    void theValueNeverAppearsInAnyResponseOrLogLineOnAnyPath() throws Exception {
        login("tok-i6");
        List<MvcResult> results = new ArrayList<>();

        results.add(set("tok-i6"));                                                    // 200
        results.add(set("tok-i6", "namespace", VALUE, 41));                            // 404
        configSecrets.failure = new StaleStateVersionException(1);
        results.add(set("tok-i6"));                                                    // 409
        configSecrets.failure = null;
        serverAccepts(DesignerControllerTest.viewModel(List.of(DesignerControllerTest.component("i1",
                DesignerControllerTest.field("solace.password", "cache-engine.solace.password", true,
                        new ViewModel.FieldValue.SecretRef(REF_TEXT), List.of(
                                new ViewModel.Validation("block", "X", "y"))))), List.of(), List.of()));
        results.add(set("tok-i6"));                                                    // 422 blocking
        results.add(set("tok-i6", "solace.password",
                "{\"value\":\"" + VALUE + "\",\"baseStateVersion\":41,\"organizationId\":\"x\"}"));   // 400
        authorization.outcome = Outcome.DENY;
        results.add(set("tok-i6"));                                                    // 403

        for (MvcResult result : results) {
            assertFalse(raw(result).contains(VALUE), "response " + result.getResponse().getStatus() + ": " + raw(result));
        }
        for (ILoggingEvent event : log.list) {
            assertFalse(event.getFormattedMessage().contains(VALUE), event.getLoggerName() + ": "
                    + event.getFormattedMessage());
            assertTrue(event.getThrowableProxy() == null
                    || !event.getThrowableProxy().getMessage().contains(VALUE), event.getLoggerName());
        }
        assertEquals(Set.of(200, 404, 409, 422, 400, 403), statuses(results), "every refusal path was exercised");
    }

    @Test
    void theCharArrayHandedToTheServiceIsZeroisedWhetherTheCallSucceedsOrIsRefused() throws Exception {
        login("tok-zero");

        set("tok-zero");
        char[] afterSuccess = configSecrets.valueHandedOver;
        assertEquals(VALUE, configSecrets.valueAtCall, "the service saw the value");
        configSecrets.failure = new StaleStateVersionException(1);
        set("tok-zero");
        char[] afterRefusal = configSecrets.valueHandedOver;

        for (char[] handed : List.of(afterSuccess, afterRefusal)) {
            assertEquals(VALUE.length(), handed.length);
            for (char c : handed) {
                assertEquals('\0', c, "the buffer is overwritten once the call returns: " + Arrays.toString(handed));
            }
        }
    }

    @Test
    void theRequestsToStringNeverPrintsTheValue() {
        SetConfigSecretRequest request = new SetConfigSecretRequest(VALUE, 3L);

        assertFalse(request.toString().contains(VALUE));
        assertTrue(request.toString().contains("baseStateVersion=3"));
    }

    private static Set<Integer> statuses(List<MvcResult> results) {
        Set<Integer> out = new java.util.TreeSet<>();
        results.forEach(r -> out.add(r.getResponse().getStatus()));
        return out;
    }

    private static List<String> sortedNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out.stream().sorted().toList();
    }
}
