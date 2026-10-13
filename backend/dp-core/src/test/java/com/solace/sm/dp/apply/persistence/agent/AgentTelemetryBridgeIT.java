package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.agent.AgentTelemetryBridge;
import com.solace.sm.dp.agent.AgentTelemetryCounts;
import com.solace.sm.dp.agent.AgentTelemetryDeclarationsService;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.TelemetryDeclaration;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.apply.persistence.JdbcIngestContextRepository;
import com.solace.sm.dp.apply.persistence.JdbcIngestCounterRepository;
import com.solace.sm.dp.apply.persistence.JdbcMetricDefinitionRepository;
import com.solace.sm.dp.apply.persistence.JdbcPlacementRepository;
import com.solace.sm.dp.apply.persistence.JdbcRawSampleRepository;
import com.solace.sm.dp.apply.persistence.JdbcRestartMarkerRepository;
import com.solace.sm.dp.apply.persistence.JdbcSeriesRepository;
import com.solace.sm.dp.apply.persistence.MetricDefinitionCollectionRows;
import com.solace.sm.dp.metrics.ingest.IngestProperties;
import com.solace.sm.dp.metrics.ingest.IngestService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T3.4 ({@code AGW-D27}): agent telemetry re-sunk through the BUILT ingest port, on PostgreSQL 14 as the runtime role.
 * The bridge, the watermark repository, the lease and the session are the real ones; the ingest port is the real
 * {@link IngestService} where the test is about rows, and a scripted port where it is about the bridge's reading of one result
 * (the real service returns {@code Pending} never, and {@code RetryAfter} only when the database fails).
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentTelemetryBridgeIT {

    private static final String GAUGE = "cache.hit_rate";
    private static final String COUNTER = "cache.requests";

    private final AgentJdbcWorld world = new AgentJdbcWorld();
    private final NamedParameterJdbcTemplate owner = world.ownerJdbc();
    private final NamedParameterJdbcTemplate runtime = world.runtimeJdbc();
    private final AgentTelemetryCounts counts = new AgentTelemetryCounts();
    private final JdbcAgentTelemetryWatermarkRepository watermarks = new JdbcAgentTelemetryWatermarkRepository(runtime,
            world.runtimeTx(), world.clock(), world.authority());
    private final List<IngestBatch> forwarded = new CopyOnWriteArrayList<>();
    private final AtomicReference<IngestResult> scripted = new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final List<IngestService> services = new ArrayList<>();

    @BeforeEach
    void clean() {
        forwarded.clear();
    }

    @AfterEach
    void close() {
        services.forEach(IngestService::close);
    }

    // ---- wiring ---------------------------------------------------------------------------------------------------------

    private IngestService realIngest() {
        IngestService service = new IngestService(world.runtimeTx(), new JdbcIngestContextRepository(runtime),
                new JdbcSeriesRepository(runtime), new JdbcRawSampleRepository(runtime), new JdbcIngestCounterRepository(runtime),
                new JdbcRestartMarkerRepository(runtime), new IngestProperties(20, 500, 5000, 30, Duration.ofMillis(250),
                        Duration.ofSeconds(20), Duration.ofMillis(40), Duration.ofMinutes(5), Duration.ofMillis(250)),
                java.time.Clock.systemUTC());
        services.add(service);
        return service;
    }

    /** Records what the bridge forwards, then answers what the test scripted (or throws what it scripted). */
    private TelemetryIngestPort scriptedPort() {
        return batch -> {
            forwarded.add(batch);
            RuntimeException boom = failure.get();
            if (boom != null) {
                throw boom;
            }
            return scripted.get();
        };
    }

    private AgentTelemetryBridge bridge(TelemetryIngestPort port) {
        return new AgentTelemetryBridge(watermarks, port, new JdbcIngestCounterRepository(runtime), counts, world.clock());
    }

    private AgentTelemetryBridge realBridge() {
        return bridge(realIngest());
    }

    private AgentTelemetryResult send(AgentTelemetryBridge bridge, AgentSessionRef session, AgentTelemetryBatch batch) {
        return bridge.ingest(session, batch, world.origin());
    }

    /** The real agent labels every sample with exactly the three ids (AGW-D25); a sample built without labels gets them here. */
    private static Map<String, String> labels(UUID placement) {
        return Map.of("binding_id", placement.toString(), "replica_id", placement.toString(),
                "incarnation_id", UUID.randomUUID().toString());
    }

    private static AgentTelemetryBatch batch(UUID placement, long seq, AgentTelemetryBatch.Sample... samples) {
        List<AgentTelemetryBatch.Sample> labelled = new ArrayList<>();
        for (AgentTelemetryBatch.Sample x : samples) {
            labelled.add(x.labels().isEmpty() ? new AgentTelemetryBatch.Sample(x.seriesKey(), x.type(), x.timestamp(), x.value(),
                    labels(placement)) : x);
        }
        return new AgentTelemetryBatch(placement.toString(), seq, labelled, List.of(), Instant.now());
    }

    private static AgentTelemetryBatch.Sample gauge(String name, Instant at, double value) {
        return new AgentTelemetryBatch.Sample(name, "gauge", at, value, Map.of());
    }

    private static GatewayPortException refused(GatewayCode code, org.junit.jupiter.api.function.Executable call) {
        GatewayPortException e = assertThrows(GatewayPortException.class, call);
        assertEquals(code, e.code());
        return e;
    }

    /** An agent with a live placement whose instance's package version declares a gauge and a counter. */
    private record Site(AgentKitWorld.Agent agent, AgentSessionRef session) {
    }

    private Site site() {
        AgentKitWorld.Agent agent = world.enrol();
        UUID solution = owner.queryForObject("SELECT catalog_solution_id FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", agent.tenantId())
                .addValue("i", agent.instanceId()), UUID.class);
        String version = owner.queryForObject("SELECT declared_package_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", agent.tenantId())
                .addValue("i", agent.instanceId()), String.class);
        JdbcMetricDefinitionRepository definitions = new JdbcMetricDefinitionRepository(runtime);
        definitions.insertDefinitionsIfAbsent(agent.tenantId(), solution, version, new MetricsDeclaration(
                "sha256:" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                List.of(http(GAUGE, "gauge", 8081, "/api/cache/stats", "$.hitRate", "degrade", "{}"),
                        http(COUNTER, "counter", 8081, "/api/cache/stats", "/requests", "error", "{\"b\":\"2 3\",\"a\":\"1\"}"),
                        http("cache.latency", "histogram", 8081, "/api/cache/stats", "/lat", "degrade", "{}"),
                        new MetricDefinitionSpec("cache.pushed", "cache", "gauge", "count", "solace_event",
                                "{\"kind\":\"solace_event\"}", null, false),
                        http("cache.portless", "gauge", 0, "/x", "/y", "degrade", "{}"),
                        http("cache.inactive", "gauge", 8081, "/api/cache/size", "/size", "degrade", "{}")),
                List.of(), Map.of(), List.of()), Instant.now());
        definitions.insertActiveMetrics(agent.tenantId(), agent.instanceId(), 0, List.of(GAUGE, COUNTER, "cache.latency",
                "cache.pushed", "cache.portless"), Instant.now());
        return new Site(agent, world.ready(agent));
    }

    private static MetricDefinitionSpec http(String name, String type, int port, String path, String pointer,
            String onUnavailable, String queryParams) {
        String portPart = port > 0 ? "\"port\":" + port + "," : "";
        return new MetricDefinitionSpec(name, "cache", type, "count", "http_json", "{\"kind\":\"http_json\"," + portPart
                + "\"path\":\"" + path + "\",\"pointer\":\"" + pointer + "\",\"queryParams\":" + queryParams
                + ",\"onUnavailable\":\"" + onUnavailable + "\"}", null, false);
    }

    private OptionalLong watermark(Site site, UUID placement) {
        return world.telemetryWatermark(site.agent(), placement.toString());
    }

    private long counter(Site site, String reason) {
        Long n = owner.queryForObject("SELECT coalesce(sum(count), 0) FROM sm_metrics.ingest_counter WHERE tenant_id = :t"
                + " AND placement_id = :p AND reason = :r", new MapSqlParameterSource().addValue("t", site.agent().tenantId())
                .addValue("p", site.agent().placementId()).addValue("r", reason), Long.class);
        return n == null ? 0 : n;
    }

    // ---- the happy path: the built port, source_discriminator = 'agent' ------------------------------------------------------

    @Test
    void aCommittedBatchIsAckedAndLandsInSmMetricsAsTheAgentSourceOnAnAgentAcquisitionInstance() {
        Site site = site();
        UUID placement = site.agent().placementId();
        owner.update("UPDATE sm_data.instance_state SET acquisition_mode = 'agent' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", site.agent().tenantId()).addValue("i", site.agent().instanceId()));
        Instant at = Instant.now().minusSeconds(30);

        AgentTelemetryResult result = send(realBridge(), site.session(), batch(placement, 1,
                gauge(GAUGE, at, 0.5), gauge(GAUGE, at.plusSeconds(5), 0.6)));

        assertEquals(AgentTelemetryResult.Disposition.ACK, result.disposition());
        assertEquals(1, result.highWatermark());
        assertEquals(OptionalLong.of(1), watermark(site, placement));
        assertEquals(List.of("agent"), owner.queryForList("SELECT DISTINCT source_discriminator FROM sm_metrics.series"
                + " WHERE tenant_id = :t", new MapSqlParameterSource("t", site.agent().tenantId()), String.class));
        assertEquals(2L, owner.queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw r JOIN sm_metrics.series s"
                + " ON s.tenant_id = r.tenant_id AND s.series_id = r.series_id WHERE s.tenant_id = :t AND s.placement_id = :p",
                new MapSqlParameterSource().addValue("t", site.agent().tenantId()).addValue("p", placement), Long.class));
    }

    @Test
    void theForwardedBatchCarriesTheSessionsOrganizationAndThePlacementTheLeaseNamesNotWhatTheLabelsClaim() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        Map<String, String> labels = labels(placement);

        send(bridge(scriptedPort()), site.session(), batch(placement, 1,
                new AgentTelemetryBatch.Sample(GAUGE, "counter", Instant.now().minusSeconds(5), 1.0, labels)));

        assertEquals(1, forwarded.size());
        IngestBatch sent = forwarded.get(0);
        assertEquals(site.agent().tenantId(), sent.organizationId());
        assertEquals(placement, sent.placementId());
        assertEquals(SourceDiscriminator.AGENT, sent.discriminator());
        assertEquals(1, sent.samples().size());
        assertEquals(GAUGE, sent.samples().get(0).metricName(), "the agent's type is advisory and never forwarded");
    }

    // ---- ack on commit only ---------------------------------------------------------------------------------------------------

    @Test
    void retryAfterIsNotAckedAndTheWatermarkDoesNotMove() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.RetryAfter(1500));

        AgentTelemetryResult result = send(bridge(scriptedPort()), site.session(), batch(placement, 7,
                gauge(GAUGE, Instant.now().minusSeconds(5), 1)));

        assertEquals(AgentTelemetryResult.Disposition.DO_NOT_ACK, result.disposition());
        assertEquals(Duration.ofMillis(1500), result.retryAfter());
        assertEquals(0, result.highWatermark(), "the unmoved watermark: a transport that echoes it acknowledges nothing");
        assertEquals(OptionalLong.empty(), watermark(site, placement));
        assertEquals(1, counts.get(AgentTelemetryCounts.NOT_ACKED));
    }

    // ---- agent gap markers (OR-142 (3), plan 015 section 10 item 23; W4 lane D) --------------------------------------------------

    /**
     * Measured in W4: the data plane has no {@code metrics_gap} storage and its ingest writer ignores {@code IngestBatch.gapMarkers};
     * a chart's gaps are derived at read time from the samples it holds ({@code GapDeriver}, DMR-21). So a dropped period charts as
     * a gap with nothing forwarded, and a marker (agent sequence numbers, no instants) is counted and never handed on.
     */
    @Test
    void anAgentGapMarkerIsCountedAndNeverForwardedAndTheBatchIsStillAckedOnCommit() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        AgentTelemetryBatch withGaps = new AgentTelemetryBatch(placement.toString(), 4,
                List.of(new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now().minusSeconds(5), 1, labels(placement))),
                List.of(new AgentTelemetryBatch.Gap(2, 3, "buffer-full"), new AgentTelemetryBatch.Gap(9, 9, "disk-error")),
                Instant.now());

        AgentTelemetryResult result = send(bridge(scriptedPort()), site.session(), withGaps);

        assertEquals(AgentTelemetryResult.Disposition.ACK, result.disposition());
        assertEquals(1, forwarded.size());
        assertEquals(List.of(), forwarded.get(0).gapMarkers(), "no marker reaches the ingest port: it has nowhere to put one");
        assertEquals(2, counts.get(AgentTelemetryCounts.GAPS_NOT_FORWARDED));
        assertEquals(OptionalLong.of(4), watermark(site, placement));
    }

    @Test
    void pendingIsNotAckedAndIsCountedAsUnexpected() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Pending(3));

        AgentTelemetryResult result = send(bridge(scriptedPort()), site.session(), batch(placement, 4,
                gauge(GAUGE, Instant.now().minusSeconds(5), 1)));

        assertEquals(AgentTelemetryResult.Disposition.DO_NOT_ACK, result.disposition());
        assertEquals(OptionalLong.empty(), watermark(site, placement));
        assertEquals(1, counts.get(AgentTelemetryCounts.PENDING_UNEXPECTED));
    }

    @Test
    void aFailureInsideTheIngestPortLeavesTheWatermarkWhereItWasSoTheBatchIsRedelivered() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        send(bridge(scriptedPort()), site.session(), batch(placement, 1, gauge(GAUGE, Instant.now().minusSeconds(9), 1)));
        failure.set(new IllegalStateException("the ingest port blew up"));

        assertThrows(IllegalStateException.class, () -> send(bridge(scriptedPort()), site.session(),
                batch(placement, 2, gauge(GAUGE, Instant.now().minusSeconds(4), 2))));

        assertEquals(OptionalLong.of(1), watermark(site, placement), "never advanced before the outcome is durable");
    }

    @Test
    void aPlacementTheIngestPortNoLongerKnowsIsRejectedCountedAndAckedAsRejectedNeverRetriedForever() {
        Site site = site();
        UUID placement = site.agent().placementId();
        world.retirePlacement(site.agent(), placement);

        AgentTelemetryResult result = send(realBridge(), site.session(), batch(placement, 2,
                gauge(GAUGE, Instant.now().minusSeconds(5), 1)));

        assertEquals(AgentTelemetryResult.Disposition.REJECTED_COUNTED, result.disposition());
        assertEquals(GatewayCode.BINDING_NOT_IN_LEASE, result.rejection());
        assertEquals(OptionalLong.of(2), watermark(site, placement), "acknowledged so the agent's buffer drains");
        assertEquals(1, counts.get(AgentTelemetryCounts.REJECTED));
    }

    @Test
    void aDuplicateBatchSeqIsIgnoredAndNeverForwardedAgain() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        AgentTelemetryBatch first = batch(placement, 5, gauge(GAUGE, Instant.now().minusSeconds(5), 1));

        send(bridge, site.session(), first);
        AgentTelemetryResult again = send(bridge, site.session(), first);
        AgentTelemetryResult older = send(bridge, site.session(), batch(placement, 3, gauge(GAUGE, Instant.now(), 1)));

        assertEquals(1, forwarded.size());
        assertEquals(AgentTelemetryResult.Disposition.ACK, again.disposition());
        assertEquals(5, again.highWatermark());
        assertEquals(5, older.highWatermark());
        assertEquals(1, counts.get(AgentTelemetryCounts.DUPLICATE_BATCH), "the exact redelivery");
        assertEquals(1, counts.get(AgentTelemetryCounts.SEQUENCE_BEHIND_WATERMARK), "the older sequence is not a redelivery");
    }

    @Test
    void anUndeclaredMetricIsDroppedAndCountedNotStored() {
        Site site = site();
        UUID placement = site.agent().placementId();

        AgentTelemetryResult result = send(realBridge(), site.session(), batch(placement, 1,
                gauge("cache.not_declared", Instant.now().minusSeconds(5), 1)));

        assertEquals(AgentTelemetryResult.Disposition.ACK, result.disposition());
        assertEquals(1, counter(site, "undeclared_dropped"));
        assertEquals(0L, owner.queryForObject("SELECT count(*) FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.agent().tenantId()), Long.class));
    }

    // ---- the clock window (OR-134): on the measured offset, not on a sample's age ------------------------------------------------

    private void offset(Site site, long millis) {
        owner.update("UPDATE sm_data.agent_session SET clock_offset_ms = :o WHERE agent_id = :a",
                new MapSqlParameterSource().addValue("o", millis).addValue("a", site.agent().agentId()));
    }

    @Test
    void anOffsetOfExactlyFiveMinutesIsAcceptedAndOneMoreSecondRefusesTheWholeBatchCountedClockSkew() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(2, Map.of()));
        AgentTelemetryBridge bridge = bridge(scriptedPort());

        offset(site, 300_000);
        assertEquals(AgentTelemetryResult.Disposition.ACK, send(bridge, site.session(), batch(placement, 1,
                gauge(GAUGE, Instant.now().minusSeconds(20), 1), gauge(GAUGE, Instant.now().minusSeconds(10), 2))).disposition());
        assertEquals(1, forwarded.size());
        assertEquals(0, counter(site, "clock_skew"));

        offset(site, 300_001);
        AgentTelemetryResult refused = send(bridge, site.session(), batch(placement, 2,
                gauge(GAUGE, Instant.now().minusSeconds(20), 1), gauge(GAUGE, Instant.now().minusSeconds(10), 2)));
        assertEquals(AgentTelemetryResult.Disposition.REJECTED_COUNTED, refused.disposition());
        assertEquals(GatewayCode.CLOCK_SUSPECT, refused.rejection());
        assertEquals(1, forwarded.size(), "nothing of the skewed batch was forwarded");
        assertEquals(2, counter(site, "clock_skew"), "one per refused sample");
        assertEquals(OptionalLong.of(2), watermark(site, placement), "refused and counted is acknowledged, not retried forever");

        offset(site, -300_001);
        assertEquals(GatewayCode.CLOCK_SUSPECT, send(bridge, site.session(), batch(placement, 3,
                gauge(GAUGE, Instant.now().minusSeconds(5), 1))).rejection(), "an agent clock AHEAD of the DP is skew too");
    }

    @Test
    void anOldBufferedSampleIsKeptWithItsOwnTimestampWhenTheClockOffsetIsSmallBecauseTheWindowIsNotOnAge() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        offset(site, 1_000);
        Instant ancient = Instant.now().minus(Duration.ofHours(3));

        AgentTelemetryResult result = send(bridge(scriptedPort()), site.session(), batch(placement, 1, gauge(GAUGE, ancient, 1)));

        assertEquals(AgentTelemetryResult.Disposition.ACK, result.disposition());
        assertEquals(ancient, forwarded.get(0).samples().get(0).ts(), "timestamps are never rewritten");
    }

    @Test
    void anUnmeasuredOffsetIsNotASkew() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        owner.update("UPDATE sm_data.agent_session SET clock_offset_ms = NULL WHERE agent_id = :a",
                new MapSqlParameterSource("a", site.agent().agentId()));

        assertEquals(AgentTelemetryResult.Disposition.ACK, send(bridge(scriptedPort()), site.session(),
                batch(placement, 1, gauge(GAUGE, Instant.now().minusSeconds(5), 1))).disposition());
    }

    // ---- I5: identity is the session's and the lease's, never the agent's ------------------------------------------------------------

    @Test
    void aStreamNamingAnotherAgentsPlacementIsRefusedAndNothingIsWrittenUnderAnyOrganization() {
        Site site = site();
        Site victim = site();
        scripted.set(new IngestResult.Committed(1, Map.of()));

        refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> send(bridge(scriptedPort()), site.session(),
                batch(victim.agent().placementId(), 1, gauge(GAUGE, Instant.now().minusSeconds(5), 1))));

        assertEquals(0, forwarded.size());
        assertEquals(OptionalLong.empty(), world.telemetryWatermark(victim.agent(), victim.agent().placementId().toString()));
        assertEquals(OptionalLong.empty(), world.telemetryWatermark(site.agent(), victim.agent().placementId().toString()));
    }

    @Test
    void aStreamThatIsNotAPlacementIdIsRefusedWithTheSameCode() {
        Site site = site();
        for (String stream : List.of("main", "bad stream!", "", "00000000-0000-0000-0000-000000000000")) {
            refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> send(bridge(scriptedPort()), site.session(),
                    new AgentTelemetryBatch(stream, 1, List.of(gauge(GAUGE, Instant.now(), 1)), List.of(), Instant.now())));
        }
        assertEquals(0, forwarded.size());
    }

    @Test
    void labelsOutsideTheClosedListAreRefusedAsUndeclaredAndNeverSelectAnything() {
        Site site = site();
        Site victim = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        scripted.set(new IngestResult.Committed(1, Map.of()));
        Map<String, String> hostile = Map.of("tenant", victim.agent().tenantId().toString());

        refused(GatewayCode.UNDECLARED_LABEL, () -> send(bridge, site.session(), batch(placement, 1,
                new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now(), 1, hostile))));
        refused(GatewayCode.LABEL_INVALID, () -> send(bridge, site.session(), batch(placement, 1,
                new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now(), 1, Map.of("replica_id", "not-an-id")))));
        refused(GatewayCode.BINDING_NOT_IN_LEASE, () -> send(bridge, site.session(), batch(placement, 1,
                new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now(), 1, labels(victim.agent().placementId())))));
        Map<String, String> otherReplica = new java.util.HashMap<>(labels(placement));
        otherReplica.put("replica_id", victim.agent().placementId().toString());
        refused(GatewayCode.UNKNOWN_REPLICA, () -> send(bridge, site.session(), batch(placement, 1,
                new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now(), 1, otherReplica))));
        refused(GatewayCode.LABEL_INVALID, () -> send(bridge, site.session(), new AgentTelemetryBatch(placement.toString(), 1,
                List.of(new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now(), 1, Map.of())), List.of(), Instant.now())));

        assertEquals(0, forwarded.size());
        assertEquals(OptionalLong.empty(), watermark(site, placement));
    }

    @Test
    void aStaleSessionAndALeaselessSessionAreRefusedBeforeAnythingIsForwarded() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        scripted.set(new IngestResult.Committed(1, Map.of()));
        AgentKitWorld.Agent leaseless = world.enrol();
        AgentSessionRef leaselessSession = world.open(leaseless);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> send(bridge, leaselessSession, batch(leaseless.placementId(), 1,
                gauge(GAUGE, Instant.now(), 1))));

        AgentSessionRef stale = site.session();
        world.open(site.agent());
        refused(GatewayCode.STALE_EPOCH, () -> send(bridge, stale, batch(placement, 1, gauge(GAUGE, Instant.now(), 1))));

        assertEquals(0, forwarded.size());
    }

    // ---- the declarations ---------------------------------------------------------------------------------------------------------

    private AgentTelemetryDeclarationsService declarations() {
        return new AgentTelemetryDeclarationsService(watermarks, new JdbcIngestContextRepository(runtime),
                new MetricDefinitionCollectionRows(new JdbcMetricDefinitionRepository(runtime)),
                (tenant, instance) -> java.util.OptionalLong.empty(), new JdbcPlacementRepository(runtime));
    }

    @Test
    void theDeclarationIsThePlacementsCollectableHttpJsonMetricsAtTheFixedFiveSecondCadence() {
        Site site = site();

        List<TelemetryDeclaration> declared = declarations().forAgent(site.session());

        assertEquals(1, declared.size());
        TelemetryDeclaration d = declared.get(0);
        assertEquals(site.agent().placementId(), d.placementId());
        assertEquals(Duration.ofSeconds(5), d.interval(), "OR-73, never the package's intervalSeconds");
        assertNull(d.healthProbe(), "the package declares no probe: nothing is probed (OR-101)");
        assertEquals(List.of(GAUGE, COUNTER), d.metrics().stream().map(TelemetryDeclaration.Metric::name).sorted().toList(),
                "histogram, non-http_json, portless and inactive metrics are not declared");
        TelemetryDeclaration.Metric gauge = d.metrics().stream().filter(m -> m.name().equals(GAUGE)).findFirst().orElseThrow();
        assertEquals(8081, gauge.containerPort());
        assertEquals("/api/cache/stats", gauge.path());
        assertEquals("$.hitRate", gauge.pointer());
        assertEquals("gauge", gauge.metricType());
        assertTrue(gauge.degradeWhenUnavailable());
        TelemetryDeclaration.Metric counter = d.metrics().stream().filter(m -> m.name().equals(COUNTER)).findFirst().orElseThrow();
        assertFalse(counter.degradeWhenUnavailable(), "onUnavailable: error");
        assertEquals("/api/cache/stats?a=1&b=2+3", counter.path(), "query params ride on the path, sorted and encoded");
    }

    @Test
    void aDeclaredProbeIsCarriedAndARetiredOrForeignPlacementIsNotDeclared() {
        AgentKitWorld.Agent agent = world.enrol();
        UUID probed = UUID.randomUUID();
        owner.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations, health_kind,"
                + " health_path, health_port, health_strength, created_at) VALUES (:t, :p, :i, :x, :c, :d,"
                + " '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, 'actuator-health',"
                + " '/actuator/health', 18081, 'readiness', :now)", new MapSqlParameterSource().addValue("t", agent.tenantId())
                .addValue("p", probed).addValue("i", agent.instanceId()).addValue("x", agent.targetId())
                .addValue("c", "sm-" + probed).addValue("d", "/srv/sm/" + probed).addValue("now", OffsetDateTime.now()));
        AgentKitWorld.Agent other = world.enrol();
        AgentSessionRef session = world.ready(agent);
        world.retirePlacement(agent, agent.placementId());

        List<TelemetryDeclaration> declared = declarations().forAgent(session);

        assertEquals(List.of(probed), declared.stream().map(TelemetryDeclaration::placementId).toList(),
                "the retired placement is not declared; another agent's placement never is");
        assertNotNull(declared.get(0).healthProbe());
        assertEquals("actuator-health", declared.get(0).healthProbe().kind());
        assertEquals("/actuator/health", declared.get(0).healthProbe().path());
        assertEquals(18081, declared.get(0).healthProbe().hostPort());
        assertEquals(ProbeStrength.READINESS, declared.get(0).healthProbe().strength());
        assertTrue(declared.get(0).metrics().isEmpty(), "no recorded active list: collect nothing, as the pull path does");
        assertFalse(declared.stream().anyMatch(x -> x.placementId().equals(other.placementId())));
    }

    @Test
    void theDeclarationsFenceTheSessionAndNeedAnActiveLease() {
        Site site = site();
        AgentSessionRef stale = site.session();
        world.open(site.agent());
        refused(GatewayCode.STALE_EPOCH, () -> declarations().forAgent(stale));
        AgentKitWorld.Agent leaseless = world.enrol();
        AgentSessionRef open = world.open(leaseless);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> declarations().forAgent(open));
    }

    // ---- fix round: the mappings and paths the first round did not exercise ---------------------------------------------------------

    @Test
    void aBatchTheIngestPortDroppedFromItsBufferIsNotAckedBecauseItCanBeSentAgain() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Rejected("buffer.dropped"));

        AgentTelemetryResult result = send(bridge(scriptedPort()), site.session(), batch(placement, 3,
                gauge(GAUGE, Instant.now().minusSeconds(5), 1)));

        assertEquals(AgentTelemetryResult.Disposition.DO_NOT_ACK, result.disposition());
        assertEquals(OptionalLong.empty(), watermark(site, placement));
        assertEquals(0, counts.get(AgentTelemetryCounts.REJECTED), "a transient drop is not a permanent rejection");
    }

    @Test
    void aPermanentRefusalIsAckedAsRejectedWithItsOwnCodeOrUnknown() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        scripted.set(new IngestResult.Rejected("batch.too-large"));
        AgentTelemetryResult large = send(bridge, site.session(), batch(placement, 1, gauge(GAUGE, Instant.now().minusSeconds(5), 1)));
        scripted.set(new IngestResult.Rejected("batch.refused"));
        AgentTelemetryResult refused = send(bridge, site.session(), batch(placement, 2, gauge(GAUGE, Instant.now().minusSeconds(4), 1)));

        assertEquals(AgentTelemetryResult.Disposition.REJECTED_COUNTED, large.disposition());
        assertEquals(GatewayCode.PAYLOAD_TOO_LARGE, large.rejection());
        assertEquals(GatewayCode.UNKNOWN, refused.rejection());
        assertEquals(OptionalLong.of(2), watermark(site, placement));
    }

    @Test
    void aDoNotAckCarriesTheExistingWatermarkNotZero() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        scripted.set(new IngestResult.Committed(1, Map.of()));
        send(bridge, site.session(), batch(placement, 3, gauge(GAUGE, Instant.now().minusSeconds(9), 1)));
        scripted.set(new IngestResult.RetryAfter(1000));

        AgentTelemetryResult held = send(bridge, site.session(), batch(placement, 4, gauge(GAUGE, Instant.now().minusSeconds(4), 1)));

        assertEquals(AgentTelemetryResult.Disposition.DO_NOT_ACK, held.disposition());
        assertEquals(3, held.highWatermark());
    }

    @Test
    void malformedSamplesAreDroppedAndCountedOnlyWhenTheBatchReachesAFinalOutcome() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        AgentTelemetryBatch.Sample good = new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now().minusSeconds(5), 1, labels(placement));
        AgentTelemetryBatch.Sample noKey = new AgentTelemetryBatch.Sample("", "gauge", Instant.now(), 1, labels(placement));
        AgentTelemetryBatch.Sample noTime = new AgentTelemetryBatch.Sample(GAUGE, "gauge", null, 1, labels(placement));
        AgentTelemetryBatch mixed = new AgentTelemetryBatch(placement.toString(), 1, List.of(good, noKey, noTime), List.of(), Instant.now());

        scripted.set(new IngestResult.RetryAfter(1000));
        send(bridge, site.session(), mixed);
        assertEquals(0, counter(site, "parse_failed"), "a held batch is resent, so nothing is counted yet");

        scripted.set(new IngestResult.Committed(1, Map.of()));
        send(bridge, site.session(), mixed);
        assertEquals(1, forwarded.get(forwarded.size() - 1).samples().size());
        assertEquals(2, counter(site, "parse_failed"));

        AgentTelemetryBatch allBad = new AgentTelemetryBatch(placement.toString(), 2, List.of(noKey), List.of(), Instant.now());
        int before = forwarded.size();
        assertEquals(AgentTelemetryResult.Disposition.ACK, send(bridge, site.session(), allBad).disposition());
        assertEquals(before, forwarded.size(), "nothing to commit is not forwarded");
        assertEquals(3, counter(site, "parse_failed"));
        assertEquals(OptionalLong.of(2), watermark(site, placement));
    }

    @Test
    void aRedeliveryAfterCommitWithoutAnAdvanceIsAbsorbedByTheIngestPortNotDoubleStored() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = realBridge();
        Instant at = Instant.now().minusSeconds(40);
        AgentTelemetryBatch same = batch(placement, 1, gauge(GAUGE, at, 0.5), gauge(GAUGE, at.plusSeconds(5), 0.6));

        send(bridge, site.session(), same);
        // the crash window: Committed, then the process died before the watermark moved
        owner.update("DELETE FROM sm_data.agent_telemetry_watermark WHERE agent_id = :a",
                new MapSqlParameterSource("a", site.agent().agentId()));
        AgentTelemetryResult again = send(bridge, site.session(), same);

        assertEquals(AgentTelemetryResult.Disposition.ACK, again.disposition());
        assertEquals(2L, owner.queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw r JOIN sm_metrics.series s"
                + " ON s.tenant_id = r.tenant_id AND s.series_id = r.series_id WHERE s.tenant_id = :t AND s.placement_id = :p",
                new MapSqlParameterSource().addValue("t", site.agent().tenantId()).addValue("p", placement), Long.class));
    }

    @Test
    void aSequenceBehindTheWatermarkIsCountedApartFromARedeliveryAndStillAcknowledged() {
        Site site = site();
        UUID placement = site.agent().placementId();
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        scripted.set(new IngestResult.Committed(1, Map.of()));
        send(bridge, site.session(), batch(placement, 5, gauge(GAUGE, Instant.now().minusSeconds(9), 1)));

        send(bridge, site.session(), batch(placement, 5, gauge(GAUGE, Instant.now().minusSeconds(9), 1)));
        assertEquals(1, counts.get(AgentTelemetryCounts.DUPLICATE_BATCH));
        assertEquals(0, counts.get(AgentTelemetryCounts.SEQUENCE_BEHIND_WATERMARK));

        AgentTelemetryResult reset = send(bridge, site.session(), batch(placement, 1, gauge(GAUGE, Instant.now().minusSeconds(4), 1)));
        assertEquals(1, counts.get(AgentTelemetryCounts.SEQUENCE_BEHIND_WATERMARK));
        assertEquals(1, counts.get(AgentTelemetryCounts.DUPLICATE_BATCH));
        assertEquals(AgentTelemetryResult.Disposition.ACK, reset.disposition(), "the rule for a reset is the owner's; behaviour unchanged");
        assertEquals(5, reset.highWatermark());
        assertEquals(1, forwarded.size());
    }

    @Test
    void aStreamIdInUpperCaseUsesTheSameWatermarkRowAsTheLowerCaseOne() {
        Site site = site();
        UUID placement = site.agent().placementId();
        scripted.set(new IngestResult.Committed(1, Map.of()));
        AgentTelemetryBridge bridge = bridge(scriptedPort());
        send(bridge, site.session(), batch(placement, 1, gauge(GAUGE, Instant.now().minusSeconds(9), 1)));

        AgentTelemetryBatch upper = new AgentTelemetryBatch(placement.toString().toUpperCase(java.util.Locale.ROOT), 2,
                List.of(new AgentTelemetryBatch.Sample(GAUGE, "gauge", Instant.now().minusSeconds(4), 1, labels(placement))),
                List.of(), Instant.now());
        send(bridge, site.session(), upper);

        assertEquals(1, world.rows("agent_telemetry_watermark", site.agent()));
        assertEquals(OptionalLong.of(2), watermark(site, placement));
    }

    @Test
    void anInstanceThatIsNotActiveDeclaresNothing() {
        Site site = site();
        owner.update("UPDATE sm_data.instance_state SET lifecycle = 'retiring' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", site.agent().tenantId()).addValue("i", site.agent().instanceId()));

        assertEquals(List.of(), declarations().forAgent(site.session()));
    }
}
