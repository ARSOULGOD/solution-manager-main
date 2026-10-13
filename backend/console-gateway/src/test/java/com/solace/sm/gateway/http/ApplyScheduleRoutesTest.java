package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleRefusedException;
import com.solace.sm.dp.api.apply.ScheduleView;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.gateway.http.ApplyControllerTest.FakeApply;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * Plan 012 T7 - the HTTP face of {@code scheduled} ({@code OPS-Q9} Sch-2): the {@code schedule} field on
 * {@code submitApply} and {@code rollbackApply}, and {@code listApplySchedules} / {@code cancelApplySchedule}, over the real
 * filter, interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports FAKED. What is
 * pinned is the ORCHESTRATION: the submitter's verbs are asked at submit in the contract's order (and again at fire, which
 * is {@code ScheduleFirerTest}'s), a scheduled request admits and copies nothing, the window is validated, and every
 * data-plane refusal is the status the contract declares.
 */
class ApplyScheduleRoutesTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENS = "2030-01-02T02:00:00Z";
    private static final String CLOSES = "2030-01-02T04:00:00Z";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private Calls calls;
    private FakeApply apply;
    private FakeSchedulePort schedules;
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
        context.register(ApplyControllerTest.Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        authorization = context.getBean(FakeAuthorizationService.class);
        calls = context.getBean(Calls.class);
        apply = context.getBean(FakeApply.class);
        schedules = context.getBean(FakeSchedulePort.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        context.getBean(FakeInventory.class).put(new InstanceView(instanceId, "orders-cache", UUID.randomUUID(),
                UUID.randomUUID(), "1", InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders",
                        Optional.of(ProbeStrength.LIVENESS))), 41L));
    }

    // -------------------------------------------------------------------------------------------------- helpers

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
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request).andReturn();
    }

    private ObjectNode window() {
        return MAPPER.createObjectNode().put("notBefore", OPENS).put("notAfter", CLOSES);
    }

    private ObjectNode applyBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", 3).put("strategy", strategy).put("placementId", placementId.toString());
        body.set("schedule", window());
        return body;
    }

    private ObjectNode rollbackBody(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", 2).put("strategy", strategy).put("placementId", placementId.toString());
        body.set("schedule", window());
        return body;
    }

    private MvcResult submit(String token, ObjectNode body) throws Exception {
        return send(token, "/apply", body.toString());
    }

    private MvcResult rollback(String token, ObjectNode body) throws Exception {
        return send(token, "/apply/rollback", body.toString());
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

    private void assertNothingAdmittedOrScheduled() {
        assertTrue(apply.submitted.isEmpty() && apply.rolledBack.isEmpty(), "an ordinary apply was admitted: " + calls.log);
        assertTrue(schedules.scheduled.isEmpty(), "a schedule reached the data plane: " + calls.log);
    }

    // ------------------------------------------------------------------------------------------------ submit

    @Test
    void aScheduledApplyIs202WithAScheduleIdAndAdmitsNothing() throws Exception {
        Session session = login("tok-s202");

        MvcResult result = submit("tok-s202", applyBody("manual"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(schedules.nextScheduleId.toString(), body.get("scheduleId").asText());
        assertEquals(91, body.get("stateVersion").asLong());
        assertEquals(2, body.size(), "stateVersion and scheduleId, nothing else (no applyId: nothing was admitted): " + body);
        assertEquals("/api/dp/instances/" + instanceId + "/apply-schedules", result.getResponse().getHeader("Location"));
        assertEquals(1, schedules.scheduled.size());
        ScheduleCommand command = schedules.scheduled.get(0);
        assertEquals(ScheduleCommand.Operation.APPLY, command.operation());
        assertEquals(placementId, command.placementId());
        assertEquals(3L, command.generation());
        assertEquals("manual", command.strategy());
        assertEquals(Instant.parse(OPENS), command.notBefore());
        assertEquals(Instant.parse(CLOSES), command.notAfter());
        assertEquals(session.principal().id(), command.actor(), "the submitter is the session's, never a body field");
        assertTrue(apply.submitted.isEmpty(), "nothing is admitted until the window opens: " + calls.log);
        assertFalse(calls.has("dp.submitApply"));
    }

    @Test
    void aScheduledRollbackReachesThePortWithItsTargetAndCopiesNothingForward() throws Exception {
        Session session = login("tok-srb");

        MvcResult byGeneration = rollback("tok-srb", rollbackBody("manual"));
        UUID applyId = UUID.randomUUID();
        ObjectNode byApply = rollbackBody("manual");
        byApply.remove("generation");
        byApply.put("applyId", applyId.toString());
        MvcResult byApplyResult = rollback("tok-srb", byApply);

        assertEquals(202, byGeneration.getResponse().getStatus(), raw(byGeneration));
        assertEquals(202, byApplyResult.getResponse().getStatus(), raw(byApplyResult));
        assertEquals(List.of(Permission.CONFIG_ROLLBACK, Permission.CONFIG_ROLLBACK), authorization.permissions,
                "a scheduled rollback is a rollback: config.rollback at the instance");
        ScheduleCommand first = schedules.scheduled.get(0);
        assertEquals(ScheduleCommand.Operation.ROLLBACK, first.operation());
        assertEquals(2L, first.rollbackGeneration());
        assertEquals(session.principal().id(), first.actor());
        assertEquals(applyId, schedules.scheduled.get(1).rollbackApplyId());
        assertTrue(apply.rolledBack.isEmpty(), "copy-forward happens at fire, not at submit (design 6.3, F12)");
    }

    @Test
    void theSubmittersVerbsAreAskedAtSubmitInTheContractsOrderAndADenialNamesTheVerb() throws Exception {
        login("tok-sverbs");

        submit("tok-sverbs", applyBody("manual"));
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions);
        authorization.permissions.clear();
        submit("tok-sverbs", applyBody("rolling-health-gated"));
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART), authorization.permissions);
        authorization.permissions.clear();
        ObjectNode ungated = applyBody("rolling-no-validation");
        submit("tok-sverbs", ungated);
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART,
                Permission.INSTANCE_RESTART_OVERRIDE_SAFETY), authorization.permissions);
        int before = schedules.scheduled.size();

        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY);
        MvcResult refused = submit("tok-sverbs", ungated);
        assertEquals(403, refused.getResponse().getStatus(), raw(refused));
        assertTrue(json(refused).get("detail").asText().contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY.id()), raw(refused));
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART, Outcome.DENY);
        MvcResult noRestart = submit("tok-sverbs", applyBody("rolling-health-gated"));
        assertEquals(403, noRestart.getResponse().getStatus(), raw(noRestart));
        assertTrue(json(noRestart).get("detail").asText().contains(Permission.INSTANCE_RESTART.id()), raw(noRestart));
        assertEquals(before, schedules.scheduled.size(), "a refused schedule is not recorded");
    }

    @Test
    void aRestartAllScheduleRecordsThePrincipalAsTheConfirmationNeverTheTypedName() throws Exception {
        Session session = login("tok-sra");
        ObjectNode body = applyBody("restart-all");
        body.put("outageConfirmedBy", "SENTINEL-typed-name");

        MvcResult result = submit("tok-sra", body);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals(session.principal().id().toString(), schedules.scheduled.get(0).outageConfirmedBy());
        assertFalse(schedules.scheduled.get(0).toString().contains("SENTINEL"), "the typed name is never carried");
        ObjectNode other = applyBody("manual");
        other.put("outageConfirmedBy", "SENTINEL-typed-name");
        submit("tok-sra", other);
        assertEquals(null, schedules.scheduled.get(1).outageConfirmedBy(), "ignored for every other strategy");

        MvcResult missing = submit("tok-sra", applyBody("restart-all"));
        assertEquals(400, missing.getResponse().getStatus(), "restart-all asserts an outage: " + raw(missing));
        assertEquals(2, schedules.scheduled.size());
    }

    @Test
    void theWindowIsValidatedAndEveryFaultIs400BeforeAnyVerbBeyondThePrimaryIsAsked() throws Exception {
        login("tok-win");
        List<ObjectNode> bad = new java.util.ArrayList<>();
        ObjectNode noOpens = applyBody("manual");
        ((ObjectNode) noOpens.get("schedule")).remove("notBefore");
        bad.add(noOpens);
        ObjectNode noCloses = applyBody("manual");
        ((ObjectNode) noCloses.get("schedule")).remove("notAfter");
        bad.add(noCloses);
        ObjectNode notADate = applyBody("manual");
        ((ObjectNode) notADate.get("schedule")).put("notBefore", "tomorrow at two");
        bad.add(notADate);
        ObjectNode dateOnly = applyBody("manual");
        ((ObjectNode) dateOnly.get("schedule")).put("notBefore", "2030-01-02");
        bad.add(dateOnly);
        ObjectNode backwards = applyBody("manual");
        ((ObjectNode) backwards.get("schedule")).put("notAfter", OPENS);
        bad.add(backwards);
        ObjectNode stray = applyBody("manual");
        ((ObjectNode) stray.get("schedule")).put("repeat", "daily");
        bad.add(stray);
        ObjectNode notAnObject = applyBody("manual");
        notAnObject.put("schedule", "02:00");
        bad.add(notAnObject);
        ObjectNode number = applyBody("manual");
        ((ObjectNode) number.get("schedule")).put("notBefore", 1893463200);
        bad.add(number);
        // W3 review W3B-4: java.time accepts a signed five-plus-digit year that RFC 3339 does not and PostgreSQL cannot store
        ObjectNode absurdYear = applyBody("manual");
        ((ObjectNode) absurdYear.get("schedule")).put("notAfter", "+300000-01-01T00:00:00Z");
        bad.add(absurdYear);
        ObjectNode fiveDigitYear = applyBody("manual");
        ((ObjectNode) fiveDigitYear.get("schedule")).put("notAfter", "10000-01-01T00:00:00Z");
        bad.add(fiveDigitYear);

        for (ObjectNode body : bad) {
            MvcResult result = submit("tok-win", body);
            assertEquals(400, result.getResponse().getStatus(), body + " -> " + raw(result));
            assertEquals("BAD_REQUEST", code(result));
            ObjectNode asRollbackBody = rollbackBody("manual");
            asRollbackBody.set("schedule", body.get("schedule"));
            MvcResult asRollback = rollback("tok-win", asRollbackBody);
            assertEquals(400, asRollback.getResponse().getStatus(), body + " -> " + raw(asRollback));
        }
        assertEquals(List.of(), authorization.permissions.stream().filter(p -> p != Permission.CONFIG_APPLY
                && p != Permission.CONFIG_ROLLBACK).toList(), "only the primary verb was asked");
        assertNothingAdmittedOrScheduled();
    }

    @Test
    void anExplicitNullScheduleIs400AndIsNeverQuietlyAnImmediateApply() throws Exception {
        login("tok-null");
        ObjectNode body = applyBody("manual");
        body.putNull("schedule");

        MvcResult result = submit("tok-null", body);

        assertEquals(400, result.getResponse().getStatus(), raw(result));
        assertNothingAdmittedOrScheduled();
        // the control: no schedule key at all is the ordinary apply it always was
        body.remove("schedule");
        assertEquals(202, submit("tok-null", body).getResponse().getStatus());
        assertEquals(1, apply.submitted.size());
        assertTrue(schedules.scheduled.isEmpty());
    }

    @Test
    void theTokenScheduledWithAScheduleFieldIsStill422NamingTheFieldAndNothingIsScheduled() throws Exception {
        login("tok-s422");

        MvcResult result = submit("tok-s422", applyBody("scheduled"));

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals("VALIDATION_FAILED", code(result));
        assertTrue(json(result).get("detail").asText().contains("schedule"), raw(result));
        assertNothingAdmittedOrScheduled();
    }

    @Test
    void anInstanceThatIsNotActiveAndAForeignPlacementAreTheSame404AndNothingIsScheduled() throws Exception {
        login("tok-s404");
        ObjectNode foreign = applyBody("manual");
        foreign.put("placementId", UUID.randomUUID().toString());
        MvcResult other = submit("tok-s404", foreign);
        assertEquals(404, other.getResponse().getStatus(), raw(other));
        assertEquals("NOT_FOUND", code(other));

        instanceId = UUID.randomUUID();
        MvcResult missing = submit("tok-s404", applyBody("manual"));
        assertEquals(404, missing.getResponse().getStatus(), raw(missing));
        assertNothingAdmittedOrScheduled();
    }

    @Test
    void theDataPlanesRefusalsAreTheStatusesTheContractDeclares() throws Exception {
        login("tok-sref");
        record Case(RuntimeException refusal, int status, String code) {
        }
        for (Case c : List.of(
                new Case(new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_NOT_IN_THE_FUTURE), 422,
                        "VALIDATION_FAILED"),
                new Case(new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_TOO_FAR_AHEAD), 422,
                        "VALIDATION_FAILED"),
                new Case(new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_AFTER_TOO_FAR_AHEAD), 422,
                        "VALIDATION_FAILED"),
                new Case(new ApplyRefusedException(ApplyRefusedException.Reason.GENERATION_NOT_STAGED), 422, "VALIDATION_FAILED"),
                new Case(new ApplyRefusedException(ApplyRefusedException.Reason.ROLLBACK_TARGET_NOT_ELIGIBLE), 409, "CONFLICT"),
                new Case(new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND), 404, "NOT_FOUND"))) {
            schedules.failure = c.refusal();
            MvcResult result = submit("tok-sref", applyBody("manual"));
            assertEquals(c.status(), result.getResponse().getStatus(), c.refusal() + " -> " + raw(result));
            assertEquals(c.code(), code(result));
            assertFalse(raw(result).contains("2030"), "the request's window is never echoed: " + raw(result));
        }
        schedules.failure = new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_TOO_FAR_AHEAD);
        MvcResult tooFar = submit("tok-sref", applyBody("manual"));
        assertTrue(json(tooFar).get("detail").asText().contains("ahead"), "says what is wrong: " + raw(tooFar));
        schedules.failure = new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_AFTER_TOO_FAR_AHEAD);
        MvcResult tooFarEnd = submit("tok-sref", applyBody("manual"));
        assertTrue(json(tooFarEnd).get("detail").asText().contains("notAfter"), "names the field: " + raw(tooFarEnd));
        schedules.failure = new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_BEFORE_NOT_IN_THE_FUTURE);
        MvcResult past = submit("tok-sref", applyBody("manual"));
        assertTrue(json(past).get("detail").asText().contains("future"), "says what is wrong: " + raw(past));
    }

    @Test
    void everyScheduleCallUsesTheSessionsOrganizationAndPrincipalNeverARequestField() throws Exception {
        Session session = login("tok-si5");
        ObjectNode body = applyBody("manual");
        body.put("organizationId", UUID.randomUUID().toString()).put("requestedBy", "somebody-else")
                .put("tenantId", UUID.randomUUID().toString());

        submit("tok-si5", body);
        mvc.perform(get(path("/apply-schedules")).cookie(new Cookie(COOKIE, "tok-si5"))).andReturn();

        assertFalse(schedules.organizationsSeen.isEmpty());
        assertTrue(schedules.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                schedules.organizationsSeen.toString());
        assertEquals(session.principal().id(), schedules.scheduled.get(0).actor());
    }

    // -------------------------------------------------------------------------------------------- list and cancel

    private ScheduleView view(String state) {
        return new ScheduleView(schedules.nextScheduleId, placementId, "apply", 3L, null, null, "manual",
                Instant.parse(OPENS), Instant.parse(CLOSES), Instant.parse("2030-01-01T00:00:00Z"), state, null, null, null,
                null, "CANCELLED".equals(state) ? Instant.parse("2030-01-01T01:00:00Z") : null, 95);
    }

    private MvcResult list(String token, String query) throws Exception {
        return mvc.perform(get(path("/apply-schedules") + query).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private MvcResult cancel(String token, UUID schedule, boolean withCsrf) throws Exception {
        var request = delete(path("/apply-schedules/" + schedule)).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN);
        if (withCsrf) {
            request = request.header("X-SM-CSRF", "csrf-" + token);
        }
        return mvc.perform(request).andReturn();
    }

    @Test
    void listAnswers200WithTheInstancesSchedulesRequiresConfigViewAndBoundsItsLimit() throws Exception {
        login("tok-list");
        schedules.listing = List.of(view("PENDING"));

        MvcResult result = list("tok-list", "");

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode item = json(result).get("items").get(0);
        assertEquals(schedules.nextScheduleId.toString(), item.get("scheduleId").asText());
        assertEquals("PENDING", item.get("state").asText());
        assertEquals("apply", item.get("operation").asText());
        assertEquals(OPENS, item.get("notBefore").asText());
        assertEquals(placementId.toString(), item.get("placementId").asText());
        assertFalse(item.has("applyId") || item.has("reason") || item.has("resolvedAt"), "omitted, not null: " + item);
        assertFalse(item.has("requestedBy") || item.has("cancelledBy") || item.has("outageConfirmedBy"),
                "no principal id is on the wire: " + item);
        assertEquals(List.of(Permission.CONFIG_VIEW), authorization.permissions);
        assertEquals(List.of(50), schedules.listLimits, "the contract's default limit");

        for (String bad : List.of("?limit=0", "?limit=201", "?limit=abc", "?limit=-1")) {
            assertEquals(400, list("tok-list", bad).getResponse().getStatus(), bad);
        }
        assertEquals(200, list("tok-list", "?limit=200").getResponse().getStatus());
        assertEquals(List.of(50, 200), schedules.listLimits, "the bad limits never reached the port");
    }

    @Test
    void listIs404ForAnInstanceThatIsNotActiveAnd403WithoutTheVerb() throws Exception {
        login("tok-list2");
        authorization.outcome = Outcome.DENY;
        assertEquals(403, list("tok-list2", "").getResponse().getStatus());
        authorization.outcome = Outcome.ALLOW;
        instanceId = UUID.randomUUID();
        MvcResult missing = list("tok-list2", "");
        assertEquals(404, missing.getResponse().getStatus(), raw(missing));
        assertFalse(calls.has("dp.listSchedules"));
    }

    @Test
    void cancelAnswers200WithTheCancelledScheduleRequiresConfigApplyAndRecordsTheCaller() throws Exception {
        Session session = login("tok-cancel");
        schedules.cancelResult = view("CANCELLED");

        MvcResult result = cancel("tok-cancel", schedules.nextScheduleId, true);

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        assertEquals("CANCELLED", json(result).get("state").asText());
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.permissions,
                "any config.apply holder at the instance, not only the submitter");
        assertEquals(List.of(session.principal().id()), schedules.cancelActors);
        assertEquals(List.of(schedules.nextScheduleId), schedules.cancelled);
    }

    @Test
    void cancelWithoutACsrfTokenIs403AndTouchesNothing() throws Exception {
        login("tok-csrf");
        schedules.cancelResult = view("CANCELLED");

        MvcResult result = cancel("tok-csrf", schedules.nextScheduleId, false);

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertEquals("CSRF_REJECTED", code(result));
        assertTrue(schedules.cancelled.isEmpty());
    }

    @Test
    void cancelOfAnAdmittedScheduleIs409AnUnknownOneIs404AndACallerWithoutTheVerbIs403() throws Exception {
        login("tok-cancel2");
        schedules.failure = new ScheduleRefusedException(ScheduleRefusedException.Reason.NOT_PENDING);
        MvcResult admitted = cancel("tok-cancel2", UUID.randomUUID(), true);
        assertEquals(409, admitted.getResponse().getStatus(), raw(admitted));
        assertEquals("CONFLICT", code(admitted));
        assertTrue(json(admitted).get("detail").asText().contains("no longer pending"), raw(admitted));

        schedules.failure = new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND);
        MvcResult unknown = cancel("tok-cancel2", UUID.randomUUID(), true);
        assertEquals(404, unknown.getResponse().getStatus(), raw(unknown));
        assertEquals("NOT_FOUND", code(unknown));

        schedules.failure = null;
        authorization.outcomeByPermission.put(Permission.CONFIG_APPLY, Outcome.DENY);
        int before = schedules.cancelled.size();
        assertEquals(403, cancel("tok-cancel2", UUID.randomUUID(), true).getResponse().getStatus());
        assertEquals(before, schedules.cancelled.size());
    }

    @Test
    void aScheduleIdThatIsNotAUuidIs400() throws Exception {
        login("tok-bad-id");
        MvcResult result = mvc.perform(delete(path("/apply-schedules/not-a-uuid")).cookie(new Cookie(COOKIE, "tok-bad-id"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-tok-bad-id")).andReturn();
        assertEquals(400, result.getResponse().getStatus(), raw(result));
    }
}
