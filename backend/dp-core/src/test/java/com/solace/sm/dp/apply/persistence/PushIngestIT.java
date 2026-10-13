package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.meshbridge.ingest.BrokerEndpoint;
import com.solace.sm.dp.meshbridge.ingest.BrokerMessageSink;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptions;
import com.solace.sm.dp.meshbridge.ingest.RawBrokerMessage;
import com.solace.sm.dp.metrics.collect.CollectorProperties;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.push.BrokerConnectionRecord;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder;
import com.solace.sm.dp.metrics.push.PushAttribution;
import com.solace.sm.dp.metrics.push.PushIngestService;
import com.solace.sm.dp.metrics.push.PushProperties;
import com.solace.sm.dp.metrics.push.PushRowsAdapter;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-5.1 over a real {@code sm_data} and {@code sm_metrics}, with NO broker: events are handed to the service exactly
 * as the broker SDK's receive thread hands them (as bytes and a topic, tagged with the connection), and the REAL plan, attribution,
 * ingest service and repositories do the rest. The broker half is {@code PushBrokerIT}.
 *
 * <p>The File Mesh events are in the MEASURED shape (facts F2, F9, F11, F12) with synthetic values; the declaration of the metric
 * that reads them is taken from the REVIEWED fixture ({@code contracts/validation/fixtures/filemesh-ftp-solace.package.json}),
 * not retyped, so Acceptance #4 is not a synthetic-only package.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class PushIngestIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMPONENT = "COMP_SOURCE_FTP@FILEEVENTS";
    private static final String FIELD = "file-events.source.general.adapter_id";
    private static final String VERSION = "1";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"src\",\"type\":\"" + COMPONENT + "\"}]}";
    private static final Instant T0 = IngestFixture.base();

    private static IngestFixture ingest;
    private static CollectFixture fx;
    private static IngestService telemetry;
    private static JdbcBrokerConnectionRepository connections;
    private static JdbcBrokerCounterRepository brokerCounters;

    @BeforeAll
    static void database() {
        ingest = IngestFixture.create("p51_push");
        fx = new CollectFixture(ingest);
        telemetry = ingest.service(IngestFixture.fast(50, 500));
        connections = new JdbcBrokerConnectionRepository(ingest.jdbc);
        brokerCounters = new JdbcBrokerCounterRepository(ingest.jdbc);
    }

    @AfterAll
    static void close() {
        ingest.close();
    }

    // ---- the reviewed declaration ------------------------------------------------------------------------------------

    /** The metrics the REVIEWED File Mesh fixture declares on the file-events source, exactly as the gateway's builder stores them. */
    static List<MetricDefinitionSpec> reviewedFixtureMetrics() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("contracts/validation/fixtures"))) {
            dir = dir.getParent();
        }
        JsonNode pkg = JSON.readTree(Files.readString(dir.resolve("contracts/validation/fixtures/filemesh-ftp-solace.package.json")));
        List<MetricDefinitionSpec> out = new ArrayList<>();
        for (JsonNode component : pkg.get("components")) {
            for (JsonNode metric : component.path("metrics")) {
                if ("solace_event".equals(metric.path("source").path("kind").asText())) {
                    out.add(new MetricDefinitionSpec(metric.get("name").asText(), component.get("type").asText(),
                            metric.get("metricType").asText(), metric.get("unit").asText(), "solace_event",
                            JSON.writeValueAsString(metric.get("source")), null, true));
                }
            }
        }
        return out;
    }

    /** A count of heartbeats, declared as the reviewed fixture declares it (here retyped for tests that need a second, different version). */
    private static MetricDefinitionSpec heartbeatsDef() {
        return new MetricDefinitionSpec("filemesh.heartbeats", COMPONENT, "counter", "events", "solace_event",
                "{\"kind\":\"solace_event\",\"topicPattern\":\"*/*/*/*/*/*/*/*/heartbeat\",\"instanceKey\":{\"configField\":\""
                        + FIELD + "\",\"pointer\":\"/header/adapterID\"},\"extract\":\"count\",\"onUnavailable\":\"degrade\"}",
                null, true);
    }

    private static MetricDefinitionSpec numeric() {
        // SYNTHETIC (the reviewed fixture declares the count only): a value extraction in the measured fcomplete shape (F12)
        return new MetricDefinitionSpec("filemesh.file_run_time_ms", COMPONENT, "gauge", "ms", "solace_event",
                "{\"kind\":\"solace_event\",\"topicPattern\":\"*/*/*/*/*/*/*/*/fcomplete\",\"instanceKey\":{\"configField\":\""
                        + FIELD + "\",\"pointer\":\"/header/adapterID\"},\"extract\":\"value\","
                        + "\"valuePointer\":\"/fileStateEvent/runTimeMs\",\"onUnavailable\":\"degrade\"}", null, true);
    }

    // ---- the world ---------------------------------------------------------------------------------------------------

    private record World(UUID tenant, UUID environment, UUID instance, List<UUID> placements, UUID connection,
            IngestFixture.Site site) {
    }

    private final Map<UUID, Long> restartObserved = new HashMap<>();
    private final List<PushIngestService> services = new ArrayList<>();

    /** A tenant with one ACTIVE instance configured with {@code adapterId}, in force at generation 1, with {@code placements} live placements. */
    private World world(String adapterId, int placementCount, List<MetricDefinitionSpec> definitions) {
        UUID tenant = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, VERSION, definitions, List.of());
        CollectFixture.Instance instance = fx.instance(tenant, solution, VERSION, "via-ssh", "active");
        UUID environment = fx.instances.find(tenant, instance.instance()).orElseThrow().environmentId();
        List<UUID> placements = new ArrayList<>();
        for (int i = 0; i < placementCount; i++) {
            placements.add(fx.placement(instance, fx.target(tenant, "vm-" + i + ".corp.example"), Map.of()));
        }
        ConfigRecordServiceImpl config = new ConfigRecordServiceImpl(ingest.transactions,
                new JdbcConfigGenerationRepository(ingest.jdbc), TestHashers.keyed(), new Retention(20), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(ingest.jdbc));
        long base = ingest.jdbc.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance.instance()),
                Long.class);
        long generation = config.stage(tenant, instance.instance(), new StageCommand(base, "file-mesh", VERSION, SELECTION,
                "{\"src\":{\"" + FIELD + "\":\"" + adapterId + "\"}}",
                List.of(RenderedEntryValue.plain("src", "file-events.source.general.adapter_id", "\"" + adapterId + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
        fx.activeList(instance, generation, definitions.stream().map(MetricDefinitionSpec::metricName).toArray(String[]::new));
        restartObserved.put(instance.instance(), generation);
        UUID connection = UUID.randomUUID();
        connections.insert(new BrokerConnectionRecord(tenant, environment, connection, "tcps",
                "broker-" + connection + ".example.test", 55443, "vpn", "sm-push", UUID.randomUUID(), ACTOR, Instant.now(), null));
        IngestFixture.Site site = new IngestFixture.Site(tenant, instance.instance(), placements.get(0), solution, VERSION);
        return new World(tenant, environment, instance.instance(), placements, connection, site);
    }

    /** The subscriptions the service is told to hold, as a recording stand-in for the broker session manager. */
    private static final class Subscriptions implements BrokerSubscriptions {
        final Map<UUID, Set<String>> held = new HashMap<>();

        @Override
        public void reconcile(UUID tenantId, UUID connectionId, BrokerEndpoint endpoint, UUID credentialRef,
                Set<String> patterns, BrokerMessageSink sink) {
            held.put(connectionId, patterns);
        }

        @Override
        public void release(UUID tenantId, UUID connectionId) {
            held.remove(connectionId);
        }

        @Override
        public Set<Key> held() {
            Set<Key> out = new java.util.HashSet<>();
            held.keySet().forEach(connection -> out.add(new Key(UUID.randomUUID(), connection)));
            return out;
        }
    }

    private PushIngestService service(World world, Subscriptions subscriptions) {
        PushSubscriptionPlan plan = new PushSubscriptionPlan(new PushRowsAdapter(fx.instances, new MetricDefinitionCollectionRows(fx.definitions),
                (tenant, instance) -> restartObserved.containsKey(instance.value())
                        ? java.util.OptionalLong.of(restartObserved.get(instance.value())) : java.util.OptionalLong.empty(),
                new JdbcConfigGenerationRepository(ingest.jdbc), fx.placements, connections));
        PushIngestService service = new PushIngestService(plan, subscriptions, new PushAttribution(new BrokerSampleDecoder(65536)),
                telemetry, brokerCounters, PushPropertiesHolder.small(), Clock.systemUTC());
        services.add(service);
        service.reconcile(new TenantId(world.tenant()));
        return service;
    }

    /** {@link PushProperties} with a small queue, built here so the test names every bound it relies on. */
    private static final class PushPropertiesHolder {
        static PushProperties small() {
            return new PushProperties(true, 1000, 65536, 100);
        }
    }

    private static byte[] heartbeat(String adapterId) {
        return ("{\"header\":{\"timestamp\":\"2026/02/05 11:23:30.56\",\"adapterType\":\"SOURCE\",\"adapterVersion\":\"1.0\","
                + "\"adapterID\":\"" + adapterId + "\",\"runID\":\"run-1\",\"host\":\"connector-host\",\"user\":\"svc\","
                + "\"ip\":\"127.0.1.1\",\"eventType\":6,\"adapterStartTime\":\"1770270000000\"},"
                + "\"adapterState\":{\"heartBeatTimeStamp\":\"2026/02/05 11:23:30\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] fileComplete(String adapterId, String runTimeMsJson) {
        return ("{\"header\":{\"adapterID\":\"" + adapterId + "\",\"eventType\":12},\"fileStateEvent\":{\"size\":123,"
                + "\"runTimeMs\":" + runTimeMsJson + "}}").getBytes(StandardCharsets.UTF_8);
    }

    private static String topic(String adapterId, String state) {
        return "DEFAULT/NONE/Alice/SE/FE/SOURCE/NWFILE/" + adapterId + "/" + state;
    }

    private static void send(PushIngestService service, World world, String adapterId, String state, byte[] body, Instant at) {
        service.accept(world.tenant(), world.connection(), new RawBrokerMessage(topic(adapterId, state), body, at));
    }

    private long brokerCount(World world, String reason) {
        Long n = ingest.superuser.queryForObject("SELECT coalesce(sum(count), 0) FROM sm_metrics.broker_ingest_counter"
                        + " WHERE tenant_id = :t AND broker_connection_id = :c AND reason = :r",
                new MapSqlParameterSource().addValue("t", world.tenant()).addValue("c", world.connection())
                        .addValue("r", reason), Long.class);
        return n == null ? 0 : n;
    }

    private int series(World world) {
        return ingest.seriesCount(world.site());
    }

    private String discriminator(World world, String metric) {
        return ingest.superuser.queryForObject("SELECT source_discriminator FROM sm_metrics.series WHERE tenant_id = :t"
                        + " AND placement_id = :p AND metric_name = :n",
                new MapSqlParameterSource().addValue("t", world.tenant()).addValue("p", world.placements().get(0))
                        .addValue("n", metric), String.class);
    }

    // ---- Acceptance #4 -----------------------------------------------------------------------------------------------

    @Test
    void aRealDeclaredSolaceEventMetricIsIngestedEndToEnd() throws IOException {
        List<MetricDefinitionSpec> reviewed = reviewedFixtureMetrics();
        assertEquals(List.of("filemesh.heartbeats"), reviewed.stream().map(MetricDefinitionSpec::metricName).toList(),
                "the reviewed fixture declares a real solace_event metric (OQ-29)");
        World world = world("SOURCE_DEFAULT_Alice_FE_NWFILE_V1", 1, reviewed);
        Subscriptions subscriptions = new Subscriptions();
        PushIngestService service = service(world, subscriptions);
        assertEquals(Set.of("*/*/*/*/*/*/*/*/heartbeat"), subscriptions.held.get(world.connection()),
                "the subscription is the declared pattern: a state, never an instance and never a wildcard last level");

        for (int i = 0; i < 3; i++) {
            send(service, world, "SOURCE_DEFAULT_Alice_FE_NWFILE_V1", "heartbeat",
                    heartbeat("SOURCE_DEFAULT_Alice_FE_NWFILE_V1"), T0.plusSeconds(5L * i));
        }
        assertEquals(3, service.drainNow());

        List<IngestFixture.Point> points = ingest.points(world.site(), "filemesh.heartbeats");
        assertEquals(List.of(1.0, 2.0, 3.0), points.stream().map(IngestFixture.Point::value).toList(),
                "one stored point per heartbeat, the Data Plane's cumulative count");
        assertEquals(List.of(T0, T0.plusSeconds(5), T0.plusSeconds(10)), points.stream().map(IngestFixture.Point::ts).toList(),
                "ts is the DP's receipt instant (OQ-4 (a))");
        assertEquals(java.util.Arrays.asList(null, 1.0, 1.0), points.stream().map(IngestFixture.Point::increment).toList());
        assertEquals("broker-push", discriminator(world, "filemesh.heartbeats"));
    }

    @Test
    void pushedSamplesLandInMetricsRawUnderTheirSeriesAndTheSeriesIsBrokerPush() {
        World world = world("ADAPTER_N", 1, List.of(numeric()));
        PushIngestService service = service(world, new Subscriptions());

        send(service, world, "ADAPTER_N", "fcomplete", fileComplete("ADAPTER_N", "1234"), T0);
        send(service, world, "ADAPTER_N", "fcomplete", fileComplete("ADAPTER_N", "4321.5"), T0.plusSeconds(7));
        service.drainNow();

        assertEquals(List.of(1234.0, 4321.5), ingest.points(world.site(), "filemesh.file_run_time_ms").stream()
                .map(IngestFixture.Point::value).toList());
        assertEquals("broker-push", discriminator(world, "filemesh.file_run_time_ms"));
        assertEquals(1, series(world), "a gauge has no increment and no reset: one series");
    }

    // ---- attribution and the broker-level counter (OQ-15, OQ-20 (b)) --------------------------------------------------

    @Test
    void anEventIsAttributedToTheInstancesSingleLivePlacementAndNoOtherInstanceSeesIt() {
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef()));
        World other = world("ADAPTER_B", 1, List.of(heartbeatsDef()));
        PushIngestService service = service(world, new Subscriptions());
        service(other, new Subscriptions());

        send(service, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0);
        service.drainNow();

        assertEquals(1, ingest.points(world.site(), "filemesh.heartbeats").size());
        assertEquals(0, ingest.points(other.site(), "filemesh.heartbeats").size());
    }

    @Test
    void anInstanceWithTwoLivePlacementsHasItsEventDroppedNotGuessed() {
        World world = world("ADAPTER_A", 2, List.of(heartbeatsDef()));
        PushIngestService service = service(world, new Subscriptions());

        send(service, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0);
        send(service, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0.plusSeconds(5));
        service.drainNow();
        service.flushCounts();

        for (UUID placement : world.placements()) {
            assertEquals(0, ingest.points(new IngestFixture.Site(world.tenant(), world.instance(), placement, UUID.randomUUID(), VERSION),
                    "filemesh.heartbeats").size(), "no sample lands on either placement");
        }
        assertEquals(0, series(world));
        assertEquals(2, brokerCount(world, "ambiguous_placement"), "counted on the broker connection, once per event");
    }

    @Test
    void anEventForNoKnownInstanceIsDroppedAndNothingIsStoredBeyondACount() {
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef()));
        PushIngestService service = service(world, new Subscriptions());

        for (int i = 0; i < 4; i++) {
            send(service, world, "SOMEONE_ELSE", "heartbeat", heartbeat("SOMEONE_ELSE"), T0.plusSeconds(i));
        }
        service.drainNow();
        service.flushCounts();

        assertEquals(0, series(world));
        assertEquals(0, ingest.points(world.site(), "filemesh.heartbeats").size());
        assertEquals(4, brokerCount(world, "undeclared_dropped"), "OQ-20 (b): the blind spot is counted, not hidden");
        assertEquals(0, ingest.counted(world.site(), "undeclared_dropped"), "and not charged to a placement it did not name");
    }

    @Test
    void aRetiredPlacementIsNeverAttributed() {
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef()));
        fx.retire(world.tenant(), world.placements().get(0));
        PushIngestService service = service(world, new Subscriptions());

        send(service, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0);
        service.drainNow();
        service.flushCounts();

        assertEquals(0, series(world));
        assertEquals(1, brokerCount(world, "undeclared_dropped"));
    }

    @Test
    void aNonNumberAtTheDeclaredPointerIsParseFailedAgainstItsPlacementAndStoresNothing() {
        World world = world("ADAPTER_N", 1, List.of(numeric()));
        PushIngestService service = service(world, new Subscriptions());

        send(service, world, "ADAPTER_N", "fcomplete", fileComplete("ADAPTER_N", "\"SENTINEL-not-a-number\""), T0);
        send(service, world, "ADAPTER_N", "fcomplete", fileComplete("ADAPTER_N", "1e400"), T0.plusSeconds(1));
        service.drainNow();

        assertEquals(0, ingest.points(world.site(), "filemesh.file_run_time_ms").size());
        assertEquals(2, ingest.counted(world.site(), "parse_failed"),
                "a non-finite number is parse_failed on the placement, never misread as a runway failure");
        assertEquals(0, ingest.counted(world.site(), "runway_degraded"));
    }

    // ---- restart ------------------------------------------------------------------------------------------------------

    @Test
    void aRestartOfTheDataPlaneStartsTheCountOverAndTheSeriesRecordsAnInferredReset() {
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef()));
        PushIngestService first = service(world, new Subscriptions());
        for (int i = 0; i < 3; i++) {
            send(first, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0.plusSeconds(i));
        }
        first.drainNow();

        PushIngestService afterRestart = service(world, new Subscriptions());   // a new accumulator: the process started again
        send(afterRestart, world, "ADAPTER_A", "heartbeat", heartbeat("ADAPTER_A"), T0.plusSeconds(60));
        afterRestart.drainNow();

        List<IngestFixture.Point> points = ingest.points(world.site(), "filemesh.heartbeats");
        assertEquals(List.of(1.0, 2.0, 3.0, 1.0), points.stream().map(IngestFixture.Point::value).toList());
        assertEquals(1.0, points.get(3).increment(), "the first count after a restart is the whole increase since");
        assertEquals(1, ingest.resets(world.site(), "filemesh.heartbeats"), "M-Q9: a decrease with no restart signal is an inferred reset");
        assertEquals("inferred", ingest.superuser.queryForObject("SELECT x.signal FROM sm_metrics.series_reset x JOIN sm_metrics.series s"
                        + " ON s.tenant_id = x.tenant_id AND s.series_id = x.series_id WHERE s.tenant_id = :t AND s.placement_id = :p",
                new MapSqlParameterSource().addValue("t", world.tenant()).addValue("p", world.placements().get(0)), String.class));
    }

    // ---- I6 ----------------------------------------------------------------------------------------------------------

    @Test
    void aPayloadSecretNeverLeavesTheDecoder() {
        String secret = "SENTINEL-broker-password-9921";
        String unmanaged = "SENTINEL-unmanaged-adapter-id";
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef(), numeric()));
        PushIngestService service = service(world, new Subscriptions());

        LogCapture.Captured<Void> logged = LogCapture.during(() -> {
            // a "start"-shaped event on a state that IS subscribed (a customised publisher): a plaintext password next to the id
            byte[] withPassword = ("{\"header\":{\"adapterID\":\"ADAPTER_A\",\"eventType\":6},\"solace\":{\"password\":\""
                    + secret + "\"},\"adapterState\":{}}").getBytes(StandardCharsets.UTF_8);
            send(service, world, "ADAPTER_A", "heartbeat", withPassword, T0);
            // the secret where a NUMBER is declared: not a number, never echoed
            send(service, world, "ADAPTER_A", "fcomplete", fileComplete("ADAPTER_A", "\"" + secret + "\""), T0.plusSeconds(1));
            // an unmanaged adapter's id, and an unparsable body that quotes the secret
            send(service, world, unmanaged, "heartbeat", heartbeat(unmanaged), T0.plusSeconds(2));
            send(service, world, "ADAPTER_A", "heartbeat", ("{broken " + secret).getBytes(StandardCharsets.UTF_8), T0.plusSeconds(3));
            service.drainNow();
            service.flushCounts();
            return null;
        });

        assertFalse(logged.text().contains(secret), "no log line carries the payload secret: " + logged.text());
        assertFalse(logged.text().contains(unmanaged), "nor an unmanaged adapter's id");
        for (String table : List.of("sm_metrics.series", "sm_metrics.series_segment", "sm_metrics.series_reset",
                "sm_metrics.restart_marker", "sm_metrics.metrics_raw", "sm_metrics.ingest_counter",
                "sm_metrics.broker_ingest_counter", "sm_data.broker_connection", "sm_data.metric_definition")) {
            List<String> rows = ingest.superuser.queryForList("SELECT row_to_json(t)::text FROM " + table + " t",
                    new MapSqlParameterSource(), String.class);
            for (String row : rows) {
                assertFalse(row.contains(secret), table + " holds the payload secret: " + row);
                assertFalse(row.contains(unmanaged), table + " holds an unmanaged adapter's id: " + row);
            }
        }
        // what DID happen: the heartbeat that carried the password still counted (the event named a managed adapter), and
        // only its count was kept
        assertEquals(1, ingest.points(world.site(), "filemesh.heartbeats").size());
        assertEquals(1, ingest.counted(world.site(), "parse_failed"), "the secret-as-a-number: counted against its placement, value never kept");
        assertEquals(1, brokerCount(world, "undeclared_dropped"));
        assertEquals(1, brokerCount(world, "parse_failed"));
    }

    // ---- the loop (OQ-6 (a)) ------------------------------------------------------------------------------------------

    @Test
    void theReconcileRunsInsideTheOneBackgroundLoopOncePerActiveTenantPerTick() {
        World world = world("ADAPTER_A", 1, List.of(heartbeatsDef()));
        List<String> calls = new ArrayList<>();
        com.solace.sm.dp.metrics.collect.PushReconcile recorder = new com.solace.sm.dp.metrics.collect.PushReconcile() {
            @Override
            public void reconcile(TenantId tenant) {
                calls.add("reconcile:" + tenant.value());
            }

            @Override
            public void retainTenants(java.util.Collection<TenantId> active) {
                calls.add("retain:" + active.size());
            }

            @Override
            public void flushCounts() {
                calls.add("flush");
            }
        };
        var loop = new com.solace.sm.dp.metrics.collect.MetricsBackgroundLoop(new TenantEnumerationRepository(ingest.jdbc),
                new com.solace.sm.dp.metrics.collect.CollectionPlanBuilder(fx.instances, fx.placements,
                        new MetricDefinitionCollectionRows(fx.definitions),
                        (tenant, instance) -> java.util.OptionalLong.empty(), 30),
                new com.solace.sm.dp.metrics.collect.SourceScheduler(4, 0.1, 8, () -> 0.5),
                new com.solace.sm.dp.metrics.collect.MetricsCollector(
                        new com.solace.sm.dp.metrics.collect.SourceScheduler(4, 0.1, 8, () -> 0.5), neverFetches(), null, telemetry,
                        new com.solace.sm.dp.metrics.collect.CollectorCounts(new JdbcIngestCounterRepository(ingest.jdbc),
                                Clock.systemUTC()), Runnable::run, Clock.systemUTC()),
                new com.solace.sm.dp.metrics.rollup.RollupService(new RecordingRollups(),
                        com.solace.sm.dp.metrics.rollup.RollupProperties.defaults()),
                CollectorProperties.defaults(), Clock.systemUTC(), recorder);

        loop.tick();

        assertTrue(calls.contains("reconcile:" + world.tenant()), calls.toString());
        assertEquals(1, calls.stream().filter(c -> c.equals("reconcile:" + world.tenant())).count(),
                "once per active tenant per tick, from the loop's one tenant list");
        assertEquals(1, calls.stream().filter(c -> c.equals("flush")).count());
        assertEquals(1, calls.stream().filter(c -> c.startsWith("retain:")).count());
    }

    private static com.solace.sm.dp.metrics.collect.fetch.MetricFetcher neverFetches() {
        return new com.solace.sm.dp.metrics.collect.fetch.MetricFetcher() {
            @Override
            public com.solace.sm.dp.metrics.collect.fetch.FetchResult fetch(
                    com.solace.sm.dp.metrics.collect.fetch.FetchRequest request) {
                throw new AssertionError("a tick plans and reconciles; it never fetches");
            }

            @Override
            public RestartRead restartToken(RestartRequest request) {
                throw new AssertionError("a tick never reads a restart token");
            }
        };
    }

    private static final class RecordingRollups implements com.solace.sm.dp.metrics.rollup.RollupRows {
        @Override
        public int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            return 0;
        }

        @Override
        public int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt) {
            return 0;
        }
    }
}
