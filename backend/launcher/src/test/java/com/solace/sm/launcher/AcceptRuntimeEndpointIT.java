package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.TargetReplica;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T10.4 ({@code OR-104}, D-ACCEPT): {@code acceptRuntimeConfig} is SERVED - the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptor, the real projector and
 * stage validator, the real read-back parser and mapper, the real config of record and the real secret store. Only the host
 * is local: the production SSH adapter is withdrawn and an in-memory one takes its place, so nothing here opens a socket and
 * the host's file is whatever the test writes into it, as a human editing the host would.
 *
 * <p>The scenarios are D-ACCEPT §1's A to H, in order, against ONE instance whose state carries from test to test: what an
 * accept stages is what the next one reads back.
 *
 * <p>⛔ <b>{@code I6} is checked at the end against everything:</b> the two host-file passwords appear in no response, no
 * log line, and no column of any {@code sm_data} table other than inside the secret store's ciphertext (where the plain text
 * cannot appear).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AcceptRuntimeEndpointIT.HostConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AcceptRuntimeEndpointIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    /** The password SM stored and rendered into the host's file. */
    private static final String STORED = "stored-password-sentinel-5f31";
    /** The password a human typed into the host's file. */
    private static final String HUMAN = "typed-on-host-sentinel-c0d8";
    private static final String LOTS = "lots-of-sentinel-99aa";

    /**
     * Everything the process wrote to its console while this class ran, from before the context started. ⚠️ Not Spring
     * Boot's {@code OutputCaptureExtension}: measured, its {@code getAll()} covers one test method, so a leak in an earlier
     * test would pass a scan made in the last (the mutation that logged the compared secret was NOT caught by it).
     */
    private static final ByteArrayOutputStream CONSOLE = new ByteArrayOutputStream();
    private static PrintStream originalOut;
    private static PrintStream originalErr;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID solutionId;
    private static UUID instance;
    private static UUID placement;
    private static UUID storedSecret;
    private static UUID secondInstance;
    private static UUID secondPlacement;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @Autowired
    private RenderedConfigSource renderer;

    @Autowired
    private ConfigHasher hasher;

    @Autowired
    private SecretStore secretStore;

    /** The host's adapter, and the real renderer over the real config of record and secret store. */
    @TestConfiguration
    static class HostConfiguration {

        @Bean
        RoutingAdapter runtimeAdapter() {
            return new RoutingAdapter();
        }

        @Bean
        RenderedConfigSource renderedConfigSource(ConfigGenerationRows rows, SecretResolver secrets, ConfigHasher hasher) {
            return new ConfigGenerationRenderedConfigSource(rows, secrets, hasher);
        }
    }

    /** Writes to both the real stream and {@link #CONSOLE}. */
    private static final class Tee extends OutputStream {
        private final OutputStream first;

        Tee(OutputStream first) {
            this.first = first;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            synchronized (CONSOLE) {
                CONSOLE.write(b);
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            first.write(bytes, offset, length);
            synchronized (CONSOLE) {
                CONSOLE.write(bytes, offset, length);
            }
        }
    }

    @BeforeAll
    static void teeTheConsoleBeforeTheContextStarts() {
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(new Tee(originalOut), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new Tee(originalErr), true, StandardCharsets.UTF_8));
    }

    @AfterAll
    static void restoreTheConsole() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
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

    private static String acceptPath(UUID id) {
        return "/api/dp/instances/" + id + "/config/accept-runtime";
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    // ---------------------------------------------------------------------------------------------- the host

    private PlacementRef ref(UUID instanceId, UUID placementId) {
        return new PlacementRef(new TenantId(world.organization()), new InstanceId(instanceId), new PlacementId(placementId));
    }

    private InMemoryRuntimeAdapter host() {
        return adapter.hostFor(ref(instance, placement));
    }

    /** The file as a human left it. */
    private void hostFile(String yaml) {
        InMemoryRuntimeAdapter host = host();
        host.writeFileAsHuman(host.replicas().get(0), yaml.getBytes(StandardCharsets.UTF_8), hasher);
    }

    /** SM's own rendering of {@code generation}, secrets resolved: what an apply would have written. */
    private String canonical(long generation) {
        return new String(renderer.render(ref(instance, placement), generation).bytes(), StandardCharsets.UTF_8);
    }

    private long currentVersion(Login login) throws Exception {
        return body(client().get("/api/dp/instances/" + instance, login)).get("stateVersion").asLong();
    }

    private HttpResponse<String> accept(Login login, long base, UUID placementId) throws Exception {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("baseStateVersion", base);
        request.put("placementId", placementId.toString());
        return client().post(acceptPath(instance), login, request);
    }

    private HttpResponse<String> acceptAsAdmin() throws Exception {
        Login admin = client().login(DeclareFixtures.ADMIN);
        return accept(admin, currentVersion(admin), placement);
    }

    private long generations() {
        Long n = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t AND"
                + " instance_id = :i", Map.of("t", world.organization(), "i", instance), Long.class);
        return n == null ? 0 : n;
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asText()));
        return out;
    }

    private static JsonNode entryAt(JsonNode response, String path) {
        for (JsonNode entry : response.get("notAccepted")) {
            if (entry.get("path").asText().equals(path)) {
                return entry;
            }
        }
        throw new AssertionError("no notAccepted entry for " + path + " in " + response);
    }

    // ------------------------------------------------------------------------------------------------- setup

    @Test
    @Order(1)
    void acceptIsReachableInTheProductionBootAndRefusesNoSession() throws Exception {
        assertEquals(401, client().anonymous("POST", acceptPath(UUID.randomUUID())).statusCode());
    }

    @Test
    @Order(2)
    void anInstanceIsDeclaredAndAGenerationWithAStoredPasswordIsStaged() throws Exception {
        Client http = client();
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        solutionId = UUID.fromString(body(http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load))
                .get("solutionId").asText());
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances", operator,
                DeclareFixtures.declareBody(solutionId, world.targetOne(), "orders-cache", "sm-orders", 9101));
        assertEquals(201, declared.statusCode(), declared.body());
        instance = UUID.fromString(body(declared).get("instanceId").asText());
        placement = UUID.fromString(body(declared).get("placements").get(0).get("placementId").asText());
        long version = body(declared).get("stateVersion").asLong();

        storedSecret = secretStore.put(world.organization(), SecretPurpose.CONFIG_FIELD, STORED.toCharArray(),
                UUID.randomUUID());
        String ref = "secretRef://" + storedSecret;
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("namespace", "billing");
        draft.put("key-source", "TOPIC");
        draft.put("max-size", 1000);
        draft.put("solace.enabled", true);
        draft.put("solace.host", "broker.example.test:55555");
        draft.put("solace.password", ref);
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("mode", "READ");
        rendered.put("provider", "caffeine");
        rendered.put("namespace", "billing");
        rendered.put("key-source", "TOPIC");
        rendered.put("max-size", 1000);
        rendered.put("solace.enabled", true);
        rendered.put("solace.host", "broker.example.test:55555");
        rendered.put("solace.messaging-mode", "MESSAGING");
        rendered.put("solace.management.enabled", false);
        rendered.put("solace.password", ref);
        ObjectNode stage = MAPPER.createObjectNode();
        stage.put("baseStateVersion", version);
        ObjectNode selected = stage.putObject("componentSelection").putArray("instances").addObject();
        selected.put("instanceKey", "i1").put("type", "cache-engine");
        stage.putObject("draftValues").set("i1", MAPPER.valueToTree(draft));
        rendered.forEach((key, value) -> stage.withArray("rendered").addObject().put("instanceKey", "i1")
                .put("renderedPath", "cache-engine." + key).set("value", MAPPER.valueToTree(value)));
        HttpResponse<String> staged = http.post("/api/dp/instances/" + instance + "/config", operator, stage);
        assertEquals(200, staged.statusCode(), staged.body());
        assertEquals(1, body(staged).get("generation").asLong());

        String canonical = canonical(1);
        assertTrue(canonical.contains(STORED), "the renderer resolves the secret into the file SM writes");
        hostFile(canonical);
    }

    // ------------------------------------------------------------------------------------------ authorization

    @Test
    @Order(3)
    void requiresBothConfigEditAndConfigOverrideWarning() throws Exception {
        Client http = client();
        int callsBefore = adapter.calls.get();

        // Operator: config.edit, but not config.overrideWarning. 403 NAMES the second verb, and the host is never asked.
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        HttpResponse<String> response = accept(operator, currentVersion(operator), placement);
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(body(response).get("detail").asText().contains("config.overrideWarning"), response.body());

        // Viewer: no config.edit at all.
        Login viewer = http.login(DeclareFixtures.VIEWER);
        assertEquals(403, accept(viewer, 1, placement).statusCode());

        // The other organization's administrator: the same 404 as no such instance.
        Login foreign = http.login(DeclareFixtures.FOREIGN);
        assertEquals(404, accept(foreign, 1, placement).statusCode());

        assertEquals(callsBefore, adapter.calls.get(), "no refusal above reached the host");
        assertEquals(1, generations());
    }

    @Test
    @Order(4)
    void theRequestNamesAPlacementThatIsThisInstancesOrIsRefused() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        long base = currentVersion(admin);
        int callsBefore = adapter.calls.get();

        ObjectNode missing = MAPPER.createObjectNode();
        missing.put("baseStateVersion", base);
        assertEquals(400, http.post(acceptPath(instance), admin, missing).statusCode(), "aMissingPlacementIdIsRefused");
        assertEquals(404, accept(admin, base, UUID.randomUUID()).statusCode(), "a placement that is not this instance's");

        assertEquals(callsBefore, adapter.calls.get());
        assertEquals(1, generations());
    }

    // ------------------------------------------------------------------------------------- A: a break-glass edit

    @Test
    @Order(5)
    void aBreakGlassValueEditIsAcceptedAsANewGenerationAndTheSamePasswordIsReportedSame() throws Exception {
        String edited = canonical(1).replace("max-size: 1000", "max-size: 250000")
                .replace("cache-engine:\n", "cache-engine:\n  ttl: \"30m\"\n");
        assertTrue(edited.contains("max-size: 250000") && edited.contains("ttl: \"30m\""), edited);
        hostFile(edited);

        HttpResponse<String> response = acceptAsAdmin();

        assertEquals(200, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(2, accepted.get("generation").asLong());
        assertFalse(accepted.get("unchanged").asBoolean());
        assertEquals(placement.toString(), accepted.get("placementId").asText());
        assertTrue(accepted.get("fileBytesDifferFromRendered").asBoolean(),
                "accept writes nothing to the host: the file is not generation 2's rendering until it is applied");
        // The Cache package now declares logger support (T9.3, OR-103), so the rendered file carries SM's two overlay keys.
        // They are SM's, never adopted and never the operator's: reported sm-owned-overlay beside the secret, and nothing else.
        assertEquals(3, accepted.get("notAccepted").size(), accepted.toString());
        assertEquals("sm-owned-overlay", entryAt(accepted, "management.endpoints.web.exposure.include").get("reason").asText());
        assertEquals("sm-owned-overlay", entryAt(accepted, "management.endpoint.loggers.access").get("reason").asText());
        JsonNode secret = entryAt(accepted, "cache-engine.solace.password");
        assertEquals("secret-not-read", secret.get("reason").asText());
        assertEquals("same", secret.get("comparison").asText());
        assertEquals("solace.password", secret.get("fieldKey").asText());

        Map<String, Object> row = ownerJdbc.queryForMap("SELECT origin, derived_from_generation, draft_values::text AS draft,"
                + " rendered_entries::text AS rendered FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i"
                + " AND generation = 2", Map.of("t", world.organization(), "i", instance));
        assertEquals("accept-runtime", row.get("origin"));
        assertEquals(1L, ((Number) row.get("derived_from_generation")).longValue());
        String draft = (String) row.get("draft");
        assertTrue(draft.contains("250000") && draft.contains("30m"), draft);
        assertTrue(((String) row.get("rendered")).contains("@sm-overlay"),
                "the accepted generation keeps SM's overlay: an accept must not withdraw the loggers endpoint's exposure");
        assertTrue(draft.contains("secretRef://" + storedSecret), "the draft keeps the CURRENT secret reference");
        assertFalse(draft.contains(STORED) || ((String) row.get("rendered")).contains(STORED), "I6: no value in a config table");
        assertTrue(((String) row.get("rendered")).contains(storedSecret.toString()));

        // the operator-visible designer shows the accepted values
        Client http = client();
        JsonNode designer = body(http.get("/api/dp/instances/" + instance + "/designer", http.login(DeclareFixtures.ADMIN)));
        assertTrue(designer.toString().contains("250000"), "the designer reads the new generation");
        assertFalse(designer.toString().contains(STORED));
    }

    @Test
    @Order(6)
    void aFileThatIsExactlySMsOwnRenderingIsUnchangedAndWritesNoRow() throws Exception {
        hostFile(canonical(2));

        JsonNode accepted = body(acceptAsAdmin());

        assertTrue(accepted.get("unchanged").asBoolean(), accepted.toString());
        assertEquals(2, accepted.get("generation").asLong());
        assertFalse(accepted.get("fileBytesDifferFromRendered").asBoolean(), "byte-identical to the rendering");
        assertEquals(2, generations(), "guard 1: nothing mappable differs, so no row");
        assertEquals("same", entryAt(accepted, "cache-engine.solace.password").get("comparison").asText());
    }

    // ----------------------------------------------------------------------------------- E: a cosmetic edit

    @Test
    @Order(7)
    void aCosmeticEditIsUnchangedButTheBytesStillDiffer() throws Exception {
        hostFile("# edited by hand, a long time ago\n\n" + canonical(2).replace("cache-engine:", "cache-engine:   # the engine"));

        JsonNode accepted = body(acceptAsAdmin());

        assertTrue(accepted.get("unchanged").asBoolean(), accepted.toString());
        assertTrue(accepted.get("fileBytesDifferFromRendered").asBoolean(), "same values, different bytes");
        assertEquals(2, generations());
    }

    // ------------------------------------------------------------------ B: the password was changed on the host

    @Test
    @Order(8)
    void aPasswordChangedOnTheHostIsReportedDiffersAndIsNeverStoredReturnedOrLogged() throws Exception {
        hostFile(canonical(2).replace(STORED, HUMAN));

        HttpResponse<String> response = acceptAsAdmin();

        assertEquals(200, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertTrue(accepted.get("unchanged").asBoolean(), "no mappable value differs; the password is not adopted");
        assertEquals("differs", entryAt(accepted, "cache-engine.solace.password").get("comparison").asText());
        assertFalse(response.body().contains(HUMAN) || response.body().contains(STORED), "a reply holds no value");
        assertEquals(2, generations());
        // the stored secret is still the old one: accept never adopts the host's
        assertEquals(STORED, new String(renderer.render(ref(instance, placement), 2).bytes(), StandardCharsets.UTF_8)
                .lines().filter(l -> l.contains("password")).findFirst().orElseThrow().split("\"")[1],
                "the next apply would write the STORED password over the host's");
    }

    // ------------------------------------------------------------------ C: keys the package does not declare

    @Test
    @Order(9)
    void keysThePackageDoesNotDeclareAreReportedByPathAndAcceptedAround() throws Exception {
        hostFile(canonical(2) + "server:\n  port: " + LOTS + "\nspring.main.banner-mode: \"off\"\ncache-engine.new-flag: true\n");

        HttpResponse<String> response = acceptAsAdmin();

        assertEquals(200, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertTrue(accepted.get("unchanged").asBoolean());
        // sorted by path; the two management.* entries are SM's own overlay, which the Cache package's loggers declaration
        // (T9.3, OR-103) puts in every rendering, so they are always reported (sm-owned-overlay), never adopted
        assertEquals(List.of("cache-engine.new-flag", "cache-engine.solace.password", "management.endpoint.loggers.access",
                "management.endpoints.web.exposure.include", "server.port", "spring.main.banner-mode"),
                texts(accepted.get("notAccepted"), "path"));
        assertEquals("undeclared-key", entryAt(accepted, "server.port").get("reason").asText());
        assertFalse(response.body().contains(LOTS), "a path names a key, never what it holds");
    }

    // --------------------------------------------------------------------------------------- D: YAML constructs

    @Test
    @Order(10)
    void anAnchorADuplicateKeyAndASecondDocumentAreRefused422AndStageNothing() throws Exception {
        String sm = canonical(2);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("anchor", sm.replace("max-size: 250000", "max-size: &m 250000") + "idle-copy: *m\n");
        files.put("duplicate-key", sm + "cache-engine:\n  max-size: 1\n");
        files.put("multi-document", sm + "---\nother: 1\n");
        files.put("tag", sm.replace("max-size: 250000", "max-size: !!int 250000"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            hostFile(file.getValue());
            HttpResponse<String> response = acceptAsAdmin();
            assertEquals(422, response.statusCode(), file.getKey() + ": " + response.body());
            assertEquals("VALIDATION_FAILED", body(response).get("code").asText());
            assertEquals("the host's config file was refused: " + file.getKey(), body(response).get("detail").asText());
        }
        assertEquals(2, generations());
    }

    // ------------------------------------------------------------------------------- F: a file that cannot be read

    @Test
    @Order(11)
    void anUnreachableHostIs503AndAnAbsentFileIs422() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        hostFile(canonical(2));
        long base = currentVersion(admin);

        adapter.unreachable = true;
        try {
            HttpResponse<String> response = accept(admin, base, placement);
            assertEquals(503, response.statusCode(), response.body());
            assertEquals("SERVICE_UNAVAILABLE", body(response).get("code").asText());
            assertEquals("a dependency did not answer; retry", body(response).get("detail").asText());
        } finally {
            adapter.unreachable = false;
        }

        // a second instance whose host file was never written
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ApplyItSupport.Declared other = ApplyItSupport.declare(http, world, "second-cache", "sm-second", 9102);
        secondInstance = other.instance();
        secondPlacement = other.placement();
        ApplyItSupport.stage(http, secondInstance, other.version(), "second");
        ObjectNode request = MAPPER.createObjectNode();
        request.put("baseStateVersion", body(http.get("/api/dp/instances/" + secondInstance, admin)).get("stateVersion").asLong());
        request.put("placementId", secondPlacement.toString());
        HttpResponse<String> absent = http.post(acceptPath(secondInstance), admin, request);
        assertEquals(422, absent.statusCode(), absent.body());
        assertEquals("the host's config file was refused: file-absent", body(absent).get("detail").asText());
        assertNotNull(operator);
        assertEquals(2, generations());
    }

    // ------------------------------------------------------------------------------------- G: values that are wrong

    @Test
    @Order(12)
    void aBlockingValueIs422NamingTheFieldAndTheRuleNeverTheValue() throws Exception {
        hostFile(canonical(2).replace("max-size: 250000", "max-size: " + LOTS).replace("mode: \"READ\"", "mode: \"SUPER\""));

        HttpResponse<String> response = acceptAsAdmin();

        assertEquals(422, response.statusCode(), response.body());
        String detail = body(response).get("detail").asText();
        assertTrue(detail.contains("max-size (BIND_TYPE)"), detail);
        assertTrue(detail.contains("mode (NOT_AN_OPTION)"), detail);
        assertFalse(response.body().contains(LOTS) || response.body().contains("SUPER"), "never the value: " + response.body());
        assertEquals(2, generations(), "a generation must be valid: a block is a refusal, not a partial accept");
    }

    @Test
    @Order(13)
    void aLossyMappingIsRefusedByTheRoundTripCheck() throws Exception {
        hostFile(canonical(2).replace("max-size: 250000", "max-size: \"250000\""));

        HttpResponse<String> response = acceptAsAdmin();

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(body(response).get("detail").asText().contains("max-size (ROUND_TRIP)"), response.body());
        assertEquals(2, generations());
    }

    // --------------------------------------------------------------------------------------- H: SM's own keys

    @Test
    @Order(14)
    void smsOwnOverlayKeysAreNeverAdoptedAndNeverCountedAsTheOperatorsOwn() throws Exception {
        // The rendering already carries SM's overlay (the Cache package declares loggers, T9.3). An operator edits one of
        // its keys by hand and adds a logging.level of their own.
        String rendering = canonical(2);
        assertTrue(rendering.contains("read-only"), rendering);
        hostFile(rendering.replace("read-only", "read-write") + "logging:\n  level:\n    com.solace.cache: DEBUG\n");

        JsonNode accepted = body(acceptAsAdmin());

        assertTrue(accepted.get("unchanged").asBoolean());
        assertEquals("sm-owned-overlay", entryAt(accepted, "management.endpoints.web.exposure.include").get("reason").asText());
        assertEquals("sm-owned-overlay", entryAt(accepted, "management.endpoint.loggers.access").get("reason").asText());
        // logging.level.* is not an overlay key (OR-103: a level change is configuration, so it is the operator's)
        assertEquals("undeclared-key", entryAt(accepted, "logging.level.com.solace.cache").get("reason").asText());
        assertEquals(2, generations());
    }

    // ---------------------------------------------------------------------------------------- the rest of the rules

    @Test
    @Order(15)
    void aStaleBaseIs409AndAcceptedValuesFromADifferentEditAreStillSingleWriter() throws Exception {
        hostFile(canonical(2).replace("max-size: 250000", "max-size: 300000"));
        Login admin = client().login(DeclareFixtures.ADMIN);
        long base = currentVersion(admin);

        HttpResponse<String> stale = accept(admin, base - 1, placement);

        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals(2, generations());
        // and the same file accepted from the right base is generation 3, derived from 2
        HttpResponse<String> fresh = accept(admin, base, placement);
        assertEquals(200, fresh.statusCode(), fresh.body());
        assertEquals(3, body(fresh).get("generation").asLong());
        assertEquals(2L, ((Number) ownerJdbc.queryForObject("SELECT derived_from_generation FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = 3",
                Map.of("t", world.organization(), "i", instance), Long.class)).longValue());
    }

    /** m3: the read ran with no database transaction open, through the whole stack. */
    @Test
    @Order(16)
    void theReadHoldsNoDatabaseTransaction() {
        assertEquals(Boolean.FALSE, adapter.transactionDuringRead);
    }

    // ------------------------------------------------------------------------------------------------- I6, last

    /** I6, over everything: no response, log line or table column holds either password in clear. */
    @Test
    @Order(17)
    void aSecretFieldsValueIsNeverInAnyResponseLogOrRow() {
        String console;
        synchronized (CONSOLE) {
            console = CONSOLE.toString(StandardCharsets.UTF_8);
        }
        assertTrue(console.length() > 1000, "the console tee saw the application's output, or this scan proves nothing");
        assertFalse(console.contains(STORED) || console.contains(HUMAN), "a log line carries a value");
        List<String> tables = ownerJdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema ="
                + " 'sm_data' AND table_type = 'BASE TABLE'", Map.of(), String.class);
        assertFalse(tables.isEmpty());
        for (String table : tables) {
            List<String> rows = ownerJdbc.queryForList("SELECT t::text FROM sm_data.\"" + table + "\" t", Map.of(),
                    String.class);
            for (String row : rows) {
                assertFalse(row.contains(STORED) || row.contains(HUMAN), "sm_data." + table + " holds a password in clear");
            }
        }
    }
}
