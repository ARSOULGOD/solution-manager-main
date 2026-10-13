package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.fleet.FleetAcceptedView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyService;
import com.solace.sm.dp.api.fleet.FleetConflictException;
import com.solace.sm.dp.api.fleet.FleetMemberView;
import com.solace.sm.dp.api.fleet.FleetObservationView;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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

/**
 * {@code submitFleetApply} and {@code observeFleetApply} (plan 010 T11.2, {@code REQ-013}, {@code OR-57}) over the real
 * filter, interceptor, argument-resolver, controller and advice pipeline with the cp-api and dp-api ports FAKED. The
 * engine, the journal and the scheduler are {@code dp-core}'s ({@code FleetServiceIT}); what is pinned here is the
 * ORCHESTRATION: the running scenario of {@code fleet-apply.md} section 1 (an operator holding {@code config.apply} at
 * {@code prod-us} only, naming {@code cache-1} and {@code cache-2} there and {@code cache-eu} in {@code prod-eu}), who
 * may call, in what order the checks run, that nothing the browser says names a tenant, an actor or a verb, and that every
 * refusal is the status the contract declares.
 */
class FleetControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeFleet fleet;
    private UUID organization;
    private UUID solution;
    private UUID cache1;
    private UUID cache2;
    private UUID cacheEu;
    private UUID p1;
    private UUID p2;
    private UUID pEu;

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
        fleet = context.getBean(FakeFleet.class);
        organization = UUID.randomUUID();
        solution = UUID.randomUUID();
        cache1 = UUID.randomUUID();
        cache2 = UUID.randomUUID();
        cacheEu = UUID.randomUUID();
        p1 = UUID.randomUUID();
        p2 = UUID.randomUUID();
        pEu = UUID.randomUUID();
        // the scenario: the operator holds config.apply (and the rest) at prod-us, so cache-eu is not even visible
        authorization.outcomeByResource.put(cacheEu, Map.of(Permission.CONFIG_APPLY, Outcome.NOT_FOUND,
                Permission.INSTANCE_RESTART, Outcome.NOT_FOUND));
    }

    // -------------------------------------------------------------------------------------------------- rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean FakeFleet fleetService(FakeAuthorizationService authorization) {
            return new FakeFleet(authorization);
        }

        @Bean
        FleetOrchestrator fleetOrchestrator(FleetApplyService fleets, AuthorizationService authorization,
                ReachAuthorization reach) {
            return new FleetOrchestrator(fleets, authorization, reach);
        }

        @Bean FleetController fleetController(FleetOrchestrator orchestrator) {
            return new FleetController(orchestrator);
        }
    }

    /** The data plane, scripted. Records what it was asked and what the authorization port had been asked by then. */
    static final class FakeFleet implements FleetApplyService {
        final FakeAuthorizationService authorization;
        final List<UUID> organizationsSeen = new ArrayList<>();
        final List<FleetApplyCommand> submitted = new ArrayList<>();
        /** Every command the data plane was handed, including one it then refused. */
        final List<FleetApplyCommand> attempted = new ArrayList<>();
        final List<Integer> requiresSeenAtSubmit = new ArrayList<>();
        final List<UUID> observed = new ArrayList<>();
        RuntimeException failure;
        UUID nextFleetId = UUID.randomUUID();
        long nextStateVersion = 501;
        FleetObservationView observation;

        FakeFleet(FakeAuthorizationService authorization) {
            this.authorization = authorization;
        }

        @Override
        public FleetAcceptedView submitFleet(UUID organizationId, FleetApplyCommand command) {
            requiresSeenAtSubmit.add(authorization.permissions.size());
            organizationsSeen.add(organizationId);
            attempted.add(command);
            if (failure != null) {
                throw failure;
            }
            submitted.add(command);
            List<FleetMemberView> members = new ArrayList<>();
            for (int i = 0; i < command.members().size(); i++) {
                FleetApplyCommand.Member m = command.members().get(i);
                members.add(m.refused()
                        ? new FleetMemberView(i, null, m.instanceId(), m.placementId(), m.generation(), "refused", null,
                                null, m.refusal().name(), null, null, null)
                        : new FleetMemberView(i, i / command.batchSize(), m.instanceId(), m.placementId(),
                                m.generation(), "pending", null, null, null, null, null, null));
            }
            return new FleetAcceptedView(nextFleetId, nextStateVersion, "RUNNING", members);
        }

        @Override
        public Optional<FleetObservationView> observeFleet(UUID organizationId, UUID fleetId) {
            organizationsSeen.add(organizationId);
            observed.add(fleetId);
            return Optional.ofNullable(observation);
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult submit(String token, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/dp/fleet-applies").cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)
                .contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn();
    }

    private MvcResult observe(String token, UUID fleetId) throws Exception {
        return mvc.perform(get("/api/dp/fleet-applies/" + fleetId).cookie(new Cookie(COOKIE, token))).andReturn();
    }

    /** The scenario's body: {@code cache-1}, {@code cache-2}, {@code cache-eu}, in that order, generations 7, 4, 9. */
    private ObjectNode body(String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("solutionId", solution.toString());
        ArrayNode members = body.putArray("members");
        member(members, cache1, p1, 7);
        member(members, cache2, p2, 4);
        member(members, cacheEu, pEu, 9);
        body.put("strategy", strategy);
        return body;
    }

    private static void member(ArrayNode members, UUID instance, UUID placement, long generation) {
        members.addObject().put("instanceId", instance.toString()).put("placementId", placement.toString())
                .put("generation", generation);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static String raw(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private void assertNothingSubmitted() {
        assertTrue(fleet.attempted.isEmpty() && fleet.requiresSeenAtSubmit.isEmpty(),
                "the data plane was reached: " + fleet.attempted);
    }

    // ------------------------------------------------------------------------- the pre-check authorizes no member

    @Test
    void theReachPreCheckIsConfigApplyHeldAnywhereAndAuthorizesNoMember() throws Exception {
        login("tok-reach");
        authorization.outcome = Outcome.DENY;

        MvcResult result = submit("tok-reach", body("manual"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertEquals("FORBIDDEN", json(result).get("code").asText());
        assertFalse(json(result).has("refusedMembers"), "no member was ever evaluated: " + raw(result));
        assertEquals(List.of(Permission.CONFIG_APPLY), authorization.reachChecks,
                "the verb the operation is registered under, held at one or more grants");
        assertTrue(authorization.permissions.isEmpty(), "and no member was require()d: the pre-check is not an authorization");
        assertNothingSubmitted();
    }

    @Test
    void bothOperationsAreClassifiedReachAndRegisteredUnderTheirVerbs() {
        assertEquals(new OperationClassification.Reach(Permission.CONFIG_APPLY),
                OperationPolicy.classify("submitFleetApply"));
        assertEquals(new OperationClassification.Reach(Permission.CONFIG_VIEW),
                OperationPolicy.classify("observeFleetApply"));
    }

    @Test
    void anonymousAndCsrfLessCallsAreRefusedBeforeAnyMember() throws Exception {
        assertEquals(401, mvc.perform(post("/api/dp/fleet-applies").header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/dp/fleet-applies/" + UUID.randomUUID())).andReturn().getResponse()
                .getStatus());
        login("tok-csrf");
        MvcResult noCsrf = mvc.perform(post("/api/dp/fleet-applies").cookie(new Cookie(COOKIE, "tok-csrf"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content(body("manual").toString())).andReturn();
        assertEquals(403, noCsrf.getResponse().getStatus());
        assertEquals("CSRF_REJECTED", json(noCsrf).get("code").asText());
        assertTrue(authorization.permissions.isEmpty());
        assertNothingSubmitted();
    }

    // ----------------------------------------------------------------- REQ-013 bullet 1: refusal, member by member

    @Test
    void aPartiallyAuthorizedPrincipalIsToldWhichMembersAreRefused() throws Exception {
        login("tok-partial");

        MvcResult result = submit("tok-partial", body("manual"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        JsonNode problem = json(result);
        assertEquals("FORBIDDEN", problem.get("code").asText(), "Q-F5 (a): always FORBIDDEN, never a fleet-specific code");
        JsonNode refused = problem.get("refusedMembers");
        assertEquals(1, refused.size(), raw(result));
        assertEquals(2, refused.get(0).get("ordinal").asInt());
        assertEquals(cacheEu.toString(), refused.get(0).get("instanceId").asText());
        assertEquals(pEu.toString(), refused.get(0).get("placementId").asText());
        assertEquals("NOT_FOUND", refused.get(0).get("code").asText(),
                "op-us holds no grant that reaches prod-eu, so cache-eu is not even visible: NOT_FOUND, as its own call");
        assertEquals("application/problem+json", result.getResponse().getContentType().split(";")[0]);
        assertTrue(problem.has("correlationId") && problem.has("title"), "an RFC 9457 problem with an extension member");
    }

    @Test
    void aPartlyRefusedFleetIsRefusedWholeByDefaultAndNothingIsApplied() throws Exception {
        login("tok-whole");
        // a member refused for a verb the caller can SEE (forbidden, not hidden) is reported as FORBIDDEN
        authorization.outcomeByResource.put(cache2, Map.of(Permission.CONFIG_APPLY, Outcome.DENY));

        MvcResult result = submit("tok-whole", body("manual"));

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        JsonNode refused = json(result).get("refusedMembers");
        assertEquals(List.of(1, 2), List.of(refused.get(0).get("ordinal").asInt(), refused.get(1).get("ordinal").asInt()));
        assertEquals(List.of("FORBIDDEN", "NOT_FOUND"),
                List.of(refused.get(0).get("code").asText(), refused.get(1).get("code").asText()),
                "each member carries the code its own single call would have answered");
        assertNothingSubmitted();
    }

    @Test
    void proceedWithAuthorizedAppliesOnlyTheAuthorizedAndReportsTheRest() throws Exception {
        Session session = login("tok-proceed");
        ObjectNode body = body("manual");
        body.put("proceedWithAuthorized", true);

        MvcResult result = submit("tok-proceed", body);

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertEquals("/api/dp/fleet-applies/" + fleet.nextFleetId, result.getResponse().getHeader("Location"));
        JsonNode accepted = json(result);
        assertEquals(fleet.nextFleetId.toString(), accepted.get("fleetId").asText());
        assertEquals(501, accepted.get("stateVersion").asLong(), "the fleet row's own stamp");
        assertEquals("RUNNING", accepted.get("state").asText());
        assertFalse(accepted.has("availability"));
        JsonNode members = accepted.get("members");
        assertEquals(List.of("pending", "pending", "refused"), List.of(members.get(0).get("disposition").asText(),
                members.get(1).get("disposition").asText(), members.get(2).get("disposition").asText()),
                "the refused member is reported IN the 202: it is the submitter's record (Q-F8 (a))");
        assertEquals("NOT_FOUND", members.get(2).get("refusalCode").asText());
        assertFalse(members.get(0).has("applyId"), "no id until the member is admitted");

        FleetApplyCommand sent = fleet.submitted.get(0);
        assertEquals(java.util.Arrays.asList(null, null, FleetApplyCommand.RefusalCode.NOT_FOUND),
                sent.members().stream().map(FleetApplyCommand.Member::refusal).toList(),
                "the data plane is told every member's verdict and applies only those with none");
        assertEquals(List.of(cache1, cache2, cacheEu),
                sent.members().stream().map(FleetApplyCommand.Member::instanceId).toList(), "ordinals are the body's order");
        assertEquals(session.principal().id(), sent.actor());
    }

    @Test
    void proceedWithAuthorizedCannotApplyAFleetWhoseEveryMemberIsRefused() throws Exception {
        login("tok-none");
        authorization.outcomeByResource.put(cache1, Map.of(Permission.CONFIG_APPLY, Outcome.DENY));
        authorization.outcomeByResource.put(cache2, Map.of(Permission.CONFIG_APPLY, Outcome.NOT_FOUND));
        ObjectNode body = body("manual");
        body.put("proceedWithAuthorized", true);

        MvcResult result = submit("tok-none", body);

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertEquals(3, json(result).get("refusedMembers").size(), "nothing is left to apply, so it is refused whole");
        assertNothingSubmitted();
    }

    @Test
    void aFullyAuthorizedFleetIsRecordedWithNoRefusedMembers() throws Exception {
        login("tok-all");
        authorization.outcomeByResource.clear();

        MvcResult result = submit("tok-all", body("manual"));

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertTrue(fleet.submitted.get(0).members().stream().noneMatch(FleetApplyCommand.Member::refused));
    }

    // ---------------------------------------------------------------------------------------- one require each

    @Test
    void eachMemberIsAuthorizedByItsOwnRequire() throws Exception {
        Session session = login("tok-each");

        submit("tok-each", body("manual"));

        // refused by its own require at its own instance (cache-eu NOT_FOUND, the others authorized) - not by one
        // fleet-level require at an organization or an environment, which no single path resource could express
        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.CONFIG_APPLY, Permission.CONFIG_APPLY),
                authorization.permissions);
        assertEquals(List.of(cache1, cache2, cacheEu), authorization.resources.stream().map(r -> r.id()).toList());
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                        && r.tenant().value().equals(session.organizationId())),
                "OR-16: every verb at the OWNING INSTANCE, never a placement; I5: the tenant is the session's");
        assertTrue(authorization.principals.stream().allMatch(session.principal()::equals));
    }

    @Test
    void aRestartingStrategyAlsoRequiresInstanceRestartAtEachMembersInstance() throws Exception {
        login("tok-restart");
        // the variant: op-us lacks instance.restart, so cache-1 and cache-2 are FORBIDDEN on the SECOND verb, and
        // cache-eu is still NOT_FOUND on the first
        authorization.outcomeByResource.put(cache1, Map.of(Permission.INSTANCE_RESTART, Outcome.DENY));
        authorization.outcomeByResource.put(cache2, Map.of(Permission.INSTANCE_RESTART, Outcome.DENY));
        ObjectNode body = body("rolling-health-gated");
        body.put("proceedWithAuthorized", true);

        MvcResult result = submit("tok-restart", body);

        assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART, Permission.CONFIG_APPLY,
                        Permission.INSTANCE_RESTART, Permission.CONFIG_APPLY), authorization.permissions,
                "config.apply then instance.restart per member; a member refused on the first is not asked the second");
        assertEquals(403, result.getResponse().getStatus(), "every member is refused, under either setting: " + raw(result));
        JsonNode refused = json(result).get("refusedMembers");
        assertEquals(List.of("FORBIDDEN", "FORBIDDEN", "NOT_FOUND"), List.of(refused.get(0).get("code").asText(),
                refused.get(1).get("code").asText(), refused.get(2).get("code").asText()));
        assertNothingSubmitted();

        // the control: a manual strategy asks for no second verb, so the same caller's authorized members go through
        authorization.permissions.clear();
        MvcResult manual = submit("tok-restart", withProceed(body("manual")));
        assertEquals(202, manual.getResponse().getStatus(), raw(manual));
        assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART));
    }

    private static ObjectNode withProceed(ObjectNode body) {
        body.put("proceedWithAuthorized", true);
        return body;
    }

    @Test
    void aMemberDataPlaneCheckNeverRunsBeforeItsRequire() throws Exception {
        login("tok-order");
        // a 422 naming "member 0 has no generation 9" would confirm that member exists to a caller who cannot see it
        fleet.failure = new FleetValidationException(0, FleetValidationException.Rule.GENERATION_NOT_STAGED);

        // (a) by default a refused member means the data plane is never called, so no 422 can be produced for anyone
        MvcResult refused = submit("tok-order", body("manual"));
        assertEquals(403, refused.getResponse().getStatus(), raw(refused));
        assertNothingSubmitted();

        // (b) with proceedWithAuthorized the data plane IS called, and only AFTER every member has been require()d
        authorization.permissions.clear();
        MvcResult proceeding = submit("tok-order", withProceed(body("manual")));
        assertEquals(422, proceeding.getResponse().getStatus(), raw(proceeding));
        assertEquals(List.of(3), fleet.requiresSeenAtSubmit,
                "all three members were authorized before the data plane was asked anything");
        assertEquals(List.of(false, false, true), fleet.attempted.get(0).members().stream()
                .map(FleetApplyCommand.Member::refused).toList(), "and it was told which member was refused");
    }

    // ------------------------------------------------------------------------------------------ 501, 400, defaults

    @Test
    void scheduledIs422BeforeAnyMemberIsAuthorizedAndSaysAFleetHasNoSchedule() throws Exception {
        // plan 012 T7 (OR-121 names OR-53/OR-60): the token is not a strategy; a scheduled FLEET is a different design (v1: none)
        login("tok-501");
        // a caller who passes the reach pre-check (config.apply is held somewhere) but would be refused at EVERY member
        authorization.outcomeByPermission.put(Permission.CONFIG_APPLY, Outcome.DENY);

        MvcResult result = submit("tok-501", body("scheduled"));

        assertEquals(422, result.getResponse().getStatus(), raw(result));
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        assertTrue(json(result).get("detail").asText().contains("fleet"), raw(result));
        assertTrue(authorization.permissions.isEmpty(), "a token that is not a strategy is 422, never 403");
        assertNothingSubmitted();
    }

    // ------------------------------------------------------- plan 012 T2: the two ungated strategies (OPS-Q6, OPS-Q7)

    private ObjectNode ungated(String strategy, boolean proceed) {
        ObjectNode body = body(strategy);
        if ("restart-all".equals(strategy)) {
            body.put("outageConfirmedBy", "SENTINEL-typed-name");
        }
        body.put("proceedWithAuthorized", proceed);
        return body;
    }

    @Test
    void anUngatedStrategyAsksEachMemberForOverrideSafetyAfterInstanceRestartAtItsOwnInstance() throws Exception {
        for (String strategy : List.of("rolling-no-validation", "restart-all")) {
            Session session = login("tok-ungated-" + strategy);
            authorization.permissions.clear();
            authorization.resources.clear();

            MvcResult result = submit("tok-ungated-" + strategy, ungated(strategy, true));

            assertEquals(202, result.getResponse().getStatus(), strategy + ": " + raw(result));
            assertEquals(List.of(Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART,
                            Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Permission.CONFIG_APPLY, Permission.INSTANCE_RESTART,
                            Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Permission.CONFIG_APPLY), authorization.permissions,
                    strategy + ": three verbs for each of the two visible members; cache-eu (hidden) is refused on the first");
            assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                    && r.tenant().value().equals(session.organizationId())), "OR-16 and I5");
            FleetApplyCommand sent = fleet.submitted.get(fleet.submitted.size() - 1);
            assertEquals(strategy, sent.strategy());
            assertEquals(java.util.Arrays.asList(null, null, FleetApplyCommand.RefusalCode.NOT_FOUND),
                    sent.members().stream().map(FleetApplyCommand.Member::refusal).toList());
        }
    }

    @Test
    void aFleetWithMixedMembersAdmitsTheOneHoldingOverrideSafetyAndRefusesTheOtherForbidden() throws Exception {
        login("tok-mixed");
        // the caller holds overrideSafety at cache-1 and not at cache-2; cache-eu is not even visible
        authorization.outcomeByResource.put(cache2, Map.of(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY));

        MvcResult refusedWhole = submit("tok-mixed", ungated("rolling-no-validation", false));
        assertEquals(403, refusedWhole.getResponse().getStatus(), "refuse-whole by default: " + raw(refusedWhole));
        JsonNode refused = json(refusedWhole).get("refusedMembers");
        assertEquals(List.of(1, 2), List.of(refused.get(0).get("ordinal").asInt(), refused.get(1).get("ordinal").asInt()));
        assertEquals(List.of("FORBIDDEN", "NOT_FOUND"),
                List.of(refused.get(0).get("code").asText(), refused.get(1).get("code").asText()),
                "the member refused on the new verb is FORBIDDEN (OR-65 Q-F5), the hidden one NOT_FOUND");
        assertNothingSubmitted();

        MvcResult proceeding = submit("tok-mixed", ungated("rolling-no-validation", true));
        assertEquals(202, proceeding.getResponse().getStatus(), raw(proceeding));
        FleetApplyCommand sent = fleet.submitted.get(0);
        assertEquals(java.util.Arrays.asList(null, FleetApplyCommand.RefusalCode.FORBIDDEN,
                FleetApplyCommand.RefusalCode.NOT_FOUND), sent.members().stream().map(FleetApplyCommand.Member::refusal)
                .toList(), "one admitted, one FORBIDDEN, one NOT_FOUND");
        JsonNode members = json(proceeding).get("members");
        assertEquals("FORBIDDEN", members.get(1).get("refusalCode").asText());
    }

    @Test
    void aFleetWhoseEveryMemberIsRefusedOnOverrideSafetyIs403EvenWithProceedWithAuthorized() throws Exception {
        login("tok-allrefused");
        authorization.outcomeByPermission.put(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, Outcome.DENY);

        for (String strategy : List.of("rolling-no-validation", "restart-all")) {
            MvcResult result = submit("tok-allrefused", ungated(strategy, true));

            assertEquals(403, result.getResponse().getStatus(), strategy + ": " + raw(result));
            assertEquals(3, json(result).get("refusedMembers").size(), "nothing is left to apply");
        }
        // the control: the gated strategy is not asked for the verb, so the same caller's two visible members go through
        authorization.permissions.clear();
        MvcResult gated = submit("tok-allrefused", ungated("rolling-health-gated", true));
        assertEquals(202, gated.getResponse().getStatus(), raw(gated));
        assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY));
        assertTrue(fleet.submitted.size() == 1, "only the control reached the data plane");
    }

    @Test
    void restartAllHandsTheDataPlaneThePrincipalAsTheConfirmationNeverTheTypedNameAndOtherStrategiesNone()
            throws Exception {
        Session session = login("tok-confirm");

        submit("tok-confirm", ungated("restart-all", true));
        assertEquals(session.principal().id().toString(), fleet.submitted.get(0).outageConfirmedBy(),
                "the server records the session principal's id on the fleet (OPS-Q7 C-2)");

        for (String strategy : List.of("manual", "rolling-health-gated", "rolling-no-validation")) {
            ObjectNode typed = body(strategy);
            typed.put("outageConfirmedBy", "SENTINEL-typed-name").put("proceedWithAuthorized", true);
            submit("tok-confirm", typed);
            assertNull(fleet.submitted.get(fleet.submitted.size() - 1).outageConfirmedBy(),
                    strategy + ": a browser-supplied name is never stored");
        }
    }

    @Test
    void restartAllWithoutAConfirmationIs400BeforeAnyMemberIsAuthorized() throws Exception {
        login("tok-noconf");

        for (String confirmation : new String[] {null, "", "  "}) {
            ObjectNode body = body("restart-all");
            body.put("proceedWithAuthorized", true);
            if (confirmation != null) {
                body.put("outageConfirmedBy", confirmation);
            }
            MvcResult result = submit("tok-noconf", body);

            assertEquals(400, result.getResponse().getStatus(), "confirmation=" + confirmation + ": " + raw(result));
            assertEquals("BAD_REQUEST", json(result).get("code").asText());
        }
        assertFalse(authorization.permissions.contains(Permission.INSTANCE_RESTART_OVERRIDE_SAFETY));
        assertNothingSubmitted();
    }

    @Test
    void theBodyIsClosedAndStructuralFaultsAre400() throws Exception {
        login("tok-400");
        List<ObjectNode> bad = new ArrayList<>();
        ObjectNode strayKey = body("manual");
        strayKey.put("organizationId", UUID.randomUUID().toString());
        bad.add(strayKey);
        ObjectNode noMembers = body("manual");
        noMembers.putArray("members");
        bad.add(noMembers);
        ObjectNode tooMany = body("manual");
        ArrayNode many = tooMany.putArray("members");
        for (int i = 0; i < 101; i++) {
            member(many, UUID.randomUUID(), UUID.randomUUID(), 1);
        }
        bad.add(tooMany);
        ObjectNode zeroBatch = body("manual");
        zeroBatch.put("batchSize", 0);
        bad.add(zeroBatch);
        ObjectNode fractionalBatch = body("manual");
        fractionalBatch.put("batchSize", 1.5);
        bad.add(fractionalBatch);
        ObjectNode badOnFailure = body("manual");
        badOnFailure.put("onFailure", "sometimes");
        bad.add(badOnFailure);
        ObjectNode badStrategy = body("yolo");
        bad.add(badStrategy);
        ObjectNode noStrategy = body("manual");
        noStrategy.remove("strategy");
        bad.add(noStrategy);
        ObjectNode noSolution = body("manual");
        noSolution.remove("solutionId");
        bad.add(noSolution);
        ObjectNode zeroGeneration = body("manual");
        ((ObjectNode) zeroGeneration.get("members").get(0)).put("generation", 0);
        bad.add(zeroGeneration);
        ObjectNode fractionalGeneration = body("manual");
        ((ObjectNode) fractionalGeneration.get("members").get(0)).put("generation", 1.5);
        bad.add(fractionalGeneration);
        ObjectNode noPlacement = body("manual");
        ((ObjectNode) noPlacement.get("members").get(0)).remove("placementId");
        bad.add(noPlacement);
        ObjectNode stringProceed = body("manual");
        stringProceed.put("proceedWithAuthorized", "yes");
        bad.add(stringProceed);

        for (ObjectNode each : bad) {
            MvcResult result = submit("tok-400", each);
            assertEquals(400, result.getResponse().getStatus(), each + " -> " + raw(result));
            assertEquals("BAD_REQUEST", json(result).get("code").asText());
        }
        assertTrue(authorization.permissions.isEmpty(), "a malformed body authorizes nothing");
        assertNothingSubmitted();
    }

    @Test
    void theDefaultsAreBatchSizeOneHaltRemainingAndRefuseWhole() throws Exception {
        login("tok-defaults");
        authorization.outcomeByResource.clear();

        submit("tok-defaults", body("manual"));
        FleetApplyCommand sent = fleet.submitted.get(0);
        assertEquals(1, sent.batchSize(), "Q-F4: the most conservative rollout");
        assertEquals("halt-remaining", sent.onFailure(), "Q-F4: a failure stops the rollout visibly");
        assertEquals(solution, sent.solutionId());
        assertEquals("manual", sent.strategy());

        ObjectNode explicit = body("manual");
        explicit.put("batchSize", 3).put("onFailure", "continue");
        submit("tok-defaults", explicit);
        assertEquals(3, fleet.submitted.get(1).batchSize());
        assertEquals("continue", fleet.submitted.get(1).onFailure());
    }

    @Test
    void theOrganizationAndTheActorAreTheSessionsNeverABodyField() throws Exception {
        Session session = login("tok-i5");
        authorization.outcomeByResource.clear();

        MvcResult result = submit("tok-i5", body("manual"));
        observe("tok-i5", UUID.randomUUID());

        assertEquals(202, result.getResponse().getStatus(), raw(result));
        assertTrue(fleet.organizationsSeen.stream().allMatch(session.organizationId()::equals),
                fleet.organizationsSeen.toString());
        assertEquals(session.principal().id(), fleet.submitted.get(0).actor());
        assertTrue(authorization.resources.stream().allMatch(r -> r.tenant().value().equals(session.organizationId())));
        assertTrue(fleet.submitted.get(0).correlationId() != null, "the submit's correlation id travels with the fleet");
    }

    // ------------------------------------------------------------------------------------------- 422 and 409

    @Test
    void aValidationFailureIs422NamingTheMemberAndAConflictIs409NamingNoOtherFleet() throws Exception {
        login("tok-422");
        authorization.outcomeByResource.clear();

        fleet.failure = new FleetValidationException(1, FleetValidationException.Rule.PLACEMENT_NOT_LIVE);
        MvcResult invalid = submit("tok-422", body("manual"));
        assertEquals(422, invalid.getResponse().getStatus(), raw(invalid));
        assertEquals("VALIDATION_FAILED", json(invalid).get("code").asText());
        assertEquals("member 1 names no live placement of its instance", json(invalid).get("detail").asText(),
                "the ordinal and the rule, never a value from the body");
        assertFalse(raw(invalid).contains(p2.toString()));

        fleet.failure = new FleetConflictException(2);
        MvcResult conflict = submit("tok-422", body("manual"));
        assertEquals(409, conflict.getResponse().getStatus(), raw(conflict));
        assertEquals("CONFLICT", json(conflict).get("code").asText());
        assertTrue(json(conflict).get("detail").asText().contains("member 2"), raw(conflict));
    }

    @Test
    void aRepeatedInstanceInARollingBatchIs422NamingTheMemberAndTheDocumentedClassifier() throws Exception {
        login("tok-k2");
        authorization.outcomeByResource.clear();

        // OPS-Q8 K-2 (plan 012 T6): the data plane refuses; the gateway's job is the wire shape - 422 VALIDATION_FAILED,
        // the ordinal, and the classifier the contract documents, never an id from the body
        fleet.failure = new FleetValidationException(1, FleetValidationException.Rule.BATCH_INSTANCE_REPEATED);
        MvcResult repeated = submit("tok-k2", body("rolling-health-gated"));

        assertEquals(422, repeated.getResponse().getStatus(), raw(repeated));
        assertEquals("VALIDATION_FAILED", json(repeated).get("code").asText());
        assertEquals("member 1 shares its batch with another placement of its instance (fleet.batch.instance.repeated)",
                json(repeated).get("detail").asText());
        assertFalse(raw(repeated).contains(p2.toString()));
    }

    // ------------------------------------------------------------------------------------------------- observe

    private FleetObservationView observation(FleetMemberView... members) {
        return new FleetObservationView(fleet.nextFleetId, solution, "RUNNING", "manual", 1, "halt-remaining", 0,
                Instant.parse("2026-10-06T10:00:00Z"), null, 612, List.of(members));
    }

    private FleetMemberView seen(int ordinal, UUID instance, UUID placement, String disposition) {
        return new FleetMemberView(ordinal, ordinal, instance, placement, 7, disposition,
                disposition.equals("admitted") ? UUID.randomUUID() : null, null,
                disposition.equals("refused") ? "NOT_FOUND" : null, null,
                disposition.equals("admitted") ? "REQUESTED" : null, disposition.equals("admitted") ? 600L : null);
    }

    @Test
    void observeFiltersMembersByConfigViewAtEachMembersInstanceAndHidesARefusedMemberFromAViewerWhoCannotSeeIt()
            throws Exception {
        login("tok-view");
        fleet.observation = observation(seen(0, cache1, p1, "admitted"), seen(1, cache2, p2, "pending"),
                seen(2, cacheEu, pEu, "refused"));
        authorization.authorizedIds.addAll(List.of(cache1, cache2));      // op-us does not see cache-eu (Q-F8 (a))

        MvcResult result = observe("tok-view", fleet.nextFleetId);

        assertEquals(200, result.getResponse().getStatus(), raw(result));
        JsonNode body = json(result);
        assertEquals(2, body.get("members").size(), "the refused member is filtered like any member: " + raw(result));
        assertEquals(cache1.toString(), body.get("members").get(0).get("instanceId").asText());
        assertEquals(1, authorization.subsetCalls.size());
        FakeAuthorizationService.SubsetCall call = authorization.subsetCalls.get(0);
        assertEquals(Permission.CONFIG_VIEW, call.permission());
        assertEquals(ResourceKind.INSTANCE, call.kind());
        assertEquals(java.util.Set.of(cache1, cache2, cacheEu), call.ids(), "every member's instance is asked about");
        assertEquals(List.of(Permission.CONFIG_VIEW), authorization.reachChecks.subList(authorization.reachChecks.size() - 1,
                authorization.reachChecks.size()));
        assertFalse(raw(result).contains(cacheEu.toString()), "a hidden instance's id never reaches this viewer");

        // a viewer who can see cache-eu sees the refused member
        authorization.authorizedIds.add(cacheEu);
        JsonNode wider = json(observe("tok-view", fleet.nextFleetId));
        assertEquals(3, wider.get("members").size());
        assertEquals("refused", wider.get("members").get(2).get("disposition").asText());
        assertEquals("NOT_FOUND", wider.get("members").get(2).get("refusalCode").asText());
    }

    @Test
    void observeAnswersTheFleetRowsOwnStampAnAvailabilityAndAnAdmittedMembersRequest() throws Exception {
        login("tok-shape");
        fleet.observation = observation(seen(0, cache1, p1, "admitted"));
        authorization.authorizedIds.add(cache1);

        JsonNode body = json(observe("tok-shape", fleet.nextFleetId));

        assertEquals(612, body.get("stateVersion").asLong(), "the fleet_action row's own stamp, never the maximum of the members'");
        assertEquals("live", body.get("availability").get("state").asText(), "required by the contract on this body");
        assertEquals(fleet.nextFleetId.toString(), body.get("fleetId").asText());
        assertEquals(solution.toString(), body.get("solutionId").asText());
        assertEquals("RUNNING", body.get("state").asText());
        assertEquals(1, body.get("batchSize").asInt());
        assertEquals("halt-remaining", body.get("onFailure").asText());
        assertEquals(0, body.get("currentBatch").asInt());
        assertEquals("2026-10-06T10:00:00Z", body.get("requestedAt").asText());
        assertFalse(body.has("terminalAt"), "set exactly when the fleet leaves RUNNING");
        JsonNode member = body.get("members").get(0);
        assertEquals("admitted", member.get("disposition").asText());
        assertTrue(member.has("applyId"), "present exactly when admitted");
        assertEquals("REQUESTED", member.get("requestState").asText());
        assertEquals(600, member.get("requestStateVersion").asLong());
        assertNull(member.get("settled"));
    }

    @Test
    void observeIs404WhenTheFleetIsAbsentOrNoMemberIsVisibleAndTheTwoAreNotDistinguished() throws Exception {
        login("tok-404");
        // absent (or another organization's: the lookup is tenant-scoped, so the data plane answers empty)
        MvcResult absent = observe("tok-404", UUID.randomUUID());
        assertEquals(404, absent.getResponse().getStatus(), raw(absent));
        assertEquals("NOT_FOUND", json(absent).get("code").asText());

        // present, but none of its members is visible to this caller (Q-F6 (a))
        fleet.observation = observation(seen(0, cacheEu, pEu, "pending"));
        MvcResult hidden = observe("tok-404", fleet.nextFleetId);
        assertEquals(404, hidden.getResponse().getStatus(), raw(hidden));
        assertEquals(raw(absent).replaceAll("\"correlationId\":\"[^\"]*\"", ""),
                raw(hidden).replaceAll("\"correlationId\":\"[^\"]*\"", ""), "byte-identical but for the correlation id");
    }

    @Test
    void observeWithoutConfigViewAnywhereIs403AndTheDataPlaneIsNeverAsked() throws Exception {
        login("tok-403");
        authorization.outcome = Outcome.DENY;

        MvcResult result = observe("tok-403", fleet.nextFleetId);

        assertEquals(403, result.getResponse().getStatus(), raw(result));
        assertTrue(fleet.observed.isEmpty());
        assertEquals(List.of(Permission.CONFIG_VIEW), authorization.reachChecks);
    }
}
