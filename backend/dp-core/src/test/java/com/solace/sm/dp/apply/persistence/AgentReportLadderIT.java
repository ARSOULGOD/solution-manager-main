package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.agent.AgentObservedState;
import com.solace.sm.dp.agent.AgentReportIntake;
import com.solace.sm.dp.api.agw.AgentObservation;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import com.solace.sm.dp.api.config.ConfigLadderView;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.ApplyOutcome;
import com.solace.sm.dp.apply.engine.HealthGate;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.ResumePlanner;
import com.solace.sm.dp.apply.engine.Sleeper;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.agent.AgentAuthority;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentAuditRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentReportRepository;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.InMemoryRuntimeAdapter;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigLadderReader;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.events.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.3, {@code AGW-D28} item 2: the container start an AGENT reports drives the config ladder to {@code restart-observed}
 * exactly as dp-remote's {@code StartObserver} does, with the host-clock rule of {@code OR-112} - a start later than the write
 * baseline the host reported, never a comparison with a data-plane time - over the real {@code sm_data} journal and the real engine.
 * In this package for the engine harness, which is package-private (the reason {@code ConfigLadderIT} lives here too).
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentReportLadderIT {

    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final UUID ACTOR = UUID.randomUUID();

    private final ConfigHasher hasher = TestHashers.keyed();
    private final List<EventEnvelope> configStates = new ArrayList<>();
    private NamedParameterJdbcTemplate runtime;
    private TenantId tenant;
    private InstanceId instance;
    private PlacementId placementId;
    private UUID agentId;
    private ScopeKey scope;
    private PlacementRef placement;
    private ConfigRecordServiceImpl config;
    private ConfigLadderReader reader;
    private JdbcLadderRepository ladderRows;
    private AgentReportIntake intake;
    private AgentSessionRef session;
    private AgentObservedState observed;

    @BeforeEach
    void setUp() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        tenant = new TenantId(SmDataPostgres.freshTenant());
        instance = new InstanceId(UUID.randomUUID());
        InventoryFixtures.declare(tenant, instance);
        agentId = UUID.randomUUID();
        String thumbprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        UUID target = InventoryFixtures.agentTarget(runtime, tenant.value(), agentId, thumbprint);
        UUID livePlacement = InventoryFixtures.placement(tenant.value(), instance.value(), target);
        placementId = new PlacementId(livePlacement);
        scope = new ScopeKey(tenant, instance, placementId);
        placement = new PlacementRef(tenant, instance, placementId);
        config = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(), new JdbcConfigGenerationRepository(runtime),
                hasher, new Retention(10), Clock.systemUTC(), new JdbcRollbackTargetRepository(SmDataPostgres.runtimeJdbc()));
        ladderRows = new JdbcLadderRepository(runtime);
        reader = new ConfigLadderReader(config, ladderRows);
        seedSessionAndLease(thumbprint, livePlacement);
        ApplyEventEmitter emitter = emitter();
        AgentAuthority authority = new AgentAuthority(runtime, Clock.systemUTC(), ApplyEngineHarness.transactions());
        JdbcAgentReportRepository reports = new JdbcAgentReportRepository(runtime, ApplyEngineHarness.transactions(),
                Clock.systemUTC(), authority);
        JdbcPlacementHealthRepository healthRows = new JdbcPlacementHealthRepository(runtime);
        intake = new AgentReportIntake(reports, new JdbcAgentAuditRepository(runtime, authority),
                new HealthRecorder(ApplyEngineHarness.transactions(), healthRows, emitter, Clock.systemUTC()), healthRows,
                new JdbcStartObservationRepository(runtime), new LadderRecorder(ladderRows), emitter,
                observed = new AgentObservedState(reports), ApplyEngineHarness.transactions(), Clock.systemUTC());
    }

    private void seedSessionAndLease(String thumbprint, UUID livePlacement) {
        runtime.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                        + " opened_at, last_seen_at) VALUES (:t, :a, 1, 1, '0.1.0', :now, :now)",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("a", agentId)
                        .addValue("now", OffsetDateTime.now()));
        runtime.update("INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_id, certificate_thumbprint, agent_epoch,"
                        + " fleet_epoch, issued_at, expires_at, audience, renewal_nonce_digest, capabilities, placement_ids,"
                        + " admission_margin_ms, server_clock, state) VALUES (:t, :a, :l, :th, 1, 1, :now, :later, 'dp', :nonce,"
                        + " ARRAY['apply-config', 'lifecycle'], CAST(:pl AS uuid[]), 1000, :now, 'ACTIVE')",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("a", agentId)
                        .addValue("l", UUID.randomUUID()).addValue("th", thumbprint).addValue("now", OffsetDateTime.now())
                        .addValue("later", OffsetDateTime.now().plusHours(1))
                        .addValue("nonce", (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64))
                        .addValue("pl", "{\"" + livePlacement + "\"}"));
        session = new AgentSessionRef(agentId, 1);
    }

    private ApplyEventEmitter emitter() {
        return new ApplyEventEmitter(event -> {
            if (event.event().equals("instance.config-state")) {
                configStates.add(event);
            }
        }, Clock.systemUTC(), UUID::randomUUID);
    }

    private ApplyExecutor executor(InMemoryRuntimeAdapter adapter) {
        ApplyEventEmitter e = emitter();
        var props = ApplyEngineHarness.engineProperties();
        OutcomeService outcomes = new OutcomeService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e, new LadderRecorder(ladderRows));
        return new ApplyExecutor(new AdmissionService(ApplyEngineHarness.transactions(), ApplyEngineHarness.scopes(),
                ApplyEngineHarness.requests(), Clock.systemUTC(), e),
                ApplyEngineHarness.stepIntents(), ApplyEngineHarness.attemptsService(ApplyEngineHarness.maxAttempts()),
                outcomes, adapter,
                (p, g) -> RenderedConfig.of(tenant, ("generation: " + g + "\n").getBytes(StandardCharsets.UTF_8), hasher),
                new HealthGate(Clock.systemUTC(), Sleeper.real(), props), new ResumePlanner(), props, e);
    }

    private long stage(String marker) {
        long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
        return config.stage(tenant.value(), instance.value(), new StageCommand(base, "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"marker\":\"" + marker + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"" + marker + "\"")),
                GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
    }

    private MapSqlParameterSource key() {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }

    private ConfigLadderView ladder() {
        return reader.read(tenant.value(), instance.value());
    }

    private String storedRung() {
        return runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", key(), String.class);
    }

    private Instant writeBaseline(long generation) {
        return Instant.parse(runtime.queryForObject("SELECT a.write_baseline->>'startedAt' FROM sm_data.apply_attempt a"
                + " JOIN sm_data.apply_request r ON r.tenant_id = a.tenant_id AND r.apply_id = a.apply_id"
                + " WHERE a.tenant_id = :t AND r.instance_id = :i AND r.generation = :g AND a.step = 'MATERIALIZE'"
                + " AND a.outcome = 'SUCCEEDED' ORDER BY a.started_at DESC LIMIT 1", key().addValue("g", generation),
                String.class));
    }

    private ReportResult report(long sequence, String incarnation, Instant startedAt) {
        ReportItem item = new ReportItem(UUID.randomUUID().toString(), sequence, placementId.value().toString(),
                "sm-" + placementId.value(), incarnation, 0, null, null, null, null, null, null, null, startedAt);
        return intake.report(session, List.of(item), new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.7")).get(0);
    }

    private ApplyOutcome.Landed applyManual(long generation) {
        return assertInstanceOf(ApplyOutcome.Landed.class,
                executor(new InMemoryRuntimeAdapter(placement, true, "replica-1"))
                        .execute(ApplyEngineHarness.apply(scope, generation)));
    }

    @Test
    void aReportedStartLaterThanTheWriteBaselineReachesRestartObservedAndAnnouncesItOnce() {
        long g = stage("one");
        applyManual(g);
        configStates.clear();
        assertEquals(LadderRung.PERSISTED, ladder().reached(), "persisted, waiting for a start");

        ReportResult accepted = report(1, "c0ffee01", writeBaseline(g).plusSeconds(1));

        assertEquals("TRUSTED", accepted.trust());
        assertEquals("APPLIED", accepted.disposition());
        assertEquals(LadderRung.RESTART_OBSERVED, ladder().reached());
        assertEquals("restart-observed", storedRung(), "the one writer moved the cache");
        assertEquals(1, configStates.size(), "one movement, one notice");
        assertEquals("restart-observed", configStates.get(0).payload().get("reached"));
    }

    @Test
    void aReportedStartThatIsNotLaterThanTheWriteBaselineLeavesTheRungAtPersisted() {
        long g = stage("one");
        applyManual(g);
        report(1, "c0ffee01", writeBaseline(g).minusSeconds(3600));
        assertEquals(LadderRung.PERSISTED, ladder().reached(), "a start before the write is the OLD process");
        report(2, "c0ffee01", writeBaseline(g));
        assertEquals(LadderRung.PERSISTED, ladder().reached(), "a start equal to the baseline is not after it");
    }

    @Test
    void theObservationOnlyMovesForwardSoAStaleReportCannotLowerTheBaselineTheEngineReadsBack() {
        long g = stage("one");
        applyManual(g);
        Instant later = writeBaseline(g).plusSeconds(5);
        report(1, "c0ffee02", later);
        assertEquals(LadderRung.RESTART_OBSERVED, ladder().reached());
        // a higher sequence, but an EARLIER start (a resend or a clock quirk): the stored observation must not go back
        report(2, "c0ffee01", writeBaseline(g).minusSeconds(60));
        Instant stored = runtime.queryForObject("SELECT started_at FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t AND placement_id = :p", key().addValue("p", placementId.value()), OffsetDateTime.class)
                .toInstant();
        assertEquals(later, stored);
        assertTrue(ladder().reached() == LadderRung.RESTART_OBSERVED);
    }

    @Test
    void anUntrustedReportAfterTheLeaseExpiredAppliesNoStartObservationAndSatisfiesNoGate() {
        long g = stage("one");
        applyManual(g);
        // the runtime role may only change a lease's state (V028's grants): the owner expires it, as time would
        SmDataPostgres.superuserJdbc().update("UPDATE sm_data.agent_lease SET expires_at = :past, issued_at = :earlier WHERE tenant_id = :t AND agent_id = :a",
                new MapSqlParameterSource().addValue("t", tenant.value()).addValue("a", agentId)
                        .addValue("past", OffsetDateTime.now().minusMinutes(1)).addValue("earlier", OffsetDateTime.now().minusHours(1)));

        ReportResult result = report(1, "c0ffee03", writeBaseline(g).plusSeconds(1));

        assertEquals("UNTRUSTED", result.trust());
        assertEquals("APPLIED", result.disposition());
        assertEquals(LadderRung.PERSISTED, ladder().reached(), "no convergence gate is satisfied by an untrusted report");
        assertEquals(0, runtime.queryForObject("SELECT count(*)::int FROM sm_data.placement_start_observation"
                + " WHERE tenant_id = :t AND placement_id = :p", key().addValue("p", placementId.value()), Integer.class));
    }

    // ---- W3 integration, minor 4: the merged health is the container's, not the host's --------------------------------------------

    private static com.solace.sm.dp.api.agw.AgentHealthObservation healthy(Instant at) {
        return new com.solace.sm.dp.api.agw.AgentHealthObservation(
                com.solace.sm.dp.api.agw.AgentHealthObservation.State.HEALTHY, at,
                com.solace.sm.dp.api.agw.AgentHealthObservation.Strength.READINESS, 200, "UP",
                com.solace.sm.dp.api.agw.AgentHealthObservation.Failure.NONE);
    }

    @Test
    void aNewContainerStartDiscardsTheHealthHeldForThePreviousOneButTheSameStartKeepsIt() {
        com.solace.sm.dp.api.agw.AgentPlacementRef ref = new com.solace.sm.dp.api.agw.AgentPlacementRef(tenant.value(),
                instance.value(), placementId.value());
        Instant t0 = Instant.parse("2026-10-11T10:00:00Z");
        Instant t1 = t0.plusSeconds(90);
        observed.remember(tenant.value(), placementId.value(), 1, healthy(t0), null, 3L, t0, t0);
        assertNotNull(observed.latest(ref).orElseThrow().health(), "the first observation holds its health");

        // the same container, a report with no health (a periodic one): the health it already showed is kept
        observed.remember(tenant.value(), placementId.value(), 2, null, null, 3L, t0, t0.plusSeconds(10));
        assertNotNull(observed.latest(ref).orElseThrow().health());

        // a RESTART's report: a new start and no health (the post-op inspect failed): the old container's HEALTHY must not survive
        observed.remember(tenant.value(), placementId.value(), 3, null, null, 3L, t1, t1);
        AgentObservation afterRestart = observed.latest(ref).orElseThrow();
        assertEquals(t1, afterRestart.startedAt());
        assertNull(afterRestart.health(), "unprobed: the gate keeps polling instead of passing on the old verdict");

        // the new container's own health arrives, and a later hash-less report keeps it
        observed.remember(tenant.value(), placementId.value(), 4, healthy(t1.plusSeconds(5)), null, 3L, t1, t1.plusSeconds(5));
        observed.remember(tenant.value(), placementId.value(), 5, null, null, null, null, t1.plusSeconds(10));
        assertNotNull(observed.latest(ref).orElseThrow().health());
    }
}
