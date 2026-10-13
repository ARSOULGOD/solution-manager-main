package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerProjector;
import com.solace.sm.cp.api.designer.DesignerStageValidator;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageOutcome;
import com.solace.sm.cp.api.designer.DesignerStageValidator.StageRefusalReason;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.readback.FieldPathTable;
import com.solace.sm.dp.api.readback.NotAcceptedReason;
import com.solace.sm.dp.api.readback.ReadBackCandidate;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import com.solace.sm.dp.api.readback.RuntimeReadBackService;
import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeCodec;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeConfig;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeObserved;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeProjector;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeSecrets;
import com.solace.sm.gateway.http.DesignerControllerTest.FakeValidator;
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
import java.util.ArrayList;
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

import static com.solace.sm.gateway.http.DesignerControllerTest.component;
import static com.solace.sm.gateway.http.DesignerControllerTest.field;
import static com.solace.sm.gateway.http.DesignerControllerTest.viewModel;

/**
 * {@code acceptRuntimeConfig} (plan 010 T10.4, {@code OR-104}) over the real filter, interceptor, controller, orchestrator
 * and advice pipeline with the cp-api and dp-api ports FAKED. The read-back's parser and mapper are dp-core's
 * ({@code ConfigFileParserTest}, {@code ReadBackMapperTest}) and the real thing end to end is
 * {@code AcceptRuntimeEndpointIT}; what is pinned here is the ORCHESTRATION: who may call, in what order, what is composed
 * into the draft, that the server's projection is what is stored, and that nothing a refusal or a response carries is a
 * value.
 */
class AcceptRuntimeControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SENTINEL = "value-sentinel-6a2e";
    private static final UUID SECRET_REF = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeInventory inventory;
    private FakeCatalog catalog;
    private FakeConfig config;
    private FakeProjector projector;
    private FakeValidator validator;
    private FakeReadBack readBack;
    private MetricsFakes.RecordingApplicability applicability;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

    @Configuration
    @EnableWebMvc
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeReadBack readBack(Calls calls) {
            return new FakeReadBack(calls);
        }

        @Bean
        AcceptRuntimeOrchestrator acceptRuntimeOrchestrator(DeclareOrchestrator instances, ConfigRecordService config,
                ObservedStateService observed, SecretReferences secrets, RuntimeReadBackService readBack,
                DesignerProjector projector, DesignerStageValidator validator, AuthorizationService authorization,
                DesignerOrchestrator designer) {
            return new AcceptRuntimeOrchestrator(instances, config, observed, secrets, readBack, projector, validator,
                    authorization, designer);
        }

        @Bean AcceptRuntimeController acceptRuntimeController(AcceptRuntimeOrchestrator orchestrator) {
            return new AcceptRuntimeController(orchestrator);
        }
    }

    /** Records what it was asked and answers what the test programmed. */
    static final class FakeReadBack implements RuntimeReadBackService {
        final Calls calls;
        final List<UUID> organizations = new ArrayList<>();
        final List<UUID> placements = new ArrayList<>();
        final List<FieldPathTable> tables = new ArrayList<>();
        final List<Long> generationsAsked = new ArrayList<>();
        ReadBackCandidate candidate;
        ReadBackRefusedException refusal;
        boolean differs = true;

        FakeReadBack(Calls calls) {
            this.calls = calls;
        }

        @Override
        public ReadBackCandidate readBack(UUID organizationId, UUID instanceId, UUID placementId, FieldPathTable table) {
            calls.add("dp.readBack");
            organizations.add(organizationId);
            placements.add(placementId);
            tables.add(table);
            if (refusal != null) {
                throw refusal;
            }
            return candidate;
        }

        @Override
        public boolean fileDiffersFromRendering(UUID organizationId, UUID instanceId, UUID placementId, long generation,
                ReadBackCandidate ignored) {
            calls.add("dp.fileDiffers");
            generationsAsked.add(generation);
            return differs;
        }
    }

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
        projector = context.getBean(FakeProjector.class);
        validator = context.getBean(FakeValidator.class);
        readBack = context.getBean(FakeReadBack.class);
        applicability = context.getBean(MetricsFakes.RecordingApplicability.class);
        organization = UUID.randomUUID();
        UUID catalogSolution = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        catalog.serve(catalogSolution, "1", "{\"solution\":\"cache\",\"packageVersion\":[1]}");
        inventory.put(new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), catalogSolution, "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))), 41L));
        // the instance's latest generation: i1 with max-size 100, ttl 1h, and a stored password reference
        config.draft = Optional.of(new ConfigDraft(7, "cache", "1",
                "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}",
                "{\"i1\":{\"max-size\":100,\"ttl\":\"1h\",\"solace.password\":\"secretRef://"
                        + SECRET_REF + "\",\"extras\":\"keep: me\"}}", "hmacsha256v1:" + "c".repeat(64)));
        config.generationAfter = 8;
        config.stateVersionAfter = 42;
    }

    // ---- the server's projection of what the file said -------------------------------------------------------

    private static ViewModel.FieldView fieldView(String key, String path, boolean masked, boolean visible,
            List<ViewModel.Validation> validations) {
        return fieldView(key, path, masked, visible, validations, "v");
    }

    private static ViewModel.FieldView fieldView(String key, String path, boolean masked, boolean visible,
            List<ViewModel.Validation> validations, Object effective) {
        return new ViewModel.FieldView(key, path, masked ? "secret" : key.equals("extras") ? "yaml-editor" : "text",
                masked ? new ViewModel.FieldValue.SecretRef("secretRef://" + SECRET_REF)
                        : new ViewModel.FieldValue.Value(effective, "default"),
                null, visible, true, false, false, masked, false, null, null, validations, visible ? List.of() : List.of("k"));
    }

    /** What the package declares and what the server renders for the accepted draft. */
    private ViewModel server(boolean tiedTimeoutVisible, List<ViewModel.Validation> maxSizeValidations,
            Object renderedMaxSize) {
        return server(tiedTimeoutVisible, maxSizeValidations, renderedMaxSize, "v");
    }

    private ViewModel server(boolean tiedTimeoutVisible, List<ViewModel.Validation> maxSizeValidations,
            Object renderedMaxSize, Object effectiveMaxSize) {
        return viewModel(List.of(component("i1",
                        fieldView("max-size", "cache-engine.max-size", false, true, maxSizeValidations, effectiveMaxSize),
                        fieldView("ttl", "cache-engine.ttl", false, true, List.of()),
                        fieldView("timeout", "cache-engine.timeout", false, tiedTimeoutVisible, List.of()),
                        fieldView("solace.password", "cache-engine.solace.password", true, true, List.of()),
                        fieldView("extras", "cache-engine.extras", false, true, List.of()))),
                List.of(new ViewModel.RenderedEntry("i1", "cache-engine.max-size", renderedMaxSize),
                        new ViewModel.RenderedEntry("i1", "cache-engine.ttl", "30m"),
                        new ViewModel.RenderedEntry("i1", "cache-engine.solace.password", "secretRef://" + SECRET_REF)),
                List.of());
    }

    private void fileSaid(ReadBackCandidate.MappedValue... values) {
        readBack.candidate = new ReadBackCandidate(placementId, List.of(values), List.of(), false,
                "hmacsha256v1:" + "d".repeat(64));
    }

    private void fileSaid(List<ReadBackCandidate.MappedValue> values, List<ReadBackCandidate.NotAccepted> notAccepted) {
        readBack.candidate = new ReadBackCandidate(placementId, values, notAccepted, false,
                "hmacsha256v1:" + "d".repeat(64));
    }

    private static ReadBackCandidate.MappedValue mapped(String field, String json) {
        return new ReadBackCandidate.MappedValue("i1", field, json);
    }

    private void serverAccepts(ViewModel server) {
        projector.next = server;
        validator.next = new StageOutcome.Accepted(server);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult accept(String token, String body) throws Exception {
        return mvc.perform(post("/api/dp/instances/" + instanceId + "/config/accept-runtime")
                .cookie(new Cookie(COOKIE, token)).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-" + token).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult accept(String token) throws Exception {
        return accept(token, body(41, placementId));
    }

    private static String body(long base, UUID placement) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseStateVersion", base);
        if (placement != null) {
            body.put("placementId", placement.toString());
        }
        return body.toString();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private void assertNothingTouched() {
        assertFalse(calls.has("dp.readBack"), calls.log.toString());
        assertTrue(config.staged.isEmpty(), "a generation was written: " + calls.log);
    }

    // ---- the request -------------------------------------------------------------------------------------------

    @Test
    void aMissingPlacementIdIsRefused() throws Exception {
        login("tok-noplacement");
        MvcResult result = accept("tok-noplacement", body(41, null));
        assertEquals(400, result.getResponse().getStatus(), raw(result));
        assertEquals("BAD_REQUEST", json(result).get("code").asText());
        assertNothingTouched();
    }

    @Test
    void aStrayKeyANegativeBaseOrAMalformedPlacementIsRefused() throws Exception {
        login("tok-bad");
        assertEquals(400, accept("tok-bad", "{\"baseStateVersion\":41,\"placementId\":\"" + placementId
                + "\",\"path\":\"/etc/passwd\"}").getResponse().getStatus(), "the request names no path, host or command");
        assertEquals(400, accept("tok-bad", body(-1, placementId)).getResponse().getStatus());
        assertEquals(400, accept("tok-bad", "{\"baseStateVersion\":41,\"placementId\":\"not-a-uuid\"}")
                .getResponse().getStatus());
        assertEquals(400, accept("tok-bad", "{\"placementId\":\"" + placementId + "\"}").getResponse().getStatus());
        assertNothingTouched();
    }

    // ---- authorization -------------------------------------------------------------------------------------------

    @Test
    void requiresBothConfigEditAndConfigOverrideWarning() throws Exception {
        login("tok-both");
        fileSaid();
        serverAccepts(server(true, List.of(), 100));

        accept("tok-both");

        assertEquals(List.of(Permission.CONFIG_EDIT, Permission.CONFIG_OVERRIDE_WARNING), authorization.permissions,
                "the interceptor's verb, then the second one at the same instance");
        assertEquals(authorization.resources.get(0), authorization.resources.get(1));
    }

    // ---- plan 011 P11-4.1, M-Q15: accept-runtime RE-PROJECTS, so the gateway evaluates the accepted draft

    @Test
    void anAcceptedGenerationCarriesTheListTheEvaluatorReturnsForTheDraftItAccepted() throws Exception {
        login("tok-active");
        fileSaid();
        serverAccepts(server(true, List.of(), 100));
        applicability.next = List.of("cache.size");

        MvcResult result = accept("tok-active");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        StageCommand command = config.staged.get(0);
        assertEquals(GenerationOrigin.ACCEPT_RUNTIME, command.origin());
        assertEquals(List.of("cache.size"), command.activeMetricNames(),
                "accept-runtime changes the draft, so its list is evaluated, not copied from the generation it derives from");
        assertEquals(1, applicability.asks.size());
        MetricsFakes.Ask ask = applicability.last();
        assertEquals(1L, ask.packageVersion());
        assertEquals(List.of("i1"), ask.selection().instances().stream().map(i -> i.instanceKey()).toList());
        assertEquals(DesignerOrchestrator.readDraft(command.draftValuesJson()), ask.draft(),
                "evaluated over the very draft the generation stores");
    }

    @Test
    void aCallerWithEditButNotOverrideWarningIs403NamingTheMissingVerbAndNothingIsRead() throws Exception {
        login("tok-edit-only");
        authorization.outcomeByPermission.put(Permission.CONFIG_OVERRIDE_WARNING, Outcome.DENY);

        MvcResult result = accept("tok-edit-only");

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertEquals("FORBIDDEN", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("config.overrideWarning"), raw(result));
        assertNothingTouched();
    }

    @Test
    void aCallerWithoutConfigEditIs403BeforeTheBodyIsReadOrAnyPlaneIsTouched() throws Exception {
        login("tok-none");
        authorization.outcome = Outcome.DENY;
        assertEquals(403, accept("tok-none").getResponse().getStatus());
        assertEquals(List.of(Permission.CONFIG_EDIT), authorization.permissions);
        assertTrue(calls.log.isEmpty(), calls.log.toString());
    }

    @Test
    void anInstanceThatIsNotThisOrganizationsIsTheSame404() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();
        MvcResult result = accept("tok-missing");
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertNothingTouched();
    }

    // ---- scenario: the named placement ------------------------------------------------------------------------------

    @Test
    void theReadBackIsOfTheNamedPlacementOnly() throws Exception {
        Session session = login("tok-placement");
        fileSaid();
        serverAccepts(server(true, List.of(), 100));

        MvcResult named = accept("tok-placement");

        assertEquals(200, named.getResponse().getStatus(), raw(named));
        assertEquals(List.of(placementId), readBack.placements);
        assertEquals(List.of(session.organizationId()), readBack.organizations, "I5: the session's, never a field");
        assertEquals(placementId.toString(), json(named).get("placementId").asText());

        MvcResult other = accept("tok-placement", body(41, UUID.randomUUID()));
        assertEquals(404, other.getResponse().getStatus(), "a placement that is not one of this instance's live ones");
        assertEquals(1, readBack.placements.size(), "and the host is never asked about it");
    }

    // ---- compose -----------------------------------------------------------------------------------------------------

    @Test
    void theMappedValuesReplaceTheDraftAbsentOnesAreUnsetAndSecretAndYamlFieldsKeepWhatTheyHold() throws Exception {
        login("tok-compose");
        fileSaid(mapped("max-size", "250000"), mapped("ttl", "\"30m\""));
        serverAccepts(server(true, List.of(), 250000));

        MvcResult result = accept("tok-compose");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        Map<String, Object> sent = projector.draft.get("i1");
        assertEquals(250000, sent.get("max-size"));
        assertEquals("30m", sent.get("ttl"));
        assertFalse(sent.containsKey("timeout"), "a declared field the file does not carry is unset");
        assertEquals("secretRef://" + SECRET_REF, sent.get("solace.password"), "a secret keeps its CURRENT reference");
        assertEquals("keep: me", sent.get("extras"), "a yaml field is not accepted: it keeps what it holds");
        assertEquals(sent, validator.request.draft().get("i1"), "the validator saw the same draft the projector did");

        var table = readBack.tables.get(0);
        assertEquals(List.of("max-size", "ttl", "timeout", "solace.password", "extras"),
                table.entries().stream().map(FieldPathTable.Entry::fieldKey).toList());
        assertEquals(SECRET_REF, table.entries().stream().filter(FieldPathTable.Entry::secret).findFirst().orElseThrow()
                .secretRef(), "the table carries the CURRENT reference, never a value");
        assertTrue(table.entries().stream().anyMatch(FieldPathTable.Entry::yaml));
    }

    /**
     * A file SM itself rendered carries every defaulted field as well. Writing those into the draft would mint a new
     * generation for a file that says nothing new, and guard 1 (unchanged) would never fire for the most ordinary accept.
     */
    @Test
    void aMappedValueEqualToTheFieldsEffectiveValueLeavesTheDraftAlone() throws Exception {
        login("tok-same");
        config.draft = Optional.of(new ConfigDraft(7, "cache", "1",
                "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}",
                "{\"i1\":{\"ttl\":\"1h\",\"solace.password\":\"secretRef://" + SECRET_REF + "\"}}",
                "hmacsha256v1:" + "c".repeat(64)));
        // max-size is not in the draft: its effective value is the package default, 100; the file says 100 too
        fileSaid(mapped("max-size", "100"), mapped("ttl", "\"30m\""));
        serverAccepts(server(true, List.of(), 100, 100));

        assertEquals(200, accept("tok-same").getResponse().getStatus());

        assertEquals(Map.of("ttl", "30m", "solace.password", "secretRef://" + SECRET_REF), projector.draft.get("i1"),
                "max-size is not written (the file says what the instance already runs on); ttl is (it differs)");
    }

    @Test
    void aDeclaredFieldTheFileDoesNotCarryIsUnsetInTheAcceptedDraft() throws Exception {
        login("tok-unset");
        // the latest generation holds ttl 1h; the host's file has no ttl at all, so the MI runs on its default
        fileSaid(mapped("max-size", "250000"));
        serverAccepts(server(true, List.of(), 250000));

        assertEquals(200, accept("tok-unset").getResponse().getStatus());

        assertFalse(projector.draft.get("i1").containsKey("ttl"), "unset, not kept at 1h");
        assertFalse(config.staged.get(0).draftValuesJson().contains("\"ttl\""), config.staged.get(0).draftValuesJson());
    }

    /**
     * OR-108 (6), the TLS-off keystore scenario: a field the current projection does not show (its {@code when} is false)
     * is not rendered, so its absence from the file is not a runtime fact. Its stored draft value is kept, and "absent
     * means unset" (D-ACCEPT section 3.3 item 5) applies to VISIBLE fields only. Without this, accepting a file from an
     * instance with TLS off would silently delete the keystore path an operator had set for when TLS is turned back on.
     */
    @Test
    void aDeclaredFieldThatIsNotVisibleInTheCurrentProjectionKeepsItsStoredDraftValueWhenTheFileHasNone() throws Exception {
        login("tok-keep-inactive");
        config.draft = Optional.of(new ConfigDraft(7, "cache", "1",
                "{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}",
                "{\"i1\":{\"max-size\":100,\"ttl\":\"1h\",\"timeout\":30,\"solace.password\":\"secretRef://"
                        + SECRET_REF + "\"}}", "hmacsha256v1:" + "c".repeat(64)));
        // timeout is declared but INACTIVE (tiedTimeoutVisible = false); the file has no timeout key
        fileSaid(mapped("max-size", "250000"), mapped("ttl", "\"30m\""));
        serverAccepts(server(false, List.of(), 250000));

        assertEquals(200, accept("tok-keep-inactive").getResponse().getStatus());

        assertEquals(30, projector.draft.get("i1").get("timeout"), "an inactive field's stored value is not unset");
        assertTrue(config.staged.get(0).draftValuesJson().contains("\"timeout\":30"), config.staged.get(0).draftValuesJson());
        assertEquals("30m", projector.draft.get("i1").get("ttl"), "a VISIBLE field is still taken from the file");
    }

    @Test
    void theAcceptedDraftIsStoredUnderAcceptRuntimeDerivedFromTheLatestAndTouchesNoLayout() throws Exception {
        Session session = login("tok-stage");
        fileSaid(mapped("max-size", "250000"));
        serverAccepts(server(true, List.of(), 250000));

        MvcResult result = accept("tok-stage");

        assertEquals(1, config.staged.size());
        var command = config.staged.get(0);
        assertEquals(GenerationOrigin.ACCEPT_RUNTIME, command.origin());
        assertEquals(7L, command.derivedFromGeneration(), "derived from the LATEST generation");
        assertEquals(41L, command.baseStateVersion());
        assertNull(command.layoutJson(), "a copy-forward leaves the presentation record alone");
        assertEquals(session.principal().id(), command.actor());
        assertEquals("{\"instances\":[{\"instanceKey\":\"i1\",\"type\":\"cache-engine\"}]}", command.componentSelectionJson());
        assertFalse(command.draftValuesJson().contains("timeout"), "an unset field is not stored as null either");
        JsonNode body = json(result);
        assertEquals(42, body.get("stateVersion").asLong());
        assertEquals(8, body.get("generation").asLong());
        assertFalse(body.get("unchanged").asBoolean());
        assertEquals(List.of(8L), readBack.generationsAsked, "the bytes gap is asked about the generation just staged");
    }

    @Test
    void theAcceptedDraftIsReProjectedByTheServer() throws Exception {
        login("tok-reproject");
        fileSaid(mapped("max-size", "250000"));
        ViewModel serverSays = server(true, List.of(), 250000);
        serverAccepts(serverSays);

        accept("tok-reproject");

        assertEquals(serverSays.rendered(), validator.request.claimedRendered(),
                "the claim is the server's own projection of the same draft, so only an undeclared key can refuse");
        var stored = config.staged.get(0).rendered();
        assertEquals(List.of("cache-engine.max-size", "cache-engine.solace.password", "cache-engine.ttl"),
                stored.stream().map(e -> e.renderedPath()).sorted().toList(),
                "the stored rendered set is the SERVER's, not the file's");
        assertTrue(stored.stream().filter(e -> e.renderedPath().endsWith("password")).allMatch(e -> e.secretRef() != null),
                "a secret entry is its reference");
    }

    @Test
    void anUndeclaredKeyTheServerRefusesIsA422AndNothingIsStaged() throws Exception {
        login("tok-undeclared");
        fileSaid(mapped("max-size", "1"));
        projector.next = server(true, List.of(), 1);
        validator.next = new StageOutcome.Refused(List.of(new StageRefusalReason.UndeclaredDraftKey("i1", "nope")),
                projector.next);
        MvcResult result = accept("tok-undeclared");
        assertEquals(422, result.getResponse().getStatus());
        assertTrue(config.staged.isEmpty());
        assertFalse(raw(result).contains("nope"));
    }

    // ---- scenario G: wrong values -------------------------------------------------------------------------------------

    @Test
    void aBlockingValueIs422NamingTheFieldAndTheRuleNotTheValue() throws Exception {
        login("tok-block");
        fileSaid(mapped("max-size", "\"" + SENTINEL + "\""));
        serverAccepts(server(true, List.of(new ViewModel.Validation("block", "BIND_TYPE",
                "expected an integer, got \"" + SENTINEL + "\"")), 100));

        MvcResult result = accept("tok-block");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        String detail = json(result).get("detail").asText();
        assertTrue(detail.contains("max-size (BIND_TYPE)"), detail);
        assertFalse(raw(result).contains(SENTINEL), "the value is never echoed: " + raw(result));
        assertTrue(config.staged.isEmpty(), "a generation must be valid: a block is a refusal, not a partial accept");
    }

    @Test
    void aWarningDoesNotBlock() throws Exception {
        login("tok-warn");
        fileSaid(mapped("max-size", "5"));
        serverAccepts(server(true, List.of(new ViewModel.Validation("warn", "BELOW_RECOMMENDED", "low")), 5));
        assertEquals(200, accept("tok-warn").getResponse().getStatus());
        assertEquals(1, config.staged.size());
    }

    @Test
    void theRoundTripCheckRefusesALossyMapping() throws Exception {
        login("tok-lossy");
        // the file holds the STRING "250000" where the package renders the integer
        fileSaid(mapped("max-size", "\"250000\""));
        serverAccepts(server(true, List.of(), 250000));

        MvcResult result = accept("tok-lossy");

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertTrue(json(result).get("detail").asText().contains("max-size (ROUND_TRIP)"), raw(result));
        assertTrue(config.staged.isEmpty());
    }

    @Test
    void anIntegerAndItsDecimalSpellingRoundTripAsOneNumber() throws Exception {
        login("tok-numbers");
        fileSaid(mapped("max-size", "250000.0"));
        serverAccepts(server(true, List.of(), 250000));
        assertEquals(200, accept("tok-numbers").getResponse().getStatus());
    }

    // ---- the report -----------------------------------------------------------------------------------------------------

    @Test
    void aReadBackSecretIsDroppedAndReportedSameOrDiffersAndNeverAdopted() throws Exception {
        login("tok-secret");
        fileSaid(List.of(mapped("max-size", "100")), List.of(
                new ReadBackCandidate.NotAccepted("cache-engine.solace.password", "i1", "solace.password",
                        NotAcceptedReason.SECRET_NOT_READ, SecretCompare.Outcome.DIFFERS)));
        serverAccepts(server(true, List.of(), 100));

        MvcResult result = accept("tok-secret");

        JsonNode entry = json(result).get("notAccepted").get(0);
        assertEquals("secret-not-read", entry.get("reason").asText());
        assertEquals("differs", entry.get("comparison").asText());
        assertEquals("cache-engine.solace.password", entry.get("path").asText());
        assertEquals("secretRef://" + SECRET_REF, projector.draft.get("i1").get("solace.password"));
        assertTrue(config.staged.get(0).draftValuesJson().contains("secretRef://" + SECRET_REF));
        assertEquals(Set.of("path", "fieldKey", "reason", "comparison"), fieldNames(entry),
                "paths and closed words: there is no value-bearing property");
    }

    @Test
    void unmappedKeysAreReportedByPathAndTheAcceptProceeds() throws Exception {
        login("tok-unmapped");
        fileSaid(List.of(mapped("max-size", "250000")), List.of(
                new ReadBackCandidate.NotAccepted("server.port", null, null, NotAcceptedReason.UNDECLARED_KEY, null),
                new ReadBackCandidate.NotAccepted("logging.level.x", null, null, NotAcceptedReason.SM_OWNED_OVERLAY, null)));
        serverAccepts(server(true, List.of(), 250000));

        MvcResult result = accept("tok-unmapped");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of("logging.level.x", "server.port"), texts(json(result).get("notAccepted"), "path"),
                "sorted by path");
        assertEquals(List.of("sm-owned-overlay", "undeclared-key"), texts(json(result).get("notAccepted"), "reason"));
        assertFalse(json(result).get("truncated").asBoolean());
        assertEquals(1, config.staged.size());
    }

    @Test
    void aDeclaredFieldThatIsInactiveUnderTheAcceptedValuesIsReportedInactive() throws Exception {
        login("tok-inactive");
        fileSaid(mapped("max-size", "100"), mapped("timeout", "5"));
        serverAccepts(server(false, List.of(), 100));

        MvcResult result = accept("tok-inactive");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode entry = json(result).get("notAccepted").get(0);
        assertEquals("inactive-field", entry.get("reason").asText());
        assertEquals("cache-engine.timeout", entry.get("path").asText());
        assertEquals(5, projector.draft.get("i1").get("timeout"), "carried into the draft like any other value");
    }

    @Test
    void theReportIsCappedAtTwoHundredAndSaysSo() throws Exception {
        login("tok-cap");
        List<ReadBackCandidate.NotAccepted> two = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            two.add(new ReadBackCandidate.NotAccepted("junk.k" + String.format("%03d", i), null, null,
                    NotAcceptedReason.UNDECLARED_KEY, null));
        }
        fileSaid(List.of(mapped("max-size", "100"), mapped("timeout", "5")), two);
        serverAccepts(server(false, List.of(), 100));

        JsonNode body = json(accept("tok-cap"));

        assertEquals(200, body.get("notAccepted").size());
        assertTrue(body.get("truncated").asBoolean(), "the inactive-field entry pushed the report past the contract's bound");
    }

    @Test
    void unchangedWithANonEmptyReportIsALegitimateAnswer() throws Exception {
        login("tok-unchanged");
        config.unchanged = true;
        fileSaid(List.of(), List.of(
                new ReadBackCandidate.NotAccepted("server.port", null, null, NotAcceptedReason.UNDECLARED_KEY, null)));
        serverAccepts(server(true, List.of(), 100));

        JsonNode body = json(accept("tok-unchanged"));

        assertTrue(body.get("unchanged").asBoolean());
        assertEquals(1, body.get("notAccepted").size());
    }

    @Test
    void acceptedBytesDifferingFromRenderedIsReported() throws Exception {
        login("tok-bytes");
        fileSaid(mapped("max-size", "100"));
        serverAccepts(server(true, List.of(), 100));
        readBack.differs = true;
        assertTrue(json(accept("tok-bytes")).get("fileBytesDifferFromRendered").asBoolean());
        readBack.differs = false;
        assertFalse(json(accept("tok-bytes")).get("fileBytesDifferFromRendered").asBoolean());
    }

    // ---- refusals from the data plane -------------------------------------------------------------------------------------

    @Test
    void anUnreadableHostIs503WithTheWidenedText() throws Exception {
        login("tok-host");
        readBack.refusal = new ReadBackRefusedException(ReadBackRefusedException.Reason.HOST_UNREADABLE);

        MvcResult result = accept("tok-host");

        assertEquals(503, result.getResponse().getStatus(), raw(result));
        assertEquals("SERVICE_UNAVAILABLE", json(result).get("code").asText());
        assertEquals("a dependency did not answer; retry", json(result).get("detail").asText());
        assertTrue(config.staged.isEmpty());
    }

    @Test
    void aRefusedFileIs422NamingTheReasonAndAnUnknownPlacementIs404() throws Exception {
        login("tok-refused");
        readBack.refusal = new ReadBackRefusedException(ReadBackRefusedException.Reason.ANCHOR);
        MvcResult anchor = accept("tok-refused");
        assertEquals(422, anchor.getResponse().getStatus());
        assertEquals("the host's config file was refused: anchor", json(anchor).get("detail").asText());

        readBack.refusal = new ReadBackRefusedException(ReadBackRefusedException.Reason.PLACEMENT_NOT_FOUND);
        assertEquals(404, accept("tok-refused").getResponse().getStatus());
        assertTrue(config.staged.isEmpty());
    }

    @Test
    void anInstanceWithNoGenerationHasNothingToReadTheFileAgainst() throws Exception {
        login("tok-nogen");
        config.draft = Optional.empty();
        MvcResult result = accept("tok-nogen");
        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertNothingTouched();
    }

    @Test
    void aStaleBaseIs409AndTheHostIsOnlyAskedAboutTheBytesAfterAStage() throws Exception {
        login("tok-stale");
        fileSaid(mapped("max-size", "100"));
        serverAccepts(server(true, List.of(), 100));
        config.stageFailure = new StaleStateVersionException(50);

        MvcResult result = accept("tok-stale");

        assertEquals(409, result.getResponse().getStatus());
        assertFalse(calls.has("dp.fileDiffers"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

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

    private static final class Set {
        static java.util.Set<String> of(String... names) {
            return new java.util.TreeSet<>(List.of(names));
        }
    }
}
