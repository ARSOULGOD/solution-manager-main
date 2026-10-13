package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.config.RollbackTargetRows;
import com.solace.sm.dp.config.RollbackTargets;
import com.solace.sm.dp.schedule.ApplyScheduleProperties;
import com.solace.sm.dp.schedule.ApplyScheduleServiceImpl;
import com.solace.sm.dp.schedule.ScheduleRows;
import com.solace.sm.dp.schedule.ScheduleTargets;
import com.solace.sm.gateway.http.ScheduleFirer;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 012 T7 - scheduled applies and rollbacks ({@code OPS-Q9} Sch-2, {@code OPS-Q10} (c), {@code OR-121}/{@code OR-122} (2)):
 * the real {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, orchestration,
 * {@code apply_schedule}, ordinary apply admission and durable worker. Only the host is local (an in-memory adapter).
 *
 * <h2>The fake clock</h2>
 * The scheduled-apply window is the FIRER's to open: a schedule is submitted through HTTP with a window an hour ahead of the
 * real clock (the data plane validates "in the future, within max-lead" at submit), and the test then drives time with a
 * {@link TestClock} handed to a {@code ScheduleFirer} it builds over the context's own port and authorization service. The
 * context's own firer is withdrawn ({@code sm.gateway.schedule.enabled=false}) so no second pass races the test. The data plane
 * reads ITS OWN clock after it holds the row's lock (W3 review W3B-1: {@code admit} takes no reading), so the firer is handed
 * a second {@code ApplyScheduleServiceImpl} built from the context's own beans and the SAME {@link TestClock}: one clock
 * decides every window check, on the firer's side and on the data plane's. (Only the fire path uses it; submit, list and
 * cancel go through the context's port on the real clock.)
 *
 * <p>W1-W6 are {@code ops-501.md} section 3's scenarios. State carries from test to test (ordered): the generations an earlier
 * test staged are what a later one schedules.
 *
 * <p>Ran green on {@code postgres:14}, 9 of 9, in the combined W3+W4 full build (2026-10-08). Because the hosts' placements here come from the declare
 * endpoint, no IT in this class needs plan 008 S4's {@code registered(scope)} fixture; {@code ApplyScheduleSchemaIT} and
 * {@code ApplyScheduleServiceIT} in {@code dp-core} do (they use {@code InventoryFixtures.declare/target/placement}).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApplyScheduleEndpointsIT {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final String W4_OPERATOR = "declare-it-operator-w4";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @Autowired
    private ScheduleRows scheduleRows;

    @Autowired
    private ScheduleTargets scheduleTargets;

    @Autowired
    private ApplyService applyService;

    @Autowired
    private RollbackTargetRows rollbackTargetRows;

    @Autowired
    private ApplyScheduleProperties scheduleProperties;

    @Autowired
    @Qualifier("smDataTransactions")
    private TransactionTemplate smDataTransactions;

    @Autowired
    private AuthorizationService authorization;

    @Autowired
    private ScheduleFirer contextFirer;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        registry.add("sm.gateway.schedule.enabled", () -> "false");
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    // ---------------------------------------------------------------------------------------------- the clock

    /** The firer's time, moved by the test. */
    static final class TestClock extends Clock {
        private volatile Instant now;

        TestClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    /** A window an hour ahead of the REAL clock, two hours wide: what the data plane's submit-time check accepts. */
    private record Window(Instant opens, Instant closes) {
        static Window ahead() {
            Instant opens = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
            return new Window(opens, opens.plus(2, ChronoUnit.HOURS));
        }

        ObjectNode json() {
            ObjectNode window = MAPPER.createObjectNode();
            window.put("notBefore", opens.toString()).put("notAfter", closes.toString());
            return window;
        }

        Instant before() {
            return opens.minus(5, ChronoUnit.MINUTES);
        }

        Instant inside() {
            return opens.plusSeconds(30);
        }

        Instant after() {
            return closes.plusSeconds(1);
        }
    }

    private final TestClock clock = new TestClock(Instant.now());

    private ScheduleFirer firer() {
        ApplySchedulePort timed = new ApplyScheduleServiceImpl(smDataTransactions, scheduleRows, scheduleTargets, applyService,
                new RollbackTargets(rollbackTargetRows), scheduleProperties, clock, UUID::randomUUID);
        return new ScheduleFirer(timed, authorization, clock, true, Duration.ofSeconds(1));
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Client client() {
        return new Client(port);
    }

    private static String path(String suffix) {
        return "/api/dp/instances/" + instance + suffix;
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> delete(String path, Login login) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ObjectNode applyBody(long generation, String strategy) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation).put("strategy", strategy).put("placementId", placement.toString());
        return body;
    }

    private static ObjectNode scheduledApply(long generation, Window window) {
        ObjectNode body = applyBody(generation, "manual");
        body.set("schedule", window.json());
        return body;
    }

    private static ObjectNode scheduledRollback(long generation, Window window) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("generation", generation).put("strategy", "manual").put("placementId", placement.toString());
        body.set("schedule", window.json());
        return body;
    }

    private Map<String, Object> key() {
        return Map.of("t", world.organization(), "i", instance);
    }

    private long generations() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long requests() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long schedules() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.apply_schedule WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private long version(Client http, Login login) throws Exception {
        return body(http.get(path("/designer"), login)).get("stateVersion").asLong();
    }

    private long stage(Client http, String namespace) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        return ApplyItSupport.stage(http, instance, version(http, operator), namespace);
    }

    /** An immediate apply (the hand fix), awaited to its terminal state. */
    private JsonNode applyNow(Client http, long generation) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> response = http.post(path("/apply"), operator, applyBody(generation, "manual"));
        assertEquals(202, response.statusCode(), response.body());
        return ApplyItSupport.awaitTerminal(http, operator, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    private UUID submit(Client http, Login login, ObjectNode body) throws Exception {
        return submit(http, login, "/apply", body);
    }

    private UUID submit(Client http, Login login, String route, ObjectNode body) throws Exception {
        HttpResponse<String> response = http.post(path(route), login, body);
        assertEquals(202, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(2, accepted.size(), "stateVersion and scheduleId, and no applyId (nothing is admitted): " + accepted);
        assertFalse(accepted.has("applyId"), response.body());
        assertEquals("/api/dp/instances/" + instance + "/apply-schedules",
                response.headers().firstValue("Location").orElseThrow());
        return UUID.fromString(accepted.get("scheduleId").asText());
    }

    private JsonNode scheduleOf(Client http, Login login, UUID schedule) throws Exception {
        HttpResponse<String> listing = http.get(path("/apply-schedules"), login);
        assertEquals(200, listing.statusCode(), listing.body());
        for (JsonNode item : body(listing).get("items")) {
            if (item.get("scheduleId").asText().equals(schedule.toString())) {
                return item;
            }
        }
        throw new AssertionError("schedule " + schedule + " is not in the list: " + listing.body());
    }

    private String dbState(UUID schedule) {
        return ownerJdbc.queryForObject("SELECT state FROM sm_data.apply_schedule WHERE tenant_id = :t"
                + " AND schedule_id = :s", new MapSqlParameterSource(key()).addValue("s", schedule), String.class);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void theScheduleRoutesAreReachableInTheProductionBootAndTheContextsOwnFirerIsWithdrawn() throws Exception {
        UUID any = UUID.randomUUID();

        assertEquals(401, client().anonymous("GET", "/api/dp/instances/" + any + "/apply-schedules").statusCode(),
                "a mapped, authenticated route: 401 with no session, not the 404 of an unserved path");
        assertEquals(401, client().anonymous("DELETE", "/api/dp/instances/" + any + "/apply-schedules/" + any)
                .statusCode());
        assertFalse(contextFirer.isRunning(), "sm.gateway.schedule.enabled=false withdraws the loop, as the test needs");
    }

    @Test
    @Order(2)
    void anInstanceHasGenerationOneAppliedAsTheBaseline() throws Exception {
        Client http = client();
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders-sched", 9301);
        instance = declared.instance();
        placement = declared.placement();
        assertEquals(1, ApplyItSupport.stage(http, instance, declared.version(), "orders"));
        assertEquals("STAGED", applyNow(http, 1).get("state").asText());
        assertEquals(1, requests());
    }

    @Test
    @Order(3)
    void aBadWindowAMissingVerbAndAForeignCallerAreRefusedAndNothingIsScheduled() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Window ok = Window.ahead();

        ObjectNode past = scheduledApply(1, ok);
        ((ObjectNode) past.get("schedule")).put("notBefore", Instant.now().minusSeconds(60).toString());
        HttpResponse<String> pastResponse = http.post(path("/apply"), operator, past);
        assertEquals(422, pastResponse.statusCode(), pastResponse.body());
        assertEquals("VALIDATION_FAILED", body(pastResponse).get("code").asText());
        assertTrue(body(pastResponse).get("detail").asText().contains("future"), pastResponse.body());

        Instant far = Instant.now().plus(8, ChronoUnit.DAYS);
        ObjectNode tooFar = scheduledApply(1, new Window(far, far.plus(1, ChronoUnit.HOURS)));
        HttpResponse<String> farResponse = http.post(path("/apply"), operator, tooFar);
        assertEquals(422, farResponse.statusCode(), "max lead is 7 days: " + farResponse.body());
        assertTrue(body(farResponse).get("detail").asText().contains("ahead"), farResponse.body());

        // W3 review W3B-4: the window's far end is bounded by the same 7 days, and an absurd instant is a 422, not a 500
        Instant soon = Instant.now().plus(1, ChronoUnit.HOURS);
        HttpResponse<String> lateEnd = http.post(path("/apply"), operator,
                scheduledApply(1, new Window(soon, Instant.now().plus(8, ChronoUnit.DAYS))));
        assertEquals(422, lateEnd.statusCode(), "notAfter beyond the 7-day horizon: " + lateEnd.body());
        assertTrue(body(lateEnd).get("detail").asText().contains("notAfter"), lateEnd.body());
        ObjectNode absurd = scheduledApply(1, ok);
        ((ObjectNode) absurd.get("schedule")).put("notAfter", "9999-12-31T23:59:59Z");
        assertEquals(422, http.post(path("/apply"), operator, absurd).statusCode());

        ObjectNode backwards = scheduledApply(1, ok);
        ((ObjectNode) backwards.get("schedule")).put("notAfter", ok.opens().toString());
        assertEquals(400, http.post(path("/apply"), operator, backwards).statusCode(), "notAfter must be after notBefore");
        ObjectNode explicitNull = applyBody(1, "manual");
        explicitNull.putNull("schedule");
        assertEquals(400, http.post(path("/apply"), operator, explicitNull).statusCode(),
                "an explicit null is not 'no schedule'");

        HttpResponse<String> token = http.post(path("/apply"), operator, scheduledApply(1, ok).put("strategy", "scheduled"));
        assertEquals(422, token.statusCode(), "the token is not a strategy: " + token.body());
        assertTrue(body(token).get("detail").asText().contains("schedule"), token.body());

        assertEquals(422, http.post(path("/apply"), operator, scheduledApply(99, ok)).statusCode(),
                "a generation that was never staged is refused NOW, not at 02:00");
        assertEquals(409, http.post(path("/apply/rollback"), operator, scheduledRollback(77, ok)).statusCode(),
                "a rollback target that was never written to the placement is refused NOW");
        assertEquals(404, http.post(path("/apply"), operator,
                scheduledApply(1, ok).put("placementId", UUID.randomUUID().toString())).statusCode());

        Login viewer = http.login(DeclareFixtures.VIEWER);
        assertEquals(403, http.post(path("/apply"), viewer, scheduledApply(1, ok)).statusCode(),
                "a viewer holds config.view only: scheduling is config.apply");
        assertEquals(200, http.get(path("/apply-schedules"), viewer).statusCode(), "but listing is config.view");
        Login foreign = http.login(DeclareFixtures.FOREIGN);
        assertEquals(404, http.get(path("/apply-schedules"), foreign).statusCode(), "another organization's instance is absent");
        assertEquals(404, http.post(path("/apply"), foreign, scheduledApply(1, ok)).statusCode());

        assertEquals(0, schedules());
        assertEquals(1, requests(), "and nothing was admitted either");
    }

    @Test
    @Order(4)
    void w1_theNormalCase_aScheduleWaitsAdmitsInsideItsWindowAndRunsOnce() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long generation = stage(http, "billing");
        assertEquals(2, generation);
        Window window = Window.ahead();
        long requestsBefore = requests();
        long generationsBefore = generations();

        UUID schedule = submit(http, operator, scheduledApply(generation, window));

        assertEquals("PENDING", dbState(schedule));
        assertEquals(requestsBefore, requests(), "14:00: nothing is admitted and no fence is held");
        JsonNode pending = scheduleOf(http, operator, schedule);
        assertEquals("PENDING", pending.get("state").asText());
        assertEquals(window.opens().toString(), pending.get("notBefore").asText());
        assertEquals(generation, pending.get("generation").asLong());
        assertFalse(pending.has("applyId") || pending.has("requestedBy") || pending.has("outageConfirmedBy"), pending.toString());

        clock.set(window.before());
        firer().fireDue();
        assertEquals("PENDING", dbState(schedule), "before the window opens nothing happens");
        assertEquals(requestsBefore, requests());

        clock.set(window.inside());
        firer().fireDue();

        assertEquals("ADMITTED", dbState(schedule));
        JsonNode admitted = scheduleOf(http, operator, schedule);
        String applyId = admitted.get("applyId").asText();
        assertEquals(generation, admitted.get("admittedGeneration").asLong());
        assertEquals(requestsBefore + 1, requests(), "exactly one ordinary apply");
        assertEquals(generationsBefore, generations(), "an apply mints no generation");
        assertEquals(principalId(DeclareFixtures.OPERATOR_ONE), actorOf(applyId),
                "the admitted apply's actor is the SUBMITTER");
        assertEquals("STAGED", ApplyItSupport.awaitTerminal(http, operator, instance, applyId, Duration.ofSeconds(20))
                .get("state").asText());

        firer().fireDue();
        clock.set(window.after());
        firer().fireDue();
        assertEquals(requestsBefore + 1, requests(), "a schedule that was admitted never runs again");
        assertEquals("ADMITTED", dbState(schedule));

        HttpResponse<String> late = delete(path("/apply-schedules/" + schedule), operator);
        assertEquals(409, late.statusCode(), "cancel after admission is 409: " + late.body());
        assertEquals("CONFLICT", body(late).get("code").asText());
    }

    @Test
    @Order(5)
    void w2_cancelAt2200_aCancelledScheduleNeverRunsAndAnyApplyHolderMayCancel() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long generation = stage(http, "w2");
        Window window = Window.ahead();
        long requestsBefore = requests();

        UUID schedule = submit(http, operator, scheduledApply(generation, window));
        HttpResponse<String> cancelled = delete(path("/apply-schedules/" + schedule), operator);

        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", body(cancelled).get("state").asText());
        assertEquals("CANCELLED", dbState(schedule));
        clock.set(window.inside());
        firer().fireDue();
        assertEquals(requestsBefore, requests(), "a cancelled schedule is not admitted when its window opens");
        assertEquals(409, delete(path("/apply-schedules/" + schedule), operator).statusCode(), "CANCELLED is not PENDING");
        assertEquals(404, delete(path("/apply-schedules/" + UUID.randomUUID()), operator).statusCode());

        // any config.apply holder, not only the submitter: the administrator cancels the operator's schedule
        Login admin = http.login(DeclareFixtures.ADMIN);
        UUID second = submit(http, operator, scheduledApply(generation, Window.ahead()));
        assertEquals(200, delete(path("/apply-schedules/" + second), admin).statusCode());
        assertEquals(principalId(DeclareFixtures.ADMIN), ownerJdbc.queryForObject(
                "SELECT cancelled_by::text FROM sm_data.apply_schedule WHERE tenant_id = :t AND schedule_id = :s",
                new MapSqlParameterSource(key()).addValue("s", second), String.class),
                "the canceller is the session's principal, recorded");
        assertEquals(403, delete(path("/apply-schedules/" + second), http.login(DeclareFixtures.VIEWER)).statusCode(),
                "a viewer cannot cancel");
    }

    @Test
    @Order(6)
    void w3_smWasDownThroughTheWindow_theScheduleIsMissedAndNeverRunsLate() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Window window = Window.ahead();
        long requestsBefore = requests();
        UUID schedule = submit(http, operator, scheduledApply(3, window));

        clock.set(window.after());
        firer().fireDue();

        assertEquals("MISSED", dbState(schedule));
        JsonNode missed = scheduleOf(http, operator, schedule);
        assertEquals("schedule.window.missed", missed.get("reason").asText());
        assertEquals(requestsBefore, requests(), "04:30 is outside 02:00-04:00: it never runs");
        assertEquals(409, delete(path("/apply-schedules/" + schedule), operator).statusCode());
    }

    @Test
    @Order(7)
    void w4_theSubmitterIsDeactivatedBeforeTheWindow_theScheduleIsRefusedAndNothingRuns() throws Exception {
        Client http = client();
        UUID submitter = DeclareFixtures.principal(ownerJdbc, world.organization(), W4_OPERATOR);
        UUID envScope = ownerJdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'environment'"
                + " AND environment_id = :e", Map.of("e", world.envOne()), UUID.class);
        DeclareFixtures.bind(ownerJdbc, submitter, "Operator", envScope);
        Login w4 = http.login(W4_OPERATOR);
        Window window = Window.ahead();
        long requestsBefore = requests();
        UUID schedule = submit(http, w4, scheduledApply(3, window));

        // 18:00: the submitter is deactivated
        ownerJdbc.update("UPDATE sm_control.principal SET status = 'deactivated', deactivated_at = now() WHERE id = :id",
                Map.of("id", submitter));
        clock.set(window.inside());
        firer().fireDue();

        assertEquals("REFUSED", dbState(schedule));
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        assertEquals("schedule.authority.revoked", scheduleOf(http, operator, schedule).get("reason").asText());
        assertEquals(requestsBefore, requests(), "authorized at submit only would have run it; re-authorized at fire does not");
    }

    @Test
    @Order(8)
    void w5_aHandFixLandsFirst_theScheduleIsRefusedByTheMonotonicityFence() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Window window = Window.ahead();
        UUID schedule = submit(http, operator, scheduledApply(3, window));

        // 23:00: an urgent fix, a NEWER generation, is staged and applied by hand - possible because nothing is held
        long fix = stage(http, "hand-fix");
        assertEquals(4, fix);
        assertEquals("STAGED", applyNow(http, fix).get("state").asText());
        long requestsBefore = requests();

        clock.set(window.inside());
        firer().fireDue();

        assertEquals("REFUSED", dbState(schedule));
        assertEquals("schedule.generation.superseded", scheduleOf(http, operator, schedule).get("reason").asText());
        assertEquals(requestsBefore, requests(), "the stale generation was not applied over the fix");
    }

    @Test
    @Order(9)
    void w6_aScheduledRollbackCopiesForwardAtFireAboveWhatWasStagedMeanwhile() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Window window = Window.ahead();
        long generationsAtSubmit = generations();
        assertEquals(4, generationsAtSubmit);

        UUID schedule = submit(http, operator, "/apply/rollback", scheduledRollback(1, window));

        assertEquals(generationsAtSubmit, generations(),
                "14:00: the copy-forward is NOT made at submit - the old content is not the desired head twelve hours early");
        // 20:00: a stage of generation 5 lands between submit and fire
        long staged = stage(http, "later");
        assertEquals(5, staged);
        long requestsBefore = requests();

        clock.set(window.inside());
        firer().fireDue();

        assertEquals("ADMITTED", dbState(schedule), scheduleOf(http, operator, schedule).toString());
        JsonNode admitted = scheduleOf(http, operator, schedule);
        assertEquals(1, admitted.get("targetGeneration").asLong(), "the target resolved AT FIRE");
        assertEquals(6, admitted.get("admittedGeneration").asLong(), "minted above the operator's generation 5");
        assertEquals(6, generations());
        assertEquals(requestsBefore + 1, requests());
        assertEquals(6L, ApplyItSupport.awaitTerminal(http, operator, instance, admitted.get("applyId").asText(),
                Duration.ofSeconds(20)).get("generation").asLong());
        assertNotEquals(5L, admitted.get("admittedGeneration").asLong());
        assertTrue(sameContent(1, 6), "generation 6 carries generation 1's content");
    }

    private boolean sameContent(long a, long b) {
        return ownerJdbc.queryForObject("SELECT x.component_selection = y.component_selection"
                + " AND x.draft_values = y.draft_values AND x.rendered_entries = y.rendered_entries"
                + " FROM sm_data.config_generation x, sm_data.config_generation y"
                + " WHERE x.tenant_id = :t AND x.instance_id = :i AND x.generation = :a"
                + " AND y.tenant_id = :t AND y.instance_id = :i AND y.generation = :b",
                new MapSqlParameterSource(key()).addValue("a", a).addValue("b", b), Boolean.class);
    }

    private String actorOf(String applyId) {
        return ownerJdbc.queryForObject("SELECT requested_by FROM sm_data.apply_request WHERE tenant_id = :t"
                + " AND apply_id = :a", new MapSqlParameterSource("t", world.organization())
                .addValue("a", UUID.fromString(applyId)), String.class);
    }

    private String principalId(String username) {
        return ownerJdbc.queryForObject("SELECT id::text FROM sm_control.principal WHERE organization_id = :o"
                + " AND username = :u", Map.of("o", world.organization(), "u", username), String.class);
    }
}
