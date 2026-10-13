package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretStore;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
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
 * Plan 010 T5.3: {@code getDesigner}, {@code stageConfig}, {@code getConfigHistory} and {@code getConfigLadder} are
 * SERVED - the real {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor,
 * the real projector and stage validator, the real config of record and the real secret store. Nothing is faked.
 *
 * <p>State carries from test to test (ordered): a stage's effect on the NEXT request - the version it hands back, the
 * generation the designer rebuilds from - is exactly what is under test.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DesignerEndpointsIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final String PLAINTEXT = "plaintext-sentinel-9f2c1d";
    private static final String LITERAL = "literal-sentinel-77ab";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instance;
    private static UUID fixtureInstance;
    private static long versionBeforeFirstStage;
    private static long versionAfterFirstStage;
    private static long versionAfterSecondStage;
    private static UUID configFieldSecret;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private SecretStore secretStore;

    @Autowired
    private ConfigRecordService configRecords;

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

    private static String path(UUID id, String suffix) {
        return "/api/dp/instances/" + id + suffix;
    }

    private JsonNode body(HttpResponse<String> response) throws IOException {
        return MAPPER.readTree(response.body());
    }

    // ------------------------------------------------------------------------------------------- the stage body

    /** A selection of one cache-engine instance {@code i1}, and the draft values the operator typed. */
    private static ObjectNode stageBody(long base, Map<String, Object> draft, Map<String, Object> rendered) {
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
        if (!body.has("rendered")) {
            body.putArray("rendered");
        }
        return body;
    }

    /** What the cache-2.4 package renders for namespace/key-source/max-size with every default applied. */
    private static Map<String, Object> basicRendered(String namespace) {
        Map<String, Object> rendered = new java.util.LinkedHashMap<>();
        rendered.put("mode", "READ");
        rendered.put("provider", "caffeine");
        rendered.put("namespace", namespace);
        rendered.put("key-source", "TOPIC");
        rendered.put("max-size", 1000);
        rendered.put("solace.enabled", false);
        return rendered;
    }

    private static Map<String, Object> basicDraft(String namespace) {
        Map<String, Object> draft = new java.util.LinkedHashMap<>();
        draft.put("namespace", namespace);
        draft.put("key-source", "TOPIC");
        draft.put("max-size", 1000);
        return draft;
    }

    private long generations() {
        return count("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i");
    }

    private static long count(String sql) {
        Long n = ownerJdbc.queryForObject(sql, Map.of("t", world.organization(), "i", instance), Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    @Order(1)
    void allFourAreReachableInTheProductionBootAndRefuseNoSession() throws Exception {
        Client http = client();
        UUID any = UUID.randomUUID();
        assertEquals(401, http.anonymous("GET", path(any, "/designer")).statusCode());
        assertEquals(401, http.anonymous("POST", path(any, "/config")).statusCode());
        assertEquals(401, http.anonymous("GET", path(any, "/config/history")).statusCode());
        assertEquals(401, http.anonymous("GET", path(any, "/config-ladder")).statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredToDesign() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        Login admin = http.login(DeclareFixtures.ADMIN);
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", admin, load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(body(loaded).get("solutionId").asText());

        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 9101));
        assertEquals(201, declared.statusCode(), declared.body());
        instance = UUID.fromString(body(declared).get("instanceId").asText());
        versionBeforeFirstStage = body(declared).get("stateVersion").asLong();
    }

    @Test
    @Order(3)
    void aFreshInstanceHasAnEmptyDesignerAtTheInstancesStateVersion() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.get(path(instance, "/designer"), http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode designer = body(response);
        assertEquals(versionBeforeFirstStage, designer.get("stateVersion").asLong());
        assertTrue(designer.has("generation") && designer.get("generation").isNull(), designer.toString());
        assertEquals(2, designer.at("/viewModel/engineVersion").asInt());
        assertEquals("cache", designer.at("/viewModel/solution").asText());
        assertEquals(0, designer.at("/viewModel/components").size());
        assertEquals("VERSION_UNVERIFIED", designer.at("/viewModel/status/version/state").asText(),
                "SM reads no version from a dp-remote MI (I1)");
        assertFalse(designer.at("/viewModel/status/version/editingBlocked").asBoolean());
    }

    @Test
    @Order(4)
    void aSiblingEnvironmentsOperatorAndAForeignAdministratorSeeNoneOfItAs404() throws Exception {
        Client http = client();
        Login sibling = http.login(DeclareFixtures.OPERATOR_TWO);
        Login foreign = http.login(DeclareFixtures.FOREIGN);

        for (Login caller : List.of(sibling, foreign)) {
            assertEquals(404, http.get(path(instance, "/designer"), caller).statusCode());
            assertEquals(404, http.get(path(instance, "/config/history"), caller).statusCode());
            assertEquals(404, http.get(path(instance, "/config-ladder"), caller).statusCode());
            assertEquals(404, http.post(path(instance, "/config"), caller,
                    stageBody(versionBeforeFirstStage, basicDraft("x"), basicRendered("x"))).statusCode());
        }
        assertEquals(0, generations(), "nothing was written");
    }

    @Test
    @Order(5)
    void aViewerReadsButCannotStage() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);

        assertEquals(200, http.get(path(instance, "/designer"), viewer).statusCode());
        assertEquals(200, http.get(path(instance, "/config/history"), viewer).statusCode());
        assertEquals(200, http.get(path(instance, "/config-ladder"), viewer).statusCode());
        HttpResponse<String> stage = http.post(path(instance, "/config"), viewer,
                stageBody(versionBeforeFirstStage, basicDraft("orders"), basicRendered("orders")));
        assertEquals(403, stage.statusCode(), stage.body());
        assertEquals(0, generations());
    }

    @Test
    @Order(6)
    void aValidStageWritesGenerationOneAndNothingToAHost() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(path(instance, "/config"), http.login(DeclareFixtures.OPERATOR_ONE),
                stageBody(versionBeforeFirstStage, basicDraft("orders"), basicRendered("orders")));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode staged = body(response);
        assertEquals(1, staged.get("generation").asLong());
        assertFalse(staged.get("unchanged").asBoolean());
        versionAfterFirstStage = staged.get("stateVersion").asLong();
        assertTrue(versionAfterFirstStage > versionBeforeFirstStage, "the stage advanced the instance's version");
        assertEquals(1, generations());
        assertEquals(0L, count("SELECT count(*) FROM sm_data.apply_request WHERE tenant_id = :t AND instance_id = :i"),
                "a stage submits no apply and so writes nothing to a host");
        String stored = ownerJdbc.queryForObject("SELECT rendered_entries::text FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 1",
                Map.of("t", world.organization(), "i", instance), String.class);
        assertTrue(stored.contains("cache-engine.namespace") && stored.contains("orders"), stored);
        assertTrue(stored.contains("cache-engine.mode"), "the SERVER's rendered set includes the defaults it applied");
    }

    @Test
    @Order(7)
    void reStagingIdenticalContentIsUnchangedAndWritesNoRow() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(path(instance, "/config"), http.login(DeclareFixtures.OPERATOR_ONE),
                stageBody(versionAfterFirstStage, basicDraft("orders"), basicRendered("orders")));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(body(response).get("unchanged").asBoolean());
        assertEquals(1, body(response).get("generation").asLong());
        assertEquals(versionAfterFirstStage, body(response).get("stateVersion").asLong());
        assertEquals(1, generations());
    }

    @Test
    @Order(8)
    void aSecondOperatorStagingFromTheOldBaseIs409AndTheFirstGenerationIsUntouched() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.post(path(instance, "/config"), http.login(DeclareFixtures.OPERATOR_ONE),
                stageBody(versionBeforeFirstStage, basicDraft("billing"), basicRendered("billing")));

        assertEquals(409, response.statusCode(), response.body());
        assertEquals("CONFLICT", body(response).get("code").asText());
        assertEquals(1, generations());
    }

    @Test
    @Order(9)
    void aClientRenderedClaimThatDisagreesWithTheServersIs422AndWritesNothing() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Map<String, Object> wrong = basicRendered("billing");
        wrong.put("namespace", "something-else");
        Map<String, Object> missing = basicRendered("billing");
        missing.remove("mode");
        Map<String, Object> extra = basicRendered("billing");
        extra.put("not-a-field", "x");

        for (Map<String, Object> claim : List.of(wrong, missing, extra)) {
            HttpResponse<String> response = http.post(path(instance, "/config"), operator,
                    stageBody(versionAfterFirstStage, basicDraft("billing"), claim));
            assertEquals(422, response.statusCode(), response.body());
            assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
            assertFalse(response.body().contains("something-else"), "no value is echoed");
        }
        Map<String, Object> draftWithUndeclaredKey = basicDraft("billing");
        draftWithUndeclaredKey.put("not-a-field", "x");
        HttpResponse<String> undeclared = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterFirstStage, draftWithUndeclaredKey, basicRendered("billing")));
        assertEquals(422, undeclared.statusCode(), undeclared.body());
        assertFalse(undeclared.body().contains("not-a-field"));
        assertEquals(1, generations());
    }

    @Test
    @Order(10)
    void aBlockingValidationIs422() throws Exception {
        Client http = client();
        Map<String, Object> draft = basicDraft("billing");
        draft.remove("namespace");   // REQUIRED
        Map<String, Object> rendered = basicRendered("billing");
        rendered.remove("namespace");

        HttpResponse<String> response = http.post(path(instance, "/config"), http.login(DeclareFixtures.OPERATOR_ONE),
                stageBody(versionAfterFirstStage, draft, rendered));

        assertEquals(422, response.statusCode(), response.body());
        assertEquals(1, generations());
    }

    @Test
    @Order(11)
    void aSecondEditIsGenerationTwoAndTheDesignerRebuildsItFromTheStoredDraft() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> staged = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterFirstStage, basicDraft("billing"), basicRendered("billing")));

        assertEquals(200, staged.statusCode(), staged.body());
        assertEquals(2, body(staged).get("generation").asLong());
        versionAfterSecondStage = body(staged).get("stateVersion").asLong();
        JsonNode designer = body(http.get(path(instance, "/designer"), operator));
        assertEquals(2, designer.get("generation").asLong());
        assertEquals(versionAfterSecondStage, designer.get("stateVersion").asLong());
        assertEquals("i1", designer.at("/viewModel/components/0/instanceKey").asText());
        JsonNode namespace = null;
        for (JsonNode field : designer.at("/viewModel/components/0/sections/0/fields")) {
            if (field.get("key").asText().equals("namespace")) {
                namespace = field;
            }
        }
        assertNotNull(namespace);
        assertEquals("billing", namespace.at("/value/value").asText(), "rebuilt from the STORED draft");
        assertEquals("draft", namespace.at("/value/source").asText());
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":10,\"y\":20}}"), designer.get("layout"),
                "OR-96: the layout the operator saved comes back beside the view model");
        assertEquals("secret", designer.at("/viewModel/components/0/sections").findValues("controlKind").stream()
                .map(JsonNode::asText).filter("secret"::equals).findFirst().orElse("none"),
                "the password field is present, masked");
    }

    @Test
    @Order(12)
    void historyIsNewestFirstAndRedacted() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.get(path(instance, "/config/history"),
                http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode history = body(response);
        assertEquals(versionAfterSecondStage, history.get("stateVersion").asLong());
        assertEquals(List.of(2L, 1L), longs(history.get("entries"), "generation"));
        for (JsonNode entry : history.get("entries")) {
            List<String> names = new ArrayList<>();
            entry.fieldNames().forEachRemaining(names::add);
            assertEquals(List.of("at", "by", "configHash", "generation"), names.stream().sorted().toList());
            assertTrue(entry.get("configHash").asText().matches("hmacsha256v1:[0-9a-f]{64}"), entry.toString());
        }
        assertFalse(response.body().contains("billing") || response.body().contains("orders"),
                "history carries no value: " + response.body());
        assertEquals(ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.OPERATOR_ONE), UUID.class).toString(),
                history.at("/entries/0/by").asText());
    }

    @Test
    @Order(13)
    void theLadderReportsDesiredAndRenderedAndDeclaresTheRestDegraded() throws Exception {
        Client http = client();

        HttpResponse<String> response = http.get(path(instance, "/config-ladder"),
                http.login(DeclareFixtures.OPERATOR_ONE));

        assertEquals(200, response.statusCode(), response.body());
        JsonNode ladder = body(response);
        assertEquals(versionAfterSecondStage, ladder.get("stateVersion").asLong());
        assertEquals("rendered", ladder.get("reached").asText());
        assertEquals(List.of("desired", "rendered", "persisted", "restart-observed", "effective-verified"),
                texts(ladder.get("rungs"), "rung"));
        assertTrue(ladder.at("/rungs/0/present").asBoolean());
        assertTrue(ladder.at("/rungs/1/present").asBoolean());
        assertEquals(2, ladder.at("/rungs/1/generation").asLong());
        assertFalse(ladder.at("/rungs/2/present").asBoolean());
        assertFalse(ladder.at("/rungs/3/present").asBoolean());
        assertNotNull(ladder.at("/availability/reason").asText());
        assertEquals("degraded", ladder.at("/availability/state").asText());
        assertEquals(body(http.get(path(instance, "/config/history"), http.login(DeclareFixtures.OPERATOR_ONE)))
                .at("/entries/0/configHash").asText(), ladder.at("/rungs/1/configHash").asText(),
                "the ladder and the history name the same keyed digest");
    }

    @Test
    @Order(14)
    void aLiteralTypedIntoASecretFieldIs422IsNeverEchoedAndNeverStored() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        Map<String, Object> draft = basicDraft("billing");
        draft.put("solace.enabled", true);
        draft.put("solace.host", "broker.example.test:55555");
        draft.put("solace.password", LITERAL);

        HttpResponse<String> visible = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterSecondStage, draft, basicRendered("billing")));
        Map<String, Object> hiddenDraft = basicDraft("billing");   // solace.enabled stays false: the field is HIDDEN
        hiddenDraft.put("solace.password", LITERAL);
        HttpResponse<String> hidden = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterSecondStage, hiddenDraft, basicRendered("billing")));

        // The prefix alone is not a reference, and a well-formed reference is resolved in a HIDDEN field too.
        Map<String, Object> hiddenPrefix = basicDraft("billing");
        hiddenPrefix.put("solace.password", "secretRef://hunter2");
        HttpResponse<String> hiddenPrefixResponse = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterSecondStage, hiddenPrefix, basicRendered("billing")));
        Map<String, Object> hiddenUnresolved = basicDraft("billing");
        hiddenUnresolved.put("solace.password", "secretRef://" + UUID.randomUUID());
        HttpResponse<String> hiddenUnresolvedResponse = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterSecondStage, hiddenUnresolved, basicRendered("billing")));

        for (HttpResponse<String> response : List.of(visible, hidden, hiddenPrefixResponse,
                hiddenUnresolvedResponse)) {
            assertEquals(422, response.statusCode(), response.body());
            assertFalse(response.body().contains(LITERAL), "I6: " + response.body());
            assertFalse(response.body().contains("hunter2"), "I6: " + response.body());
        }
        assertEquals(0L, count("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id"
                + " = :i AND draft_values::text LIKE '%hunter2%'"));
        assertEquals(0L, count("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id"
                + " = :i AND (draft_values::text LIKE '%" + LITERAL + "%' OR rendered_entries::text LIKE '%"
                + LITERAL + "%')"));
        assertEquals(2, generations());
    }

    @Test
    @Order(15)
    void aSecretReferenceMustResolveToAConfigFieldSecretOfTheOrganization() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        UUID actor = UUID.randomUUID();
        UUID sshKey = secretStore.put(world.organization(), SecretPurpose.SSH_PRIVATE_KEY,
                "ssh-key-sentinel".toCharArray(), actor);
        UUID foreign = secretStore.put(world.otherOrganization(), SecretPurpose.CONFIG_FIELD,
                PLAINTEXT.toCharArray(), actor);

        for (UUID unresolvable : List.of(UUID.randomUUID(), sshKey, foreign)) {
            Map<String, Object> draft = secretDraft("secretRef://" + unresolvable);
            Map<String, Object> rendered = secretRendered("secretRef://" + unresolvable);
            HttpResponse<String> response = http.post(path(instance, "/config"), operator,
                    stageBody(versionAfterSecondStage, draft, rendered));
            assertEquals(422, response.statusCode(), "a reference to " + unresolvable + ": " + response.body());
            assertFalse(response.body().contains(unresolvable.toString()), "the reference is not echoed");
        }
        assertEquals(2, generations());

        configFieldSecret = secretStore.put(world.organization(), SecretPurpose.CONFIG_FIELD,
                PLAINTEXT.toCharArray(), actor);
        String ref = "secretRef://" + configFieldSecret;
        HttpResponse<String> staged = http.post(path(instance, "/config"), operator,
                stageBody(versionAfterSecondStage, secretDraft(ref), secretRendered(ref)));

        assertEquals(200, staged.statusCode(), staged.body());
        assertEquals(3, body(staged).get("generation").asLong());
        String stored = ownerJdbc.queryForObject("SELECT draft_values::text || rendered_entries::text FROM"
                + " sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i AND generation = 3",
                Map.of("t", world.organization(), "i", instance), String.class);
        assertTrue(stored.contains(configFieldSecret.toString()), "the reference is what is stored");
        assertFalse(stored.contains(PLAINTEXT), "I6: the value is never in a config table");
        JsonNode designer = body(http.get(path(instance, "/designer"), operator));
        assertFalse(designer.toString().contains(PLAINTEXT), "I6: the value is never in a response");
        assertTrue(designer.toString().contains("\"kind\":\"secretRef\""), "the view model names the reference");
        versionAfterSecondStage = body(staged).get("stateVersion").asLong();
    }

    private static Map<String, Object> secretDraft(String ref) {
        Map<String, Object> draft = basicDraft("billing");
        draft.put("solace.enabled", true);
        draft.put("solace.host", "broker.example.test:55555");
        draft.put("solace.password", ref);
        return draft;
    }

    private static Map<String, Object> secretRendered(String ref) {
        Map<String, Object> rendered = basicRendered("billing");
        rendered.put("solace.enabled", true);
        rendered.put("solace.host", "broker.example.test:55555");
        rendered.put("solace.messaging-mode", "MESSAGING");
        rendered.put("solace.management.enabled", false);
        rendered.put("solace.password", ref);
        return rendered;
    }

    // ----------------------------------------------------------------------------- the byte identity, over HTTP

    @Test
    @Order(16)
    void theServedViewModelIsTheTypeScriptEnginesFixtureBytesKeyOrderIncluded() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "fixtures-cache", "sm-fixtures", 9102));
        assertEquals(201, declared.statusCode(), declared.body());
        fixtureInstance = UUID.fromString(body(declared).get("instanceId").asText());

        int compared = 0;
        for (Path file : fixtureFiles()) {
            JsonNode fixture = MAPPER.readTree(Files.readString(file));
            JsonNode observed = fixture.get("observed");
            String versionState = observed.at("/version/state").asText();
            boolean comparable = fixture.get("package").asText().equals("cache-2.4")
                    && (versionState.equals("OK") || versionState.equals("VERSION_UNVERIFIED"))
                    && !observed.has("restartObservedValues") && !fixture.has("catalog");
            if (!comparable) {
                continue;
            }
            long base = body(http.get(path(fixtureInstance, "/designer"), operator)).get("stateVersion").asLong();
            // Seeded through the service, not the endpoint: a fixture's draft is not always a VALID draft (it often
            // leaves a required field unset on purpose), and this test is about what is SERVED, not what is accepted.
            configRecords.stage(world.organization(), fixtureInstance, new StageCommand(base, "cache", "1",
                    fixture.get("selection").toString(), fixture.get("draft").toString(), List.of(),
                    GenerationOrigin.EDIT, null, UUID.randomUUID()));

            JsonNode served = body(http.get(path(fixtureInstance, "/designer"), operator)).get("viewModel");
            JsonNode expected = fixture.get("expected");

            assertEquals(names(expected), names(served), file.getFileName() + ": top-level key order");
            for (String key : names(expected)) {
                if (key.equals("status")) {
                    // The fixture's observed state is the engine's input; the served one is what the data plane
                    // observes (no replicas, MI version unverified). Only the SHAPE and key order is comparable.
                    assertEquals(names(expected.get(key)), names(served.get(key)), file.getFileName() + ": status keys");
                    continue;
                }
                assertEquals(pretty(expected.get(key)), pretty(served.get(key)),
                        file.getFileName() + ": '" + key + "' byte for byte");
            }
            compared++;
        }
        assertTrue(compared >= 15, "the byte check ran over " + compared + " fixtures");
    }

    // ------------------------------------------------------------------------------------------ OR-96, over HTTP

    private static ObjectNode movedTo(ObjectNode body, double x, double y) {
        ((ObjectNode) body.at("/componentSelection/instances/0/layout")).put("x", x).put("y", y);
        return body;
    }

    @Test
    @Order(17)
    void aLayoutOnlySaveIsUnchangedMintsNoGenerationAndIsWhatTheDesignerReturnsNext() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String ref = "secretRef://" + configFieldSecret;
        long generationsBefore = generations();
        String digestBefore = ownerJdbc.queryForObject("SELECT content_digest FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3",
                Map.of("t", world.organization(), "i", instance), String.class);

        HttpResponse<String> dragged = http.post(path(instance, "/config"), operator,
                movedTo(stageBody(versionAfterSecondStage, secretDraft(ref), secretRendered(ref)), 400.5, -250));

        assertEquals(200, dragged.statusCode(), dragged.body());
        JsonNode staged = body(dragged);
        assertTrue(staged.get("unchanged").asBoolean(), "layout is not config: " + staged);
        assertEquals(3, staged.get("generation").asLong());
        assertEquals(versionAfterSecondStage, staged.get("stateVersion").asLong(), "no stamp drawn");
        assertEquals(generationsBefore, generations(), "no generation row");
        assertEquals(digestBefore, ownerJdbc.queryForObject("SELECT content_digest FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3",
                Map.of("t", world.organization(), "i", instance), String.class), "and the hash did not move");

        JsonNode designer = body(http.get(path(instance, "/designer"), operator));
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":400.5,\"y\":-250.0}}"), designer.get("layout"),
                "the dragged position is what the designer returns");
        assertEquals(3, designer.get("generation").asLong());
        assertEquals(0L, count("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id"
                + " = :i AND component_selection::text LIKE '%layout%'"), "no generation holds presentation state");
        assertEquals(1L, count("SELECT count(*) FROM sm_data.designer_layout WHERE tenant_id = :t AND instance_id = :i"));
        JsonNode history = body(http.get(path(instance, "/config/history"), operator));
        assertEquals(generationsBefore, history.get("entries").size(), "history is unchanged by a layout save");
        assertFalse(history.toString().contains("400.5"), "layout is in no history entry");
    }

    @Test
    @Order(18)
    void aViewerCannotSaveALayoutButSeesTheStoredOne() throws Exception {
        Client http = client();
        Login viewer = http.login(DeclareFixtures.VIEWER);
        String ref = "secretRef://" + configFieldSecret;

        HttpResponse<String> refused = http.post(path(instance, "/config"), viewer,
                movedTo(stageBody(versionAfterSecondStage, secretDraft(ref), secretRendered(ref)), 1, 2));

        assertEquals(403, refused.statusCode(), refused.body());
        JsonNode designer = body(http.get(path(instance, "/designer"), viewer));
        assertEquals(MAPPER.readTree("{\"i1\":{\"x\":400.5,\"y\":-250.0}}"), designer.get("layout"),
                "config.edit is required to change the layout, config.view to read it");
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static String pretty(JsonNode node) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static List<Path> fixtureFiles() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/view-models/fixtures/viewmodel");
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> files = Files.list(candidate)) {
                    return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("contracts/view-models/fixtures/viewmodel not found");
    }

    private static List<Long> longs(JsonNode array, String field) {
        List<Long> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asLong()));
        return out;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }
}
