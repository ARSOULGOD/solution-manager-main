package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.engine.ApplyEngineProperties;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.LivePlacements;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.health.HealthPoller;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.ApplyItSupport.RoutingAdapter;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
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
 * Plan 008 S5 (Step 1, {@code BE-D44}..{@code BE-D48}, {@code BE-D53}): an instance with TWO live placements, end to end
 * over real HTTP - the real {@code SolutionManagerApplication}, the real gateway, interceptor and authorization, the real
 * inventory and apply engine, the real fleet scheduler and durable worker, the real health poller, the real ingest seam
 * and query API, PostgreSQL with both planes' real triggers. Only the managed host is local: an in-memory runtime adapter
 * per placement ({@link ApplyItSupport.RoutingAdapter}), and two loopback stubs standing in for the Cache actuator.
 *
 * <p><b>The scenario, in the plan's own words.</b> P1 and P2 sit on two DIFFERENT targets and carry the SAME container
 * name (the case plan 008 S2's journal identity exists for: both placements' replica is {@code replica-1}). The class
 * walks: declare, stage, apply P1 (ladder {@code persisted}); {@code addPlacement} (ladder drops to {@code rendered},
 * exactly one {@code instance.config-state}, P2 unpolled then polled); pull-path metrics per placement; the push-metrics
 * refusal (S-e, {@code OQ-8.9 (a)}); a fleet over both placements (the ladder is {@code persisted} only after P2); a failed
 * second member (S-b: {@code COMPLETED_WITH_FAILURES}, the ladder at P2's rung); a rollback of P1 alone (S-c, {@code OQ-8.7
 * (a)}: the ladder drops until P2 follows); {@code acceptRuntimeConfig} on P2 only; a stop of one placement and the
 * instance-wide stop (S-d: two steps, each with its own {@code placementId}); and {@code retirePlacement} (a retired
 * placement is no longer addressed - R6's defect).
 *
 * <p><b>What this test does NOT claim.</b> It runs against an in-memory host, so it proves the SM side of the matrix, not
 * Docker's. Plan 008 S5 Step 2 (live gate {@code L2}) is the same scenario against a real second Cache container on the
 * E2E host; see {@code docs/runbooks/operations.md}, "Give an instance a second placement", and the plan's Results.
 *
 * <p><b>The strategy is {@code manual} throughout.</b> {@code manual} writes the file and restarts nothing, so no container
 * start can be observed after a write and the ladder's top reachable rung here is {@code persisted}. That keeps every
 * ladder assertion about the registry (which placements count) and not about start observation, which
 * {@code ConfigLadderTest} and {@code StartObservationIT} already pin. The ungated strategies across two placements are
 * plan 012 T6. The one exception is the LAST test (W4 review W4A-4): the gated strategy, which needs no start observation,
 * over two placements of a fresh instance, so the lowest-rung-across-placements rule is exercised above {@code persisted}.
 *
 * <p>State carries from test to test (ordered). The health poller's schedule is set to an hour and driven by
 * {@link HealthPoller#pollOnce()}, so "P2 unpolled, then polled" is a decision of the test and not of a timer.
 */
@Import({LoopbackTargetAddressConfiguration.class, MultiPlacementEndToEndIT.Host.class})
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiPlacementEndToEndIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Client.REQUEST_TIMEOUT).build();
    private static final String V1 = "/api/console/v1";
    private static final String CONTAINER = "sm-orders";

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static UUID secondTarget;
    private static HttpServer stubOne;
    private static HttpServer stubTwo;
    private static int stubOnePort;
    private static int stubTwoPort;
    private static ListAppender<ILoggingEvent> drops;
    private static Logger publisherLog;
    private static Level previousLevel;

    private static UUID instance;
    private static UUID p1;
    private static UUID p2;
    private static UUID solution;
    private static long baseGeneration;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private RoutingAdapter adapter;

    @Autowired
    private HealthPoller poller;

    @Autowired
    private TelemetryIngestPort ingest;

    @Autowired
    private RenderedConfigSource renderer;

    @Autowired
    private ConfigHasher hasher;

    /** The host: a per-placement in-memory adapter, the REAL renderer, and an executor that publishes its rollout notices. */
    @TestConfiguration
    static class Host {

        @Bean
        RoutingAdapter runtimeAdapter() {
            return new RoutingAdapter();
        }

        @Bean
        RenderedConfigSource renderedConfigSource(ConfigGenerationRows rows, SecretResolver secrets, ConfigHasher hasher) {
            return new ConfigGenerationRenderedConfigSource(rows, secrets, hasher);
        }

        @Bean
        ApplyExecutor applyExecutor(AdmissionService admissionService, StepIntentService stepIntentService,
                AttemptService attemptService, OutcomeService outcomeService, RuntimeAdapter runtimeAdapter,
                RenderedConfigSource renderedConfigSource, HealthGate healthGate, ResumePlanner resumePlanner,
                ApplyEngineProperties applyEngineProperties, ApplyEventEmitter events, LivePlacements livePlacements) {
            // The registry's live placements (BE-D46) and the CONFIGURED emitter (not the silent one ApplyItSupport uses),
            // so the per-member instance.rollout notices are real ones.
            return new ApplyExecutor(admissionService, stepIntentService, attemptService, outcomeService, runtimeAdapter,
                    renderedConfigSource, healthGate, resumePlanner, applyEngineProperties, events, livePlacements);
        }
    }

    @BeforeAll
    static void listenForOfferedNotices() {
        // The mesh is in its declared degraded state here (no sm.mesh.*): each offered notice is dropped, counted and logged
        // with its topic at DEBUG. The topic is what is observed (ApplyEventsProductionBootIT's technique).
        publisherLog = (Logger) LoggerFactory.getLogger(JcsmpEventPublisher.class);
        previousLevel = publisherLog.getLevel();
        drops = new ListAppender<>();
        drops.start();
        publisherLog.setLevel(Level.DEBUG);
        publisherLog.addAppender(drops);
    }

    @AfterAll
    static void stopListeningAndStopTheStubs() {
        publisherLog.detachAppender(drops);
        publisherLog.setLevel(previousLevel);
        stubOne.stop(0);
        stubTwo.stop(0);
    }

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) throws IOException {
        stubOne = healthStub();
        stubTwo = healthStub();
        stubOnePort = stubOne.getAddress().getPort();
        stubTwoPort = stubTwo.getAddress().getPort();

        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add(TwoSchemaTestDatabase.HEALTH_POLLER, () -> "true");
        // An hour: the poller's own schedule never fires inside this class; the test drives pollOnce().
        registry.add("sm.dp.health.interval", () -> "1h");
        registry.add("sm.dp.health.timeout", () -> "2s");
        registry.add("sm.dp.health.jitter", () -> "0");
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
        registry.add("sm.dp.fleet.advance-interval", () -> "200ms");
        ownerJdbc = provisioned.db().ownerJdbc;
        world = DeclareFixtures.seed(ownerJdbc);
        secondTarget = secondTargetInEnvironmentOne(ownerJdbc, world);
        // The registered address (OR-82) of both targets is the loopback the stubs listen on: the only thing a direct probe may use.
        ownerJdbc.update("UPDATE sm_data.target_connection SET host = '127.0.0.1' WHERE tenant_id = :t AND target_id IN"
                + " (:one, :two)", Map.of("t", world.organization(), "one", world.targetOne(), "two", secondTarget));
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache-push", 2,
                DeclareFixtures.ADMIN);
    }

    private static HttpServer healthStub() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/actuator/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    /**
     * A second target in the SAME environment as {@code targetOne}, with its own connection record and envelope. The
     * fixtures seed one target per environment and nothing in the contract creates a target from a lane, so this writes the
     * same rows {@link DeclareFixtures#seed} does, as the owner.
     */
    private static UUID secondTargetInEnvironmentOne(NamedParameterJdbcTemplate jdbc, World seeded) {
        UUID envScope = jdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'environment'"
                + " AND environment_id = :e", Map.of("e", seeded.envOne()), UUID.class);
        UUID admin = jdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o"
                + " AND username = :u", Map.of("o", seeded.organization(), "u", DeclareFixtures.ADMIN), UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                + " transport, created_by, created_at) VALUES (:id, :o, :s, 'target-one-b', 'docker', 'ssh-docker',"
                + " :c, now())", Map.of("id", id, "o", seeded.organization(), "s", envScope, "c", admin));
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix, created_by,"
                + " created_at) VALUES (:t, :g, 'ssh-docker', '127.0.0.1', 22, 'sm', :fp, :cred, '/srv/sm', 'sm-', :by,"
                + " now())", Map.of("t", seeded.organization(), "g", id, "fp", "SHA256:" + "D".repeat(43),
                "cred", UUID.randomUUID(), "by", UUID.randomUUID()));
        return id;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Client client() {
        return new Client(port);
    }

    private static JsonNode body(HttpResponse<String> response) throws Exception {
        return MAPPER.readTree(response.body());
    }

    private static String instancePath(String suffix) {
        return "/api/dp/instances/" + instance + suffix;
    }

    private HttpResponse<String> delete(String path, Login login) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(Client.REQUEST_TIMEOUT).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A valid add body for {@code addPlacement}: only the host-side ports are the caller's; the probe comes from the package. */
    private static ObjectNode addBody(UUID target, String container, int healthPort) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("targetId", target.toString());
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", container);
        placement.put("configDirectory", "/srv/sm/" + container);
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", healthPort);
        placement.putObject("metricsPorts").put("8081", healthPort + 30_000 <= 65_535 ? healthPort + 30_000
                : healthPort - 30_000);
        return body;
    }

    private long stateVersion(Client http, Login login) throws Exception {
        return body(http.get(instancePath("/designer"), login)).get("stateVersion").asLong();
    }

    private long stage(Client http, String namespace) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        return ApplyItSupport.stage(http, instance, stateVersion(http, operator), namespace);
    }

    private JsonNode applyTo(Client http, UUID placement, long generation) throws Exception {
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        ObjectNode apply = MAPPER.createObjectNode();
        apply.put("generation", generation).put("strategy", "manual").put("placementId", placement.toString());
        HttpResponse<String> accepted = http.post(instancePath("/apply"), operator, apply);
        assertEquals(202, accepted.statusCode(), accepted.body());
        return ApplyItSupport.awaitTerminal(http, operator, instance, body(accepted).get("applyId").asText(),
                Duration.ofSeconds(30));
    }

    private String reached(Client http) throws Exception {
        HttpResponse<String> response = http.get(instancePath("/config-ladder"), http.login(DeclareFixtures.ADMIN));
        assertEquals(200, response.statusCode(), response.body());
        return body(response).get("reached").asText();
    }

    private JsonNode health(Client http) throws Exception {
        HttpResponse<String> response = http.get(instancePath("/health"), http.login(DeclareFixtures.ADMIN));
        assertEquals(200, response.statusCode(), response.body());
        return body(response);
    }

    private List<UUID> livePlacements(Client http) throws Exception {
        List<UUID> out = new ArrayList<>();
        body(http.get(instancePath(""), http.login(DeclareFixtures.ADMIN))).get("placements")
                .forEach(p -> out.add(UUID.fromString(p.get("placementId").asText())));
        return out;
    }

    private long newestGeneration() {
        Long n = ownerJdbc.queryForObject("SELECT max(generation) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), Long.class);
        return n == null ? 0 : n;
    }

    private PlacementRef ref(UUID placement) {
        return new PlacementRef(new TenantId(world.organization()), new InstanceId(instance), new PlacementId(placement));
    }

    /** The topics the mesh transport was offered (and dropped, the mesh being disabled), in order. */
    private static List<String> offered() {
        synchronized (drops) {
            List<String> out = new ArrayList<>();
            drops.list.forEach(e -> out.add(e.getFormattedMessage()));
            return out;
        }
    }

    private static long offered(String fragment) {
        return offered().stream().filter(t -> t.contains(fragment)).count();
    }

    /** The publish follows a commit by microseconds and the HTTP answer can win that race, so wait for the condition. */
    private static void awaitOffered(Predicate<List<String>> condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.test(offered())) {
                return;
            }
            Thread.sleep(50);
        }
        fail("never offered: " + what + "; offered " + offered());
    }

    private JsonNode awaitFleet(Client http, Login login, String fleetId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        JsonNode last = null;
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = http.get("/api/dp/fleet-applies/" + fleetId, login);
            assertEquals(200, response.statusCode(), response.body());
            last = body(response);
            if (!last.get("state").asText().equals("RUNNING")) {
                return last;
            }
            Thread.sleep(100);
        }
        fail("fleet " + fleetId + " never left RUNNING; last seen " + last);
        throw new AssertionError("unreachable");
    }

    /** A fleet whose members are the instance's two placements, in order P1, P2. */
    private ObjectNode fleetOverBoth(long generation) {
        return fleetOver(instance, List.of(p1, p2), generation, "manual");
    }

    private ObjectNode fleetOver(UUID ofInstance, List<UUID> placements, long generation, String strategy) {
        ObjectNode fleet = MAPPER.createObjectNode();
        fleet.put("solutionId", solution.toString());
        ArrayNode members = fleet.putArray("members");
        for (UUID placement : placements) {
            members.addObject().put("instanceId", ofInstance.toString()).put("placementId", placement.toString())
                    .put("generation", generation);
        }
        fleet.put("strategy", strategy);
        fleet.put("batchSize", 1);
        fleet.put("onFailure", "halt-remaining");
        return fleet;
    }

    private static Set<UUID> stepPlacements(JsonNode observation) {
        Set<UUID> out = new LinkedHashSet<>();
        observation.get("steps").forEach(s -> {
            if (s.hasNonNull("placementId")) {
                out.add(UUID.fromString(s.get("placementId").asText()));
            }
        });
        return out;
    }

    // ----------------------------------------------------------------------------------------------- the matrix

    @Test
    @Order(1)
    void anInstanceIsDeclaredWithOnePlacementAppliedAndPolledAndTheLadderIsPersisted() throws Exception {
        Client http = client();
        Declared declared = ApplyItSupport.declare(http, world, "orders-cache", CONTAINER, stubOnePort);
        instance = declared.instance();
        p1 = declared.placement();
        solution = UUID.fromString(body(http.get(instancePath(""), http.login(DeclareFixtures.OPERATOR_ONE)))
                .get("solutionId").asText());

        baseGeneration = ApplyItSupport.stage(http, instance, declared.version(), "orders-1");
        JsonNode landed = applyTo(http, p1, baseGeneration);

        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        assertEquals("persisted", reached(http), "one live placement, applied: persisted");
        poller.pollOnce();
        JsonNode polled = health(http);
        assertEquals(1, polled.get("placements").size(), polled.toString());
        assertEquals("GREEN", polled.at("/placements/0/state").asText());
        assertFalse(polled.hasNonNull("availability"), "every live placement has been polled: " + polled);
        // the landing offered its config-state notice (the ladder moved off 'desired'); let it settle before counting
        awaitOffered(t -> t.stream().anyMatch(m -> m.contains("/config-state")), "the first config-state notice");
        Thread.sleep(300);
    }

    @Test
    @Order(2)
    void aSecondPlacementOnAnotherTargetWithTheSameContainerNameDropsTheLadderAndAnnouncesItOnce() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long configStateBefore = offered("/config-state");
        long placementsBefore = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                + " AND instance_id = :i", Map.of("t", world.organization(), "i", instance), Long.class);

        HttpResponse<String> response = http.post(instancePath("/placements"), operator,
                addBody(secondTarget, CONTAINER, stubTwoPort));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode added = body(response);
        assertEquals(2, added.get("placements").size(), added.toString());
        assertEquals(p1.toString(), added.at("/placements/0/placementId").asText());
        p2 = UUID.fromString(added.at("/placements/1/placementId").asText());
        assertEquals(world.targetOne().toString(), added.at("/placements/0/targetId").asText());
        assertEquals(secondTarget.toString(), added.at("/placements/1/targetId").asText());
        assertEquals(CONTAINER, added.at("/placements/0/containerName").asText());
        assertEquals(CONTAINER, added.at("/placements/1/containerName").asText(),
                "the same container name on a different target: a legal pair, and the case the placement-aware journal is for");
        assertEquals(placementsBefore + 1, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement"
                + " WHERE tenant_id = :t AND instance_id = :i", Map.of("t", world.organization(), "i", instance),
                Long.class));

        // OQ-8.10 (a): the new placement has been given nothing, so the instance is back at 'rendered' - and says so, once
        assertEquals("rendered", reached(http));
        awaitOffered(t -> t.stream().filter(m -> m.contains("/config-state")).count() > configStateBefore,
                "a config-state notice for the ladder's drop");
        Thread.sleep(300);
        assertEquals(configStateBefore + 1, offered("/config-state"),
                "the stored rung moved once (persisted to rendered), so exactly one notice");

        // P2 is unpolled: getHealth says so, and lists only the placement it has a verdict for; then the poller reaches it
        JsonNode unpolled = health(http);
        assertEquals(1, unpolled.get("placements").size(), unpolled.toString());
        assertEquals(p1.toString(), unpolled.at("/placements/0/placementId").asText());
        assertTrue(unpolled.hasNonNull("availability") && unpolled.toString().contains("have not been polled yet"),
                "the unpolled placement is declared, not hidden: " + unpolled);
        poller.pollOnce();
        JsonNode polled = health(http);
        assertEquals(2, polled.get("placements").size(), polled.toString());
        Set<String> ids = new LinkedHashSet<>();
        polled.get("placements").forEach(p -> {
            ids.add(p.get("placementId").asText());
            assertEquals("GREEN", p.get("state").asText());
        });
        assertEquals(Set.of(p1.toString(), p2.toString()), ids, "getHealth lists both placements, each with its own verdict");
        assertFalse(polled.hasNonNull("availability"), polled.toString());
    }

    @Test
    @Order(3)
    void pullPathMetricsAreOneSeriesPerPlacement() throws Exception {
        Client http = client();
        Instant monthStart = Instant.now().atZone(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant last = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
        Instant start = last.minusSeconds(30L * 4);
        if (start.isBefore(monthStart)) {
            start = monthStart.plusSeconds(1);
        }
        for (UUID placement : List.of(p1, p2)) {
            List<IngestSample> batch = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Instant ts = start.plusSeconds(30L * i);
                batch.add(new IngestSample("cache.size", ts, 100 + i, ts));
            }
            IngestResult result = ingest.ingest(IngestBatch.polled(world.organization(), placement, batch));
            assertEquals(IngestResult.Committed.class, result.getClass(), "ingest: " + result);
        }

        ObjectNode query = MAPPER.createObjectNode();
        query.put("queryVersion", 1);
        query.put("metric", "cache.size");
        ObjectNode selector = query.putObject("selector");
        selector.put("entityType", "cache-engine");
        selector.putArray("instanceIds").add(instance.toString());
        selector.putObject("labels");
        query.putObject("range").put("from", start.minusSeconds(60).toString()).put("to", Instant.now().toString());
        query.put("resolution", "auto");
        query.put("seriesBy", "replica");
        query.putObject("aggregation").put("acrossReplicas", "none").put("acrossInstances", "none");
        HttpResponse<String> response = http.post(V1 + "/query/range", http.login(DeclareFixtures.ADMIN), query);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode series = body(response).get("series");
        Set<String> placements = new LinkedHashSet<>();
        series.forEach(s -> placements.add(s.at("/seriesRef/placementId").asText()));
        assertEquals(2, series.size(), "one series per placement, not one per instance: " + series);
        assertEquals(Set.of(p1.toString(), p2.toString()), placements,
                "each series carries its own placement's identity (OR-75)");
    }

    @Test
    @Order(4)
    void anInstanceThatDeclaresPushMetricsIsRefusedASecondPlacement409AndNothingIsWritten() throws Exception {
        Client http = client();
        // A cache package with one solace_event (push) metric added: the instance has a single placement, which is legal,
        // and the push path cannot attribute an event to one of TWO placements (PushAttribution: ambiguous_placement).
        ObjectNode pushPackage = DeclareFixtures.cachePackage();
        pushPackage.put("solution", "cache-push");
        ObjectNode metric = ((ArrayNode) pushPackage.at("/components/0/metrics")).addObject();
        metric.put("name", "cache.push_heartbeats").put("metricType", "counter").put("unit", "events")
                .put("layer", "package-baseline");
        ObjectNode source = metric.putObject("source");
        source.put("kind", "solace_event").put("topicPattern", "*/*/*/*/*/*/*/*/heartbeat");
        source.putObject("instanceKey").put("configField", "namespace").put("pointer", "/header/adapterID");
        source.put("extract", "count").put("onUnavailable", "degrade");
        ObjectNode load = MAPPER.createObjectNode();
        load.set("package", pushPackage);
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), "the push variant of the cache package loads: " + loaded.body());
        UUID pushSolution = UUID.fromString(body(loaded).get("solutionId").asText());
        HttpResponse<String> declared = http.post("/api/dp/environments/" + world.envOne() + "/instances",
                http.login(DeclareFixtures.OPERATOR_ONE),
                DeclareFixtures.declareBody(pushSolution, world.targetOne(), "push-cache", "sm-push", 9611));
        assertEquals(201, declared.statusCode(), "a push instance with ONE placement is declared: " + declared.body());
        String pushInstance = body(declared).get("instanceId").asText();
        long before = ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class);

        HttpResponse<String> refused = http.post("/api/dp/instances/" + pushInstance + "/placements",
                http.login(DeclareFixtures.OPERATOR_ONE), addBody(secondTarget, "sm-push", 9612));

        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("CONFLICT", body(refused).get("code").asText());
        assertEquals(before, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t",
                Map.of("t", world.organization()), Long.class), "S-e: the refusal wrote nothing");
    }

    @Test
    @Order(5)
    void aFleetOverBothPlacementsAppliesEachAndTheLadderIsPersistedOnlyAfterTheSecond() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        // P1 alone takes the next generation: P2 is behind it, so the instance stays at 'rendered' (the lowest live rung)
        long g2 = stage(http, "orders-2");
        assertEquals("STAGED", applyTo(http, p1, g2).get("state").asText());
        assertEquals("rendered", reached(http), "P1 has generation " + g2 + "; P2 has nothing: the lowest rung counts");

        long g3 = stage(http, "orders-3");
        HttpResponse<String> submitted = http.post("/api/dp/fleet-applies", operator, fleetOverBoth(g3));
        assertEquals(202, submitted.statusCode(), submitted.body());
        JsonNode landed = awaitFleet(http, operator, body(submitted).get("fleetId").asText());

        assertEquals("SUCCEEDED", landed.get("state").asText(), landed.toString());
        assertEquals(2, landed.get("members").size());
        assertEquals(p1.toString(), landed.at("/members/0/placementId").asText());
        assertEquals(p2.toString(), landed.at("/members/1/placementId").asText());
        Set<String> applyIds = new LinkedHashSet<>();
        for (JsonNode member : landed.get("members")) {
            assertEquals("admitted", member.get("disposition").asText());
            assertEquals("succeeded", member.get("settled").asText());
            assertEquals("STAGED", member.get("requestState").asText());
            applyIds.add(member.get("applyId").asText());
            // the member's own request names its own placement, step by step
            JsonNode own = body(http.get(instancePath("/apply/observe?applyId=" + member.get("applyId").asText()),
                    operator)).get("items").get(0);
            assertEquals(member.get("placementId").asText(), own.get("placementId").asText());
            assertEquals(Set.of(UUID.fromString(member.get("placementId").asText())), stepPlacements(own));
        }
        assertEquals(2, applyIds.size(), "two members, two requests, two ids");
        assertEquals("persisted", reached(http), "every live placement now holds generation " + g3);

        // one apply-outcome and at least one rollout per member (OR-4: events are placement-scoped, one request each)
        for (String applyId : applyIds) {
            awaitOffered(t -> t.stream().filter(m -> m.contains("/apply-outcome/" + applyId)).count() == 1
                    && t.stream().anyMatch(m -> m.contains("/rollout/" + applyId)), "the notices of member " + applyId);
            assertEquals(1, offered("/apply-outcome/" + applyId), "exactly one apply-outcome per member");
        }
    }

    @Test
    @Order(6)
    void aFailedSecondMemberCompletesTheFleetWithFailuresAndTheLadderSitsAtThatPlacementsRung() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long g4 = stage(http, "orders-4");
        InMemoryRuntimeAdapter hostTwo = adapter.host(p2);
        hostTwo.fault(InMemoryRuntimeAdapter.Fault.PREFLIGHT_REFUSED);
        try {
            HttpResponse<String> submitted = http.post("/api/dp/fleet-applies", operator, fleetOverBoth(g4));
            assertEquals(202, submitted.statusCode(), submitted.body());
            JsonNode landed = awaitFleet(http, operator, body(submitted).get("fleetId").asText());

            assertEquals("COMPLETED_WITH_FAILURES", landed.get("state").asText(), landed.toString());
            assertEquals("succeeded", landed.at("/members/0/settled").asText());
            assertEquals("failed", landed.at("/members/1/settled").asText());
            assertTrue(landed.at("/members/1/requestState").asText().startsWith("FAILED"), landed.toString());
        } finally {
            hostTwo.fault(InMemoryRuntimeAdapter.Fault.NONE);
        }
        assertEquals("rendered", reached(http), "P1 holds " + g4 + ", P2's attempt failed: the instance is at P2's rung");

        // the retry the operator would make: the same generation to P2 alone, now that its host answers
        assertEquals("STAGED", applyTo(http, p2, g4).get("state").asText());
        assertEquals("persisted", reached(http));
    }

    @Test
    @Order(7)
    void aRollbackOfOnePlacementAloneMintsAGenerationAndTheLadderDropsUntilTheOtherFollows() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        long before = newestGeneration();
        // generation 'baseGeneration' was applied to P1 (test 1), so it is a legal rollback target for P1
        ObjectNode rollback = MAPPER.createObjectNode();
        rollback.put("strategy", "manual").put("placementId", p1.toString()).put("generation", baseGeneration);

        HttpResponse<String> accepted = http.post(instancePath("/apply/rollback"), operator, rollback);

        assertEquals(202, accepted.statusCode(), accepted.body());
        JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, instance, body(accepted).get("applyId").asText(),
                Duration.ofSeconds(30));
        assertEquals("STAGED", landed.get("state").asText(), landed.toString());
        assertEquals(p1.toString(), landed.get("placementId").asText());
        long minted = newestGeneration();
        assertEquals(before + 1, minted, "OQ-8.7 (a): a one-placement operation mints an INSTANCE generation");
        assertEquals("rendered", reached(http), "only P1 has generation " + minted + "; P2 reads behind");

        assertEquals("STAGED", applyTo(http, p2, minted).get("state").asText());
        assertEquals("persisted", reached(http), "P2 followed: every live placement holds the newest generation");
    }

    @Test
    @Order(8)
    void acceptRuntimeConfigOnOnePlacementReadsThatPlacementsFileAndMintsOneInstanceGeneration() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        long newest = newestGeneration();
        String canonical = new String(renderer.render(ref(p2), newest).bytes(), StandardCharsets.UTF_8);
        String edited = canonical.replace("max-size: 1000", "max-size: 250000");
        assertTrue(edited.contains("max-size: 250000"), "the rendering carries the value the test edits: " + canonical);
        InMemoryRuntimeAdapter hostTwo = adapter.host(p2);
        hostTwo.writeFileAsHuman(hostTwo.replicas().get(0), edited.getBytes(StandardCharsets.UTF_8), hasher);
        ObjectNode accept = MAPPER.createObjectNode();
        accept.put("baseStateVersion", body(http.get(instancePath(""), admin)).get("stateVersion").asLong());
        accept.put("placementId", p2.toString());

        HttpResponse<String> response = http.post(instancePath("/config/accept-runtime"), admin, accept);

        assertEquals(200, response.statusCode(), response.body());
        JsonNode accepted = body(response);
        assertEquals(p2.toString(), accepted.get("placementId").asText(), "P2's file was read, not P1's");
        assertFalse(accepted.get("unchanged").asBoolean());
        assertEquals(newest + 1, accepted.get("generation").asLong());
        assertEquals("accept-runtime", ownerJdbc.queryForObject("SELECT origin FROM sm_data.config_generation"
                + " WHERE tenant_id = :t AND instance_id = :i AND generation = :g",
                Map.of("t", world.organization(), "i", instance, "g", newest + 1), String.class));
        assertEquals("rendered", reached(http), "the accepted generation has been applied to neither placement");
    }

    @Test
    @Order(9)
    void aStopOfOnePlacementAddressesThatPlacementAndTheInstanceWideStopAddressesBothWithTheirOwnIds() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> one = http.post(instancePath("/stop"), operator,
                MAPPER.createObjectNode().put("placementId", p2.toString()));
        assertEquals(202, one.statusCode(), one.body());
        JsonNode stoppedOne = ApplyItSupport.awaitTerminal(http, operator, instance, body(one).get("applyId").asText(),
                Duration.ofSeconds(30));
        assertEquals("STOPPED", stoppedOne.get("state").asText(), stoppedOne.toString());
        assertEquals(Set.of(p2), stepPlacements(stoppedOne), "a placement-scoped stop touched only that placement");

        HttpResponse<String> all = http.post(instancePath("/stop"), operator, MAPPER.createObjectNode());
        assertEquals(202, all.statusCode(), all.body());
        JsonNode stoppedAll = ApplyItSupport.awaitTerminal(http, operator, instance, body(all).get("applyId").asText(),
                Duration.ofSeconds(30));
        assertEquals("STOPPED", stoppedAll.get("state").asText(), stoppedAll.toString());
        assertFalse(stoppedAll.hasNonNull("placementId"), "instance-wide: " + stoppedAll);
        assertEquals(Set.of(p1, p2), stepPlacements(stoppedAll),
                "S-d: a STOP step for each LIVE placement, each carrying its own placementId (BE-D44, BE-D46)");
        assertTrue(stoppedAll.get("steps").size() >= 2, stoppedAll.toString());
    }

    @Test
    @Order(10)
    void aRetiredPlacementIsNoLongerAddressedAndTheInstanceWideStopStillLands() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);

        HttpResponse<String> retired = delete(instancePath("/placements/" + p2 + "?workload=leave-running"), operator);

        assertEquals(200, retired.statusCode(), retired.body());
        assertEquals("left-running", body(retired).get("workload").asText());
        assertEquals(List.of(p1), livePlacements(http), "P2 is gone from the instance's live placements");
        assertEquals(1L, ownerJdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p AND retired_at IS NOT NULL", Map.of("t", world.organization(), "p", p2),
                Long.class), "the row is kept");
        poller.pollOnce();
        assertEquals(1, health(http).get("placements").size(), "a retired placement is no longer polled or listed");

        // R6's defect: the history enumerator would try to stop P2 and fail the request; the registry's does not
        HttpResponse<String> all = http.post(instancePath("/stop"), operator, MAPPER.createObjectNode());
        assertEquals(202, all.statusCode(), all.body());
        JsonNode stopped = ApplyItSupport.awaitTerminal(http, operator, instance, body(all).get("applyId").asText(),
                Duration.ofSeconds(30));
        assertEquals("STOPPED", stopped.get("state").asText(), stopped.toString());
        assertEquals(Set.of(p1), stepPlacements(stopped), "only the live placement was stopped");
    }

    /**
     * W4 review W4A-4: the tests above are {@code manual} throughout, so none of them leaves {@code persisted}. This one
     * walks the gated strategy ({@code rolling-health-gated}: served, and exempt from start observation because its proof
     * is its gate) over TWO placements of a fresh instance, so the "lowest rung across live placements" rule is exercised
     * ABOVE {@code persisted} too: P1 alone gated does not raise the instance past {@code rendered} while P2 holds nothing,
     * and a gated fleet over both lands both members {@code APPLIED} and the instance {@code restart-observed}.
     *
     * <p>A fresh instance, because the in-memory host of a placement is created with its health signal at first use
     * ({@link RoutingAdapter#healthSignal}) and the placements of the instance above were created without one.
     * <b>Not run</b> (Docker was down when it was written): its premises are that the gated strategy's gate passes on the
     * in-memory host's signal exactly as {@code RollingHealthGatedIT} shows, and that a gated fleet member needs no
     * {@code outageConfirmedBy}.
     */
    @Test
    @Order(11)
    void aGatedFleetOverTwoPlacementsLandsBothAppliedAndTheLadderIsRestartObservedOnlyAfterTheSecond() throws Exception {
        Client http = client();
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        adapter.healthSignal = true;   // the declared probe publishes a signal for each placement first used from now on
        try {
            ApplyItSupport.Declared declared = ApplyItSupport.declare(http, world, "gated-pair", "sm-gated-pair", 9701);
            UUID gated = declared.instance();
            UUID gatedP1 = declared.placement();
            String path = "/api/dp/instances/" + gated;
            long g1 = ApplyItSupport.stage(http, gated, declared.version(), "gated-1");
            ObjectNode single = MAPPER.createObjectNode();
            single.put("generation", g1).put("strategy", "rolling-health-gated").put("placementId", gatedP1.toString());
            HttpResponse<String> accepted = http.post(path + "/apply", operator, single);
            assertEquals(202, accepted.statusCode(), accepted.body());
            JsonNode landed = ApplyItSupport.awaitTerminal(http, operator, gated, body(accepted).get("applyId").asText(),
                    Duration.ofSeconds(30));
            assertEquals("APPLIED", landed.get("state").asText(), landed.toString());
            assertEquals("restart-observed", ladderOf(http, path), "one live placement, gated and verified: restart-observed");

            HttpResponse<String> added = http.post(path + "/placements", operator, addBody(secondTarget, "sm-gated-pair", 9702));
            assertEquals(201, added.statusCode(), added.body());
            UUID gatedP2 = UUID.fromString(body(added).at("/placements/1/placementId").asText());
            assertEquals("rendered", ladderOf(http, path), "the new placement holds nothing: the lowest rung counts");

            long g2 = ApplyItSupport.stage(http, gated,
                    body(http.get(path + "/designer", operator)).get("stateVersion").asLong(), "gated-2");
            single.put("generation", g2);
            HttpResponse<String> again = http.post(path + "/apply", operator, single);
            assertEquals(202, again.statusCode(), again.body());
            assertEquals("APPLIED", ApplyItSupport.awaitTerminal(http, operator, gated,
                    body(again).get("applyId").asText(), Duration.ofSeconds(30)).get("state").asText());
            assertEquals("rendered", ladderOf(http, path), "P1 alone, gated and verified, does not raise the instance: P2 "
                    + "still holds nothing");

            long g3 = ApplyItSupport.stage(http, gated,
                    body(http.get(path + "/designer", operator)).get("stateVersion").asLong(), "gated-3");
            HttpResponse<String> submitted = http.post("/api/dp/fleet-applies", operator,
                    fleetOver(gated, List.of(gatedP1, gatedP2), g3, "rolling-health-gated"));
            assertEquals(202, submitted.statusCode(), submitted.body());
            JsonNode fleet = awaitFleet(http, operator, body(submitted).get("fleetId").asText());

            assertEquals("SUCCEEDED", fleet.get("state").asText(), fleet.toString());
            for (JsonNode member : fleet.get("members")) {
                assertEquals("APPLIED", member.get("requestState").asText(), member.toString());
            }
            assertEquals("restart-observed", ladderOf(http, path),
                    "both live placements are gated and verified at generation " + g3 + ": the lowest rung is restart-observed");
        } finally {
            adapter.healthSignal = false;
        }
    }

    private String ladderOf(Client http, String instancePath) throws Exception {
        HttpResponse<String> response = http.get(instancePath + "/config-ladder", http.login(DeclareFixtures.ADMIN));
        assertEquals(200, response.statusCode(), response.body());
        return body(response).get("reached").asText();
    }
}
