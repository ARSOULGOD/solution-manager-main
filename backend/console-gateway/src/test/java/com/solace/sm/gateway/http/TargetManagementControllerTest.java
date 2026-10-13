package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.EditPlan;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionAdministration;
import com.solace.sm.dp.api.inventory.TargetConnectionChangedException;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.ScanAccounting;
import com.solace.sm.dp.api.inventory.TargetConnectionManagement;
import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.api.inventory.TargetWorkInFlightException;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.TargetRegistrationControllerTest.FakeAudit;
import com.solace.sm.gateway.http.TargetRegistrationControllerTest.Fakes;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * {@code editTargetConnection}, {@code rotateTargetCredential}, {@code testTargetConnection} and {@code retireTarget}
 * (plan 013 T1.5) over the real filter, interceptor, resolver, controller, orchestrator and advice pipeline, with every
 * cp-api and dp-api port faked.
 *
 * <p>What each group proves: (1) every operation is authorized by the interceptor at the TARGET (its own scope, never one the
 * body names), and a refusal reaches no port; (2) an edit that rescans draws on the SAME per-session budget as a scan and
 * leaves an audit row of {@code target.edit}, while one that does not is neither charged nor recorded; (3) every refusal the
 * data plane can make has exactly one status, code and fixed detail; (4) a rotation's key is zeroised and appears in no
 * response; (5) a test is always a 200 with a result and is audited by the furthest step it reached; (6) a retirement is
 * data plane first, control plane second, and the retry of an interrupted one completes it.
 */
class TargetManagementControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_MARKER = "KEYMARKER-7c1d9e3a-do-not-leak";
    private static final String KEY_TEXT = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + KEY_MARKER
            + "\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String PIN = "SHA256:" + "A".repeat(43);
    private static final String PUBLIC_FINGERPRINT = "SHA256:" + "P".repeat(43);

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private Rig rig;
    private FakeAuthorizationService authorization;
    private UUID organization;
    private UUID target;

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
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*")
                .addFilter(new PeekableBodyFilter(), "/*").build();
        rig = context.getBean(Rig.class);
        authorization = context.getBean(FakeAuthorizationService.class);
        organization = UUID.randomUUID();
        rig.base.hierarchy.organization = organization;
        target = UUID.randomUUID();
        rig.base.targets.rows.add(new TargetView(target, "vm-1", "docker", "ssh-docker",
                ScopePath.organization(organization)));
    }

    // ---------------------------------------------------------------------------------------------- the rig

    @Configuration
    @EnableWebMvc
    @Import(GatewayTestSupport.TestConfig.class)
    static class Config {
        @Bean
        Rig rig() {
            return new Rig();
        }

        @Bean
        TargetDirectory targetDirectory(Rig rig) {
            return rig.base.targets;
        }

        @Bean
        TargetRegistration targetRegistration(Rig rig) {
            return rig.base.targets;
        }

        @Bean
        TargetConnectionAdministration administration(Rig rig) {
            return rig.base.admin;
        }

        @Bean
        HostKeyScanService hostKeyScanService(Rig rig) {
            return rig.base.scanner;
        }

        @Bean
        TargetProbeAudit targetProbeAudit(Rig rig) {
            return rig.base.audit;
        }

        @Bean
        @Primary
        HierarchyDirectory treeHierarchy(Rig rig) {
            return rig.base.hierarchy;
        }

        @Bean
        ScanRateGate scanRateGate(Rig rig) {
            return new ScanRateGate(10, rig.base.nanos::get);
        }

        @Bean
        TargetRegistrationOrchestrator targetRegistrationOrchestrator(Rig rig, RequestOriginFactory origins,
                ScanRateGate gate) {
            return new TargetRegistrationOrchestrator(rig.base.targets, rig.base.targets, rig.base.admin,
                    rig.base.scanner, rig.base.audit, rig.base.hierarchy, origins, gate);
        }

        @Bean
        TargetConnectionManagement management(Rig rig) {
            return rig.management;
        }

        @Bean
        TargetConnectionTester tester(Rig rig) {
            return rig.tester;
        }

        @Bean
        TargetManagementOrchestrator targetManagementOrchestrator(Rig rig,
                TargetRegistrationOrchestrator registrations, RequestOriginFactory origins) {
            return new TargetManagementOrchestrator(registrations, rig.management, rig.tester, rig.base.targets,
                    rig.base.audit, origins);
        }

        @Bean
        TargetResolver targetResolver() {
            return new TargetResolver();
        }

        @Bean
        OwnershipScopeResolver ownershipScopeResolver() {
            return new OwnershipScopeResolver();
        }

        @Bean
        TargetRegistrationController targetRegistrationController(TargetRegistrationOrchestrator orchestrator) {
            return new TargetRegistrationController(orchestrator);
        }

        @Bean
        TargetManagementController targetManagementController(TargetManagementOrchestrator orchestrator) {
            return new TargetManagementController(orchestrator);
        }

        @Bean
        TargetRegistrationErrorAdvice targetRegistrationErrorAdvice() {
            return new TargetRegistrationErrorAdvice();
        }
    }

    static final class Rig {
        final Fakes base = new Fakes();
        final FakeManagement management = new FakeManagement(base.events);
        final FakeTester tester = new FakeTester(base.events);
    }

    static final class FakeManagement implements TargetConnectionManagement {
        final List<String> events;
        final List<UUID> organizations = new ArrayList<>();
        final List<TargetConnectionEdit> edits = new ArrayList<>();
        final List<UUID> actors = new ArrayList<>();
        final List<TargetChangeOrigin> origins = new ArrayList<>();
        final List<char[]> keys = new ArrayList<>();
        EditPlan plan = new EditPlan(true, false);
        RuntimeException planFailure;
        RuntimeException editFailure;
        RuntimeException rotateFailure;
        RuntimeException retireFailure;
        ConnectionRetirement retirement = ConnectionRetirement.RETIRED;
        String host = "vm1.example.com";
        int port = 22;
        String sshUser = "sm";

        FakeManagement(List<String> events) {
            this.events = events;
        }

        TargetConnectionView viewOf(UUID targetId) {
            return new TargetConnectionView(targetId, host, port, sshUser, PIN, List.of(PIN), "/srv/sm", "sm-",
                    new TargetConnectionView.CredentialSummary(true, PUBLIC_FINGERPRINT,
                            Instant.parse("2026-10-08T00:00:00Z")));
        }

        @Override
        public EditPlan planEdit(UUID organizationId, UUID targetId, TargetConnectionEdit edit) {
            events.add("dp.plan");
            organizations.add(organizationId);
            if (planFailure != null) {
                throw planFailure;
            }
            return plan;
        }

        /**
         * Whether the data plane scans, decided by IT and not by the plan (review W4C-3): {@code null} means "as planned".
         * A test sets it to model a record that moved between the plan and the write.
         */
        Boolean scans;
        /** The scan began and failed in a way nothing classifies (it never answered). */
        boolean scanFailsBeforeAnswering;

        @Override
        public TargetConnectionView editConnection(UUID organizationId, UUID targetId, TargetConnectionEdit edit,
                UUID actor, TargetChangeOrigin origin, ScanAccounting accounting) {
            organizations.add(organizationId);
            boolean scanning = scans != null ? scans : plan.rescan();
            if (scanning) {
                accounting.beforeScan();             // a refusal here is the data plane's: nothing is scanned or written
                events.add("dp.scan");
                boolean scannerFailed = editFailure instanceof TargetAddressRefusedException
                        || editFailure instanceof HostKeyScanUnavailableException;
                if (!scannerFailed && !scanFailsBeforeAnswering) {
                    accounting.afterScan();
                }
            }
            events.add("dp.edit");
            edits.add(edit);
            actors.add(actor);
            origins.add(origin);
            if (editFailure != null) {
                throw editFailure;
            }
            if (edit.port() != null) {
                port = edit.port();
            }
            if (edit.host() != null) {
                host = edit.host();
            }
            if (edit.sshUser() != null) {
                sshUser = edit.sshUser();
            }
            return viewOf(targetId);
        }

        @Override
        public TargetConnectionView.CredentialSummary rotateConnectionCredential(UUID organizationId, UUID targetId,
                char[] privateKey, UUID actor, TargetChangeOrigin origin) {
            events.add("dp.rotate");
            organizations.add(organizationId);
            keys.add(privateKey);
            actors.add(actor);
            origins.add(origin);
            if (rotateFailure != null) {
                throw rotateFailure;
            }
            return new TargetConnectionView.CredentialSummary(true, PUBLIC_FINGERPRINT,
                    Instant.parse("2026-10-08T01:00:00Z"));
        }

        @Override
        public ConnectionRetirement retireConnection(UUID organizationId, UUID targetId, UUID actor,
                TargetChangeOrigin origin) {
            events.add("dp.retire");
            organizations.add(organizationId);
            actors.add(actor);
            origins.add(origin);
            if (retireFailure != null) {
                RuntimeException failure = retireFailure;
                retireFailure = null;
                throw failure;
            }
            ConnectionRetirement answered = retirement;
            retirement = ConnectionRetirement.ALREADY_RETIRED;
            return answered;
        }
    }

    static final class FakeTester implements TargetConnectionTester {
        final List<String> events;
        final List<UUID> organizations = new ArrayList<>();
        TargetConnectionTestResult result = new TargetConnectionTestResult(true, true, true, "28.5.1",
                Instant.parse("2026-10-08T02:00:00Z"));
        RuntimeException failure;

        FakeTester(List<String> events) {
            this.events = events;
        }

        /** When set, a test announces itself on {@code entered} and then waits for {@code release} (a slow SSH connection). */
        java.util.concurrent.CountDownLatch entered;
        java.util.concurrent.CountDownLatch release;

        @Override
        public TargetConnectionTestResult test(UUID organizationId, UUID targetId) {
            events.add("dp.test");
            organizations.add(organizationId);
            if (entered != null) {
                entered.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    // --------------------------------------------------------------------------------------------- helpers

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MvcResult send(MockHttpServletRequestBuilder builder, String token, String body) throws Exception {
        MockHttpServletRequestBuilder request = builder.cookie(new Cookie(COOKIE, token))
                .header("Origin", GatewayTestSupport.TRUSTED_ORIGIN).header("X-SM-CSRF", "csrf-" + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult edit(String token, UUID id, ObjectNode body) throws Exception {
        return send(patch("/api/dp/targets/" + id + "/connection"), token, body.toString());
    }

    private MvcResult rotate(String token, UUID id, String key) throws Exception {
        return send(put("/api/dp/targets/" + id + "/credential"), token,
                MAPPER.createObjectNode().put("privateKey", key).toString());
    }

    private MvcResult test(String token, UUID id) throws Exception {
        return send(post("/api/dp/targets/" + id + "/connection/test"), token, null);
    }

    private MvcResult retire(String token, UUID id) throws Exception {
        return send(post("/api/cp/targets/" + id + "/retire"), token, null);
    }

    private static ObjectNode body(String field, Object value) {
        ObjectNode node = MAPPER.createObjectNode();
        if (value instanceof Integer i) {
            node.put(field, i);
        } else {
            node.put(field, String.valueOf(value));
        }
        return node;
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private void assertNothingReached() {
        assertEquals(List.of(), rig.base.events, "no port of either plane was reached");
    }

    // ---------------------------------------------------------------------------------------- authorization

    @Test
    void everyOperationIsAuthorizedAtTheTargetItselfWithItsOwnVerbBeforeAnyPort() throws Exception {
        login("tok-auth");
        edit("tok-auth", target, body("sshUser", "deploy"));
        rotate("tok-auth", target, KEY_TEXT);
        test("tok-auth", target);
        retire("tok-auth", target);

        assertEquals(List.of(Permission.TARGET_EDIT, Permission.TARGET_EDIT, Permission.TARGET_USE,
                Permission.TARGET_EDIT), authorization.permissions);
        for (ResourceRef resource : authorization.resources) {
            assertEquals(ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, target, new TenantId(organization)), resource,
                    "the target named by the PATH, in the SESSION's organization (I5)");
        }
    }

    @Test
    void aCallerWithoutTheVerbIs403OnEveryOperationAndNoPortIsReached() throws Exception {
        login("tok-viewer");
        authorization.outcome = FakeAuthorizationService.Outcome.DENY;

        for (MvcResult result : List.of(edit("tok-viewer", target, body("sshUser", "deploy")),
                rotate("tok-viewer", target, KEY_TEXT), test("tok-viewer", target), retire("tok-viewer", target))) {
            assertEquals(403, result.getResponse().getStatus());
            assertEquals("FORBIDDEN", json(result).get("code").asText());
        }
        assertNothingReached();
    }

    @Test
    void anotherOrganizationsTargetIsAnIndistinguishable404OnEveryOperation() throws Exception {
        login("tok-foreign");
        authorization.outcome = FakeAuthorizationService.Outcome.NOT_FOUND;

        for (MvcResult result : List.of(edit("tok-foreign", target, body("sshUser", "deploy")),
                rotate("tok-foreign", target, KEY_TEXT), test("tok-foreign", target), retire("tok-foreign", target))) {
            assertEquals(404, result.getResponse().getStatus());
            assertEquals("NOT_FOUND", json(result).get("code").asText());
        }
        assertNothingReached();
    }

    // ------------------------------------------------------------------------------------- editTargetConnection

    @Test
    void anEditPlansThenAppliesAsTheSessionsPrincipalInTheSessionsOrganizationWithTheRequestsOwnOrigin() throws Exception {
        Session session = login("tok-edit");

        MvcResult result = edit("tok-edit", target, body("sshUser", "deploy"));

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of("dp.plan", "dp.edit"), rig.base.events);
        assertEquals(List.of(organization, organization), rig.management.organizations);
        assertEquals(new TargetConnectionEdit(null, null, "deploy", null), rig.management.edits.get(0));
        assertEquals(session.principal().id(), rig.management.actors.get(0));
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"),
                rig.management.origins.get(0).correlationId().toString());
        JsonNode view = json(result);
        assertEquals(target.toString(), view.get("targetId").asText());
        assertEquals("deploy", view.get("sshUser").asText());
        assertEquals(PUBLIC_FINGERPRINT, view.at("/credential/publicKeyFingerprint").asText());
        assertFalse(result.getResponse().getContentAsString().contains("credentialRef"));
    }

    @Test
    void anEditThatDoesNotRescanIsNeitherChargedToTheScanBudgetNorAudited() throws Exception {
        login("tok-user-only");
        for (int i = 0; i < 25; i++) {
            assertEquals(200, edit("tok-user-only", target, body("sshUser", "deploy" + i)).getResponse().getStatus());
        }
        assertTrue(rig.base.audit.others.isEmpty(), rig.base.audit.others.toString());
    }

    @Test
    void aRescanningEditDrawsOnTheSameBudgetAsAScanAndIsAuditedAsTargetEditOnTheTarget() throws Exception {
        Session session = login("tok-rescan");
        rig.management.plan = new EditPlan(true, true);

        MvcResult result = edit("tok-rescan", target, body("port", 2222));

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, rig.base.audit.others.size());
        FakeAudit.Other row = rig.base.audit.others.get(0);
        assertEquals("rescan", row.verb());
        assertEquals("completed", row.outcome());
        assertEquals(target, row.targetId());
        assertEquals(session.principal(), row.actor());
        assertEquals(new TenantId(organization), row.organization());
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"), row.origin().correlationId().toString());
        assertTrue(rig.base.audit.rows.isEmpty(), "not a target.create scan row");
    }

    @Test
    void theEleventhRescanInAMinuteIs429AuditedAndNeverReachesTheDataPlaneWriteAndScansShareTheBucket() throws Exception {
        login("tok-rescan-rate");
        rig.management.plan = new EditPlan(true, true);
        for (int i = 0; i < 10; i++) {
            assertEquals(200, edit("tok-rescan-rate", target, body("port", 2000 + i)).getResponse().getStatus());
        }
        rig.base.events.clear();
        rig.base.audit.others.clear();

        MvcResult result = edit("tok-rescan-rate", target, body("port", 3000));

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(result).get("code").asText());
        assertNotNull(result.getResponse().getHeader("Retry-After"));
        assertEquals(List.of("dp.plan"), rig.base.events, "planned (it does no I/O), then refused before the write");
        assertEquals("rate_limited", rig.base.audit.others.get(0).outcome());
        // the bucket is the scans' too
        MvcResult scan = send(post("/api/dp/host-key-scans"), "tok-rescan-rate",
                MAPPER.createObjectNode().put("host", "10.0.0.7").set("ownershipScope", MAPPER.createObjectNode())
                        .toString());
        assertEquals(429, scan.getResponse().getStatus(), "a scan shares the budget the edits spent");
        // and it refills
        rig.base.nanos.addAndGet(60L * 1_000_000_000L);
        assertEquals(200, edit("tok-rescan-rate", target, body("port", 3000)).getResponse().getStatus());
    }

    @Test
    void everyRefusalAfterTheHostWasContactedIsAuditedWithTheOutcomeOfThatScan() throws Exception {
        record Case(RuntimeException failure, int status, String code, String outcome) {
        }
        for (Case c : List.of(
                new Case(new TargetAddressRefusedException(), 422, "TARGET_ADDRESS_REFUSED", "address_refused"),
                new Case(new HostKeyScanUnavailableException(), 503, "SERVICE_UNAVAILABLE", "unreachable"),
                new Case(new HostKeyNotOfferedException(), 422, "HOST_KEY_NOT_OFFERED", "completed"),
                new Case(new HostKeyHeldElsewhereException(), 409, "CONFLICT", "completed"),
                new Case(new TargetWorkInFlightException(), 409, "CONFLICT", "completed"))) {
            rig.base.audit.others.clear();
            login("tok-refusal-" + c.outcome() + c.code());
            rig.management.plan = new EditPlan(true, true);
            rig.management.editFailure = c.failure();

            MvcResult result = edit("tok-refusal-" + c.outcome() + c.code(), target, body("host", "vm9.example.com"));

            assertEquals(c.status(), result.getResponse().getStatus(), c.failure().getClass().getSimpleName());
            assertEquals(c.code(), json(result).get("code").asText());
            assertEquals(1, rig.base.audit.others.size());
            assertEquals(c.outcome(), rig.base.audit.others.get(0).outcome(), c.failure().getClass().getSimpleName());
        }
    }

    // ---- review W4C-3: the scan that is accounted for is the one the data plane made --------------------------------------

    @Test
    void aRecordChangeAfterTheScanIsStillAuditedAsTheCompletedScanItFollowed() throws Exception {
        login("tok-changed-after");
        rig.management.plan = new EditPlan(true, true);
        rig.management.editFailure = new TargetConnectionChangedException();

        MvcResult result = edit("tok-changed-after", target, body("host", "vm9.example.com"));

        assertEquals(409, result.getResponse().getStatus());
        assertEquals(1, rig.base.audit.others.size(), "the host was scanned, so the scan leaves its row");
        assertEquals("completed", rig.base.audit.others.get(0).outcome());
    }

    @Test
    void aScanTheDataPlaneDecidesOnItsOwnIsChargedAndAuditedEvenThoughThePlanSaidNoScan() throws Exception {
        // The plan was made against a record a concurrent edit has since moved: the data plane now needs a scan.
        login("tok-unplanned");
        rig.management.plan = new EditPlan(true, false);
        rig.management.scans = true;

        MvcResult result = edit("tok-unplanned", target, body("sshUser", "deploy"));

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, rig.base.audit.others.size(), "a scan with no row and no token is what this review found");
        assertEquals("completed", rig.base.audit.others.get(0).outcome());
    }

    @Test
    void aScanTheDataPlaneDoesNotMakeIsNeitherChargedNorAuditedEvenThoughThePlanSaidScan() throws Exception {
        login("tok-unscanned");
        rig.management.plan = new EditPlan(true, true);
        rig.management.scans = false;                 // the record moved so that the stored host is now the requested one

        for (int i = 0; i < 25; i++) {                // more than the budget: none of them drew a token
            assertEquals(200, edit("tok-unscanned", target, body("port", 2000 + i)).getResponse().getStatus());
        }

        assertTrue(rig.base.audit.others.isEmpty(), "no scan, no row: " + rig.base.audit.others);
    }

    @Test
    void aScanThatBeganAndNeverAnsweredIsAuditedAsUnreachableAndTheFailureIsTheFiveHundredItWas() throws Exception {
        login("tok-unclassified");
        rig.management.plan = new EditPlan(true, true);
        rig.management.scanFailsBeforeAnswering = true;
        rig.management.editFailure = new IllegalStateException("simulated unclassified failure inside the scan");

        MvcResult result = edit("tok-unclassified", target, body("host", "vm9.example.com"));

        assertEquals(500, result.getResponse().getStatus());
        assertEquals(1, rig.base.audit.others.size());
        assertEquals("unreachable", rig.base.audit.others.get(0).outcome());
    }

    @Test
    void theFixedDetailsNameNeitherTheHostNorTheOtherTargetNorTheWork() throws Exception {
        login("tok-detail");
        rig.management.plan = new EditPlan(true, true);
        rig.management.editFailure = new TargetWorkInFlightException();
        String inFlight = json(edit("tok-detail", target, body("host", "vm9.example.com"))).get("detail").asText();
        assertEquals("work is in flight on a placement of this target; let it finish, then edit the connection", inFlight);

        rig.management.editFailure = new HostKeyHeldElsewhereException();
        assertEquals("a host key of this machine is registered to another target",
                json(edit("tok-detail", target, body("host", "vm9.example.com"))).get("detail").asText());

        rig.management.editFailure = new TargetConnectionChangedException();
        String changed = json(edit("tok-detail", target, body("host", "vm9.example.com"))).get("detail").asText();
        assertTrue(changed.contains("changed") && changed.contains("retry"), changed);
    }

    @Test
    void aTargetThatVanishedBetweenThePlanAndTheEditIs404() throws Exception {
        login("tok-vanish");
        rig.management.editFailure = new NoSuchElementException("the target has no live connection record");

        MvcResult result = edit("tok-vanish", target, body("sshUser", "deploy"));

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
    }

    @Test
    void aTargetWithNoLiveConnectionRecordIs404AtThePlanAndNothingIsScanned() throws Exception {
        login("tok-noconn");
        rig.management.planFailure = new NoSuchElementException("the target has no live connection record");

        MvcResult result = edit("tok-noconn", target, body("sshUser", "deploy"));

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(List.of("dp.plan"), rig.base.events);
        assertTrue(rig.base.audit.others.isEmpty());
    }

    @Test
    void aFieldOutsideItsGrammarIs422NamingTheFieldNeverTheValueAndNothingIsScanned() throws Exception {
        login("tok-grammar");
        rig.management.planFailure = new IllegalArgumentException("sshUser is not a login name");

        MvcResult result = edit("tok-grammar", target, body("sshUser", "not a login " + KEY_MARKER));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("VALIDATION_FAILED", json(result).get("code").asText());
        assertEquals("field 'sshUser' failed validation", json(result).get("detail").asText());
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
        assertEquals(List.of("dp.plan"), rig.base.events);
        assertTrue(rig.base.audit.others.isEmpty(), "never contacted a host: not a scan");
    }

    @Test
    void aGrammarMessageNamingNoKnownFieldNamesOnlyTheConnection() throws Exception {
        login("tok-grammar2");
        rig.management.planFailure = new IllegalArgumentException("something with " + KEY_MARKER);

        MvcResult result = edit("tok-grammar2", target, body("sshUser", "deploy"));

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("field 'connection' failed validation", json(result).get("detail").asText());
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
    }

    @Test
    void theEnvelopeTheOrganizationTheTargetIdAndTheKeyAreNotAcceptedInAnEdit() throws Exception {
        login("tok-envelope");
        for (String field : List.of("allowedConfigRoot", "allowedContainerPrefix", "organizationId", "targetId",
                "privateKey", "credentialRef")) {
            MvcResult result = edit("tok-envelope", target, body(field, "/srv/other"));
            assertEquals(400, result.getResponse().getStatus(), field);
            assertEquals("BAD_REQUEST", json(result).get("code").asText(), field);
        }
        assertNothingReached();
    }

    @Test
    void anEmptyEditAndAWronglyTypedFieldAre400() throws Exception {
        login("tok-bad");
        assertEquals(400, edit("tok-bad", target, MAPPER.createObjectNode()).getResponse().getStatus());
        assertEquals(400, send(patch("/api/dp/targets/" + target + "/connection"), "tok-bad", "{\"port\":{}}")
                .getResponse().getStatus());
        assertEquals(400, send(patch("/api/dp/targets/" + target + "/connection"), "tok-bad", "not json")
                .getResponse().getStatus());
        assertEquals(400, edit("tok-bad", target, body("host", "")).getResponse().getStatus(), "an empty string names nothing");
        assertNothingReached();
    }

    // --------------------------------------------------------------------------------- rotateTargetCredential

    @Test
    void aRotationReturnsTheStoredKeysSummaryAndZeroisesTheKeyAfterward() throws Exception {
        Session session = login("tok-rotate");

        MvcResult result = rotate("tok-rotate", target, KEY_TEXT);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode credential = json(result).get("credential");
        assertTrue(credential.get("present").asBoolean());
        assertEquals(PUBLIC_FINGERPRINT, credential.get("publicKeyFingerprint").asText());
        assertEquals("2026-10-08T01:00:00Z", credential.get("setAt").asText());
        assertEquals(List.of("dp.rotate"), rig.base.events, "not planned, scanned or checked for work in flight");
        assertEquals(session.principal().id(), rig.management.actors.get(0));
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"),
                rig.management.origins.get(0).correlationId().toString());
        assertFalse(rig.management.keys.isEmpty());
        for (char[] seen : rig.management.keys) {
            assertArrayEquals(new char[seen.length], seen, "the buffer handed to the data plane is zeroised after");
        }
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
        assertTrue(rig.base.audit.others.isEmpty() && rig.base.audit.rows.isEmpty(), "a rotation is not a probe");
    }

    @Test
    void aKeyThatDoesNotOpenIs422CredentialInvalidAndIsStillZeroisedAndNeverEchoed() throws Exception {
        login("tok-rotate-bad");
        rig.management.rotateFailure = new CredentialInvalidException();

        MvcResult result = rotate("tok-rotate-bad", target, KEY_TEXT);

        assertEquals(422, result.getResponse().getStatus());
        assertEquals("CREDENTIAL_INVALID", json(result).get("code").asText());
        assertFalse(result.getResponse().getContentAsString().contains(KEY_MARKER));
        for (char[] seen : rig.management.keys) {
            assertArrayEquals(new char[seen.length], seen);
        }
    }

    @Test
    void aRotationOfATargetWithNoLiveRecordIs404AndARecordThatChangedIs409() throws Exception {
        login("tok-rotate-gone");
        rig.management.rotateFailure = new NoSuchElementException("gone");
        assertEquals(404, rotate("tok-rotate-gone", target, KEY_TEXT).getResponse().getStatus());

        rig.management.rotateFailure = new TargetConnectionChangedException();
        MvcResult changed = rotate("tok-rotate-gone", target, KEY_TEXT);
        assertEquals(409, changed.getResponse().getStatus());
        assertEquals("CONFLICT", json(changed).get("code").asText());
    }

    @Test
    void aRotationBodyWithAnythingButTheKeyOrAKeyPastTheLengthOrNoKeyIs400() throws Exception {
        login("tok-rotate-400");
        assertEquals(400, send(put("/api/dp/targets/" + target + "/credential"), "tok-rotate-400",
                "{\"privateKey\":\"k\",\"extra\":1}").getResponse().getStatus());
        assertEquals(400, send(put("/api/dp/targets/" + target + "/credential"), "tok-rotate-400", "{}")
                .getResponse().getStatus());
        assertEquals(400, rotate("tok-rotate-400", target, "x".repeat(RegisterTargetRequest.MAX_PRIVATE_KEY_CHARS + 1))
                .getResponse().getStatus());
        assertEquals(400, rotate("tok-rotate-400", target, "").getResponse().getStatus());
        assertNothingReached();
    }

    @Test
    void theRotationRequestNeverPrintsTheKey() {
        RotateTargetCredentialRequest request = new RotateTargetCredentialRequest(KEY_TEXT);

        assertFalse(request.toString().contains(KEY_MARKER), request.toString());
        assertTrue(request.toString().contains("[REDACTED]"));
    }

    @Test
    void aKeyOverPlainHttpFromAnotherHostIsRefusedBeforeAnyoneLooksAtItButALoopbackOrSecureOneIsNot() throws Exception {
        login("tok-plain");
        MvcResult plain = mvc.perform(put("/api/dp/targets/" + target + "/credential").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).cookie(new Cookie(COOKIE, "tok-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-plain").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.createObjectNode().put("privateKey", KEY_TEXT).toString())).andReturn();

        assertEquals(403, plain.getResponse().getStatus());
        assertEquals("INSECURE_TRANSPORT", json(plain).get("code").asText());
        assertTrue(authorization.permissions.isEmpty(), "before the session, the CSRF check or the body");
        assertNothingReached();
        assertFalse(plain.getResponse().getContentAsString().contains(KEY_MARKER));

        MvcResult secure = mvc.perform(put("/api/dp/targets/" + target + "/credential").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            request.setSecure(true);
            return request;
        }).cookie(new Cookie(COOKIE, "tok-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-plain").contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.createObjectNode().put("privateKey", KEY_TEXT).toString())).andReturn();
        assertEquals(200, secure.getResponse().getStatus());
        assertEquals(200, rotate("tok-plain", target, KEY_TEXT).getResponse().getStatus(), "loopback");
    }

    @Test
    void anEditCarriesNoCredentialSoItIsNotTransportGated() throws Exception {
        login("tok-edit-plain");
        MvcResult result = mvc.perform(patch("/api/dp/targets/" + target + "/connection").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).cookie(new Cookie(COOKIE, "tok-edit-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-edit-plain").contentType(MediaType.APPLICATION_JSON)
                .content(body("sshUser", "deploy").toString())).andReturn();
        assertEquals(200, result.getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------- testTargetConnection

    @Test
    void aPassingTestIs200WithEveryStepAndTheVersionAndIsAuditedAsPassed() throws Exception {
        Session session = login("tok-test");

        MvcResult result = test("tok-test", target);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        JsonNode body = json(result);
        assertTrue(body.get("hostKeyPinned").asBoolean());
        assertTrue(body.get("authenticated").asBoolean());
        assertTrue(body.at("/docker/available").asBoolean());
        assertEquals("28.5.1", body.at("/docker/serverVersion").asText());
        assertEquals("2026-10-08T02:00:00Z", body.get("checkedAt").asText());
        assertEquals(List.of(organization), rig.tester.organizations);
        assertEquals(1, rig.base.audit.others.size());
        FakeAudit.Other row = rig.base.audit.others.get(0);
        assertEquals("test", row.verb());
        assertEquals("passed", row.outcome());
        assertEquals(target, row.targetId());
        assertEquals(session.principal(), row.actor());
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"), row.origin().correlationId().toString());
    }

    @Test
    void everyFailureIsA200ResultNeverA5xxAndIsAuditedByTheFurthestStepReached() throws Exception {
        record Case(TargetConnectionTestResult result, String outcome) {
        }
        Instant at = Instant.parse("2026-10-08T02:00:00Z");
        for (Case c : List.of(
                new Case(new TargetConnectionTestResult(true, true, false, null, at), "docker_unavailable"),
                new Case(new TargetConnectionTestResult(true, false, false, null, at), "not_authenticated"),
                new Case(new TargetConnectionTestResult(false, false, false, null, at), "not_reached"))) {
            rig.base.audit.others.clear();
            login("tok-test-" + c.outcome());
            rig.tester.result = c.result();

            MvcResult result = test("tok-test-" + c.outcome(), target);

            assertEquals(200, result.getResponse().getStatus(), c.outcome());
            JsonNode body = json(result);
            assertEquals(c.result().hostKeyPinned(), body.get("hostKeyPinned").asBoolean());
            assertEquals(c.result().authenticated(), body.get("authenticated").asBoolean());
            assertFalse(body.at("/docker/available").asBoolean());
            assertTrue(body.at("/docker").path("serverVersion").isMissingNode(), "no version for a daemon that did not answer");
            assertEquals(c.outcome(), rig.base.audit.others.get(0).outcome());
        }
    }

    @Test
    void aTestOfATargetWithNoLiveRecordIs404AndIsNotAudited() throws Exception {
        login("tok-test-gone");
        rig.tester.failure = new NoSuchElementException("gone");

        MvcResult result = test("tok-test-gone", target);

        assertEquals(404, result.getResponse().getStatus());
        assertTrue(rig.base.audit.others.isEmpty());
    }

    @Test
    void aTestCarriesNoBodyAndNoCredentialSoItIsNotTransportGatedAndWritesNothingButItsAuditRow() throws Exception {
        login("tok-test-plain");
        MvcResult result = mvc.perform(post("/api/dp/targets/" + target + "/connection/test").with(request -> {
            request.setRemoteAddr("203.0.113.9");
            return request;
        }).cookie(new Cookie(COOKIE, "tok-test-plain")).header("Origin", GatewayTestSupport.TRUSTED_ORIGIN)
                .header("X-SM-CSRF", "csrf-tok-test-plain")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of("dp.test"), rig.base.events);
    }

    // ---- review W4C-4: a test is a probe, so it is bounded like one --------------------------------------------------------

    @Test
    void theEleventhTestInAMinuteIs429DeniedAuditedAndNeverReachesTheDataPlaneAndSharesTheScanBucket() throws Exception {
        login("tok-test-rate");
        for (int i = 0; i < 10; i++) {
            assertEquals(200, test("tok-test-rate", target).getResponse().getStatus());
        }
        rig.base.events.clear();
        rig.base.audit.others.clear();

        MvcResult result = test("tok-test-rate", target);

        assertEquals(429, result.getResponse().getStatus());
        assertEquals("RATE_LIMITED", json(result).get("code").asText());
        assertNotNull(result.getResponse().getHeader("Retry-After"));
        assertEquals(List.of(), rig.base.events, "no connection was made");
        assertEquals(1, rig.base.audit.others.size());
        assertEquals("test", rig.base.audit.others.get(0).verb());
        assertEquals("rate_limited", rig.base.audit.others.get(0).outcome());
        // the bucket is the scans' and the edits' too
        MvcResult scan = send(post("/api/dp/host-key-scans"), "tok-test-rate",
                MAPPER.createObjectNode().put("host", "10.0.0.7").set("ownershipScope", MAPPER.createObjectNode())
                        .toString());
        assertEquals(429, scan.getResponse().getStatus(), "a scan shares the budget the tests spent");
        // and it refills
        rig.base.nanos.addAndGet(60L * 1_000_000_000L);
        assertEquals(200, test("tok-test-rate", target).getResponse().getStatus());
    }

    @Test
    void aSecondTestOfATargetWhileOneIsRunningIs429NotAQueueAndTheFirstStillFinishes() throws Exception {
        login("tok-test-one");
        login("tok-test-two");
        rig.tester.entered = new java.util.concurrent.CountDownLatch(1);
        rig.tester.release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<MvcResult> first = pool.submit(() -> test("tok-test-one", target));
            assertTrue(rig.tester.entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "the first test is running");

            MvcResult second = test("tok-test-two", target);

            assertEquals(429, second.getResponse().getStatus());
            assertEquals("1", second.getResponse().getHeader("Retry-After"));
            assertEquals("rate_limited", rig.base.audit.others.get(0).outcome());
            assertEquals(1, rig.base.events.stream().filter("dp.test"::equals).count(), "only the first connected");

            rig.tester.release.countDown();
            assertEquals(200, first.get(10, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus());
        } finally {
            rig.tester.release.countDown();
            pool.shutdownNow();
        }
        // the slot is free again once the first has finished (a refusal must not leak it)
        rig.tester.entered = null;
        assertEquals(200, test("tok-test-two", target).getResponse().getStatus());
    }

    // ------------------------------------------------------------------------------------------ retireTarget

    @Test
    void aRetirementIsDataPlaneFirstThenControlPlaneAndReturnsTheTargetWithWhenItWasRetired() throws Exception {
        Session session = login("tok-retire");

        MvcResult result = retire("tok-retire", target);

        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(List.of("dp.retire", "cp.retire"), rig.base.events);
        JsonNode body = json(result);
        assertEquals(target.toString(), body.get("targetId").asText());
        assertEquals("vm-1", body.get("name").asText());
        assertEquals("2026-10-08T12:00:00Z", body.get("retiredAt").asText());
        assertEquals(session.principal(), rig.base.targets.actors.get(0));
        assertEquals(organization, rig.base.targets.organizations.get(0));
        assertEquals(result.getResponse().getHeader("X-Correlation-Id"),
                rig.base.targets.origins.get(0).correlationId().toString());
        assertEquals(session.principal().id(), rig.management.actors.get(0));
    }

    @Test
    void aLivePlacementIs409AndTheControlPlaneRowIsNotTouched() throws Exception {
        login("tok-retire-busy");
        rig.management.retireFailure = new TargetHasLivePlacementsException();

        MvcResult result = retire("tok-retire-busy", target);

        assertEquals(409, result.getResponse().getStatus());
        assertEquals("CONFLICT", json(result).get("code").asText());
        assertEquals("a target cannot be retired while it has live placements", json(result).get("detail").asText());
        assertEquals(List.of("dp.retire"), rig.base.events, "the control plane is not reached");
        assertEquals(1, rig.base.targets.rows.size());
    }

    @Test
    void retiringARetiredTargetIs200AgainAndTheDataPlaneAnswersAlreadyRetired() throws Exception {
        login("tok-retire-again");
        assertEquals(200, retire("tok-retire-again", target).getResponse().getStatus());

        MvcResult again = retire("tok-retire-again", target);

        assertEquals(200, again.getResponse().getStatus());
        assertEquals("2026-10-08T12:00:00Z", json(again).get("retiredAt").asText(), "the original time");
    }

    @Test
    void aControlPlaneRowWithNoConnectionRecordIsRetiredOnItsOwn() throws Exception {
        login("tok-retire-orphan");
        rig.management.retirement = ConnectionRetirement.NO_CONNECTION;

        MvcResult result = retire("tok-retire-orphan", target);

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(List.of("dp.retire", "cp.retire"), rig.base.events);
    }

    @Test
    void aFailureBetweenTheTwoStepsLeavesAListedTargetAndTheRetryCompletesIt() throws Exception {
        login("tok-retire-crash");
        rig.base.targets.retireFailure = new IllegalStateException("simulated crash after the data plane step");

        assertEquals(500, retire("tok-retire-crash", target).getResponse().getStatus());
        assertEquals(1, rig.base.targets.rows.size(), "still listed");
        rig.base.events.clear();

        MvcResult retry = retire("tok-retire-crash", target);

        assertEquals(200, retry.getResponse().getStatus());
        assertEquals(List.of("dp.retire", "cp.retire"), rig.base.events, "the data plane answers 'already retired'");
        assertTrue(rig.base.targets.rows.isEmpty());
    }

    @Test
    void aTargetThatIsNotInTheOrganizationsControlPlaneIs404FromTheControlPlane() throws Exception {
        login("tok-retire-unknown");

        MvcResult result = retire("tok-retire-unknown", UUID.randomUUID());

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
    }

    // ---------------------------------------------------------------------------------- the response shapes

    @Test
    void theResponsesCarryNoKeyNoCredentialReferenceAndNoHostOfAnotherTarget() throws Exception {
        login("tok-shape");
        rig.base.audit.others.clear();
        List<MvcResult> results = List.of(edit("tok-shape", target, body("sshUser", "deploy")),
                rotate("tok-shape", target, KEY_TEXT), test("tok-shape", target), retire("tok-shape", target));

        for (MvcResult result : results) {
            String text = result.getResponse().getContentAsString();
            assertFalse(text.contains(KEY_MARKER), text);
            assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("credentialref"), text);
            assertFalse(text.contains("BEGIN"), text);
        }
    }
}
