package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricExec;
import com.solace.sm.dp.apply.runtime.ssh.HostMetricProbe;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.config.ConfigLadder;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.health.HealthPoller;
import com.solace.sm.dp.health.HealthPollerProperties;
import com.solace.sm.dp.health.HealthProber;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.dp.health.StartObserver;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.metrics.collect.CollectionPlan;
import com.solace.sm.dp.metrics.collect.CollectionPlanBuilder;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.push.BrokerConnectionRecord;
import com.solace.sm.dp.metrics.push.BrokerConnectionRows;
import com.solace.sm.dp.metrics.push.PushRowsAdapter;
import com.solace.sm.dp.metrics.push.PushSubscriptionPlan;
import com.solace.sm.dp.meshbridge.SpringTransactionBoundary;
import com.solace.sm.events.EventEnvelope;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-112 (1) end to end, in the shape the owner named: a MANUAL-applied instance whose container restarts. The real engine
 * writes the file; the real {@link HealthPoller} round reads the container start (through a scripted host, since this
 * module has no sshd) and records it; and the four readers that must follow it do:
 * <ul>
 *   <li>the config ladder reaches {@code restart-observed} and {@code instance.config-state} announces it once;</li>
 *   <li>the metrics ACTIVE LIST of path 1 ({@link CollectionPlanBuilder}) switches to the generation's own list;</li>
 *   <li>the path 2 PUSH plan ({@link PushSubscriptionPlan}) plans a File Mesh instance, with its key, that it did not plan
 *       before the restart (MON-02): a manual apply's configuration is not attributed until its container has restarted.</li>
 * </ul>
 * The negative is in every case: a start observed BEFORE the write is the predecessor running, and counts for nothing.
 *
 * <p>The database is shared by every IT in this JVM, so the poller visits other tests' tenants too; the scripted host
 * answers "no start" for any container it was not told about, and every assertion names only its own rows.
 */
@Testcontainers(disabledWithoutDocker = true)
class StartObservationIT {

    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"src\",\"type\":\"COMP_SOURCE_FTP@FILEEVENTS\"}]}";
    private static final String FIELD = "file-events.source.general.adapter_id";
    private static final String ADAPTER = "ADAPTER_A";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ConfigHasher hasher = TestHashers.keyed();
    private final List<EventEnvelope> configStates = new ArrayList<>();
    private NamedParameterJdbcTemplate runtime;
    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private ScopeKey scope;
    private PlacementRef placement;
    private ConfigRecordServiceImpl config;
    private JdbcLadderRepository ladderRows;
    private ScriptedHost host;
    private HealthPoller poller;
    /** When set, every probe of a placement OTHER than the one under test fails as an unreachable host would. */
    private volatile boolean otherProbesFail;

    /** Answers {@code docker inspect} for the one container it was told about; counts the asks. */
    private static final class ScriptedHost implements HostMetricExec {
        final String container;
        volatile Instant start;
        int asks;

        ScriptedHost(String container) {
            this.container = container;
        }

        @Override
        public HostProbe.ProbeResult curl(SshDockerPlacement placement, HostMetricProbe.Curl command) {
            throw new AssertionError("the start observer never runs a curl");
        }

        @Override
        public InspectResult inspect(SshDockerPlacement placement, HostMetricProbe.Inspect command) {
            if (!container.equals(placement.containerName())) {
                return new InspectResult(1, "");
            }
            asks++;
            return start == null ? new InspectResult(1, "") : new InspectResult(0, "c".repeat(64) + " " + start + "\n");
        }
    }

    @BeforeEach
    void setUp() {
        build("via-ssh");
    }

    /** A fresh tenant with one ACTIVE instance (of the named acquisition mode) and one live placement that declares no probe. */
    private void build(String acquisition) {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        UUID tenantId = SmDataPostgres.freshTenant();
        tenant = new TenantId(tenantId);
        UUID instanceId = HealthFixtures.instance(runtime, tenantId, acquisition, "active");
        instance = new InstanceId(instanceId);
        UUID livePlacement = InventoryFixtures.placement(tenantId, instanceId, InventoryFixtures.target(tenantId));
        placementId = new PlacementId(livePlacement);
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(), new JdbcConfigGenerationRepository(runtime),
                hasher, new Retention(10), Clock.systemUTC(), new JdbcRollbackTargetRepository(runtime));
        ladderRows = new JdbcLadderRepository(runtime);
        configStates.clear();
        host = new ScriptedHost("sm-" + livePlacement);

        JdbcInstanceStateRepository instances = new JdbcInstanceStateRepository(runtime);
        JdbcPlacementRepository placements = new JdbcPlacementRepository(runtime);
        JdbcTargetConnectionRepository targets = new JdbcTargetConnectionRepository(runtime);
        TablePlacementCatalog catalog = new TablePlacementCatalog(placements, targets, instances);
        ApplyEventEmitter emitter = emitter();
        StartObserver observer = new StartObserver(host, catalog, ladderRows, new JdbcStartObservationRepository(runtime),
                ApplyEngineHarness.transactions(), new LadderRecorder(ladderRows), emitter, Clock.systemUTC(), TIMEOUT);
        // the database is shared: other tests' placements DO declare probes and the poller visits them too, so the probe
        // answers harmlessly for them and fails only for this test's own placement, which declares none
        HostProbe noProbes = (probed, port, path, timeout) -> {
            if (probed.containerName().equals(host.container)) {
                throw new AssertionError("a placement that declares no probe is never probed");
            }
            if (otherProbesFail) {
                throw new RuntimeAdapterException(new ErrorClass("ssh.connect.failed"));
            }
            return new HostProbe.ProbeResult(0, 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8));
        };
        HealthPollerProperties props = new HealthPollerProperties(true, Duration.ofSeconds(30), TIMEOUT, 0.0,
                Duration.ofMinutes(5), 1);
        poller = new HealthPoller(new TenantEnumerationRepository(runtime), instances, placements,
                new JdbcPlacementHealthRepository(runtime),
                new HealthProber(noProbes, catalog, targets, (h, p, path, t) ->
                        new HostProbe.ProbeResult(0, 200, "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8)), TIMEOUT),
                new HealthRecorder(ApplyEngineHarness.transactions(), new JdbcPlacementHealthRepository(runtime), emitter,
                        Clock.systemUTC()),
                props, Clock.systemUTC(), Runnable::run, () -> { }, observer);
    }

    private ApplyEventEmitter emitter() {
        return new ApplyEventEmitter(new AfterCommitEventPublisher(event -> {
            if (event.event().equals("instance.config-state")) {
                synchronized (configStates) {
                    configStates.add(event);
                }
            }
        }, new SpringTransactionBoundary()), Clock.systemUTC(), UUID::randomUUID);
    }

    private ApplyExecutor executor(InMemoryRuntimeAdapter adapter) {
        ApplyEventEmitter e = emitter();
        var props = ApplyEngineHarness.engineProperties();
        OutcomeService outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e, new LadderRecorder(ladderRows));
        return new ApplyExecutor(new AdmissionService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e), ApplyEngineHarness.stepIntents(),
                ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()), outcomes, adapter,
                (p, g) -> RenderedConfig.of(tenant, ("generation: " + g + "\n").getBytes(StandardCharsets.UTF_8), hasher),
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, e);
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }

    /** Stages the next generation of a File Mesh source configured with {@link #ADAPTER}. */
    private long stage() {
        return stage("");
    }

    /** A later generation: the {@code marker} makes the content differ, so staging mints a new generation. */
    private long stage(String marker) {
        long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        return config.stage(tenant.value(), instance.value(), new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"src\":{\"" + FIELD + "\":\"" + ADAPTER + "\"" + (marker.isEmpty() ? "" : ",\"marker\":\"" + marker + "\"")
                + "}}",
                List.of(RenderedEntryValue.plain("src", "file-events.source.general.adapter_id", "\"" + ADAPTER + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    private void applyManually(long generation) {
        applyManually(generation, new InMemoryRuntimeAdapter(placement, true, "replica-1"));
    }

    private ApplyOutcome.Landed applyManually(long generation, InMemoryRuntimeAdapter host) {
        ApplyCommand command = ApplyEngineHarness.apply(scope, generation);
        return assertInstanceOf(ApplyOutcome.Landed.class, executor(host).execute(command));
    }

    /** The start the host reported when the generation's file was read back: what an observed start must be later than. */
    private Instant writeBaseline(long generation) {
        return Instant.parse(runtime.queryForObject("SELECT a.write_baseline->>'startedAt' FROM sm_data.apply_attempt a"
                + " JOIN sm_data.apply_request r ON r.tenant_id = a.tenant_id AND r.apply_id = a.apply_id"
                + " WHERE a.tenant_id = :t AND r.instance_id = :i AND r.generation = :g AND a.step = 'MATERIALIZE'"
                + " AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1", key().addValue("g", generation),
                String.class));
    }

    private LadderRung rung(long generation) {
        return ConfigLadder.view(config.latestDraft(tenant.value(), instance.value()), ladderRows.evidence(tenant, instance))
                .reached();
    }

    private String storedRung() {
        return runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), String.class);
    }

    private List<String> announced() {
        synchronized (configStates) {
            return configStates.stream().filter(e -> e.entity().instanceId().equals(instance.value().toString()))
                    .map(e -> (String) e.payload().get("reached")).collect(Collectors.toList());
        }
    }

    // ---- the poller observes, and the ladder follows -------------------------------------------------------------

    @Test
    void aPollRoundObservesTheStartOfAManualAppliedPlacementWithNoProbeAndTheLadderMovesOnce() {
        long g = stage();
        applyManually(g);
        assertEquals(List.of("persisted"), announced());

        host.start = writeBaseline(g).plusSeconds(1);
        poller.pollOnce();

        assertEquals(LadderRung.RESTART_OBSERVED, rung(g), "a start after the write: the new file was loaded");
        assertEquals("restart-observed", storedRung());
        assertEquals(List.of("persisted", "restart-observed"), announced(), "each movement announced once");

        int asked = host.asks;
        poller.pollOnce();
        poller.pollOnce();

        assertEquals(asked, host.asks, "observed: the placement is not inspected again");
        assertEquals(List.of("persisted", "restart-observed"), announced(), "and nothing is announced twice");
    }

    @Test
    void aStartBeforeTheWriteIsInspectedAgainEachRoundAndNeverCounts() {
        long g = stage();
        applyManually(g);
        host.start = writeBaseline(g).minusSeconds(3600);

        poller.pollOnce();
        poller.pollOnce();

        assertEquals(2, host.asks, "still waiting: asked each round");
        assertEquals(LadderRung.PERSISTED, rung(g));
        assertEquals(List.of("persisted"), announced());

        host.start = writeBaseline(g).plusSeconds(1);
        poller.pollOnce();

        assertEquals(LadderRung.RESTART_OBSERVED, rung(g));
        assertEquals(List.of("persisted", "restart-observed"), announced());
    }

    @Test
    void aPlacementNothingWasAppliedToIsNeverInspected() {
        stage();
        host.start = Instant.now().plusSeconds(60);

        poller.pollOnce();

        assertEquals(0, host.asks, "staged is not persisted: there is no file to wait for a restart of");
    }

    @Test
    void theStartIsObservedWhateverTheInstancesAcquisitionMode() {
        build("direct-http");
        long g = stage();
        applyManually(g);
        host.start = writeBaseline(g).plusSeconds(1);

        poller.pollOnce();

        assertEquals(LadderRung.RESTART_OBSERVED, rung(g),
                "start observation is the runtime's own state, not a metric: M-Q2's no-fallback rule does not apply");
    }

    // ---- OR-112 round 2 -----------------------------------------------------------------------------------------------

    @Test
    void aPollerThatRecordsTheStartAFailedGatedWriteLoadedDoesNotCreditTheGenerationBeforeIt() {
        InMemoryRuntimeAdapter container = new InMemoryRuntimeAdapter(placement, true, "replica-1");
        container.hostClock(Instant.now());                   // one host, one clock, for all three applies
        long g1 = stage();
        applyManually(g1, container);                         // g1: manual, written, never restarted
        long g2 = stage("two");
        container.fault(InMemoryRuntimeAdapter.Fault.NEVER_HEALTHY);
        ApplyCommand gated = ApplyEngineHarness.rollingApply(scope, g2, Strategy.ROLLING_HEALTH_GATED);
        ApplyOutcome outcome = executor(container).execute(gated);
        assertEquals(RequestState.CONFIG_DIVERGED,
                assertInstanceOf(ApplyOutcome.Landed.class, outcome, String.valueOf(outcome)).state(),
                "g2 was written, the container restarted onto it, the gate failed");
        container.fault(InMemoryRuntimeAdapter.Fault.NONE);
        long g3 = stage("three");
        applyManually(g3, container);                         // g3: manual, written after that restart, staged

        host.start = container.startOf("replica-1");          // the start the poller reads: the one that loaded g2's file
        poller.pollOnce();

        assertEquals(OptionalLong.empty(),
                new JdbcRestartObservedGenerationRepository(runtime).latestRestartObservedGeneration(tenant, instance),
                "g3 was written after that start, g2 never persisted, and g1's file was overwritten before it");
        assertEquals(LadderRung.PERSISTED, rung(g3));
        int asked = host.asks;
        assertTrue(asked >= 1);
        poller.pollOnce();
        assertEquals(asked + 1, host.asks, "g3 still waits for a start of its own, so the placement is asked again");
    }

    @Test
    void aTargetInBackoffIsNotAskedForAContainerStartEitherUntilItsBackoffEnds() {
        UUID target = runtime.queryForObject("SELECT target_id FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", tenant.value())
                .addValue("p", placementId.value()), UUID.class);
        // a second placement on the SAME target, which declares a probe: its failure puts the target into backoff
        HealthFixtures.placement(runtime, tenant.value(), instance.value(), target, "http-status", "/health", 8080);
        long g = stage();
        applyManually(g);
        host.start = writeBaseline(g).minusSeconds(60);            // never counts, so the placement keeps waiting
        poller.pollOnce();                                    // control: nothing is wrong with the target
        int controlled = host.asks;
        poller.pollOnce();
        assertEquals(controlled + 1, host.asks, "a healthy target: asked every round while it waits");

        otherProbesFail = true;
        poller.pollOnce();                                    // the probe fails: the target enters backoff
        int afterFailure = host.asks;
        poller.pollOnce();
        poller.pollOnce();

        assertEquals(afterFailure, host.asks, "an unreachable target in backoff is not tried over SSH every round");
    }

    // ---- the readers that follow: MON-02 ----------------------------------------------------------------------------

    private static MetricDefinitionSpec heartbeats() {
        return new MetricDefinitionSpec("filemesh.heartbeats", "COMP_SOURCE_FTP@FILEEVENTS", "counter", "events", "solace_event",
                "{\"kind\":\"solace_event\",\"topicPattern\":\"*/*/*/*/*/*/*/*/heartbeat\",\"instanceKey\":{\"configField\":\""
                        + FIELD + "\",\"pointer\":\"/header/adapterID\"},\"extract\":\"count\",\"onUnavailable\":\"degrade\"}",
                null, true);
    }

    private PushSubscriptionPlan pushPlan() {
        CollectionRows collection = new CollectionRows() {
            @Override
            public List<MetricDefinitionSpec> definitions(UUID t, UUID solution, String version) {
                return List.of(heartbeats());
            }

            @Override
            public List<RestartSignalSpec> restartSignals(UUID t, UUID solution, String version) {
                return List.of();
            }

            @Override
            public Map<Integer, Integer> endpoints(UUID t, UUID placement) {
                return Map.of();
            }

            @Override
            public Optional<List<String>> activeMetrics(UUID t, UUID instanceId, long generation) {
                return Optional.of(List.of("filemesh.heartbeats"));
            }
        };
        BrokerConnectionRows connections = new BrokerConnectionRows() {
            @Override
            public void insert(BrokerConnectionRecord row) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<BrokerConnectionRecord> find(UUID t, UUID id) {
                return Optional.empty();
            }

            @Override
            public Optional<BrokerConnectionRecord> live(UUID t, UUID environmentId) {
                return Optional.of(new BrokerConnectionRecord(t, environmentId, UUID.randomUUID(), "tcps", "broker.example.test",
                        55443, "vpn", "sm-push", UUID.randomUUID(), ACTOR, Instant.EPOCH, null));
            }

            @Override
            public List<BrokerConnectionRecord> liveOf(UUID t) {
                return List.of();
            }
        };
        return new PushSubscriptionPlan(new PushRowsAdapter(new JdbcInstanceStateRepository(runtime), collection,
                new JdbcRestartObservedGenerationRepository(runtime), new JdbcConfigGenerationRepository(runtime),
                new JdbcPlacementRepository(runtime), connections));
    }

    @Test
    void aManualAppliedFileMeshInstanceIsPlannedForPushOnlyAfterAnObservedRestart() {
        long g = stage();
        applyManually(g);

        assertTrue(pushPlan().build(tenant.value()).environments().isEmpty(),
                "persisted, never restarted: its configuration is not attributed yet, so nothing is subscribed for it");

        host.start = writeBaseline(g).minusSeconds(60);
        poller.pollOnce();
        assertTrue(pushPlan().build(tenant.value()).environments().isEmpty(), "a restart BEFORE the write counts for nothing");

        host.start = writeBaseline(g).plusSeconds(1);
        poller.pollOnce();

        PushSubscriptionPlan.TenantPush plan = pushPlan().build(tenant.value());
        assertEquals(1, plan.environments().size(), plan.toString());
        var route = plan.environments().get(0).routing().routes().get(0);
        assertEquals("filemesh.heartbeats", route.metric().name());
        assertEquals(Set.of(ADAPTER), route.byKey().keySet(), "attributed by the adapter id the generation in force configures");
        assertEquals(instance.value(), route.byKey().get(ADAPTER).get(0).instanceId());
        assertEquals(List.of(placementId.value()), route.byKey().get(ADAPTER).get(0).livePlacements());
    }

    @Test
    void theCollectorsActiveListSwitchesToTheGenerationOnlyAfterAnObservedRestart() {
        long g = stage();
        applyManually(g);
        CollectionRows collection = new CollectionRows() {
            @Override
            public List<MetricDefinitionSpec> definitions(UUID t, UUID solution, String version) {
                return List.of(
                        com.solace.sm.dp.apply.persistence.CollectFixture.http("cache.hit_rate", "gauge", "cache-engine",
                                "/api/cache/stats", 8081, "/hitRate", Map.of(), null),
                        com.solace.sm.dp.apply.persistence.CollectFixture.http("cache.size", "gauge", "cache-engine",
                                "/api/cache/size", 8081, null, Map.of(), null));
            }

            @Override
            public List<RestartSignalSpec> restartSignals(UUID t, UUID solution, String version) {
                return List.of();
            }

            @Override
            public Map<Integer, Integer> endpoints(UUID t, UUID placement) {
                return Map.of(8081, 18081);
            }

            @Override
            public Optional<List<String>> activeMetrics(UUID t, UUID instanceId, long generation) {
                return Optional.of(generation == g ? List.of("cache.hit_rate") : List.of("cache.hit_rate", "cache.size"));
            }
        };
        CollectionPlanBuilder builder = new CollectionPlanBuilder(new JdbcInstanceStateRepository(runtime),
                new JdbcPlacementRepository(runtime), collection, new JdbcRestartObservedGenerationRepository(runtime), 30);

        assertEquals(Set.of("cache.hit_rate", "cache.size"), names(builder.build(tenant)),
                "persisted only: generation 0's list is still the one read");

        host.start = writeBaseline(g).plusSeconds(1);
        poller.pollOnce();

        assertEquals(Set.of("cache.hit_rate"), names(builder.build(tenant)),
                "a start after the manual apply's write: the generation's own list is now the one read (W3-01)");
    }

    private static Set<String> names(CollectionPlan plan) {
        return plan.units().stream().flatMap(u -> u.sources().stream()).flatMap(s -> s.metrics().stream())
                .map(CollectionPlan.Metric::name).collect(Collectors.toSet());
    }
}
