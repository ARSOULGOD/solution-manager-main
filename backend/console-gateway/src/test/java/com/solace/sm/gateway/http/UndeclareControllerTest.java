package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.RetirementBegun;
import com.solace.sm.dp.api.inventory.RetirementOutcome;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementRefusedException.Reason;
import com.solace.sm.dp.api.inventory.RetirementView;
import com.solace.sm.dp.api.inventory.RetirementView.PlacementHealth;
import com.solace.sm.dp.api.inventory.RetiringInstance;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.GatewayTestSupport.MutableClock;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code undeclareInstance} (plan 012 T5, {@code BE-D49}, {@code OPS-Q2} U-C) over the real filter, interceptor,
 * argument-resolver, controller and advice pipeline, with the cp-api and dp-api ports FAKED and one ordered call log. What
 * this pins is the gateway's half: WHICH verb is asked at WHICH resource and in what order, that the three steps run in
 * the protocol's order (the data plane records the intent BEFORE the control plane is written, and finishes after), the
 * half-window guard, how each refusal is answered, and that the lazy sweep runs before the reads and the declare it
 * protects. The data plane's half is {@code InstanceRetirementServiceIT}, the control plane's {@code InstanceRetirementServiceIT}
 * in {@code cp-core}, and the two over real HTTP and databases are {@code UndeclareEndpointIT}.
 */
class UndeclareControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private InstanceControllerTest.Calls calls;
    private Retirements retirements;
    private Governance governance;
    private MutableClock clock;
    private Inventory inventory;
    private UUID organization;
    private UUID instance;
    private UUID placementOne;
    private UUID placementTwo;

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
        retirements = context.getBean(Retirements.class);
        governance = context.getBean(Governance.class);
        clock = context.getBean("undeclareClock", MutableClock.class);
        inventory = context.getBean(Inventory.class);
        organization = UUID.randomUUID();
        instance = UUID.randomUUID();
        placementOne = UUID.randomUUID();
        placementTwo = UUID.randomUUID();
        retirements.placements = List.of(new PlacementHealth(placementOne, Optional.of("GREEN")),
                new PlacementHealth(placementTwo, Optional.empty()));
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
        MutableClock undeclareClock() {
            return new MutableClock(NOW);
        }

        @Bean
        Retirements retirements(InstanceControllerTest.Calls calls, @org.springframework.beans.factory.annotation.Qualifier("undeclareClock") MutableClock clock) {
            return new Retirements(calls, clock);
        }

        @Bean
        Governance retirementGovernance(InstanceControllerTest.Calls calls) {
            return new Governance(calls);
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
        EnvironmentResolver environmentResolver() {
            return new EnvironmentResolver();
        }

        @Bean
        InstanceResolver instanceResolver() {
            return new InstanceResolver();
        }

        @Bean
        UndeclareSweep undeclareSweep(Retirements retirements, Governance governance,
                @org.springframework.beans.factory.annotation.Qualifier("undeclareClock") MutableClock clock) {
            return new UndeclareSweep(retirements, governance, clock, Duration.ofMinutes(10));
        }

        @Bean
        DeclareOrchestrator declareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
                InstanceGovernance governance, SolutionCatalog catalog,
                com.solace.sm.cp.api.authz.AuthorizationService authorization, ReachAuthorization reach,
                com.solace.sm.cp.api.designer.DesignerCodec codec,
                com.solace.sm.cp.api.designer.MetricApplicability applicability, UndeclareSweep sweep) {
            return new DeclareOrchestrator(inventory, connections, governance, catalog, authorization, reach, codec,
                    applicability, Duration.ofMinutes(10), sweep);
        }

        @Bean
        InstanceController instanceController(DeclareOrchestrator orchestrator, RequestOriginFactory origins) {
            return new InstanceController(orchestrator, origins);
        }

        @Bean
        UndeclareOrchestrator undeclareOrchestrator(Retirements retirements, Governance governance,
                @org.springframework.beans.factory.annotation.Qualifier("undeclareClock") MutableClock clock,
                UndeclareSweep sweep) {
            return new UndeclareOrchestrator(retirements, governance, clock, Duration.ofMinutes(10), sweep);
        }

        @Bean
        UndeclareController undeclareController(UndeclareOrchestrator orchestrator, RequestOriginFactory origins) {
            return new UndeclareController(orchestrator, origins);
        }
    }

    /** The data plane's undeclare port, scripted. */
    static final class Retirements implements InstanceRetirementService {
        final InstanceControllerTest.Calls calls;
        final Clock clock;
        final List<UUID> organizations = new ArrayList<>();
        final List<UUID> requestedBy = new ArrayList<>();
        final List<UUID> correlations = new ArrayList<>();
        final List<WorkloadDisposition> workloads = new ArrayList<>();
        final Map<UUID, Instant> retiring = new LinkedHashMap<>();
        List<PlacementHealth> placements = List.of();
        RuntimeException beginFailure;
        RuntimeException completeFailure;
        /** When set, {@code beginRetire} answers a record opened at this instant and {@code resumed}. */
        Instant resumedFrom;
        /** When set, a roll-back answers {@code true} but leaves the open record in place (a lost race). */
        boolean rollBackLeavesTheRecord;

        Retirements(InstanceControllerTest.Calls calls, Clock clock) {
            this.calls = calls;
            this.clock = clock;
        }

        RetirementView view(UUID instanceId, Instant requestedAt, boolean closed) {
            return new RetirementView(UUID.randomUUID(), instanceId, UUID.randomUUID(), requestedAt, UUID.randomUUID(),
                    WorkloadDisposition.LEAVE_RUNNING, placements,
                    closed ? Optional.of(clock.instant()) : Optional.empty(),
                    closed ? Optional.of(RetirementOutcome.RETIRED) : Optional.empty(), closed ? 42L : 41L);
        }

        @Override
        public RetirementBegun beginRetire(UUID organizationId, UUID instanceId, UUID by, UUID correlationId,
                WorkloadDisposition workload) {
            calls.add("dp.beginRetire");
            organizations.add(organizationId);
            requestedBy.add(by);
            correlations.add(correlationId);
            workloads.add(workload);
            if (beginFailure != null) {
                throw beginFailure;
            }
            return resumedFrom == null
                    ? new RetirementBegun(view(instanceId, clock.instant(), false), false)
                    : new RetirementBegun(view(instanceId, resumedFrom, false), true);
        }

        @Override
        public RetirementView completeRetire(UUID organizationId, UUID instanceId) {
            calls.add("dp.completeRetire");
            organizations.add(organizationId);
            if (completeFailure != null) {
                throw completeFailure;
            }
            return view(instanceId, clock.instant(), true);
        }

        @Override
        public boolean rollBackRetire(UUID organizationId, UUID instanceId) {
            calls.add("dp.rollBackRetire");
            organizations.add(organizationId);
            if (!rollBackLeavesTheRecord) {
                resumedFrom = null;
            }
            return true;
        }

        @Override
        public List<RetiringInstance> retiring(UUID organizationId) {
            calls.add("dp.retiring");
            organizations.add(organizationId);
            List<RetiringInstance> list = new ArrayList<>();
            retiring.forEach((id, at) -> list.add(new RetiringInstance(id, UUID.randomUUID(), at)));
            return list;
        }
    }

    /** The control plane's undeclare port, scripted. */
    static final class Governance implements InstanceRetirementGovernance {
        final InstanceControllerTest.Calls calls;
        final List<PrincipalRef> actors = new ArrayList<>();
        final List<RequestOrigin> origins = new ArrayList<>();
        final List<UUID> organizations = new ArrayList<>();
        boolean wasAlreadyRetired;
        RuntimeException deregisterFailure;

        Governance(InstanceControllerTest.Calls calls) {
            this.calls = calls;
        }

        @Override
        public boolean deregister(PrincipalRef actor, RequestOrigin origin, UUID organizationId, UUID instanceId) {
            calls.add("cp.deregister");
            actors.add(actor);
            origins.add(origin);
            organizations.add(organizationId);
            if (deregisterFailure != null) {
                throw deregisterFailure;
            }
            return !wasAlreadyRetired;
        }

        @Override
        public Set<UUID> retired(UUID organizationId, Collection<UUID> instanceIds) {
            calls.add("cp.retired");
            organizations.add(organizationId);
            return Set.of();
        }
    }

    /** The inventory as far as the reads and the declare's sweep see it. */
    static final class Inventory implements InstanceInventoryService {
        final InstanceControllerTest.Calls calls;

        Inventory(InstanceControllerTest.Calls calls) {
            this.calls = calls;
        }

        @Override
        public InstanceView reserve(UUID organizationId, DeclareInstanceCommand command) {
            throw new AssertionError();
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
            return Optional.empty();
        }

        @Override
        public List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId) {
            calls.add("dp.listActive");
            return List.of();
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
            throw new AssertionError();
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult undeclare(String token, String query) throws Exception {
        return mvc.perform(delete("/api/dp/instances/" + instance + query).cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token)).andReturn();
    }

    private MvcResult undeclare(String token) throws Exception {
        return undeclare(token, "?workload=leave-running");
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private void assertNothingWritten() {
        assertFalse(calls.has("dp.beginRetire") || calls.has("cp.deregister") || calls.has("dp.completeRetire"),
                "a plane was written: " + calls.log);
    }

    // ------------------------------------------------------------------------------------ authorization

    @Test
    void undeclareRequiresTheUndeclareVerbAtTheInstanceNamedByThePathAndNoSecondVerb() throws Exception {
        Session session = login("tok-verb");

        MvcResult result = undeclare("tok-verb");

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of(Permission.INSTANCE_UNDECLARE), authorization.permissions,
                "OPS-Q3 P-1: one verb, at the instance; it touches no target and no host");
        assertEquals(ResourceKind.INSTANCE, authorization.resources.get(0).kind());
        assertEquals(instance, authorization.resources.get(0).id());
        assertEquals(session.organizationId(), authorization.resources.get(0).tenant().value(), "I5");
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNeitherPlaneIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcomeByPermission.put(Permission.INSTANCE_UNDECLARE, Outcome.DENY);

        MvcResult result = undeclare("tok-deny");

        assertEquals(403, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), "no port was touched: " + calls.log);
    }

    @Test
    void anInstanceThatIsNotThisOrganizationsOrIsAlreadyRetiredInTheControlPlaneIs404AndNeitherPlaneIsTouched()
            throws Exception {
        login("tok-404");
        authorization.outcomeByPermission.put(Permission.INSTANCE_UNDECLARE, Outcome.NOT_FOUND);

        MvcResult result = undeclare("tok-404");

        assertEquals(404, result.getResponse().getStatus());
        assertTrue(calls.log.isEmpty(), "a retired node is 'not found' to the authorizer: a repeated DELETE is 404");
    }

    // ------------------------------------------------------------------------------------------ workload

    @Test
    void theWorkloadMustBeSaidAndAnythingElseIs400BeforeAnyPlaneIsWritten() throws Exception {
        login("tok-workload");

        for (String query : List.of("", "?workload=", "?workload=stop", "?workload=remove", "?workload=LEAVE-RUNNING",
                "?other=leave-running")) {
            assertEquals(400, undeclare("tok-workload", query).getResponse().getStatus(), query);
        }

        assertNothingWritten();
    }

    // ----------------------------------------------------------------------------------------- the protocol

    @Test
    void theThreeStepsRunInTheProtocolsOrderTheDataPlaneRecordsTheIntentBeforeTheControlPlaneIsWritten() throws Exception {
        Session session = login("tok-order");

        MvcResult result = undeclare("tok-order");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of("dp.beginRetire", "cp.deregister", "dp.completeRetire"), calls.log,
                "BE-D32 reversed: DP intent first (a CP node with no DP intent is unrecoverable), CP release, DP finish");
        assertEquals(List.of(organization, organization), retirements.organizations, "I5: the session's organization");
        assertEquals(List.of(organization), governance.organizations, "I5");
        assertEquals(List.of(session.principal().id()), retirements.requestedBy, "the requester is the session principal");
        assertEquals(List.of(WorkloadDisposition.LEAVE_RUNNING), retirements.workloads);
        assertEquals(session.principal(), governance.actors.get(0), "the audit actor is the requesting session");
        String correlation = result.getResponse().getHeader("X-Correlation-Id");
        assertEquals(correlation, retirements.correlations.get(0).toString(), "the DP record joins to this response");
        assertEquals(correlation, governance.origins.get(0).correlationId().toString(), "and so does the CP audit row");
    }

    @Test
    void theResponseListsEachPlacementWithItsLastHealthAndSaysTheWorkloadWasLeftRunning() throws Exception {
        login("tok-body");

        JsonNode body = json(undeclare("tok-body"));

        assertEquals(instance.toString(), body.get("instanceId").asText());
        assertEquals("left-running", body.get("workload").asText());
        assertEquals(NOW.toString(), body.get("retiredAt").asText(), "the record's completed_at");
        assertEquals(42, body.get("stateVersion").asLong(), "the instance's stamp after the final write (OR-35)");
        assertEquals(2, body.get("placements").size());
        assertEquals(placementOne.toString(), body.get("placements").get(0).get("placementId").asText());
        assertEquals("GREEN", body.get("placements").get(0).get("lastHealth").asText());
        assertTrue(body.get("placements").get(1).get("lastHealth").isNull(), "never probed: null, not omitted");
        String all = body.toString();
        assertFalse(all.contains("containerName") || all.contains("host") || all.contains("configDirectory"),
                "no host, container name or path in the answer: " + all);
    }

    @Test
    void anAuthorizedRetryOfAHalfDoneUndeclareResumesAndTheRetryingPrincipalIsTheCpActor() throws Exception {
        Session retrier = login("tok-retry");
        retirements.resumedFrom = NOW.minus(Duration.ofMinutes(2));

        MvcResult result = undeclare("tok-retry");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of("dp.beginRetire", "cp.deregister", "dp.completeRetire"), calls.log);
        assertEquals(retrier.principal(), governance.actors.get(0),
                "the CP write is made by the session that performs it; the DP record keeps the original requester");
    }

    @Test
    void anAlreadyReleasedControlPlaneNodeIsNotAnErrorTheDataPlaneStillFinishes() throws Exception {
        login("tok-idempotent");
        governance.wasAlreadyRetired = true;

        assertEquals(200, undeclare("tok-idempotent").getResponse().getStatus());

        assertEquals(List.of("dp.beginRetire", "cp.deregister", "dp.completeRetire"), calls.log);
    }

    // ------------------------------------------------------------------------------------ the half-window guard

    @Test
    void aRecordOlderThanHalfTheWindowIs503AndTheControlPlaneIsNotWritten() throws Exception {
        login("tok-guard");
        retirements.resumedFrom = NOW.minus(Duration.ofMinutes(5)).minusSeconds(1);

        MvcResult result = undeclare("tok-guard");

        assertEquals(503, result.getResponse().getStatus(), "a sweep roll-back must not race a slow step 4");
        assertEquals(List.of("dp.beginRetire"), calls.log, "nothing on the control plane was written, and step 5 is not run");
        assertEquals("SERVICE_UNAVAILABLE", json(result).get("code").asText());
    }

    /** W5 review W5B-4: a retry of the DELETE alone must be able to clear a record the sweep would have rolled back. */
    @Test
    void aRecordOlderThanTheWholeWindowIsRolledBackByTheRetryItselfAndTheUndeclareThenProceeds() throws Exception {
        login("tok-stale");
        Instant stale = NOW.minus(Duration.ofMinutes(10)).minusSeconds(1);
        retirements.resumedFrom = stale;
        retirements.retiring.put(instance, stale);

        MvcResult result = undeclare("tok-stale");

        assertEquals(200, result.getResponse().getStatus(), "a retry of the DELETE alone must not be 503 forever");
        assertEquals(List.of("dp.beginRetire", "dp.retiring", "cp.retired", "dp.rollBackRetire", "dp.beginRetire",
                "cp.deregister", "dp.completeRetire"), calls.log);
        assertEquals(Set.of(organization), Set.copyOf(retirements.organizations), "I5: the session's organization only");
    }

    @Test
    void aRecordThePostSweepBeginStillFindsTooOldIs503AndTheControlPlaneIsNotWritten() throws Exception {
        login("tok-stale-race");
        Instant stale = NOW.minus(Duration.ofMinutes(10)).minusSeconds(1);
        retirements.resumedFrom = stale;
        retirements.retiring.put(instance, stale);
        retirements.rollBackLeavesTheRecord = true;

        MvcResult result = undeclare("tok-stale-race");

        assertEquals(503, result.getResponse().getStatus());
        assertFalse(calls.has("cp.deregister"));
        assertEquals(2, calls.log.stream().filter("dp.beginRetire"::equals).count(), "one retry, not a loop: " + calls.log);
    }

    @Test
    void aRecordBetweenHalfAndTheWholeWindowIs503WithoutRunningTheSweep() throws Exception {
        login("tok-mid");
        retirements.resumedFrom = NOW.minus(Duration.ofMinutes(9));

        assertEquals(503, undeclare("tok-mid").getResponse().getStatus());
        assertEquals(List.of("dp.beginRetire"), calls.log, "inside the window the sweep must not roll it back yet");
    }

    @Test
    void aRecordExactlyHalfTheWindowOldStillProceeds() throws Exception {
        login("tok-edge");
        retirements.resumedFrom = NOW.minus(Duration.ofMinutes(5));

        assertEquals(200, undeclare("tok-edge").getResponse().getStatus());
        assertTrue(calls.has("cp.deregister"));
    }

    // ----------------------------------------------------------------------------------------------- refusals

    @Test
    void workInFlightIs409NamingTheKindAndTheControlPlaneIsNotWritten() throws Exception {
        login("tok-409");
        retirements.beginFailure = new RetirementRefusedException(Reason.REQUEST_IN_FLIGHT);
        MvcResult request = undeclare("tok-409");
        retirements.beginFailure = new RetirementRefusedException(Reason.FLEET_IN_FLIGHT);
        MvcResult fleet = undeclare("tok-409");

        assertEquals(409, request.getResponse().getStatus());
        assertEquals(409, fleet.getResponse().getStatus());
        assertEquals("CONFLICT", json(request).get("code").asText());
        assertTrue(json(request).get("detail").asText().contains("request"), json(request).toString());
        assertTrue(json(fleet).get("detail").asText().contains("fleet"), json(fleet).toString());
        assertFalse(json(fleet).get("detail").asText().matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no id is echoed");
        retirements.beginFailure = new RetirementRefusedException(Reason.SCHEDULE_PENDING);
        MvcResult schedule = undeclare("tok-409");
        assertEquals(409, schedule.getResponse().getStatus());
        assertTrue(json(schedule).get("detail").asText().contains("scheduled apply"), json(schedule).toString());
        assertTrue(json(schedule).get("detail").asText().contains("cancel"), json(schedule).toString());
        assertFalse(json(schedule).get("detail").asText().matches(".*[0-9a-f]{8}-[0-9a-f]{4}-.*"), "no id is echoed");
        assertFalse(calls.has("cp.deregister"), "BE-D32: a refused intent leaves the control plane alone");
    }

    @Test
    void aDataPlaneThatDoesNotKnowTheInstanceIs404() throws Exception {
        login("tok-dp404");
        retirements.beginFailure = new RetirementRefusedException(Reason.NOT_FOUND);

        assertEquals(404, undeclare("tok-dp404").getResponse().getStatus());
        assertFalse(calls.has("cp.deregister"));
    }

    @Test
    void aControlPlaneFailureAfterTheIntentLeavesTheRowToTheRetryOrTheSweepAndStepFiveIsNotRun() throws Exception {
        login("tok-cpdown");
        governance.deregisterFailure = new IllegalStateException("control plane down");

        MvcResult result = undeclare("tok-cpdown");

        assertEquals(500, result.getResponse().getStatus());
        assertEquals(List.of("dp.beginRetire", "cp.deregister"), calls.log);
        assertFalse(result.getResponse().getContentAsString().contains("control plane down"), "no internals on the wire");
    }

    @Test
    void aVanishedRetirementAfterTheControlPlaneWriteIsAnInternalErrorNeverAClaimOfSuccess() throws Exception {
        login("tok-inconsistent");
        retirements.completeFailure = new RetirementRefusedException(Reason.NOT_RETIRING);

        MvcResult result = undeclare("tok-inconsistent");

        assertEquals(500, result.getResponse().getStatus());
        assertEquals("INTERNAL_ERROR", json(result).get("code").asText());
    }

    // ------------------------------------------------------------------------------------------- the sweep

    @Test
    void theSweepRunsBeforeGetInstanceAndListInstancesReadAndAsksTheSessionsOrganizationOnly() throws Exception {
        login("tok-sweep");
        mvc.perform(get("/api/dp/instances/" + instance).cookie(new Cookie(COOKIE, "tok-sweep"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)).andReturn();
        int getRead = calls.indexOf("dp.findActive");
        assertTrue(calls.indexOf("dp.retiring") >= 0 && calls.indexOf("dp.retiring") < getRead,
                "getInstance: the sweep first: " + calls.log);
        calls.log.clear();

        mvc.perform(get("/api/dp/solutions/" + UUID.randomUUID() + "/instances").cookie(new Cookie(COOKIE, "tok-sweep"))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)).andReturn();
        assertTrue(calls.indexOf("dp.retiring") >= 0 && calls.indexOf("dp.retiring") < calls.indexOf("dp.listActive"),
                "listInstances: the sweep first: " + calls.log);
        assertEquals(Set.of(organization), Set.copyOf(retirements.organizations), "I5: one organization, no enumeration");
    }

    @Test
    void theUndeclareItselfDoesNotRunTheSweepUnlessItsOwnRecordIsStale() throws Exception {
        login("tok-nosweep");

        undeclare("tok-nosweep");

        assertFalse(calls.has("dp.retiring"), "the sweep is for the reads and the declare, not for undeclare itself");
    }
}
