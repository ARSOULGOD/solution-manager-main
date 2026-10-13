package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.governance.InstanceRetirementGovernance;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 012 T5 ({@code BE-D49}, {@code OPS-Q2} U-C): {@code undeclareInstance} is SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real
 * {@code GrantReachAuthorization}, the real orchestrator, BOTH planes' real services and triggers, and the real lazy sweep.
 * Nothing is faked. {@code UndeclareControllerTest} pins the gateway's half with the ports faked; the data plane's and the
 * control plane's halves are their own ITs; this is the same thing through the one door a browser has.
 *
 * <p><b>The crash windows are driven, not guessed.</b> The test holds the same two ports the orchestrator composes
 * ({@code InstanceRetirementService}, {@code InstanceRetirementGovernance}) and performs steps 3 and 4 by hand to leave an
 * undeclare exactly where a crash would: after step 3 (data plane {@code retiring}, control plane live) and after step 4 (both
 * done but step 5). Then the three recoveries are exercised through HTTP: an authorized retry, and the sweep run by a
 * <i>Viewer's</i> {@code listInstances} - which must write nothing in {@code sm_control}. The window is
 * {@code sm.dp.undeclare.rollback-after = PT10S}, so the half-window guard is five seconds and the roll-back ten.
 *
 * <p>State carries from test to test (ordered), as {@code AddPlacementEndpointIT}. The host is never touched: every test that
 * undeclares asserts no {@code apply_request} was written.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UndeclareEndpointIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Client.REQUEST_TIMEOUT).build();
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final String LEAVE = "?workload=leave-running";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;

    private static ApplyItSupport.Declared a;
    private static UUID aSecondPlacement;
    private static ApplyItSupport.Declared b;
    private static ApplyItSupport.Declared c;
    private static ApplyItSupport.Declared d;
    private static ApplyItSupport.Declared e;
    private static Instant stoppedAfterStepThree;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private InstanceRetirementService retirements;

    @Autowired
    private InstanceRetirementGovernance controlPlane;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.undeclare.rollback-after", () -> WINDOW.toString());
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 10,
                DeclareFixtures.ADMIN);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private Client client() {
        return new Client(port);
    }

    private static String path(UUID instance) {
        return "/api/dp/instances/" + instance;
    }

    private HttpResponse<String> delete(String path, Login login) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    private UUID principalId(String username) {
        return ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o AND username = :u",
                Map.of("o", world.organization(), "u", username), UUID.class);
    }

    private String lifecycle(UUID instance) {
        return ownerJdbc.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), String.class);
    }

    private boolean controlPlaneRetired(UUID instance) {
        return Boolean.TRUE.equals(ownerJdbc.queryForObject("SELECT retired_at IS NOT NULL FROM sm_control.instance"
                + " WHERE instance_id = :i", Map.of("i", instance), Boolean.class));
    }

    private long undeclaredAuditRows(UUID instance) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'instance'"
                + " AND audit_operation = 'INSTANCE_UNDECLARED' AND row_key ->> 'instance_id' = :i",
                Map.of("i", instance.toString()), Long.class);
    }

    /**
     * Audited writes to the governance node ({@code sm_control.instance}). Not the whole audit table: every login in this IT
     * writes (and so audits) a session row, which has nothing to do with undeclare.
     */
    private long controlPlaneAuditRows() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'instance'",
                Map.of(), Long.class);
    }

    private long count(String table) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data." + table + " WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class);
    }

    private Map<String, Object> record(UUID instance) {
        return ownerJdbc.queryForMap("SELECT requested_by, correlation_id, workload_disposition, outcome,"
                + " completed_at IS NOT NULL AS closed FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND instance_id = :i AND placement_id IS NULL ORDER BY requested_at DESC LIMIT 1",
                Map.of("t", world.organization(), "i", instance));
    }

    private long retirementRecords(UUID instance) {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), Long.class);
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("127.0.0.1", UUID.randomUUID());
    }

    /** Step 3 by hand, as the requester a crash would leave behind. */
    private void stopAfterStepThree(UUID instance, String requester) {
        retirements.beginRetire(world.organization(), instance, principalId(requester), UUID.randomUUID(),
                WorkloadDisposition.LEAVE_RUNNING);
    }

    private List<String> listedIds(Client http, Login login) throws Exception {
        HttpResponse<String> response = http.get("/api/dp/solutions/" + solutionId + "/instances", login);
        assertEquals(200, response.statusCode(), response.body());
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        body(response).get("items").forEach(item -> ids.add(item.get("instanceId").asText()));
        return ids;
    }

    private ObjectNode addBody(UUID target, String container, int healthPort, int metricsHostPort) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("targetId", target.toString());
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", container);
        placement.put("configDirectory", "/srv/sm/" + container);
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", healthPort);
        placement.putObject("metricsPorts").put("8081", metricsHostPort);
        return body;
    }

    /** Sleeps until {@code at}; the IT's only clock-dependent step, and it is the window being waited out. */
    private static void sleepUntil(Instant at) throws InterruptedException {
        long millis = Duration.between(Instant.now(), at).toMillis();
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    // ------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void theRouteIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertEquals(401, client().anonymous("DELETE", path(UUID.randomUUID()) + LEAVE).statusCode());
    }

    @Test
    @Order(2)
    void instancesAreDeclaredAndTheFirstGetsASecondPlacement() throws Exception {
        Client http = client();
        a = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);
        b = ApplyItSupport.declare(http, world, "b-cache", "sm-b", 9102);
        c = ApplyItSupport.declare(http, world, "c-cache", "sm-c", 9103);
        d = ApplyItSupport.declare(http, world, "d-cache", "sm-d", 9104);
        e = ApplyItSupport.declare(http, world, "e-cache", "sm-e", 9105);
        solutionId = UUID.fromString(body(http.get(path(a.instance()), http.login(DeclareFixtures.OPERATOR_ONE)))
                .get("solutionId").asText());
        HttpResponse<String> added = http.post(path(a.instance()) + "/placements", http.login(DeclareFixtures.OPERATOR_ONE),
                addBody(world.targetOne(), "sm-orders-2", 9111, 39111));
        assertEquals(201, added.statusCode(), added.body());
        aSecondPlacement = UUID.fromString(body(added).get("placements").get(1).get("placementId").asText());
        assertEquals(5, listedIds(http, http.login(DeclareFixtures.OPERATOR_ONE)).size());
    }

    @Test
    @Order(3)
    void aViewerIs403ASiblingEnvironmentsOperatorAndAForeignAdministratorAre404AndNothingMoves() throws Exception {
        Client http = client();
        long audit = controlPlaneAuditRows();

        HttpResponse<String> viewer = delete(path(a.instance()) + LEAVE, http.login(DeclareFixtures.VIEWER));
        assertEquals(403, viewer.statusCode(), viewer.body());
        assertEquals("FORBIDDEN", body(viewer).get("code").asText());
        for (String who : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            assertEquals(404, delete(path(a.instance()) + LEAVE, http.login(who)).statusCode(), who);
        }

        assertEquals("active", lifecycle(a.instance()));
        assertEquals(0, retirementRecords(a.instance()));
        assertFalse(controlPlaneRetired(a.instance()));
        assertEquals(audit, controlPlaneAuditRows());
    }

    @Test
    @Order(4)
    void theWorkloadMustBeSaidAndAnythingElseIs400BeforeAnythingIsWritten() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        for (String query : List.of("", "?workload=stop", "?workload=remove", "?workload=", "?workload=LEAVE-RUNNING")) {
            assertEquals(400, delete(path(a.instance()) + query, operator).statusCode(), query);
        }

        assertEquals("active", lifecycle(a.instance()));
        assertEquals(0, retirementRecords(a.instance()));
        assertEquals(2, body(http.get(path(a.instance()), operator)).get("placements").size());
    }

    @Test
    @Order(5)
    void workInFlightIs409NamingTheKindAndNeitherPlaneChanges() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ownerJdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i) ON CONFLICT DO NOTHING",
                Map.of("t", world.organization(), "i", a.instance()));
        UUID request = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 3, 'manual', 'operator-1', now(), 'RUNNING')",
                Map.of("a", request, "t", world.organization(), "i", a.instance(), "p", a.placement()));
        long audit = controlPlaneAuditRows();

        HttpResponse<String> response = delete(path(a.instance()) + LEAVE, operator);

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertTrue(body(response).get("detail").asText().contains("request"), response.body());
        assertFalse(response.body().contains(request.toString()), "never another request's id");
        assertEquals("active", lifecycle(a.instance()));
        assertEquals(0, retirementRecords(a.instance()));
        assertFalse(controlPlaneRetired(a.instance()), "a refused intent leaves the control plane alone");
        assertEquals(audit, controlPlaneAuditRows());
        assertEquals(200, http.get(path(a.instance()), operator).statusCode(), "and it is still readable");

        ownerJdbc.update("UPDATE sm_data.apply_request SET state = 'FAILED_NO_EFFECT', terminal_at = now()"
                + " WHERE tenant_id = :t AND apply_id = :a", Map.of("t", world.organization(), "a", request));
    }

    @Test
    @Order(6)
    void anOperatorUndeclaresAndTheHostIsNeverTouched() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long requests = count("apply_request");
        long placements = count("placement");
        long auditBefore = undeclaredAuditRows(a.instance());

        HttpResponse<String> response = delete(path(a.instance()) + LEAVE, operator);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode retired = body(response);
        assertEquals(a.instance().toString(), retired.get("instanceId").asText());
        assertEquals("left-running", retired.get("workload").asText());
        assertEquals(2, retired.get("placements").size());
        assertEquals(List.of(a.placement().toString(), aSecondPlacement.toString()).stream().sorted().toList(),
                java.util.stream.StreamSupport.stream(retired.get("placements").spliterator(), false)
                        .map(p -> p.get("placementId").asText()).toList(), "id order, both live placements");
        assertTrue(retired.get("placements").get(0).has("lastHealth"), "present even when never probed");
        assertTrue(retired.get("stateVersion").asLong() > a.version());
        Instant.parse(retired.get("retiredAt").asText());
        String correlation = response.headers().firstValue("X-Correlation-Id").orElseThrow();

        // the data plane: retired, every placement retired, the record closed and attributed
        assertEquals("retired", lifecycle(a.instance()));
        assertEquals(2L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                + " AND instance_id = :i AND retired_at IS NOT NULL",
                Map.of("t", world.organization(), "i", a.instance()), Long.class));
        Map<String, Object> record = record(a.instance());
        assertEquals(principalId(DeclareFixtures.OPERATOR_ONE), record.get("requested_by"));
        assertEquals(correlation, record.get("correlation_id").toString(), "the record joins to this response");
        assertEquals("leave-running", record.get("workload_disposition"));
        assertEquals("retired", record.get("outcome"));
        // the control plane: retired by the requester, one audit row naming the actor and the same correlation id
        assertTrue(controlPlaneRetired(a.instance()));
        assertEquals(principalId(DeclareFixtures.OPERATOR_ONE), ownerJdbc.queryForObject(
                "SELECT retired_by FROM sm_control.instance WHERE instance_id = :i", Map.of("i", a.instance()), UUID.class));
        assertEquals(auditBefore + 1, undeclaredAuditRows(a.instance()));
        Map<String, Object> audit = ownerJdbc.queryForMap("SELECT actor_principal_id, correlation_id::text AS c"
                + " FROM sm_control.change_audit WHERE audit_operation = 'INSTANCE_UNDECLARED'"
                + " AND row_key ->> 'instance_id' = :i", Map.of("i", a.instance().toString()));
        assertEquals(principalId(DeclareFixtures.OPERATOR_ONE), audit.get("actor_principal_id"));
        assertEquals(correlation, audit.get("c"));
        // the host: nothing was written that could touch it, and no placement row was deleted
        assertEquals(requests, count("apply_request"), "no stop, no apply: undeclare never reaches the host (U-C)");
        assertEquals(placements, count("placement"), "rows are kept forever");
    }

    @Test
    @Order(7)
    void aRetiredInstanceIsGoneFromEveryReadAndARepeatedDeleteIs404() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Login admin = http.login(DeclareFixtures.ADMIN);

        assertEquals(404, delete(path(a.instance()) + LEAVE, operator).statusCode(), "a repeated DELETE is the same 404");
        assertEquals(404, delete(path(a.instance()) + LEAVE, admin).statusCode(), "even for an organization Administrator");
        for (String suffix : List.of("", "/health", "/designer", "/config/history", "/config-ladder")) {
            assertEquals(404, http.get(path(a.instance()) + suffix, admin).statusCode(), suffix);
        }
        assertFalse(listedIds(http, admin).contains(a.instance().toString()));
    }

    @Test
    @Order(8)
    void theFreedNameContainerNameDirectoryAndPortsCanBeDeclaredAgain() throws Exception {
        Client http = client();

        ApplyItSupport.Declared again = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9101);

        assertTrue(!again.instance().equals(a.instance()), "ids are never reused");
        assertEquals("retired", lifecycle(a.instance()));
        assertEquals("active", lifecycle(again.instance()));
    }

    @Test
    @Order(9)
    void aCrashAfterStepThreeWithNoRetryLeavesItToTheSweepAndTheHalfWindowGuardProtectsStepFour() throws Exception {
        // B and E stop after step 3, together; their fate is decided at the end (tests 12 and 13)
        stopAfterStepThree(b.instance(), DeclareFixtures.OPERATOR_ONE);
        stopAfterStepThree(e.instance(), DeclareFixtures.OPERATOR_ONE);
        stoppedAfterStepThree = Instant.now();
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        assertEquals("retiring", lifecycle(b.instance()));
        assertFalse(controlPlaneRetired(b.instance()));
        assertEquals(404, http.get(path(b.instance()), admin).statusCode(), "a retiring instance is out of every read");
        assertFalse(listedIds(http, admin).contains(b.instance().toString()));
        assertEquals(404, http.get(path(b.instance()) + "/health", admin).statusCode());
    }

    @Test
    @Order(10)
    void anAuthorizedRetryResumesAndTheControlPlaneNamesTheRetryingPrincipal() throws Exception {
        Client http = client();
        stopAfterStepThree(c.instance(), DeclareFixtures.OPERATOR_ONE);
        Login admin = http.login(DeclareFixtures.ADMIN);

        HttpResponse<String> response = delete(path(c.instance()) + LEAVE, admin);

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("retired", lifecycle(c.instance()));
        assertEquals(1, retirementRecords(c.instance()), "the retry resumed the open record; it did not start another");
        assertEquals(principalId(DeclareFixtures.OPERATOR_ONE), record(c.instance()).get("requested_by"),
                "the data-plane record keeps the ORIGINAL requester");
        assertEquals(principalId(DeclareFixtures.ADMIN), ownerJdbc.queryForObject(
                "SELECT retired_by FROM sm_control.instance WHERE instance_id = :i", Map.of("i", c.instance()), UUID.class),
                "the control-plane write was made by the session that performed it");
        assertEquals(principalId(DeclareFixtures.ADMIN), ownerJdbc.queryForObject("SELECT actor_principal_id"
                + " FROM sm_control.change_audit WHERE audit_operation = 'INSTANCE_UNDECLARED'"
                + " AND row_key ->> 'instance_id' = :i", Map.of("i", c.instance().toString()), UUID.class));
        assertEquals(404, delete(path(c.instance()) + LEAVE, admin).statusCode());
    }

    @Test
    @Order(11)
    void aCrashAfterStepFourIsFinishedBySomeoneElsesListWithoutAnyControlPlaneWrite() throws Exception {
        Client http = client();
        stopAfterStepThree(d.instance(), DeclareFixtures.OPERATOR_ONE);
        assertTrue(controlPlane.deregister(new PrincipalRef(principalId(DeclareFixtures.ADMIN)), origin(),
                world.organization(), d.instance()), "step 4 by hand");
        assertEquals("retiring", lifecycle(d.instance()));
        long audit = controlPlaneAuditRows();
        long auditForD = undeclaredAuditRows(d.instance());

        List<String> seen = listedIds(http, http.login(DeclareFixtures.VIEWER));   // the sweep, under a VIEWER's session

        assertEquals("retired", lifecycle(d.instance()), "step 5 was finished by the sweep");
        assertEquals("retired", record(d.instance()).get("outcome"));
        assertEquals(0L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                + " AND instance_id = :i AND retired_at IS NULL", Map.of("t", world.organization(), "i", d.instance()),
                Long.class), "its placements are retired too");
        assertEquals(audit, controlPlaneAuditRows(), "the sweep wrote nothing in sm_control, and so no audit row under a Viewer");
        assertEquals(auditForD, undeclaredAuditRows(d.instance()));
        assertFalse(seen.contains(d.instance().toString()));
        assertEquals(404, delete(path(d.instance()) + LEAVE, http.login(DeclareFixtures.ADMIN)).statusCode());
    }

    @Test
    @Order(12)
    void aRecordOlderThanHalfTheWindowIs503AndTheControlPlaneIsNotWritten() throws Exception {
        sleepUntil(stoppedAfterStepThree.plus(WINDOW.dividedBy(2)).plusMillis(750));
        Client http = client();
        long audit = controlPlaneAuditRows();

        HttpResponse<String> response = delete(path(e.instance()) + LEAVE, http.login(DeclareFixtures.ADMIN));

        assertEquals(503, response.statusCode(), response.body());
        assertEquals("SERVICE_UNAVAILABLE", body(response).get("code").asText());
        assertFalse(controlPlaneRetired(e.instance()), "step 4 was not taken: a roll-back cannot now race it");
        assertEquals("retiring", lifecycle(e.instance()));
        assertEquals(audit, controlPlaneAuditRows());
    }

    @Test
    @Order(13)
    void afterTheWindowTheSweepRollsBackWhatNobodyFinishedAndWritesNothingInTheControlPlane() throws Exception {
        sleepUntil(stoppedAfterStepThree.plus(WINDOW).plusMillis(1500));
        Client http = client();
        long audit = controlPlaneAuditRows();

        List<String> seen = listedIds(http, http.login(DeclareFixtures.VIEWER));   // the sweep, under a VIEWER's session

        for (UUID rolledBack : List.of(b.instance(), e.instance())) {
            assertEquals("active", lifecycle(rolledBack));
            assertEquals("rolled-back", record(rolledBack).get("outcome"));
            assertTrue(seen.contains(rolledBack.toString()), "the instance reads again: the caller never got a 200");
            assertFalse(controlPlaneRetired(rolledBack));
            assertEquals(0, undeclaredAuditRows(rolledBack));
        }
        assertEquals(audit, controlPlaneAuditRows(), "no sm_control row, no change_audit row");
        assertNull(ownerJdbc.queryForObject("SELECT retired_by FROM sm_control.instance WHERE instance_id = :i",
                Map.of("i", b.instance()), UUID.class));
        // and it is a perfectly good instance again: it can be undeclared for real
        Client again = client();
        assertEquals(200, delete(path(b.instance()) + LEAVE, again.login(DeclareFixtures.OPERATOR_ONE)).statusCode());
        assertEquals("retired", lifecycle(b.instance()));
    }

    /**
     * W5 review W5B-4: with nobody listing or reading instances, a retry of the DELETE alone must get out of the 503 once the
     * record has outlived the whole window. The request itself runs the sweep for its organization (the record is rolled back,
     * with no control-plane write), begins again on a clean record and finishes.
     */
    @Test
    @Order(14)
    void aRetryOfTheDeleteAloneRollsAStaleRecordBackItselfAndUndeclaresForReal() throws Exception {
        Client http = client();
        ApplyItSupport.Declared f = ApplyItSupport.declare(http, world, "f-cache", "sm-f", 9106);
        stopAfterStepThree(f.instance(), DeclareFixtures.OPERATOR_ONE);
        Instant stopped = Instant.now();
        sleepUntil(stopped.plus(WINDOW).plusMillis(1500));
        // no list, no get and no declare from here on: only the DELETE itself can clear the record
        long audit = controlPlaneAuditRows();

        HttpResponse<String> response = delete(path(f.instance()) + LEAVE, http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("retired", lifecycle(f.instance()));
        assertTrue(controlPlaneRetired(f.instance()));
        assertEquals(2, retirementRecords(f.instance()), "the stale record was rolled back, then a clean one began");
        assertEquals(1L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND instance_id = :i AND placement_id IS NULL AND outcome = 'rolled-back'",
                Map.of("t", world.organization(), "i", f.instance()), Long.class));
        assertEquals("retired", record(f.instance()).get("outcome"));
        assertEquals(1, undeclaredAuditRows(f.instance()), "one control-plane release, audited under the retrying session");
        assertEquals(audit + 1, controlPlaneAuditRows(), "the roll-back wrote nothing in sm_control; the release wrote one row");
    }

    /**
     * OR-131 (W5 review W5B-5): a PENDING schedule refuses the undeclare (409 naming the kind), nothing on either plane moves,
     * and once the schedule is cancelled the undeclare succeeds. The concurrent-create ordering (schedule() takes the
     * apply_scope lock beginRetire holds, then re-checks the lifecycle) is pinned in ApplyScheduleServiceTest.
     */
    @Test
    @Order(15)
    void aPendingScheduleRefusesTheUndeclareAndAfterItIsCancelledTheUndeclareSucceeds() throws Exception {
        Client http = client();
        ApplyItSupport.Declared g = ApplyItSupport.declare(http, world, "g-cache", "sm-g", 9107);
        UUID schedule = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_data.apply_schedule (tenant_id, schedule_id, instance_id, placement_id, operation,"
                + " generation, strategy, not_before, not_after, requested_by, requested_at) VALUES (:t, :s, :i, :p, 'apply',"
                + " 1, 'manual', now() + interval '1 hour', now() + interval '2 hours', :by, now())",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("t", world.organization())
                        .addValue("s", schedule).addValue("i", g.instance()).addValue("p", g.placement())
                        .addValue("by", principalId(DeclareFixtures.OPERATOR_ONE)));
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long audit = controlPlaneAuditRows();

        HttpResponse<String> refused = delete(path(g.instance()) + LEAVE, operator);

        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("CONFLICT", body(refused).get("code").asText());
        assertTrue(body(refused).get("detail").asText().contains("scheduled apply"), refused.body());
        assertEquals("active", lifecycle(g.instance()), "nothing changed");
        assertEquals(0, retirementRecords(g.instance()));
        assertFalse(controlPlaneRetired(g.instance()));
        assertEquals(audit, controlPlaneAuditRows());

        assertEquals(200, delete(path(g.instance()) + "/apply-schedules/" + schedule, operator).statusCode());

        HttpResponse<String> done = delete(path(g.instance()) + LEAVE, operator);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals("retired", lifecycle(g.instance()));
    }
}
