package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T10.1 (OR-30, OR-45, OR-60): {@code POST …/config/secrets/{fieldKey}} is SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real
 * projector and stage validator, the real secret store and the real config of record. Nothing is faked.
 *
 * <p>State carries from test to test (ordered): the version a call hands back is the base of the next, and the stored
 * draft the secret is copied forward from is the previous call's.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigSecretEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String VALUE = "broker-password-sentinel-t101-5c8e";
    private static final String SECOND_VALUE = "rotated-password-sentinel-t101-77f0";
    private static final String FIELD = "solace.password";
    /** The container's raw request-bytes dump, enabled only by setting TRACE on this logger. */
    private static final String TOMCAT_WIRE_LOGGER = "org.apache.coyote.http11.Http11InputBuffer";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instance;
    private static long versionAfterStage;
    private static long versionAfterSecret;
    private static String firstReference;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RenderedConfigSource renderedConfigSource;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private static String stagePath() {
        return "/api/dp/instances/" + instance + "/config";
    }

    private static String secretPath(String fieldKey) {
        return "/api/dp/instances/" + instance + "/config/secrets/" + fieldKey;
    }

    private static ObjectNode secretBody(String value, long base) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("value", value).put("baseStateVersion", base);
        return body;
    }

    private static long count(String sql) {
        Long n = ownerJdbc.queryForObject(sql, Map.of("t", world.organization(), "i", instance), Long.class);
        return n == null ? 0 : n;
    }

    private static long configSecrets() {
        return count("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t AND purpose = 'CONFIG_FIELD'");
    }

    /** How many entries of a generation's stored rendered set sit under the overlay's reserved instance key. */
    private static long overlayEntries(long generation) {
        return count("SELECT count(*) FROM sm_data.config_generation g, jsonb_array_elements(g.rendered_entries) e"
                + " WHERE g.tenant_id = :t AND g.instance_id = :i AND g.generation = " + generation
                + " AND e->>'instanceKey' = '@sm-overlay'");
    }

    private static long generations() {
        return count("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i");
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    /** The cache-2.4 package with the broker enabled and its host set, and NO password yet. */
    private static ObjectNode stageBody(long base) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("namespace", "orders");
        draft.put("key-source", "TOPIC");
        draft.put("max-size", 1000);
        draft.put("solace.enabled", true);
        draft.put("solace.host", "broker.example.test:55555");
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("mode", "READ");
        rendered.put("provider", "caffeine");
        rendered.put("namespace", "orders");
        rendered.put("key-source", "TOPIC");
        rendered.put("max-size", 1000);
        rendered.put("solace.enabled", true);
        rendered.put("solace.host", "broker.example.test:55555");
        rendered.put("solace.messaging-mode", "MESSAGING");
        rendered.put("solace.management.enabled", false);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("baseStateVersion", base);
        ObjectNode selected = body.putObject("componentSelection").putArray("instances").addObject();
        selected.put("instanceKey", "i1").put("type", "cache-engine");
        selected.putObject("layout").put("x", 10).put("y", 20);
        body.putObject("draftValues").set("i1", MAPPER.valueToTree(draft));
        for (Map.Entry<String, Object> entry : rendered.entrySet()) {
            body.withArray("rendered").addObject().put("instanceKey", "i1")
                    .put("renderedPath", "cache-engine." + entry.getKey()).set("value", MAPPER.valueToTree(entry.getValue()));
        }
        return body;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void itIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertEquals(401, client().anonymous("POST",
                "/api/dp/instances/" + UUID.randomUUID() + "/config/secrets/" + FIELD).statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredAndOneGenerationStagedWithNoPasswordYet() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(body(loaded).get("solutionId").asText());
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 9101));
        assertEquals(201, declared.statusCode(), declared.body());
        instance = UUID.fromString(body(declared).get("instanceId").asText());

        HttpResponse<String> staged = http.post(stagePath(), operator,
                stageBody(body(declared).get("stateVersion").asLong()));

        assertEquals(200, staged.statusCode(), staged.body());
        versionAfterStage = body(staged).get("stateVersion").asLong();
        assertEquals(1, generations());
        assertEquals(0, configSecrets());
    }

    @Test
    @Order(3)
    void aNonSecretOrUndeclaredFieldIs404AndAnotherEnvironmentsOperatorOrAForeignAdministratorSeesNoInstance()
            throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        for (String field : List.of("namespace", "solace.host", "no.such.field")) {
            HttpResponse<String> response = http.post(secretPath(field), operator, secretBody(VALUE, versionAfterStage));
            assertEquals(404, response.statusCode(), field + ": " + response.body());
            assertEquals("NOT_FOUND", body(response).get("code").asText());
            assertFalse(response.body().contains(VALUE));
        }
        for (String user : List.of(DeclareFixtures.OPERATOR_TWO, DeclareFixtures.FOREIGN)) {
            HttpResponse<String> response = http.post(secretPath(FIELD), http.login(user),
                    secretBody(VALUE, versionAfterStage));
            assertEquals(404, response.statusCode(), user + ": " + response.body());
        }
        assertEquals(0, configSecrets(), "nothing was stored");
        assertEquals(1, generations());
    }

    @Test
    @Order(4)
    void requiresConfigEditSoAViewerIs403AndNothingIsStored() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(secretPath(FIELD), http.login(DeclareFixtures.VIEWER),
                secretBody(VALUE, versionAfterStage));

        assertEquals(403, response.statusCode(), response.body());
        assertFalse(response.body().contains(VALUE));
        assertEquals(0, configSecrets());
        assertEquals(1, generations());
    }

    @Test
    @Order(5)
    void aStaleBaseStateVersionIs409AndStoresNoSecret() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(secretPath(FIELD), http.login(DeclareFixtures.OPERATOR_ONE),
                secretBody(VALUE, versionAfterStage - 1));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertFalse(response.body().contains(VALUE));
        assertEquals(0, configSecrets(), "the secret row and the generation commit together or not at all");
        assertEquals(1, generations());
    }

    @Test
    @Order(6)
    void anUnusableValueIs422AndStoresNothing() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> empty = http.post(secretPath(FIELD), operator, secretBody("", versionAfterStage));
        HttpResponse<String> oversized = http.post(secretPath(FIELD), operator,
                secretBody("x".repeat(16385), versionAfterStage));

        for (HttpResponse<String> response : List.of(empty, oversized)) {
            assertEquals(422, response.statusCode(), response.body());
            assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
        }
        assertEquals(0, configSecrets());
        assertEquals(1, generations());
    }

    @Test
    @Order(7)
    void theValueIsStoredAndStagedInOneCallAndNeverComesBackOrIsLoggedOrLandsInAnyRow() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ListAppender<ILoggingEvent> log = new ListAppender<>();
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level original = root.getLevel();
        log.start();
        root.addAppender(log);
        root.setLevel(Level.TRACE);
        HttpResponse<String> response;
        try {
            response = http.post(secretPath(FIELD), operator, secretBody(VALUE, versionAfterStage));
        } finally {
            root.detachAppender(log);
            root.setLevel(original);
            log.stop();
        }

        assertEquals(200, response.statusCode(), response.body());
        JsonNode staged = body(response);
        List<String> names = new ArrayList<>();
        staged.fieldNames().forEachRemaining(names::add);
        assertEquals(List.of("generation", "stateVersion"), names.stream().sorted().toList(),
                "a plain StagedConfig: no unchanged flag, no value, no reference");
        assertEquals(2, staged.get("generation").asLong());
        versionAfterSecret = staged.get("stateVersion").asLong();
        assertTrue(versionAfterSecret > versionAfterStage);
        assertFalse(response.body().contains(VALUE));
        for (ILoggingEvent event : log.list) {
            if (event.getLoggerName().startsWith(TOMCAT_WIRE_LOGGER)) {
                // Tomcat's own raw-bytes dump (`Received [...]`), opt-in at TRACE for the container's logger and not
                // written by any code of ours. The same bytes carry every login credential. See the plan's Results.
                continue;
            }
            assertFalse(event.getFormattedMessage().contains(VALUE), event.getLoggerName());
            assertTrue(event.getThrowableProxy() == null || event.getThrowableProxy().getMessage() == null
                    || !event.getThrowableProxy().getMessage().contains(VALUE), event.getLoggerName());
        }
        assertTrue(log.list.stream().anyMatch(e -> e.getLoggerName().startsWith("org.springframework.web")),
                "the capture was live: the framework logged at TRACE while the call ran");

        assertEquals(1, configSecrets());
        assertEquals(2, generations());
        firstReference = ownerJdbc.queryForObject("SELECT secret_id::text FROM sm_secret.secret WHERE tenant_id = :t"
                + " AND purpose = 'CONFIG_FIELD'", Map.of("t", world.organization()), String.class);
        String stored = ownerJdbc.queryForObject("SELECT draft_values::text || rendered_entries::text FROM"
                + " sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i AND generation = 2",
                Map.of("t", world.organization(), "i", instance), String.class);
        assertTrue(stored.contains("secretRef://" + firstReference), "the draft names the reference: " + stored);
        assertTrue(stored.contains("\"secretRef\": \"" + firstReference + "\"")
                || stored.contains("\"secretRef\":\"" + firstReference + "\""), "the rendered set names it: " + stored);
        assertTrue(stored.contains("broker.example.test:55555"), "the rest of the draft was copied forward");
        // T9.3 x T10.1: a secret entered through the console re-stages the draft, and the stage is where the SM-owned overlay
        // enters a generation, so the new generation keeps the overlay generation 1 had (the loggers endpoint stays exposed).
        assertTrue(overlayEntries(1) > 0, "generation 1 carries the overlay the Cache package declares");
        assertEquals(overlayEntries(1), overlayEntries(2), "a secret set must not drop SM's overlay from the new generation");

        List<String> hits = new ArrayList<>();
        for (Map<String, Object> column : ownerJdbc.queryForList(
                "SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema IN ('sm_data', 'sm_secret', 'sm_control')", Map.of())) {
            String qualified = column.get("table_schema") + "." + column.get("table_name");
            String name = "\"" + column.get("column_name") + "\"";
            String expression = "bytea".equals(column.get("data_type"))
                    ? "encode(" + name + ", 'escape')" : name + "::text";
            Long found = ownerJdbc.queryForObject("SELECT count(*) FROM " + qualified + " WHERE " + expression
                    + " LIKE :needle", Map.of("needle", "%" + VALUE + "%"), Long.class);
            if (found != null && found > 0) {
                hits.add(qualified + "." + column.get("column_name"));
            }
        }
        assertEquals(List.of(), hits, "the plaintext is in no column of any table");
        assertEquals(List.of("CONFIG_FIELD", "CONFIG_HASH_KEY"), ownerJdbc.queryForList(
                "SELECT purpose FROM sm_secret.secret WHERE tenant_id = :t ORDER BY purpose",
                Map.of("t", world.organization()), String.class),
                "the new row is a config-field secret, beside the tenant's keyed-hash key row");
    }

    @Test
    @Order(8)
    void theDesignerAndTheHistoryShowOnlyThatTheSecretIsSetAndTheLayoutIsUntouched() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> designer = http.get("/api/dp/instances/" + instance + "/designer", operator);
        HttpResponse<String> history = http.get("/api/dp/instances/" + instance + "/config/history", operator);

        assertEquals(200, designer.statusCode(), designer.body());
        JsonNode view = body(designer);
        assertEquals(versionAfterSecret, view.get("stateVersion").asLong());
        assertEquals(2, view.get("generation").asLong());
        JsonNode password = null;
        for (JsonNode field : view.at("/viewModel/components/0/sections").findParents("key")) {
            if (field.get("key").asText().equals(FIELD)) {
                password = field;
            }
        }
        assertTrue(password != null, "the password field is present");
        assertEquals("secretRef", password.at("/value/kind").asText());
        assertEquals("secretRef://" + firstReference, password.at("/value/ref").asText());
        assertFalse(designer.body().contains(VALUE));
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":10,\"y\":20}}"), view.get("layout"),
                "a secret is not a layout change (OR-96)");
        assertFalse(history.body().contains(VALUE));
        assertFalse(history.body().contains(firstReference), "history shows no value and no reference");
        assertEquals(List.of(2L, 1L), longs(body(history).get("entries")));
    }

    @Test
    @Order(9)
    void theStagedReferenceRendersTheValueIntoTheFileAtApplyTimeAndNeverTheReference() throws Exception {
        PlacementRef placement = new PlacementRef(new TenantId(world.organization()), new InstanceId(instance),
                new PlacementId(UUID.randomUUID()));

        RenderedConfig file = renderedConfigSource.render(placement, 2);

        String yaml = new String(file.bytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains(VALUE), "the reference is resolved when the file is written");
        assertFalse(yaml.contains("secretRef"));
        assertTrue(file.hash().startsWith("hmacsha256v"), "the hash is keyed (OR-32): " + file.hash());
    }

    @Test
    @Order(10)
    void settingItAgainStoresANewRowStagesANewGenerationAndKeepsTheOldRowForRotation() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(secretPath(FIELD), http.login(DeclareFixtures.OPERATOR_ONE),
                secretBody(SECOND_VALUE, versionAfterSecret));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(3, body(response).get("generation").asLong());
        assertEquals(2, configSecrets(), "the old row is kept (OR-85 OQ-4); a value is write-once");
        String stored = ownerJdbc.queryForObject("SELECT draft_values::text FROM sm_data.config_generation WHERE"
                + " tenant_id = :t AND instance_id = :i AND generation = 3",
                Map.of("t", world.organization(), "i", instance), String.class);
        assertFalse(stored.contains(firstReference), "generation 3 names the NEW reference only");
        assertFalse(response.body().contains(SECOND_VALUE));
        PlacementRef placement = new PlacementRef(new TenantId(world.organization()), new InstanceId(instance),
                new PlacementId(UUID.randomUUID()));
        String yaml = new String(renderedConfigSource.render(placement, 3).bytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains(SECOND_VALUE) && !yaml.contains(VALUE), "apply writes the rotated value");
    }

    private static List<Long> longs(JsonNode array) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("generation").asLong()));
        return out;
    }
}
