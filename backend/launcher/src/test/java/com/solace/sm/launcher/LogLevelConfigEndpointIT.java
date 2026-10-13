package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T9.4 (OR-103, P2): {@code setLogLevel} with {@code mode: config} is SERVED in the production boot - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, with the real Cache package fixture (which declares
 * {@code logs.loggers}) and the durable worker. Only the host is local (an in-memory adapter). A level change is an
 * ordinary apply of a new {@code edit} generation whose SM-owned overlay carries {@code logging.level.<logger>}; it takes
 * effect at the next restart, and the ladder says so. State carries from test to test (ordered).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogLevelConfigEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String LOGGER = "com.solace.solutions.cache";
    private static final String LEVEL_PATH = "logging.level." + LOGGER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID instance;
    private static UUID placement;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static String path(UUID of, String suffix) {
        return "/api/dp/instances/" + of + suffix;
    }

    private static String levelPath(UUID of) {
        return path(of, "/log-levels/cache-engine");
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static ObjectNode change(UUID onPlacement, String level, String mode) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("placementId", onPlacement.toString());
        body.put("level", level);
        body.put("mode", mode);
        return body;
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource(Map.of("t", world.organization(), "i", instance));
    }

    private long generations() {
        return ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", key(), Long.class);
    }

    private String column(String column, long generation) {
        return ownerJdbc.queryForObject("SELECT " + column + "::text FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i AND generation = :g", key().addValue("g", generation), String.class);
    }

    /** The overlay level stored in a generation, or null. */
    private String storedLevel(long generation) {
        List<String> values = ownerJdbc.queryForList("SELECT e->>'value' FROM sm_data.config_generation g,"
                + " jsonb_array_elements(g.rendered_entries) e WHERE g.tenant_id = :t AND g.instance_id = :i"
                + " AND g.generation = :g AND e->>'instanceKey' = '@sm-overlay' AND e->>'renderedPath' = :p",
                key().addValue("g", generation).addValue("p", LEVEL_PATH), String.class);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * W2-01: a level change copies what is on the host, and refuses while a staged designer edit was never applied. So a
     * test that stages a generation and then changes a level applies the generation first, as an administrator.
     */
    private void applyManually(Client http, long generation) throws Exception {
        Login admin = http.login(DeclareFixtures.ADMIN);
        ObjectNode submit = MAPPER.createObjectNode();
        submit.put("generation", generation);
        submit.put("strategy", "manual");
        submit.put("placementId", placement.toString());
        HttpResponse<String> response = http.post(path(instance, "/apply"), admin, submit);
        assertEquals(202, response.statusCode(), response.body());
        ApplyItSupport.awaitTerminal(http, admin, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    private JsonNode ladder(Client http) throws Exception {
        HttpResponse<String> response = http.get(path(instance, "/config-ladder"),
                http.login(DeclareFixtures.OPERATOR_ONE));
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    private JsonNode read(Client http) throws Exception {
        HttpResponse<String> response = http.get(levelPath(instance), http.login(DeclareFixtures.OPERATOR_ONE));
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void setLogLevelIsReachableAndAnInstanceWithNothingStagedCannotCarryALevel() throws Exception {
        Client http = client();
        assertEquals(401, http.anonymous("POST", levelPath(UUID.randomUUID())).statusCode());
        ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "orders-cache", "sm-orders", 9201);
        instance = declared.instance();
        placement = declared.placement();

        HttpResponse<String> response = http.post(levelPath(instance), http.login(DeclareFixtures.ADMIN),
                change(placement, "DEBUG", "config"));

        assertEquals(422, response.statusCode(), response.body());
        assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        assertEquals(0, generations(), "nothing was minted");
        assertEquals(1, ApplyItSupport.stage(http, instance, declared.version(), "orders"));
        assertEquals(null, storedLevel(1), "the first stage carries the baseline overlay but no level");

        // W2-01: while that generation is staged and not applied, a level change is refused and mints nothing
        HttpResponse<String> unapplied = http.post(levelPath(instance), http.login(DeclareFixtures.ADMIN),
                change(placement, "DEBUG", "config"));
        assertEquals(409, unapplied.statusCode(), unapplied.body());
        assertEquals("CONFLICT", body(unapplied).get("code").asText());
        assertEquals(1, generations(), "nothing was minted");
        applyManually(http, 1);
    }

    @Test
    @Order(2)
    void aConfigChangeIsA202ApplyOfANewEditGenerationThatTakesEffectOnlyAfterARestart() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);

        HttpResponse<String> response = http.post(levelPath(instance), admin, change(placement, "DEBUG", "config"));

        assertEquals(202, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(2, accepted.size(), "stateVersion and applyId: " + accepted);
        assertEquals("/api/dp/instances/" + instance + "/apply/observe?applyId=" + accepted.get("applyId").asText(),
                response.headers().firstValue("Location").orElseThrow());
        assertEquals(2, generations());
        assertEquals("DEBUG", storedLevel(2));
        assertEquals("edit", column("origin", 2));
        assertEquals(null, storedLevel(1));

        JsonNode landed = ApplyItSupport.awaitTerminal(http, admin, instance, accepted.get("applyId").asText(),
                Duration.ofSeconds(20));
        assertEquals("apply", landed.get("operation").asText(), "an ordinary apply, observed like any other");
        assertEquals(2, landed.get("generation").asLong());
        assertEquals("STAGED", landed.get("state").asText(), "manual: the file is written and nothing restarts");
        JsonNode ladder = ladder(http);
        assertEquals("persisted", ladder.get("reached").asText(),
                "the file holds the level, but no restart is observed: the ladder claims nothing more");
        assertFalse(ladder.toString().contains("restart-observed\":true"), ladder.toString());
    }

    @Test
    @Order(3)
    void getReportsTheConfiguredLevelFromTheLatestGeneration() throws Exception {
        JsonNode level = read(client());

        assertEquals("DEBUG", level.get("configuredLevel").asText());
        assertEquals("cache-engine", level.get("component").asText());
        assertFalse(level.has("liveLevel"), "a direct-HTTP placement has no live read; the record is still known");
    }

    @Test
    @Order(4)
    void aDesignerEditKeepsTheConfiguredLevelAndANewLevelReplacesIt() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        long base = body(http.get(path(instance, "/designer"), http.login(DeclareFixtures.OPERATOR_ONE)))
                .get("stateVersion").asLong();

        long edited = ApplyItSupport.stage(http, instance, base, "billing");

        assertEquals(3, edited);
        assertEquals("DEBUG", storedLevel(3), "an unrelated edit does not drop the level");
        assertEquals("DEBUG", read(http).get("configuredLevel").asText());
        applyManually(http, 3);

        HttpResponse<String> response = http.post(levelPath(instance), admin, change(placement, "ERROR", "config"));
        assertEquals(202, response.statusCode(), response.body());
        assertEquals("ERROR", storedLevel(4));
        assertEquals("DEBUG", storedLevel(3), "history is immutable");
        assertEquals("ERROR", read(http).get("configuredLevel").asText());
        ApplyItSupport.awaitTerminal(http, admin, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    @Test
    @Order(5)
    void restoreRuntimeCarriesTheLevelUnchanged() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode restore = MAPPER.createObjectNode();
        restore.put("placementId", placement.toString());

        HttpResponse<String> response = http.post(path(instance, "/config/restore-runtime"), operator, restore);

        assertEquals(202, response.statusCode(), response.body());
        assertEquals("restore-runtime", column("origin", 5));
        assertEquals("ERROR", storedLevel(5));
        ApplyItSupport.awaitTerminal(http, operator, instance, body(response).get("applyId").asText(),
                Duration.ofSeconds(20));
    }

    @Test
    @Order(6)
    void aBusyScopeIs409AndMintsNothing() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        adapter.gate = new CountDownLatch(1);
        try {
            HttpResponse<String> first = http.post(levelPath(instance), admin, change(placement, "WARN", "config"));
            assertEquals(202, first.statusCode(), first.body());
            assertEquals(6, generations());

            HttpResponse<String> busy = http.post(levelPath(instance), admin, change(placement, "TRACE", "config"));

            assertEquals(409, busy.statusCode(), busy.body());
            assertEquals("CONFLICT", body(busy).get("code").asText());
            assertEquals(6, generations(), "the scope is checked BEFORE the copy: the refusal minted nothing");
            assertEquals("WARN", read(http).get("configuredLevel").asText());

            adapter.gate.countDown();
            ApplyItSupport.awaitTerminal(http, admin, instance, body(first).get("applyId").asText(),
                    Duration.ofSeconds(20));
        } finally {
            adapter.gate.countDown();
            adapter.gate = null;
        }
    }

    @Test
    @Order(7)
    void requiresLogsSetLevelAndAForeignCallerSeesA404AndLiveIsStillRefused() throws Exception {
        Client http = client();
        long before = generations();

        assertEquals(403, http.post(levelPath(instance), http.login(DeclareFixtures.VIEWER),
                change(placement, "DEBUG", "config")).statusCode(), "a viewer holds no logs.setLevel");
        for (String who : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            assertEquals(404, http.post(levelPath(instance), http.login(who), change(placement, "DEBUG", "config"))
                    .statusCode(), who);
        }
        HttpResponse<String> live = http.post(levelPath(instance), http.login(DeclareFixtures.ADMIN),
                change(placement, "DEBUG", "live"));
        assertTrue(live.statusCode() == 422 || live.statusCode() == 501, live.body());
        assertEquals(400, http.post(levelPath(instance), http.login(DeclareFixtures.ADMIN),
                change(placement, "LOUD", "config")).statusCode());
        assertEquals(before, generations(), "no refusal minted a generation");
    }
}
