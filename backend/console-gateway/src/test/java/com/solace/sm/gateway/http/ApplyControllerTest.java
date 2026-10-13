package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.ApplyStepView;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.StopCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
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
import java.util.List;
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
 * {@code submitApply}, {@code observeApply} and {@code stopInstance} (plan 010 T6.3) over the real filter,
 * interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports FAKED. The engine,
 * the journal and the fences are {@code dp-core}'s ({@code ApplyServiceIT}); what is pinned here is the ORCHESTRATION:
 * who may call, in what order the checks run, that nothing the browser says names a tenant, an actor or a verb, and
 * that every refusal is the status the contract declares.
 */
class ApplyControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeInventory inventory;
    private FakeApply apply;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

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
        apply = context.getBean(FakeApply.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        inventory.put(new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), UUID.randomUUID(), "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders",
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

        @Bean FakeApply apply(Calls calls) {
            return new FakeApply(calls);
        }

        @Bean InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean MetricsFakes.JacksonPackageCodec codec() {
            return new MetricsFakes.JacksonPackageCodec();
        }

        @Bean MetricsFakes.RecordingApplicability applicability() {
            return new MetricsFakes.RecordingApplicability();
        }

        @Bean
        DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
                InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
                ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10));
        }

        @Bean FakeSchedulePort schedulePort(Calls calls) {
            return new FakeSchedulePort(calls);
        }

        @Bean
        ApplyOrchestrator applyOrchestrator(DeclareOrchestrator instances, ApplyService apply,
                AuthorizationService authorization, FakeSchedulePort schedulePort) {
            return new ApplyOrchestrator(instances, apply, authorization, schedulePort);
        }

        @Bean ScheduleController scheduleController(ApplyOrchestrator orchestrator) {
            return new ScheduleController(orchestrator);
        }

        @Bean ApplyController applyController(ApplyOrchestrator orchestrator) {
            return new ApplyController(orchestrator);
        }

        @Bean RestoreRuntimeController restoreRuntimeController(ApplyOrchestrator orchestrator) {
            return new RestoreRuntimeController(orchestrator);
        }
    }

    static final class FakeApply implements ApplyService {
        final Calls calls;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<SubmitApplyCommand> submitted = new ArrayList<>();
        final List<StopCommand> stopped = new ArrayList<>();
        final List<com.solace.sm.dp.api.apply.RollbackCommand> rolledBack = new ArrayList<>();
        final List<com.solace.sm.dp.api.apply.RestoreRuntimeCommand> restored = new ArrayList<>();
        final List<com.solace.sm.dp.api.apply.SetLogLevelCommand> levelSet = new ArrayList<>();
        final List<ObserveQuery> observed = new ArrayList<>();
        RuntimeException failure;
        UUID nextApplyId = UUID.randomUUID();
        long nextStateVersion = 77;
        List<ApplyObservationView> observations = List.of();

        FakeApply(Calls calls) {
            this.calls = calls;
        }

        @Override
        public ApplyAcceptedView submitApply(UUID organizationId, UUID instanceId, SubmitApplyCommand command) {
            calls.add("dp.submitApply");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            submitted.add(command);
            return new ApplyAcceptedView(nextApplyId, nextStateVersion);
        }

        @Override
        public ApplyAcceptedView rollbackApply(UUID organizationId, UUID instanceId,
                com.solace.sm.dp.api.apply.RollbackCommand command) {
            calls.add("dp.rollbackApply");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            rolledBack.add(command);
            return new ApplyAcceptedView(nextApplyId, nextStateVersion);
        }

        @Override
        public ApplyAcceptedView restoreRuntime(UUID organizationId, UUID instanceId,
                com.solace.sm.dp.api.apply.RestoreRuntimeCommand command) {
            calls.add("dp.restoreRuntime");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            restored.add(command);
            return new ApplyAcceptedView(nextApplyId, nextStateVersion);
        }

        @Override
        public ApplyAcceptedView setLogLevel(UUID organizationId, UUID instanceId,
                com.solace.sm.dp.api.apply.SetLogLevelCommand command) {
            calls.add("dp.setLogLevel");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            levelSet.add(command);
            return new ApplyAcceptedView(nextApplyId, nextStateVersion);
        }

        @Override
        public ApplyAcceptedView stopInstance(UUID organizationId, UUID instanceId, StopCommand command) {
            calls.add("dp.stopInstance");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            stopped.add(command);
            return new ApplyAcceptedView(nextApplyId, nextStateVersion);
        }

        @Override
        public List<ApplyObservationView> observeApply(UUID organizationId, UUID instanceId, ObserveQuery query) {
            calls.add("dp.observeApply");
            organizationsSeen.add(organizationId);
            if (failure != null) {
                throw failure;
            }
            observed.add(query);
            return observations;
        }
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

    private MvcResult send(String token, String suffix, String body) throws Exception {
        var request = post(path(suffix)).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult submit(String token, ObjectNode body) throws Exception {
        return send(token, "/apply", body.toString());
    }

    private MvcResult observe(String token, String query) throws Exception {
        return mvc.perform(get(path("/apply/observe") + query).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private ObjectNode submitBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", 3);
        body.put("strategy", strategy);
        body.put("placementId", placementId.toString());
        return body;
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

    private void assertNothingSubmitted() {
        assertTrue(apply.submitted.isEmpty() && apply.stopped.isEmpty(), "a command reached the data plane: " + calls.log);
        assertFalse(calls.has("dp.submitApply") || calls.has("dp.stopInstance"), calls.log.toString());
    }

    // ----------------------------------------------------------------------------------------- authorization

    @Test
    void submitRequiresConfigApplyObserveConfigViewAndStopInstanceStopAtThePathsInstance() throws Exception {
        Session session = login("tok-verbs");

        submit("tok-verbs", submitBody("manual"));
        observe("tok-verbs", "");
        send("tok-verbs", "/stop", null);

        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.CONFIG_VIEW, Permission.INSTANCE_STOP),
                authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                "OR-16: every verb is checked at the OWNING INSTANCE, never at a placement: "
                        + authorization.resources);
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNoPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, submit("tok-deny", submitBody("manual")).getResponse().getStatus());
        assertEquals(403, observe("tok-deny", "").getResponse().getStatus());
        assertEquals(403, send("tok-deny", "/stop", null).getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), "no plane was read or written: " + calls.log);
    }

    @Test
    void anOperatorIsRefusedStopAndAViewerIsRefusedSubmitWith403() throws Exception {
        login("tok-roles");
        authorization.outcomeByPermission.put(Permission.INSTANCE_STOP, Outcome.DENY);
        authorization.outcomeByPermission.put(Permission.CONFIG_APPLY, Outcome.DENY);

        assertEquals(403, send("tok-roles", "/stop", null).getResponse().getStatus());
        assertEquals(403, submit("tok-roles", submitBody("manual")).getResponse().getStatus());
        assertEquals(200, observe("tok-roles", "").getResponse().getStatus(), "config.view is a different verb");
        assertNothingSubmitted();
    }

    @Test
    void anInstanceThatIsNotActiveIsTheSame404OnAllThreeAndNothingIsSubmitted() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();   // no such instance on the data plane

        for (MvcResult result : List.of(submit("tok-missing", submitBody("manual")), observe("tok-missing", ""),
                send("tok-missing", "/stop", null))) {
            assertEquals(404, result.getResponse().getStatus(), raw(result));
            assertEquals("NOT_FOUND", code(result));
        }
        assertTrue(apply.observed.isEmpty());
        assertNothingSubmitted();
    }

    @Test
    void everyCallUsesTheSessionsOrganizationAndPrincipalNeverARequestField() throws Exception {
        Session session = login("tok-i5");
        UUID other = UUID.randomUUID();
        ObjectNode body = submitBody("manual");
        body.put("organizationId", other.toString()).put("requestedBy", "somebody-else")
                .put("tenantId", other.toString());

        MvcResult result = submit("tok-i5", body);
        MvcResult stop = send("tok-i5", "/stop", "{\"placementId\":\"" + placementId + "\"}");
        observe("tok-i5", "?organizationId=" + other);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(202, stop.getResponse().getStatus(), raw(stop));
        assertFalse(apply.organizationsSeen.isEmpty());
        assertTrue(apply.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                apply.organizationsSeen.toString());
        assertEquals(session.principal().id(), apply.submitted.get(0).actor(),
                "requestedBy is the session's principal, never a body field");
        assertEquals(session.principal().id(), apply.stopped.get(0).actor());
    }

    // ------------------------------------------------------------------------------------------------ submit

    @Test
    void submitAnswers202WithTheApplyIdAndTheStateVersionAndALocationToPoll() throws Exception {
        Session session = login("tok-202");

        MvcResult result = submit("tok-202", submitBody("manual"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(apply.nextApplyId.toString(), body.get("applyId").asText());
        assertEquals(77, body.get("stateVersion").asLong());
        assertEquals("/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + apply.nextApplyId,
                result.getResponse().getHeader("Location"));
        assertEquals(List.of(new SubmitApplyCommand(placementId, 3, "manual", null, session.principal().id())),
                apply.submitted);
    }

    @Test
    void theOutcomeIsNotInTheResponseBody() throws Exception {
        login("tok-body");

        JsonNode body = json(submit("tok-body", submitBody("manual")));

        assertFalse(body.has("kind"), body.toString());
        assertFalse(body.has("state"), body.toString());
        assertFalse(body.has("outcome"), body.toString());
        assertEquals(2, body.size(), "stateVersion and applyId, nothing else: " + body);
    }

    @Test
    void manualNeedsOnlyConfigApply() throws Exception {
        login("tok-manual");

        submit("tok-manual", submitBody("manual"));

        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions);
        assertEquals("manual", apply.submitted.get(0).strategy());
    }

    @Test
    void rollingHealthGatedIsAdmittedAndAlsoRequiresInstanceRestartAtTheInstance() throws Exception {
        Session session = login("tok-rolling");

        MvcResult result = submit("tok-rolling", submitBody("rolling-health-gated"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART), authorization.permissions);
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(1).kind());
        assertEquals(instanceId, authorization.resources.get(1).id());
        assertEquals(session.organizationId(), authorization.resources.get(1).tenant().value());
        assertEquals("rolling-health-gated", apply.submitted.get(0).strategy());
    }

    @Test
    void rollingHealthGatedWithoutInstanceRestartIs403NamingTheVerbAndNothingIsSubmitted() throws Exception {
        login("tok-norestart");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);

        MvcResult result = submit("tok-norestart", submitBody("rolling-health-gated"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertEquals("FORBIDDEN", code(result));
        assertTrue(json(result).get("detail").asText().contains(Permission.INSTANCE_RESTART.id()), raw(result));
        assertNothingSubmitted();
        // the control: the same caller on the manual strategy is not asked for the second verb
        assertEquals(202, submit("tok-norestart", submitBody("manual")).getResponse().getStatus());
    }

    @Test
    void scheduledIs422EvenForACallerWithoutInstanceRestartOrOverrideSafety() throws Exception {
        // Plan 012 T7 (OR-121 names OR-53/OR-60): the token is no longer a strategy anyone selects - the window is the
        // `schedule` field - so it is a 422 naming that, and never 501 (it is built) or 403 (no verb is asked for it).
        login("tok-501");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY);

        ObjectNode body = submitBody("scheduled");
        body.put("outageConfirmedBy", "operator-1");
        MvcResult result = submit("tok-501", body);

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("schedule"), raw(result));
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions,
                "a token that is not a strategy is 422 before any secondary verb is asked");
        assertNothingSubmitted();
        assertTrue(context.getBean(FakeSchedulePort.class).scheduled.isEmpty(), "and nothing was scheduled either");
    }

    @Test
    void anUnknownStrategyStringIs400NotAnImplementationGap() throws Exception {
        login("tok-yolo");

        for (String strategy : List.of("yolo", "MANUAL", "", "rolling_health_gated")) {
            MvcResult result = submit("tok-yolo", submitBody(strategy));
            assertEquals(400, result.getResponse().getStatus(), strategy + ": " + raw(result));
            assertEquals("BAD_REQUEST", code(result));
        }
        assertNothingSubmitted();
    }

    @Test
    void anAbsentPlacementIdIs400AndAForeignOneIs404() throws Exception {
        login("tok-placement");
        ObjectNode absent = submitBody("manual");
        absent.remove("placementId");
        ObjectNode foreign = submitBody("manual");
        foreign.put("placementId", UUID.randomUUID().toString());
        ObjectNode notAUuid = submitBody("manual");
        notAUuid.put("placementId", "not-a-uuid");

        assertEquals(400, submit("tok-placement", absent).getResponse().getStatus(),
                "v1 refuses instance-wide apply: the placement is required (BE-D23)");
        assertEquals(400, submit("tok-placement", notAUuid).getResponse().getStatus());
        MvcResult other = submit("tok-placement", foreign);
        assertEquals(404, other.getResponse().getStatus(), raw(other));
        assertEquals("NOT_FOUND", code(other), "indistinguishable from an out-of-scope instance");
        assertNothingSubmitted();
    }

    @Test
    void aMissingOrNonPositiveOrNonIntegerGenerationIs400() throws Exception {
        login("tok-gen");
        ObjectNode missing = submitBody("manual");
        missing.remove("generation");
        ObjectNode zero = submitBody("manual");
        zero.put("generation", 0);
        ObjectNode fractional = submitBody("manual");
        fractional.put("generation", 1.5);
        ObjectNode text = submitBody("manual");
        text.put("generation", "three");

        for (ObjectNode body : List.of(missing, zero, fractional, text)) {
            assertEquals(400, submit("tok-gen", body).getResponse().getStatus(), body.toString());
        }
        assertNothingSubmitted();
    }

    @Test
    void theDataPlanesRefusalsAreTheStatusesTheContractDeclares() throws Exception {
        login("tok-refusals");
        record Case(ApplyRefusedException.Reason reason, int status, String code) {
        }
        for (Case c : List.of(
                new Case(ApplyRefusedException.Reason.GENERATION_NOT_STAGED, 422, "VALIDATION_FAILED"),
                new Case(ApplyRefusedException.Reason.SCOPE_BUSY, 409, "CONFLICT"),
                new Case(ApplyRefusedException.Reason.STOP_IN_FLIGHT, 409, "CONFLICT"),
                new Case(ApplyRefusedException.Reason.GENERATION_NOT_ABOVE_WATERMARK, 409, "CONFLICT"),
                new Case(ApplyRefusedException.Reason.CONCURRENT_ADMISSION, 409, "CONFLICT"),
                new Case(ApplyRefusedException.Reason.NOT_FOUND, 404, "NOT_FOUND"))) {
            apply.failure = new ApplyRefusedException(c.reason());
            MvcResult result = submit("tok-refusals", submitBody("manual"));
            assertEquals(c.status(), result.getResponse().getStatus(), c.reason() + ": " + raw(result));
            assertEquals(c.code(), code(result), c.reason().toString());
        }
        apply.failure = new NotImplementedStrategyException("restart-all");
        assertEquals(501, submit("tok-refusals", submitBody("manual")).getResponse().getStatus(),
                "the service's own refusal of an unserved strategy is the same 501");
    }

    @Test
    void aRefusalNeverEchoesAnythingTheRequestCarried() throws Exception {
        login("tok-echo");
        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.SCOPE_BUSY);
        ObjectNode body = submitBody("manual");
        body.put("outageConfirmedBy", "SENTINEL-operator-name");

        MvcResult result = submit("tok-echo", body);

        assertFalse(raw(result).contains("SENTINEL"), raw(result));
        assertFalse(raw(result).contains(placementId.toString()), raw(result));
    }

    // -------------------------------------------------------------------------------------------------- stop

    @Test
    void anOmittedBodyOrPlacementIsInstanceWideAndTheCommandCarriesNothingElse() throws Exception {
        Session session = login("tok-stop");

        MvcResult noBody = send("tok-stop", "/stop", null);
        MvcResult emptyObject = send("tok-stop", "/stop", "{}");
        MvcResult explicitNull = send("tok-stop", "/stop", "{\"placementId\":null}");

        for (MvcResult result : List.of(noBody, emptyObject, explicitNull)) {
            assertEquals(202, result.getResponse().getStatus(), raw(result));
            assertEquals(apply.nextApplyId.toString(), json(result).get("applyId").asText());
            assertEquals("/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + apply.nextApplyId,
                    result.getResponse().getHeader("Location"));
        }
        assertEquals(3, apply.stopped.size());
        assertTrue(apply.stopped.stream().allMatch(c -> c.placementId() == null
                && c.actor().equals(session.principal().id())), apply.stopped.toString());
    }

    @Test
    void aPlacementTargetedStopNamesThatPlacementAndIsAuthorizedAtTheOwningInstanceOnly() throws Exception {
        login("tok-stop-one");

        MvcResult result = send("tok-stop-one", "/stop", "{\"placementId\":\"" + placementId + "\"}");

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(placementId, apply.stopped.get(0).placementId());
        assertEquals(List.of(Permission.INSTANCE_STOP), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE),
                "OR-16: there is no placement-scoped binding path: " + authorization.resources);
    }

    @Test
    void aForeignPlacementOnAStopIs404AndNothingIsSubmitted() throws Exception {
        login("tok-stop-foreign");

        MvcResult result = send("tok-stop-foreign", "/stop", "{\"placementId\":\"" + UUID.randomUUID() + "\"}");

        assertEquals(404, result.getResponse().getStatus(), raw(result));
        assertEquals("NOT_FOUND", code(result));
        assertNothingSubmitted();
    }

    @Test
    void aStopRefusesAGenerationAStrategyAndAnOutageConfirmationBecauseTheEngineWouldRejectAllThree() throws Exception {
        login("tok-stop-extra");

        for (String extra : List.of("\"generation\":3", "\"strategy\":\"manual\"", "\"outageConfirmedBy\":\"x\"")) {
            MvcResult result = send("tok-stop-extra", "/stop", "{" + extra + "}");
            assertEquals(400, result.getResponse().getStatus(), extra + ": " + raw(result));
        }
        assertNothingSubmitted();
    }

    @Test
    void aRepeatStopIsAnotherAdmittedRequestNotAConflictFromTheGateway() throws Exception {
        login("tok-stop-twice");

        assertEquals(202, send("tok-stop-twice", "/stop", null).getResponse().getStatus());
        assertEquals(202, send("tok-stop-twice", "/stop", null).getResponse().getStatus());

        assertEquals(2, apply.stopped.size(), "each stop is one journal row, which is its audit record");
    }

    @Test
    void aStopInFlightIs409() throws Exception {
        login("tok-stop-busy");
        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.STOP_IN_FLIGHT);

        MvcResult result = send("tok-stop-busy", "/stop", null);

        assertEquals(409, result.getResponse().getStatus(), raw(result));
        assertEquals("CONFLICT", code(result));
    }

    // ------------------------------------------------------------------------------------------------ observe

    private static ApplyObservationView view(UUID applyId, String state, boolean terminal, long stateVersion,
            List<ApplyStepView> steps) {
        return new ApplyObservationView(applyId, "apply", UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 3L,
                Instant.parse("2026-10-05T10:00:00Z"), state, terminal, stateVersion, steps);
    }

    @Test
    void observePassesTheQueryThroughWithTheDefaultLimitAndReturnsItemsInTheOrderTheDataPlaneGaveThem() throws Exception {
        login("tok-observe");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        apply.observations = List.of(
                view(a, "STAGED", true, 10, List.of(new ApplyStepView("preflight", null, "succeeded", null))),
                view(b, "RUNNING", false, 12, List.of()));

        MvcResult result = observe("tok-observe", "?sinceStateVersion=9");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(new ObserveQuery(null, 9L, 50)), apply.observed);
        JsonNode items = json(result).get("items");
        assertEquals(2, items.size());
        assertEquals(a.toString(), items.get(0).get("applyId").asText());
        assertEquals("STAGED", items.get(0).get("state").asText());
        assertTrue(items.get(0).get("terminal").asBoolean());
        assertFalse(items.get(1).get("terminal").asBoolean(), "a running request is distinguishable from a terminal one");
        assertEquals(10, items.get(0).get("stateVersion").asLong(), "each item carries its own watermark");
        assertFalse(json(result).has("stateVersion"), "a collection carries no version of its own (OR-55)");
        assertEquals("apply", items.get(0).get("operation").asText());
        assertEquals(3, items.get(0).get("generation").asLong());
        assertEquals("preflight", items.get(0).at("/steps/0/step").asText());
        assertEquals("succeeded", items.get(0).at("/steps/0/outcome").asText());
        assertFalse(items.get(0).at("/steps/0").has("reason"), "an absent reason is omitted, not null");
    }

    @Test
    void observeByApplyIdPassesTheIdAndAStopsObservationOmitsGenerationAndPlacement() throws Exception {
        login("tok-observe-id");
        UUID stop = UUID.randomUUID();
        apply.observations = List.of(new ApplyObservationView(stop, "stop", null, null,
                Instant.parse("2026-10-05T10:00:00Z"), "STOPPED", true, 20, List.of()));

        MvcResult result = observe("tok-observe-id", "?applyId=" + stop + "&limit=5");

        assertEquals(List.of(new ObserveQuery(stop, null, 5)), apply.observed);
        JsonNode item = json(result).get("items").get(0);
        assertEquals("stop", item.get("operation").asText());
        assertFalse(item.has("generation"), "a stop has no generation: " + item);
        assertFalse(item.has("placementId"), "instance-wide: no placement: " + item);
        assertEquals("STOPPED", item.get("state").asText());
    }

    @Test
    void aFailedStepCarriesItsClassifierAsTheReason() throws Exception {
        login("tok-observe-reason");
        apply.observations = List.of(view(UUID.randomUUID(), "FAILED_NO_EFFECT", true, 30,
                List.of(new ApplyStepView("materialize", null, "failed", "io.timeout"))));

        JsonNode step = json(observe("tok-observe-reason", "")).at("/items/0/steps/0");

        assertEquals("failed", step.get("outcome").asText());
        assertEquals("io.timeout", step.get("reason").asText());
    }

    @Test
    void aPassedGatesProvedStrengthReachesTheWireAndAnUnprovedStepOmitsIt() throws Exception {
        login("tok-observe-gate");
        apply.observations = List.of(view(UUID.randomUUID(), "APPLIED", true, 31, List.of(
                new ApplyStepView("lifecycle", null, "succeeded", null, "liveness"),
                new ApplyStepView("verify", null, "succeeded", null))));

        JsonNode steps = json(observe("tok-observe-gate", "")).at("/items/0/steps");

        assertEquals("liveness", steps.get(0).get("gateProved").asText(), "T6.9: the strength the gate proved");
        assertFalse(steps.get(1).has("gateProved"), "a step that proved no gate omits it, never null: " + steps.get(1));
    }

    /** OR-108 (1): the paths a changed pre-image differs at reach the console, on the step that found them, and only there. */
    @Test
    void theChangedPathsOfAnAlteredPreImageReachTheWireAndAnAbsentRecordIsOmittedNotNull() throws Exception {
        login("tok-observe-changed");
        apply.observations = List.of(view(UUID.randomUUID(), "APPLY_INDETERMINATE", false, 32, List.of(
                new ApplyStepView("rollback-assess", null, "succeeded", null, null,
                        List.of("cache-engine.solace.host", "cache-engine.ttl"), false, true),
                new ApplyStepView("verify", null, "succeeded", null))));

        JsonNode steps = json(observe("tok-observe-changed", "")).at("/items/0/steps");

        assertEquals(List.of("cache-engine.solace.host", "cache-engine.ttl"),
                List.of(steps.get(0).at("/changedPaths/0").asText(), steps.get(0).at("/changedPaths/1").asText()));
        assertEquals(2, steps.get(0).get("changedPaths").size());
        assertFalse(steps.get(0).get("changedPathsTruncated").asBoolean());
        assertTrue(steps.get(0).get("otherContentChanged").asBoolean());
        for (String field : List.of("changedPaths", "changedPathsTruncated", "otherContentChanged")) {
            assertFalse(steps.get(1).has(field), "a step that found no change omits " + field + ", never null: " + steps.get(1));
        }
    }

    @Test
    void aReconcilersGiveUpReasonReachesTheWireAndAnAbsentOneIsOmittedNotNull() throws Exception {
        login("tok-observe-reconcile");
        UUID gaveUp = UUID.randomUUID();
        apply.observations = List.of(
                new ApplyObservationView(gaveUp, "apply", UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 3L,
                        Instant.parse("2026-10-05T10:00:00Z"), "APPLY_INDETERMINATE", false, 40, List.of(),
                        "reconcile.foreign.content"),
                view(UUID.randomUUID(), "APPLY_INDETERMINATE", false, 41, List.of()));

        JsonNode items = json(observe("tok-observe-reconcile", "")).get("items");

        assertEquals("reconcile.foreign.content", items.get(0).get("reconcileReason").asText(),
                "T6.8: the classifier, never file content");
        assertFalse(items.get(1).has("reconcileReason"), "a request the reconciler has not given up on omits it: " + items.get(1));
    }

    @Test
    void observeBoundsAreEnforcedAs400() throws Exception {
        login("tok-observe-bounds");

        for (String query : List.of("?limit=0", "?limit=201", "?limit=-1", "?limit=abc", "?sinceStateVersion=-1",
                "?sinceStateVersion=abc", "?applyId=not-a-uuid")) {
            MvcResult result = observe("tok-observe-bounds", query);
            assertEquals(400, result.getResponse().getStatus(), query + ": " + raw(result));
            assertEquals("BAD_REQUEST", code(result));
        }
        assertTrue(apply.observed.isEmpty());
        assertEquals(200, observe("tok-observe-bounds", "?limit=200&sinceStateVersion=0").getResponse().getStatus());
    }

    @Test
    void anUnknownApplyIdIs404() throws Exception {
        login("tok-observe-404");
        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND);

        MvcResult result = observe("tok-observe-404", "?applyId=" + UUID.randomUUID());

        assertEquals(404, result.getResponse().getStatus(), raw(result));
        assertEquals("NOT_FOUND", code(result));
        assertNull(result.getResponse().getHeader("Location"));
    }
    // --------------------------------------------------------------------------------------------- rollback (T8.1)

    private MvcResult rollback(String token, ObjectNode body) throws Exception {
        return send(token, "/apply/rollback", body.toString());
    }

    private ObjectNode rollbackBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", 2);
        body.put("strategy", strategy);
        body.put("placementId", placementId.toString());
        return body;
    }

    private void assertNothingRolledBack() {
        assertTrue(apply.rolledBack.isEmpty(), "a rollback reached the data plane: " + calls.log);
        assertFalse(calls.has("dp.rollbackApply"), calls.log.toString());
    }

    @Test
    void rollbackRequiresConfigRollbackAtThePathsInstanceAndManualNeedsNothingElse() throws Exception {
        Session session = login("tok-rb-verbs");

        MvcResult result = rollback("tok-rb-verbs", rollbackBody("manual"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(Permission.CONFIG_ROLLBACK), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                "OR-16: checked at the OWNING INSTANCE, never at a placement: " + authorization.resources);
    }

    @Test
    void aCallerWithoutConfigRollbackIs403AndNoPortIsTouched() throws Exception {
        login("tok-rb-deny");
        authorization.outcomeByPermission.put(Permission.CONFIG_ROLLBACK, Outcome.DENY);

        MvcResult result = rollback("tok-rb-deny", rollbackBody("manual"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertTrue(calls.log.isEmpty(), "no plane was read or written: " + calls.log);
        assertNothingRolledBack();
    }

    @Test
    void rollbackAnswers202WithTheApplyIdAndALocationToPollAndTheSessionIsTheActor() throws Exception {
        Session session = login("tok-rb-202");
        ObjectNode body = rollbackBody("manual");
        body.put("organizationId", UUID.randomUUID().toString()).put("requestedBy", "somebody-else");

        MvcResult result = rollback("tok-rb-202", body);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(2, json(result).size(), "stateVersion and applyId, nothing else: " + raw(result));
        assertEquals(apply.nextApplyId.toString(), json(result).get("applyId").asText());
        assertEquals("/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + apply.nextApplyId,
                result.getResponse().getHeader("Location"));
        assertEquals(List.of(new com.solace.sm.dp.api.apply.RollbackCommand(placementId, null, 2L, "manual",
                null, session.principal().id())), apply.rolledBack);
        assertTrue(apply.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                "I5: the organization is the session's, never a body field");
    }

    @Test
    void anApplyIdTargetIsPassedThroughInPlaceOfAGeneration() throws Exception {
        Session session = login("tok-rb-applyid");
        UUID earlier = UUID.randomUUID();
        ObjectNode body = rollbackBody("manual");
        body.remove("generation");
        body.put("applyId", earlier.toString());

        MvcResult result = rollback("tok-rb-applyid", body);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(new com.solace.sm.dp.api.apply.RollbackCommand(placementId, earlier, null, "manual",
                null, session.principal().id())), apply.rolledBack);
    }

    @Test
    void aRestartingStrategyAlsoRequiresInstanceRestartAndNamesTheVerbOnDenial() throws Exception {
        login("tok-rb-rolling");
        MvcResult allowed = rollback("tok-rb-rolling", rollbackBody("rolling-health-gated"));
        assertEquals(202, allowed.getResponse().getStatus(), raw(allowed));
        assertEquals(List.of(Permission.CONFIG_ROLLBACK, Permission.INSTANCE_RESTART), authorization.permissions);
        assertEquals(instanceId, authorization.resources.get(1).id());
        apply.rolledBack.clear();
        calls.log.clear();

        login("tok-rb-norestart");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);
        MvcResult denied = rollback("tok-rb-norestart", rollbackBody("rolling-health-gated"));

        assertEquals(403, denied.getResponse().getStatus(), raw(denied));
        assertTrue(json(denied).get("detail").asText().contains(Permission.INSTANCE_RESTART.id()), raw(denied));
        assertNothingRolledBack();
        assertEquals(202, rollback("tok-rb-norestart", rollbackBody("manual")).getResponse().getStatus(),
                "the control: manual is not asked for the second verb");
    }

    @Test
    void scheduledIs422OnARollbackEvenForACallerWithoutInstanceRestart() throws Exception {
        login("tok-rb-501");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);

        MvcResult result = rollback("tok-rb-501", rollbackBody("scheduled"));

        assertEquals(422, result.getResponse().getStatus(), "scheduled: " + raw(result));
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("schedule"), raw(result));
        assertNothingRolledBack();
    }

    @Test
    void aBodyWithoutAPlacementIdIs400BeforeAnythingIsRead() throws Exception {
        login("tok-rb-noplacement");
        ObjectNode absent = rollbackBody("manual");
        absent.remove("placementId");

        MvcResult result = rollback("tok-rb-noplacement", absent);

        assertEquals(400, result.getResponse().getStatus(), "v1 refuses instance-wide apply (BE-D23, OR-60): "
                + raw(result));
        assertEquals("BAD_REQUEST", code(result));
        assertNothingRolledBack();
        assertFalse(calls.has("dp.rollbackApply"));
    }

    @Test
    void exactlyOneOfApplyIdAndGenerationAndAWellFormedGenerationAreRequired() throws Exception {
        login("tok-rb-oneof");
        ObjectNode neither = rollbackBody("manual");
        neither.remove("generation");
        ObjectNode both = rollbackBody("manual");
        both.put("applyId", UUID.randomUUID().toString());
        ObjectNode notAUuid = rollbackBody("manual");
        notAUuid.remove("generation");
        notAUuid.put("applyId", "not-a-uuid");

        for (ObjectNode bad : List.of(neither, both, notAUuid)) {
            assertEquals(400, rollback("tok-rb-oneof", bad).getResponse().getStatus(), bad.toString());
        }
        for (String generation : List.of("0", "-3", "1.5", "\"3\"", "null", "true")) {
            String raw = rollbackBody("manual").toString().replace("\"generation\":2", "\"generation\":" + generation);
            MvcResult result = send("tok-rb-oneof", "/apply/rollback", raw);
            assertEquals(400, result.getResponse().getStatus(), generation + ": " + raw(result));
        }
        for (String strategy : List.of("yolo", "MANUAL", "")) {
            assertEquals(400, rollback("tok-rb-oneof", rollbackBody(strategy)).getResponse().getStatus(), strategy);
        }
        ObjectNode noStrategy = rollbackBody("manual");
        noStrategy.remove("strategy");
        assertEquals(400, rollback("tok-rb-oneof", noStrategy).getResponse().getStatus());
        assertNothingRolledBack();
    }

    @Test
    void aForeignPlacementAndAnInstanceThatIsNotActiveAreTheSame404() throws Exception {
        login("tok-rb-404");
        ObjectNode foreign = rollbackBody("manual");
        foreign.put("placementId", UUID.randomUUID().toString());

        MvcResult other = rollback("tok-rb-404", foreign);
        assertEquals(404, other.getResponse().getStatus(), raw(other));
        assertEquals("NOT_FOUND", code(other));

        instanceId = UUID.randomUUID();
        MvcResult missing = rollback("tok-rb-404", rollbackBody("manual"));
        assertEquals(404, missing.getResponse().getStatus(), raw(missing));
        assertNothingRolledBack();
    }

    @Test
    void anIneligibleTargetIs409WithAFixedDetailThatEchoesNothing() throws Exception {
        login("tok-rb-409");
        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.ROLLBACK_TARGET_NOT_ELIGIBLE);
        ObjectNode body = rollbackBody("manual");
        body.put("generation", 987654);

        MvcResult result = rollback("tok-rb-409", body);

        assertEquals(409, result.getResponse().getStatus(), raw(result));
        assertEquals("CONFLICT", code(result));
        assertFalse(raw(result).contains("987654"), "the generation the caller named is not echoed: " + raw(result));
        assertFalse(raw(result).contains(placementId.toString()), raw(result));
    }
    // ---------------------------------------------------------------------------------- restore-runtime (T10.2)

    private MvcResult restore(String token, ObjectNode body) throws Exception {
        return send(token, "/config/restore-runtime", body.toString());
    }

    private ObjectNode restoreBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("placementId", placementId.toString());
        if (strategy != null) {
            body.put("strategy", strategy);
        }
        return body;
    }

    private void assertNothingRestored() {
        assertTrue(apply.restored.isEmpty(), "a restore reached the data plane: " + calls.log);
        assertFalse(calls.has("dp.restoreRuntime"), calls.log.toString());
    }

    @Test
    void restoreRequiresConfigApplyAtThePathsInstance() throws Exception {
        Session session = login("tok-rs-verbs");

        MvcResult result = restore("tok-rs-verbs", restoreBody("manual"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                "OR-16: checked at the OWNING INSTANCE, never at a placement: " + authorization.resources);
    }

    @Test
    void aCallerWithoutConfigApplyIs403OnARestoreAndNoPortIsTouched() throws Exception {
        login("tok-rs-deny");
        authorization.outcomeByPermission.put(Permission.CONFIG_APPLY, Outcome.DENY);

        MvcResult result = restore("tok-rs-deny", restoreBody("manual"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertTrue(calls.log.isEmpty(), "no plane was read or written: " + calls.log);
        assertNothingRestored();
    }

    @Test
    void restoreAnswers202WithTheApplyIdAndALocationAndTheSessionIsTheActor() throws Exception {
        Session session = login("tok-rs-202");
        // W3 review W3B-3: the contract closes this object, so organizationId / requestedBy here are a 400 (the next-but-one
        // test), where they used to be silently ignored. The organization and actor are still never a body field.
        ObjectNode body = restoreBody("manual");

        MvcResult result = restore("tok-rs-202", body);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(2, json(result).size(), "stateVersion and applyId, nothing else: " + raw(result));
        assertEquals(apply.nextApplyId.toString(), json(result).get("applyId").asText());
        assertEquals("/api/dp/instances/" + instanceId + "/apply/observe?applyId=" + apply.nextApplyId,
                result.getResponse().getHeader("Location"));
        assertEquals(List.of(new com.solace.sm.dp.api.apply.RestoreRuntimeCommand(placementId, "manual",
                null, session.principal().id())), apply.restored);
        assertTrue(apply.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                "I5: the organization is the session's, never a body field");
    }

    @Test
    void aMissingStrategyDefaultsToManualAndNeverChecksInstanceRestart() throws Exception {
        login("tok-rs-default");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);

        MvcResult result = restore("tok-rs-default", restoreBody(null));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals("manual", apply.restored.get(0).strategy());
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions,
                "manual restarts nothing, so instance.restart is never asked");
    }

    @Test
    void aRestartingStrategyAlsoRequiresInstanceRestartOnARestoreAndNamesTheVerbOnDenial() throws Exception {
        login("tok-rs-rolling");
        MvcResult allowed = restore("tok-rs-rolling", restoreBody("rolling-health-gated"));
        assertEquals(202, allowed.getResponse().getStatus(), raw(allowed));
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART), authorization.permissions);
        assertEquals(instanceId, authorization.resources.get(1).id());
        apply.restored.clear();
        calls.log.clear();

        login("tok-rs-norestart");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);
        MvcResult denied = restore("tok-rs-norestart", restoreBody("rolling-health-gated"));

        assertEquals(403, denied.getResponse().getStatus(), raw(denied));
        assertTrue(json(denied).get("detail").asText().contains(Permission.INSTANCE_RESTART.id()), raw(denied));
        assertNothingRestored();
    }

    @Test
    void scheduledIs422OnARestoreEvenForACallerWithoutInstanceRestartAndSaysRestoreHasNoSchedule() throws Exception {
        login("tok-rs-501");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);

        MvcResult result = restore("tok-rs-501", restoreBody("scheduled"));

        assertEquals(422, result.getResponse().getStatus(), "scheduled: " + raw(result));
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("restore"), raw(result));
        assertNothingRestored();
    }

    @Test
    void aStrayKeyOnARestoreIs400BecauseTheContractClosesTheObjectAndAScheduleIsNeverQuietlyARestartNow() throws Exception {
        login("tok-rs-stray");
        ObjectNode withSchedule = restoreBody("manual");
        withSchedule.set("schedule", MAPPER.createObjectNode().put("notBefore", "2030-01-02T02:00:00Z")
                .put("notAfter", "2030-01-02T04:00:00Z"));
        ObjectNode withTenant = restoreBody("manual");
        withTenant.put("organizationId", UUID.randomUUID().toString());

        for (ObjectNode body : List.of(withSchedule, withTenant)) {
            MvcResult result = restore("tok-rs-stray", body);
            assertEquals(400, result.getResponse().getStatus(), body + " -> " + raw(result));
            assertEquals("BAD_REQUEST", code(result));
        }
        assertNothingRestored();
        assertEquals(202, restore("tok-rs-stray", restoreBody("manual")).getResponse().getStatus(),
                "the same body without the stray key is still admitted");
    }

    @Test
    void aMissingPlacementIdAndAnUnknownStrategyAre400AndAForeignPlacementIs404() throws Exception {
        login("tok-rs-400");
        ObjectNode absent = restoreBody("manual");
        absent.remove("placementId");

        assertEquals(400, restore("tok-rs-400", absent).getResponse().getStatus(),
                "v1 refuses instance-wide apply (BE-D23, OR-60)");
        for (String strategy : List.of("yolo", "MANUAL", "")) {
            assertEquals(400, restore("tok-rs-400", restoreBody(strategy)).getResponse().getStatus(), strategy);
        }
        ObjectNode foreign = restoreBody("manual");
        foreign.put("placementId", UUID.randomUUID().toString());
        MvcResult other = restore("tok-rs-400", foreign);
        assertEquals(404, other.getResponse().getStatus(), raw(other));
        assertEquals("NOT_FOUND", code(other));
        assertNothingRestored();
    }

    @Test
    void anInstanceThatIsNotActiveIs404AndAScopeConflictIs409() throws Exception {
        login("tok-rs-409");
        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.SCOPE_BUSY);
        assertEquals(409, restore("tok-rs-409", restoreBody("manual")).getResponse().getStatus());

        apply.failure = new ApplyRefusedException(ApplyRefusedException.Reason.GENERATION_NOT_STAGED);
        assertEquals(422, restore("tok-rs-409", restoreBody("manual")).getResponse().getStatus(),
                "an instance with nothing staged has no desired generation to restore");

        apply.failure = null;
        instanceId = UUID.randomUUID();
        assertEquals(404, restore("tok-rs-409", restoreBody("manual")).getResponse().getStatus());
    }

    // ------------------------------------------------------------------ plan 012 T2: the two ungated strategies

    /** The three single-apply operations that take a strategy, driven through one set of tests (OPS-Q7 C-2). */
    private enum Op { SUBMIT, ROLLBACK, RESTORE }

    private MvcResult call(Op op, String token, String strategy, String confirmation, boolean withConfirmationKey)
            throws Exception {
        ObjectNode body = switch (op) {
            case SUBMIT -> submitBody(strategy);
            case ROLLBACK -> rollbackBody(strategy);
            case RESTORE -> restoreBody(strategy);
        };
        if (withConfirmationKey) {
            body.put("outageConfirmedBy", confirmation);
        }
        return switch (op) {
            case SUBMIT -> submit(token, body);
            case ROLLBACK -> rollback(token, body);
            case RESTORE -> restore(token, body);
        };
    }

    /** What the data plane was handed as the outage confirmation (the last command of the operation). */
    private String handedConfirmation(Op op) {
        return switch (op) {
            case SUBMIT -> apply.submitted.get(apply.submitted.size() - 1).outageConfirmedBy();
            case ROLLBACK -> apply.rolledBack.get(apply.rolledBack.size() - 1).outageConfirmedBy();
            case RESTORE -> apply.restored.get(apply.restored.size() - 1).outageConfirmedBy();
        };
    }

    private int handed(Op op) {
        return switch (op) {
            case SUBMIT -> apply.submitted.size();
            case ROLLBACK -> apply.rolledBack.size();
            case RESTORE -> apply.restored.size();
        };
    }

    private Permission primary(Op op) {
        return op == Op.ROLLBACK ? Permission.CONFIG_ROLLBACK : Permission.CONFIG_APPLY;
    }

    @Test
    void rollingNoValidationIsAdmittedOnEveryOperationAndAsksTheTwoSecondaryVerbsInOrder() throws Exception {
        for (Op op : Op.values()) {
            login("tok-nv-" + op);
            authorization.permissions.clear();

            MvcResult result = call(op, "tok-nv-" + op, "rolling-no-validation", null, false);

            assertEquals(202, result.getResponse().getStatus(), op + ": " + raw(result));
            assertEquals(List.of(primary(op), Permission.INSTANCE_RESTART, Permission.INSTANCE_RESTART_OVERRIDE_SAFETY),
                    authorization.permissions, op + ": the primary verb, then instance.restart, then overrideSafety");
            assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                    && r.id().equals(instanceId)), op + ": OR-16, every verb at the owning instance");
            assertNull(handedConfirmation(op), op + ": rolling-no-validation asserts no outage, so none is stored");
        }
    }

    @Test
    void restartAllIsAdmittedOnEveryOperationAndTheConfirmationHandedOnIsThePrincipalNeverTheTypedName()
            throws Exception {
        for (Op op : Op.values()) {
            Session session = login("tok-all-" + op);
            authorization.permissions.clear();

            MvcResult result = call(op, "tok-all-" + op, "restart-all", "SENTINEL-typed-name", true);

            assertEquals(202, result.getResponse().getStatus(), op + ": " + raw(result));
            assertEquals(List.of(primary(op), Permission.INSTANCE_RESTART, Permission.INSTANCE_RESTART_OVERRIDE_SAFETY),
                    authorization.permissions, op.toString());
            assertEquals(session.principal().id().toString(), handedConfirmation(op),
                    op + ": the server records the session principal's id (OPS-Q7 C-2)");
            assertFalse(raw(result).contains("SENTINEL"), op + ": nothing the browser typed is echoed");
        }
    }

    @Test
    void anUngatedStrategyWithoutOverrideSafetyIs403NamingTheVerbAndNothingReachesTheDataPlane() throws Exception {
        for (Op op : Op.values()) {
            for (String strategy : List.of("rolling-no-validation", "restart-all")) {
                login("tok-deny-" + op + strategy);
                authorization.permissions.clear();
                authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY);
                int before = handed(op);

                MvcResult result = call(op, "tok-deny-" + op + strategy, strategy, "operator-1", true);

                assertEquals(403, result.getResponse().getStatus(), op + " " + strategy + ": " + raw(result));
                assertEquals("FORBIDDEN", code(result));
                assertTrue(json(result).get("detail").asText().contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY.id()),
                        op + " " + strategy + " names the verb: " + raw(result));
                assertEquals(before, handed(op), op + " " + strategy + ": nothing reached the data plane");
                // the control: the same caller is not asked for the verb on the gated strategy
                authorization.permissions.clear();
                assertEquals(202, call(op, "tok-deny-" + op + strategy, "rolling-health-gated", null, false)
                        .getResponse().getStatus(), op + ": rolling-health-gated never asks overrideSafety");
                assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY), op.toString());
            }
        }
    }

    @Test
    void anUngatedStrategyWithoutInstanceRestartIs403BeforeOverrideSafetyIsAsked() throws Exception {
        for (Op op : Op.values()) {
            login("tok-norestart-" + op);
            authorization.permissions.clear();
            authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);

            MvcResult result = call(op, "tok-norestart-" + op, "rolling-no-validation", null, false);

            assertEquals(403, result.getResponse().getStatus(), op + ": " + raw(result));
            assertTrue(json(result).get("detail").asText().contains(Permission.INSTANCE_RESTART.id()), raw(result));
            assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY),
                    op + ": the refusal order is fixed: instance.restart first");
        }
    }

    @Test
    void restartAllWithoutAConfirmationIs400OnEveryOperationAndNoSecondaryVerbIsAskedNorAnythingSent() throws Exception {
        for (Op op : Op.values()) {
            login("tok-noconf-" + op);
            for (String confirmation : new String[] {null, "", "   "}) {
                authorization.permissions.clear();
                int before = handed(op);

                MvcResult result = call(op, "tok-noconf-" + op, "restart-all", confirmation, confirmation != null);

                assertEquals(400, result.getResponse().getStatus(),
                        op + " confirmation=" + confirmation + ": " + raw(result));
                assertEquals("BAD_REQUEST", code(result));
                assertEquals(before, handed(op), op + ": nothing reached the data plane (so no generation is minted)");
                assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY), op.toString());
            }
        }
    }

    @Test
    void aTypedNameOnAnyOtherStrategyIsIgnoredAndNeverHandedToTheDataPlane() throws Exception {
        for (Op op : Op.values()) {
            for (String strategy : List.of("manual", "rolling-health-gated", "rolling-no-validation")) {
                login("tok-typed-" + op + strategy);

                MvcResult result = call(op, "tok-typed-" + op + strategy, strategy, "SENTINEL-typed-name", true);

                assertEquals(202, result.getResponse().getStatus(), op + " " + strategy + ": " + raw(result));
                assertNull(handedConfirmation(op), op + " " + strategy
                        + ": a browser-supplied name is never stored (design 5.3)");
            }
        }
    }

    @Test
    void theRequestBodiesPrintNoConfirmation() throws Exception {
        UUID p = UUID.randomUUID();
        assertFalse(new RollbackRequest(null, MAPPER.readTree("2"), p, "restart-all", "SENTINEL-typed-name")
                .toString().contains("SENTINEL"));
        assertFalse(new RestoreRuntimeRequest(p, "restart-all", "SENTINEL-typed-name").toString().contains("SENTINEL"));
    }
}
