package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptionManager;
import com.solace.sm.dp.meshbridge.ingest.JcsmpSampleSubscriberFactory;
import com.solace.sm.dp.meshbridge.ingest.LocalPushBroker;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.push.BrokerConnectionRecord;
import com.solace.sm.dp.metrics.push.BrokerSampleDecoder;
import com.solace.sm.dp.metrics.push.PushAttribution;
import com.solace.sm.dp.metrics.push.PushIngestService;
import com.solace.sm.dp.metrics.push.PushProperties;
import com.solace.sm.dp.metrics.push.PushRowsAdapter;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.KeyId;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SecretPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-5.1, the whole push path against a LOCAL broker and a local PostgreSQL: a File Mesh-shaped event published on the
 * broker is subscribed by the REAL session manager and JCSMP subscriber with the credential resolved from the REAL secret store,
 * attributed by the REAL plan from the REAL config of record, and stored through the REAL ingest service. Nothing is faked
 * except the apply journal's "restart-observed" answer (the engine is not the subject).
 *
 * <p>The metric is the one the REVIEWED File Mesh fixture declares ({@code filemesh.heartbeats}, a count of heartbeat events keyed by
 * the adapter id), so Acceptance #4's "at least one real, declared solace_event metric in a reviewed fixture, ingested end to
 * end" is this class's {@code aRealDeclaredSolaceEventMetricIsIngestedEndToEnd}. Without the broker image it is SKIPPED with
 * its reason, never silently green (L19); P11-10.1's Results must say whether it ran.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PushBrokerIT {

    private static final String COMPONENT = "COMP_SOURCE_FTP@FILEEVENTS";
    private static final String FIELD = "file-events.source.general.adapter_id";
    private static final String VERSION = "1";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"src\",\"type\":\"" + COMPONENT + "\"}]}";
    private static final String ADAPTER = "SOURCE_DEFAULT_Alice_FE_NWFILE_V1";

    private static LocalPushBroker broker;
    private static IngestFixture ingest;
    private static CollectFixture fx;
    private static IngestService telemetry;
    private static EnvelopeSecretStore secrets;
    private static JdbcBrokerConnectionRepository connections;
    private static JdbcBrokerCounterRepository brokerCounters;

    private final Map<UUID, Long> restartObserved = new java.util.HashMap<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeAll
    static void startEverything() {
        assumeTrue(LocalPushBroker.imageAvailable(), "the local image " + LocalPushBroker.IMAGE
                + " is not in this Docker daemon (it is loaded from the Solace support-portal tarball, never pulled)");
        broker = LocalPushBroker.start();
        ingest = IngestFixture.create("p51_broker");
        fx = new CollectFixture(ingest);
        // The events here carry the REAL receipt instant (the subscriber's own clock), so the ingest service must run on the real
        // clock too: IngestFixture.testClock() is pinned near day 2 of the month for the synthetic-timestamp ITs, and from the
        // 3rd on every real receipt is more than W2-10's five-minute skew past it, so each sample is dropped as parse_failed.
        telemetry = ingest.service(IngestFixture.fast(50, 500), Clock.systemUTC());
        connections = new JdbcBrokerConnectionRepository(ingest.jdbc);
        brokerCounters = new JdbcBrokerCounterRepository(ingest.jdbc);
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 9);
        secrets = new EnvelopeSecretStore(new JdbcSecretRepository(ingest.jdbc),
                new MasterKeyRing(MasterKey.of(new KeyId("push-it"), key), Optional.empty()), Envelope.standard(),
                Clock.systemUTC());
    }

    @AfterAll
    static void stopEverything() {
        if (ingest != null) {
            ingest.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @AfterEach
    void closeSessions() throws Exception {
        for (AutoCloseable c : closeables) {
            c.close();
        }
        closeables.clear();
    }

    // ---- the reviewed declaration ---------------------------------------------------------------------------------

    private static List<MetricDefinitionSpec> reviewed() throws java.io.IOException {
        return PushIngestIT.reviewedFixtureMetrics();
    }

    private record World(UUID tenant, UUID instance, UUID placement, UUID connection, IngestFixture.Site site) {
    }

    private World world(String adapterId, List<MetricDefinitionSpec> definitions) {
        return world(adapterId, definitions, broker.host(), true);
    }

    /**
     * @param host the broker's address as THIS connection names it
     * @param releaseTheBroker OR-112 (2): one live connection per (host, port, VPN) across the install, so a test that
     *        needs a fresh connection to this one broker first retires the previous test's (what an operator does before
     *        registering another); a second connection in the SAME test names the broker by another address instead
     */
    private World world(String adapterId, List<MetricDefinitionSpec> definitions, String host, boolean releaseTheBroker) {
        if (releaseTheBroker) {
            ingest.superuser.update("UPDATE sm_data.broker_connection SET retired_at = now()"
                    + " WHERE retired_at IS NULL AND port = :port AND vpn = :vpn",
                    new MapSqlParameterSource().addValue("port", broker.smfPort()).addValue("vpn", LocalPushBroker.VPN));
        }
        UUID tenant = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        fx.declareVersion(tenant, solution, VERSION, definitions, List.of());
        CollectFixture.Instance instance = fx.instance(tenant, solution, VERSION, "via-ssh", "active");
        UUID environment = fx.instances.find(tenant, instance.instance()).orElseThrow().environmentId();
        UUID placement = fx.placement(instance, fx.target(tenant, "vm-0.corp.example"), Map.of());
        ConfigRecordServiceImpl config = new ConfigRecordServiceImpl(ingest.transactions,
                new JdbcConfigGenerationRepository(ingest.jdbc), TestHashers.keyed(), new Retention(20), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(ingest.jdbc));
        long base = ingest.jdbc.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance.instance()),
                Long.class);
        long generation = config.stage(tenant, instance.instance(), new StageCommand(base, "file-mesh", VERSION, SELECTION,
                "{\"src\":{\"" + FIELD + "\":\"" + adapterId + "\"}}",
                List.of(RenderedEntryValue.plain("src", FIELD, "\"" + adapterId + "\"")), GenerationOrigin.EDIT, null, ACTOR))
                .generation().generation();
        fx.activeList(instance, generation, definitions.stream().map(MetricDefinitionSpec::metricName).toArray(String[]::new));
        restartObserved.put(instance.instance(), generation);
        // the credential is a REAL sealed secret under its own purpose; the row holds only its reference
        UUID credential = secrets.put(tenant, SecretPurpose.BROKER_CREDENTIAL, broker.subscriberPassword(), ACTOR);
        UUID connection = UUID.randomUUID();
        connections.insert(new BrokerConnectionRecord(tenant, environment, connection, "ws", host, broker.smfPort(),
                LocalPushBroker.VPN, LocalPushBroker.SUBSCRIBER_USERNAME, credential, ACTOR, Instant.now(), null));
        return new World(tenant, instance.instance(), placement, connection,
                new IngestFixture.Site(tenant, instance.instance(), placement, solution, VERSION));
    }

    /** The production wiring minus Spring: the real manager over the real JCSMP factory and the real secret store. */
    private PushIngestService service(World... worlds) {
        BrokerSubscriptionManager manager = new BrokerSubscriptionManager(secrets, new JcsmpSampleSubscriberFactory(Clock.systemUTC()));
        closeables.add(manager);
        PushSubscriptionPlan plan = new PushSubscriptionPlan(new PushRowsAdapter(fx.instances,
                new MetricDefinitionCollectionRows(fx.definitions),
                (tenant, instance) -> restartObserved.containsKey(instance.value())
                        ? OptionalLong.of(restartObserved.get(instance.value())) : OptionalLong.empty(),
                new JdbcConfigGenerationRepository(ingest.jdbc), fx.placements, connections));
        PushIngestService service = new PushIngestService(plan, manager, new PushAttribution(new BrokerSampleDecoder(65536)),
                telemetry, brokerCounters, new PushProperties(true, 1000, 65536, 100), Clock.systemUTC());
        service.start();
        closeables.add(service);
        for (World w : worlds) {
            service.reconcile(new TenantId(w.tenant()));
        }
        try {
            assertTrue(manager.awaitIdle(Duration.ofSeconds(60)), "the subscriptions were applied");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return service;
    }

    private static boolean eventually(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static String heartbeat(String adapterId) {
        return "{\"header\":{\"timestamp\":\"2026/02/05 11:23:30.56\",\"adapterType\":\"SOURCE\",\"adapterVersion\":\"1.0\","
                + "\"adapterID\":\"" + adapterId + "\",\"runID\":\"run-1\",\"host\":\"connector-host\",\"user\":\"svc\","
                + "\"ip\":\"127.0.1.1\",\"eventType\":6,\"adapterStartTime\":\"1770270000000\"},"
                + "\"adapterState\":{\"heartBeatTimeStamp\":\"2026/02/05 11:23:30\"}}";
    }

    private static String topic(String adapterId, String state) {
        return "DEFAULT/NONE/Alice/SE/FE/SOURCE/NWFILE/" + adapterId + "/" + state;
    }

    private int points(World world) {
        return ingest.points(world.site(), "filemesh.heartbeats").size();
    }

    private long brokerCount(World world, String reason) {
        Long n = ingest.superuser.queryForObject("SELECT coalesce(sum(count), 0) FROM sm_metrics.broker_ingest_counter"
                        + " WHERE tenant_id = :t AND broker_connection_id = :c AND reason = :r",
                new MapSqlParameterSource().addValue("t", world.tenant()).addValue("c", world.connection())
                        .addValue("r", reason), Long.class);
        return n == null ? 0 : n;
    }

    // ---- Acceptance #4, through a real broker -----------------------------------------------------------------------

    @Test
    @Order(1)
    void aRealDeclaredSolaceEventMetricIsIngestedEndToEnd() throws Exception {
        World world = world(ADAPTER, reviewed());
        service(world);

        try (LocalPushBroker.Publisher publisher = broker.publisher()) {
            for (int i = 0; i < 3; i++) {
                publisher.publish(topic(ADAPTER, "heartbeat"), heartbeat(ADAPTER), com.solacesystems.jcsmp.DeliveryMode.PERSISTENT);
                Thread.sleep(20);
            }
            assertTrue(eventually(Duration.ofSeconds(30), () -> points(world) == 3), "three heartbeats, three stored points: "
                    + points(world));
        }

        List<IngestFixture.Point> stored = ingest.points(world.site(), "filemesh.heartbeats");
        assertEquals(List.of(1.0, 2.0, 3.0), stored.stream().map(IngestFixture.Point::value).toList());
        assertEquals("broker-push", ingest.superuser.queryForObject("SELECT source_discriminator FROM sm_metrics.series"
                        + " WHERE tenant_id = :t AND placement_id = :p", new MapSqlParameterSource().addValue("t", world.tenant())
                        .addValue("p", world.placement()), String.class));
        assertEquals(0, brokerCount(world, "undeclared_dropped") + brokerCount(world, "parse_failed"));
    }

    @Test
    @Order(2)
    void aStartEventWithAPasswordIsNeverDeliveredAndNothingOfItIsKept() throws Exception {
        String secret = "SENTINEL-start-password-5521";
        World world = world(ADAPTER, reviewed());
        service(world);

        com.solace.sm.dp.apply.persistence.LogCapture.Captured<Void> logged = LogCapture.during(() -> {
            try (LocalPushBroker.Publisher publisher = broker.publisher()) {
                publisher.publish(topic(ADAPTER, "start"), "{\"header\":{\"adapterID\":\"" + ADAPTER
                        + "\"},\"solace\":{\"password\":\"" + secret + "\"}}");
                publisher.publish(topic(ADAPTER, "heartbeat"), heartbeat(ADAPTER));
                assertTrue(eventually(Duration.ofSeconds(30), () -> points(world) == 1));
                Thread.sleep(500);
            } catch (Exception e) {
                throw new IllegalStateException(e.getClass().getSimpleName());
            }
            return null;
        });

        assertEquals(1, points(world), "only the heartbeat arrived");
        assertEquals(0, brokerCount(world, "undeclared_dropped") + brokerCount(world, "parse_failed")
                + brokerCount(world, "ambiguous_placement"), "the start event never reached the Data Plane, so nothing was counted");
        assertFalse(logged.text().contains(secret), "no log line carries it");
        for (String table : List.of("sm_metrics.series", "sm_metrics.metrics_raw", "sm_metrics.ingest_counter",
                "sm_metrics.broker_ingest_counter", "sm_data.broker_connection")) {
            for (String row : ingest.superuser.queryForList("SELECT row_to_json(t)::text FROM " + table + " t",
                    new MapSqlParameterSource(), String.class)) {
                assertFalse(row.contains(secret), table + ": " + row);
            }
        }
    }

    @Test
    @Order(3)
    void theBrokerCredentialIsResolvedInMemoryOnlyAndNeverLeavesTheSecretStore() throws Exception {
        World world = world(ADAPTER, reviewed());
        String password = new String(broker.subscriberPassword());

        com.solace.sm.dp.apply.persistence.LogCapture.Captured<Void> logged = LogCapture.during(() -> {
            service(world);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });

        assertFalse(logged.text().contains(password), "the credential is in no log line, TRACE included");
        for (String table : List.of("sm_data.broker_connection", "sm_metrics.series", "sm_metrics.broker_ingest_counter")) {
            for (String row : ingest.superuser.queryForList("SELECT row_to_json(t)::text FROM " + table + " t",
                    new MapSqlParameterSource(), String.class)) {
                assertFalse(row.contains(password), table + " holds the credential in clear: " + row);
            }
        }
        String ciphertext = ingest.superuser.queryForObject("SELECT encode(ciphertext, 'escape') FROM sm_secret.secret"
                        + " WHERE tenant_id = :t AND purpose = 'BROKER_CREDENTIAL'", new MapSqlParameterSource("t", world.tenant()),
                String.class);
        assertFalse(ciphertext.contains(password), "sealed at rest");
    }

    @Test
    @Order(4)
    void anEventForAnotherTenantsAdapterIsCountedAtThatTenantsConnectionAndStoresNothingThere() throws Exception {
        // two organizations, each with its own connection, both reaching this one broker: an adapter id is meaningful per tenant
        // (one live connection per broker AND VPN, OR-112 (2): B's names the same broker by its numeric address)
        World a = world("ADAPTER_OF_A", reviewed());
        World b = world("ADAPTER_OF_B", reviewed(), "127.0.0.1", false);
        PushIngestService service = service(a, b);

        try (LocalPushBroker.Publisher publisher = broker.publisher()) {
            publisher.publish(topic("ADAPTER_OF_A", "heartbeat"), heartbeat("ADAPTER_OF_A"));
            assertTrue(eventually(Duration.ofSeconds(30), () -> points(a) == 1), "A's own event is stored for A");
            // B's connection is on the same broker, so it is delivered the same event: it must count it, not store it
            assertTrue(eventually(Duration.ofSeconds(30), () -> {
                service.flushCounts();
                return brokerCount(b, "undeclared_dropped") == 1;
            }), "B counts an adapter it does not manage, at its own connection");
        }
        assertEquals(0, points(b), "B stores nothing for A's adapter");
        assertEquals(1, points(a));
        assertEquals(0, brokerCount(a, "undeclared_dropped"), "and A counts no drop: the event was its own");
    }

    @Test
    @Order(99)
    void aBrokerOutageIsAGapAndReconnectResubscribes() throws Exception {
        World world = world(ADAPTER, reviewed());
        service(world);
        try (LocalPushBroker.Publisher publisher = broker.publisher()) {
            publisher.publish(topic(ADAPTER, "heartbeat"), heartbeat(ADAPTER));
            assertTrue(eventually(Duration.ofSeconds(30), () -> points(world) == 1));
        }

        broker.stopBroker();
        Thread.sleep(3000);
        broker.startBroker();

        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (points(world) < 2 && System.nanoTime() < deadline) {
            try (LocalPushBroker.Publisher publisher = broker.publisher()) {
                publisher.publish(topic(ADAPTER, "heartbeat"), heartbeat(ADAPTER));
            } catch (Exception e) {
                // the broker is still coming back
            }
            Thread.sleep(1000);
        }
        assertTrue(points(world) >= 2, "after the outage the Data Plane receives again with no reconcile and no new subscription call");
        List<IngestFixture.Point> stored = ingest.points(world.site(), "filemesh.heartbeats");
        assertEquals(1.0, stored.get(0).value());
        assertEquals(2.0, stored.get(1).value(), "the count continues: the gap lost events, it did not reset the series");
        assertEquals(0, ingest.resets(world.site(), "filemesh.heartbeats"));
    }
}
