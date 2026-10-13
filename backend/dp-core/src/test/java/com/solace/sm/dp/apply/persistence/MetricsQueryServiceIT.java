package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.query.CollectorHealthView;
import com.solace.sm.dp.api.metrics.query.MetricsQueryRefusedException;
import com.solace.sm.dp.api.metrics.query.MetricsQueryUnavailableException;
import com.solace.sm.dp.api.metrics.query.ObservedMetric;
import com.solace.sm.dp.api.metrics.query.QueryRefusal;
import com.solace.sm.dp.api.metrics.query.RangeQuery;
import com.solace.sm.dp.api.metrics.query.RangeQuery.Across;
import com.solace.sm.dp.api.metrics.query.RangeQuery.Resolution;
import com.solace.sm.dp.api.metrics.query.RangeQuery.SeriesBy;
import com.solace.sm.dp.api.metrics.query.RangeResult;
import com.solace.sm.dp.api.metrics.query.RangeResult.Tier;
import com.solace.sm.dp.api.metrics.query.SeriesView;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.metrics.MetricsDbBudget;
import com.solace.sm.dp.metrics.collect.CollectionRows;
import com.solace.sm.dp.metrics.query.MetricsQueryServiceImpl;
import com.solace.sm.dp.metrics.query.QueryProperties;
import com.solace.sm.dp.metrics.rollup.DiskProperties;
import com.solace.sm.dp.metrics.rollup.MetricsDiskAlarm;
import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import com.solace.sm.dp.metrics.rollup.RollupService;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-6.1: the metrics query service over real PostgreSQL as the runtime credential, on a database of its own.
 * Everything is planted the way collection leaves it (series, segments, raw samples with their increments, real rollups
 * by {@link RollupService}); the clock is FIXED, so no case waits on a wall clock and the provisional edge is exact.
 *
 * <p>Dates are in the current UTC month ({@code metrics_raw} has a partition for it and the three after), and every
 * placement is created on day 1, so a case never depends on the real date or on a month boundary.
 *
 * <p>(In the persistence test package: {@link MetricsDb}, {@link SqlRecorder} and the Jdbc repositories' constructors are
 * package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MetricsQueryServiceIT {

    private static final YearMonth MONTH = YearMonth.now(ZoneOffset.UTC);
    private static final Instant NOW = at(20, "14:40:00");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RollupProperties ROLLUP = new RollupProperties(Duration.ofMinutes(5), Duration.ofHours(2),
            Duration.ofHours(24));
    private static final String DIGEST = "sha256:" + "ab".repeat(32);

    private static MetricsDb db;
    private static DataSource runtime;
    private static NamedParameterJdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate superuser;
    private static DataSourceTransactionManager transactions;
    private static PartitionRunway runway;
    private static MetricsDiskAlarm disk;
    private static final Map<UUID, Long> OBSERVED_GENERATION = new ConcurrentHashMap<>();
    private static JdbcMetricDefinitionRepository definitions;

    private static MetricsQueryServiceImpl service;

    @BeforeAll
    static void start() {
        db = MetricsDb.fresh("query_service");
        runtime = db.runtime();
        jdbc = new NamedParameterJdbcTemplate(runtime);
        superuser = db.superuserJdbc();
        transactions = new DataSourceTransactionManager(runtime);
        definitions = new JdbcMetricDefinitionRepository(jdbc);
        runway = new PartitionRunway(new JdbcPartitionRunwayRepository(jdbc), new ScheduledThreadPoolExecutor(1), ROLLUP,
                CLOCK, () -> { });
        runway.keep();
        disk = new MetricsDiskAlarm(new JdbcMetricsDiskRepository(jdbc), new DiskProperties(null, Duration.ofHours(1)),
                new ScheduledThreadPoolExecutor(1));
        disk.refresh();
        service = serviceOver(new JdbcMetricsQueryRepository(jdbc, transactions), QueryProperties.defaults(),
                new MetricsDbBudget(4));
    }

    private static MetricsQueryServiceImpl serviceOver(JdbcMetricsQueryRepository rows, QueryProperties properties,
            MetricsDbBudget budget) {
        CollectionRows active = new MetricDefinitionCollectionRows(definitions);
        return new MetricsQueryServiceImpl(rows, budget, properties, ROLLUP, CLOCK,
                (tenant, instance) -> OBSERVED_GENERATION.containsKey(instance.value())
                        ? OptionalLong.of(OBSERVED_GENERATION.get(instance.value())) : OptionalLong.empty(),
                active, runway, disk);
    }

    private static QueryProperties props(int cap, Duration statementTimeout, Duration budgetWait) {
        QueryProperties d = QueryProperties.defaults();
        return new QueryProperties(d.maxPointsPerSeries(), cap, statementTimeout, budgetWait, d.rawMaxRange(),
                d.hourlyMaxRange(), d.rateSeconds(), d.rawRetention(), d.hourlyRetention(), d.placementOverlapGrace());
    }

    // ---- arrangement ---------------------------------------------------------------------------------------------

    private static Instant at(int day, String time) {
        return Instant.parse(MONTH.atDay(day) + "T" + time + "Z");
    }

    private static OffsetDateTime odt(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** A tenant of its own: the shared container's isolation unit. */
    private record Tenant(UUID id, UUID catalog) {
        Tenant() {
            this(UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private static UUID instance(Tenant t, String name, String lifecycle) {
        UUID instance = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, :l, 'via-ssh', :u, :now)",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("i", instance)
                        .addValue("e", UUID.randomUUID()).addValue("c", t.catalog()).addValue("n", name)
                        .addValue("l", lifecycle).addValue("u", UUID.randomUUID()).addValue("now", OffsetDateTime.now()));
        return instance;
    }

    private static UUID instance(Tenant t, String name) {
        return instance(t, name, "active");
    }

    private static UUID placement(Tenant t, UUID instance, Instant createdAt) {
        UUID target = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', :h, 22, 'ec2-user', :fp, :cred,"
                        + " '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("x", target)
                        .addValue("h", "vm-" + target.toString().substring(0, 8) + ".corp.example")
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :at)",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("p", placement).addValue("i", instance)
                        .addValue("x", target).addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("at", odt(createdAt)));
        return placement;
    }

    private static UUID placement(Tenant t, UUID instance) {
        return placement(t, instance, at(1, "00:00:00"));
    }

    private static void retire(Tenant t, UUID placement, Instant at) {
        jdbc.update("UPDATE sm_data.placement SET retired_at = :at WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("at", odt(at)).addValue("t", t.id()).addValue("p", placement));
    }

    private static long series(Tenant t, UUID instance, UUID placement, String metric, String type, Integer interval) {
        long id = jdbc.queryForObject("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name,"
                        + " metric_type, unit, source_discriminator, labels_fingerprint, created_at) VALUES (:t, :i, :p, :n,"
                        + " :mt, 'count', 'dp-poll', :fp, :now) RETURNING series_id",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("i", instance).addValue("p", placement)
                        .addValue("n", metric).addValue("mt", type).addValue("fp", "sha256:" + "0".repeat(64))
                        .addValue("now", OffsetDateTime.now()), Long.class);
        if (interval != null) {
            jdbc.update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version,"
                            + " interval_seconds) VALUES (:t, :s, :f, '2.4', :iv)",
                    new MapSqlParameterSource().addValue("t", t.id()).addValue("s", id)
                            .addValue("f", odt(at(1, "00:00:00"))).addValue("iv", interval));
        }
        return id;
    }

    private static void raw(Tenant t, long series, Instant ts, double value, Double increment) {
        jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                        + " VALUES (:t, :s, :ts, :v, :inc, :ts)",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", series).addValue("ts", odt(ts))
                        .addValue("v", value).addValue("inc", increment));
    }

    /** A gauge reading of {@code value} every {@code stepSeconds} from {@code from} to {@code to} inclusive. */
    private static void gauge(Tenant t, long series, Instant from, Instant to, int stepSeconds, double value) {
        jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                        + " SELECT :t, :s, g, :v, NULL, g FROM generate_series(:from::timestamptz, :to::timestamptz,"
                        + " make_interval(secs => :step)) g",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", series).addValue("v", value)
                        .addValue("from", odt(from)).addValue("to", odt(to)).addValue("step", stepSeconds));
    }

    /** As {@link #gauge}, at a step that is not a whole number of seconds: the collector's realised period (Fable MON-01). */
    private static void gaugeEvery(Tenant t, long series, Instant from, Instant to, double stepSeconds, double value) {
        jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                        + " SELECT :t, :s, g, :v, NULL, g FROM generate_series(:from::timestamptz, :to::timestamptz,"
                        + " make_interval(secs => :step::double precision)) g",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", series).addValue("v", value)
                        .addValue("from", odt(from)).addValue("to", odt(to)).addValue("step", stepSeconds));
    }

    private static void rollUpEveryHour(Tenant t, Instant from) {
        for (Instant now = from; !now.isAfter(NOW); now = now.plus(Duration.ofHours(1))) {
            rollups().rollUp(new TenantId(t.id()), now);
        }
        rollups().rollUp(new TenantId(t.id()), NOW);
    }

    /** A counter climbing by {@code incrementPerStep} each {@code stepSeconds}; the first sample has no increment. */
    private static void counter(Tenant t, long series, Instant from, Instant to, int stepSeconds, double incrementPerStep) {
        jdbc.update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                        + " SELECT :t, :s, g, 1000 + (row_number() OVER (ORDER BY g) - 1) * :inc::double precision,"
                        + " CASE WHEN g = :from::timestamptz THEN NULL ELSE :inc::double precision END, g"
                        + " FROM generate_series(:from::timestamptz, :to::timestamptz, make_interval(secs => :step)) g",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", series).addValue("inc", incrementPerStep)
                        .addValue("from", odt(from)).addValue("to", odt(to)).addValue("step", stepSeconds));
    }

    private static void declare(Tenant t, MetricDefinitionSpec... specs) {
        definitions.insertDefinitionsIfAbsent(t.id(), t.catalog(), "2.4",
                new MetricsDeclaration(DIGEST, List.of(specs), List.of(), Map.of(), List.of()), Instant.now());
    }

    private static MetricDefinitionSpec definition(String name, String component, String type) {
        return new MetricDefinitionSpec(name, component, type, "count", "http_json",
                "{\"kind\":\"http_json\",\"path\":\"/x\",\"port\":8081,\"pointer\":\"/v\",\"onUnavailable\":\"degrade\"}",
                30, true);
    }

    private static RollupService rollups() {
        return new RollupService(new JdbcRollupRepository(jdbc), ROLLUP);
    }

    private static RangeQuery query(String metric, List<UUID> instances, Instant from, Instant to, Resolution resolution,
            SeriesBy seriesBy, Across across) {
        return new RangeQuery(metric, null, instances, Map.of(), from, to, resolution, seriesBy, across, List.of());
    }

    private static RangeQuery auto(String metric, UUID instance, Instant from, Instant to) {
        return query(metric, List.of(instance), from, to, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE);
    }

    private static List<SeriesView.Point> points(RangeResult result, int line) {
        return result.series().get(line).points();
    }

    private static SeriesView.Point pointAt(RangeResult result, int line, Instant ts) {
        return points(result, line).stream().filter(p -> p.ts().equals(ts)).findFirst().orElse(null);
    }

    private static MetricsQueryRefusedException refused(Tenant t, RangeQuery q) {
        return assertThrows(MetricsQueryRefusedException.class, () -> service.range(t.id(), q));
    }

    // ---- tier routing --------------------------------------------------------------------------------------------

    @Test
    void sevenDaysIsRawBucketedToAtMostOneThousandPoints() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        UUID p = placement(t, i);
        long s = series(t, i, p, "cache.size", "gauge", 30);
        gauge(t, s, at(13, "14:40:00"), NOW, 300, 5.0);
        RangeResult r = service.range(t.id(), auto("cache.size", i, at(13, "14:40:00"), NOW));
        assertEquals(Tier.RAW, r.resolution().served());
        assertTrue(r.resolution().bucketSeconds() >= 606, "7 d over at most 999 buckets: " + r.resolution().bucketSeconds());
        List<SeriesView.Point> points = points(r, 0);
        assertTrue(points.size() <= 1000 && points.size() > 900, "points " + points.size());
        assertTrue(points.stream().allMatch(pt -> pt.value() == 5.0));
        assertEquals(r.resolution().bucketSeconds() / 30, points.get(5).expectedCount(), "bucket / the interval in force");
        assertEquals(List.of(), r.series().get(0).gaps());
        assertNull(r.coverage().provisionalFrom(), "raw is the source: nothing is provisional");
        assertFalse(r.coverage().truncatedByRetention());
        assertEquals(RangeResult.Transform.NONE, r.render().transform());
    }

    @Test
    void aForcedRawRangeReadsRawHoweverLong() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(18, "00:00:00"), NOW, 600, 5.0);
        RangeResult r = service.range(t.id(), query("cache.size", List.of(i), NOW.minus(Duration.ofDays(30)), NOW,
                Resolution.RAW, SeriesBy.INSTANCE, Across.NONE));
        assertEquals(Tier.RAW, r.resolution().served());
        assertEquals(Resolution.RAW, r.resolution().requested());
        assertTrue(points(r, 0).size() <= 1000);
        assertTrue(points(r, 0).size() > 50, "the data really came from raw");
    }

    @Test
    void thirtyDaysIsHourlyWithAProvisionalRawTail() {
        // S-L at 14:40 with a 2 h late window: hours before 11:00 are final rollups; 11:00 onward is raw.
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 600);
        gauge(t, s, at(18, "00:00:00"), NOW, 600, 10.0);
        for (Instant now = at(18, "01:00:00"); !now.isAfter(NOW); now = now.plus(Duration.ofHours(1))) {
            rollups().rollUp(new TenantId(t.id()), now);
        }
        rollups().rollUp(new TenantId(t.id()), NOW);
        long rolledAt12 = jdbc.queryForObject("SELECT sample_count FROM sm_metrics.metrics_rollup_1h WHERE tenant_id = :t"
                        + " AND series_id = :s AND bucket = :b",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", s).addValue("b", odt(at(20, "12:00:00"))),
                Long.class);
        assertEquals(6, rolledAt12);
        // A late sample lands in the 12:00 hour AFTER the rollup ran: only the raw tail can show it.
        raw(t, s, at(20, "12:35:00"), 1000.0, null);

        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));
        assertEquals(Tier.HOURLY, r.resolution().served());
        assertEquals(3600, r.resolution().bucketSeconds());
        assertEquals(7200, r.gapThresholdSeconds(), "2 x the hourly bucket, not 2 x a scrape interval");
        assertEquals(at(20, "11:00:00"), r.coverage().provisionalFrom(),
                "the first hour RollupService may still rewrite at 14:40 with a 2 h late window");
        assertEquals(10.0, pointAt(r, 0, at(20, "10:00:00")).value(), 1e-9);
        assertEquals(6, pointAt(r, 0, at(20, "10:00:00")).sampleCount());
        assertEquals(6, pointAt(r, 0, at(20, "10:00:00")).expectedCount());
        SeriesView.Point twelve = pointAt(r, 0, at(20, "12:00:00"));
        assertEquals(7, twelve.sampleCount(), "the stitched tail sees the late sample the rollup row does not");
        assertEquals((6 * 10.0 + 1000.0) / 7, twelve.value(), 1e-9);
        assertEquals(6, pointAt(r, 0, at(20, "11:00:00")).sampleCount());
        assertEquals(6, pointAt(r, 0, at(20, "13:00:00")).sampleCount());
        SeriesView.Point open = pointAt(r, 0, at(20, "14:00:00"));
        assertNotNull(open, "the open hour has no rollup row yet: only the raw tail can show it (an empty right edge otherwise)");
        assertEquals(4, open.sampleCount());
        assertEquals(List.of(), r.series().get(0).gaps(), "the open, shorter hour is provisional, not a gap");
        assertEquals(at(18, "00:00:00"), points(r, 0).get(0).ts());
    }

    /** W3-08: a rollup range whose buckets would exceed maxPointsPerSeries is widened to a multiple of the tier's width. */
    @Test
    void ninetyDaysHourlyIsWidenedSoNoSeriesExceedsTheAdvertisedPointCap() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i, NOW.minus(Duration.ofDays(120))), "cache.size", "gauge", 600);
        long c = series(t, i, placement(t, i, NOW.minus(Duration.ofDays(120))), "cache.hit_count", "counter", 600);
        Instant first = NOW.minus(Duration.ofDays(89)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        superuser.update("INSERT INTO sm_metrics.metrics_rollup_1h (tenant_id, series_id, bucket, metric_type, sample_count,"
                        + " expected_count, sum, min, max, last_value, last_ts, computed_at)"
                        + " SELECT :t, :s, g, 'gauge', 6, 6, 60, 10, 10, 10, g, now()"
                        + " FROM generate_series(:from::timestamptz, :to::timestamptz, interval '1 hour') g",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", s).addValue("from", odt(first))
                        .addValue("to", odt(at(18, "00:00:00"))));
        superuser.update("INSERT INTO sm_metrics.metrics_rollup_1h (tenant_id, series_id, bucket, metric_type, sample_count,"
                        + " expected_count, delta, covered_seconds, last_value, last_ts, computed_at)"
                        + " SELECT :t, :s, g, 'counter', 6, 6, 3600, 3600, 1, g, now()"
                        + " FROM generate_series(:from::timestamptz, :to::timestamptz, interval '1 hour') g",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("s", c).addValue("from", odt(first))
                        .addValue("to", odt(at(18, "00:00:00"))));
        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(90)), NOW));
        assertEquals(Tier.HOURLY, r.resolution().served());
        assertEquals(3 * 3600, r.resolution().bucketSeconds(), "2160 hourly buckets over a cap of 1000: three hours each");
        assertEquals(6 * 3600, r.gapThresholdSeconds(), "2 x the WIDENED bucket");
        assertTrue(points(r, 0).size() <= 1000, "points " + points(r, 0).size());
        SeriesView.Point folded = points(r, 0).get(5);
        assertEquals(18, folded.sampleCount(), "three hourly rows pooled");
        assertEquals(18, folded.expectedCount());
        assertEquals(10.0, folded.value(), 1e-9, "a gauge pools sum and count: the mean stays the mean");
        assertEquals(List.of(), r.series().get(0).gaps(), "whole three-hour bins, none missing");
        RangeResult rate = service.range(t.id(), auto("cache.hit_count", i, NOW.minus(Duration.ofDays(90)), NOW));
        assertTrue(points(rate, 0).size() <= 1000);
        assertEquals(60.0, points(rate, 0).get(5).value(), 1e-9, "1/s: sum delta over sum covered, per minute");
    }

    /** W3-11: one rate across a re-target bucket, not both placements' rates added. */
    @Test
    void aCounterRetargetedInsideABucketIsOneRateNotTwo() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        Instant moved = at(20, "14:20:11");
        UUID before = placement(t, i, at(1, "00:00:00"));
        retire(t, before, moved);
        UUID after = placement(t, i, moved);
        long c1 = series(t, i, before, "cache.hit_count", "counter", 30);
        long c2 = series(t, i, after, "cache.hit_count", "counter", 30);
        counter(t, c1, at(20, "14:00:00"), at(20, "14:20:10"), 10, 10.0);    // 1/s, last sample 14:20:10
        counter(t, c2, at(20, "14:20:12"), at(20, "14:30:00"), 6, 6.0);      // 1/s, first sample 14:20:12
        RangeResult r = service.range(t.id(), auto("cache.hit_count", i, at(20, "14:00:00"), NOW));
        assertEquals(1, r.series().size());
        assertEquals(30, r.resolution().bucketSeconds());
        SeriesView.Point seam = pointAt(r, 0, at(20, "14:20:00"));
        assertNotNull(seam);
        assertEquals(60.0, seam.value(), 1e-6, "1 per second per minute, across the switch (the placements do not overlap)");
        assertEquals(60.0, pointAt(r, 0, at(20, "14:10:00")).value(), 1e-6);
        assertEquals(60.0, pointAt(r, 0, at(20, "14:25:00")).value(), 1e-6);
    }

    @Test
    void anHourMissingFromTheRollupsIsAGapAndAnAbsentBucketEmitsNoPoint() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 600);
        gauge(t, s, at(19, "10:00:00"), at(19, "10:50:00"), 600, 4.0);
        gauge(t, s, at(19, "12:00:00"), at(19, "12:50:00"), 600, 4.0);
        rollups().rollUp(new TenantId(t.id()), at(19, "12:30:00"));
        rollups().rollUp(new TenantId(t.id()), at(19, "15:30:00"));
        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));
        assertEquals(List.of(at(19, "10:00:00"), at(19, "12:00:00")), points(r, 0).stream().map(SeriesView.Point::ts).toList(),
                "no tuple, never a null, for the hour with no samples");
        assertEquals(List.of(new SeriesView.Gap(at(19, "11:00:00"), at(19, "12:00:00"), "no-samples")), r.series().get(0).gaps(),
                "an ABSENT hourly bucket is a gap even though two bucket widths is the reported threshold");
    }

    @Test
    void aDailyRangeIsServedFromTheDayRollupsWithTheTodayStitchedFromRaw() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 600);
        gauge(t, s, at(18, "00:00:00"), NOW, 600, 8.0);
        for (Instant now = at(18, "01:00:00"); !now.isAfter(NOW); now = now.plus(Duration.ofHours(1))) {
            rollups().rollUp(new TenantId(t.id()), now);
        }
        rollups().rollUp(new TenantId(t.id()), NOW);
        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(120)), NOW));
        assertEquals(Tier.DAILY, r.resolution().served());
        assertEquals(86400, r.gapThresholdSeconds() / 2);
        assertEquals(at(20, "00:00:00"), r.coverage().provisionalFrom(), "the days the recomputed hours touch are recomputed whole");
        assertEquals(144, pointAt(r, 0, at(18, "00:00:00")).sampleCount(), "a whole day of 10-minute samples, from the day rollup");
        SeriesView.Point today = pointAt(r, 0, at(20, "00:00:00"));
        assertEquals(88, today.sampleCount(), "00:00 to 14:30 every 10 minutes, from the raw tail");
        assertEquals(8.0, today.value(), 1e-9);
    }

    @Test
    void aRangeOlderThanRetentionIsTruncatedAndSaysSo() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 600);
        gauge(t, s, at(18, "00:00:00"), NOW, 600, 1.0);
        Instant from = NOW.minus(Duration.ofDays(300));
        RangeResult r = service.range(t.id(), query("cache.size", List.of(i), from, NOW, Resolution.RAW, SeriesBy.INSTANCE, Across.NONE));
        assertTrue(r.coverage().truncatedByRetention());
        assertEquals(from, r.coverage().requestedFrom());
        assertEquals(NOW.minus(Duration.ofDays(183)), r.coverage().servedFrom());
    }

    // ---- counters ------------------------------------------------------------------------------------------------

    @Test
    void counterRateUsesIncrementsNeverTheRawClimb() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.hit_count", "counter", 30);
        counter(t, s, at(20, "14:00:00"), at(20, "14:30:00"), 30, 15.0);    // 0.5 per second
        RangeResult r = service.range(t.id(), auto("cache.hit_count", i, at(20, "14:00:00"), NOW));
        assertEquals(RangeResult.Transform.RATE, r.render().transform());
        assertEquals(60, r.render().rateSeconds());
        assertTrue(points(r, 0).stream().allMatch(pt -> Math.abs(pt.value() - 30.0) < 1e-6),
                "0.5/s rendered per minute is 30, nowhere near the raw climb of 1000 and up: " + points(r, 0));
    }

    @Test
    void aCounterRateUsesTheActualTimestampDeltasNotTheNominalInterval() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.hit_count", "counter", 30);
        // A 30 s source with jitter: spacings of 27, 33, 30 and 29 s, with increments of exactly the same amounts, so the
        // true rate is 1.0 per second at every point. A nominal-30 s rate would be 0.9, 1.1, 1.0 and 0.967.
        raw(t, s, at(20, "14:00:01"), 1000, null);
        raw(t, s, at(20, "14:00:28"), 1027, 27.0);
        raw(t, s, at(20, "14:01:01"), 1060, 33.0);
        raw(t, s, at(20, "14:01:31"), 1090, 30.0);
        raw(t, s, at(20, "14:02:00"), 1119, 29.0);
        RangeResult r = service.range(t.id(), auto("cache.hit_count", i, at(20, "14:00:00"), at(20, "14:04:00")));
        assertEquals(30, r.resolution().bucketSeconds());
        assertEquals(4, points(r, 0).size());
        for (SeriesView.Point point : points(r, 0)) {
            assertEquals(60.0, point.value(), 1e-6, "1.0 per second per 60 s at " + point.ts());
        }
        assertEquals(2, pointAt(r, 0, at(20, "14:00:00")).sampleCount());
    }

    @Test
    void anHourlyCounterRateIsDeltaOverCoveredSeconds() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.hit_count", "counter", 600);
        // 10:00 first sample; 10:10 and 10:20 and 10:50 carry 20 each over 600 s; 10:40 follows a 20-minute gap, no delta.
        raw(t, s, at(19, "10:00:00"), 1000, null);
        raw(t, s, at(19, "10:10:00"), 1020, 20.0);
        raw(t, s, at(19, "10:20:00"), 1040, 20.0);
        raw(t, s, at(19, "10:40:00"), 1100, null);
        raw(t, s, at(19, "10:50:00"), 1120, 20.0);
        rollups().rollUp(new TenantId(t.id()), at(19, "12:30:00"));
        RangeResult r = service.range(t.id(), auto("cache.hit_count", i, NOW.minus(Duration.ofDays(30)), NOW));
        assertEquals(Tier.HOURLY, r.resolution().served());
        // delta 60 over 1800 covered seconds = 1/30 per second = 2 per minute; over the whole hour it would be 1.
        assertEquals(2.0, pointAt(r, 0, at(19, "10:00:00")).value(), 1e-9);
    }

    // ---- placements and instances (M-Q20) -------------------------------------------------------------------------

    @Test
    void overlappingGaugePlacementsAreSeparateLinesAndCountersSum() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        UUID p1 = placement(t, i);
        UUID p2 = placement(t, i);
        long g1 = series(t, i, p1, "cache.size", "gauge", 30);
        long g2 = series(t, i, p2, "cache.size", "gauge", 30);
        gauge(t, g1, at(20, "14:00:00"), at(20, "14:30:00"), 30, 400.0);
        gauge(t, g2, at(20, "14:00:00"), at(20, "14:30:00"), 30, 600.0);
        RangeResult gauges = service.range(t.id(), auto("cache.size", i, at(20, "14:00:00"), NOW));
        assertEquals(2, gauges.series().size(), "no cross-placement gauge aggregation: one line per placement");
        assertEquals(Set.of(400.0, 600.0), Set.of(points(gauges, 0).get(0).value(), points(gauges, 1).get(0).value()));
        assertEquals(Set.of(p1, p2), Set.of(gauges.series().get(0).ref().placementId(), gauges.series().get(1).ref().placementId()));

        long c1 = series(t, i, p1, "cache.hit_count", "counter", 30);
        long c2 = series(t, i, p2, "cache.hit_count", "counter", 30);
        counter(t, c1, at(20, "14:00:00"), at(20, "14:30:00"), 30, 30.0);    // 1/s
        counter(t, c2, at(20, "14:00:00"), at(20, "14:30:00"), 30, 15.0);    // 0.5/s
        RangeResult counters = service.range(t.id(), auto("cache.hit_count", i, at(20, "14:00:00"), NOW));
        assertEquals(1, counters.series().size(), "counters sum across placements: one line");
        assertEquals(90.0, points(counters, 0).get(3).value(), 1e-6, "1.5 per second, per minute");
        assertEquals(2, counters.series().get(0).replicasTotal());
    }

    @Test
    void aRetargetedInstanceIsOneContinuousLine() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        Instant moved = at(19, "00:00:00");
        UUID before = placement(t, i, at(1, "00:00:00"));
        retire(t, before, moved);
        UUID after = placement(t, i, moved);
        long g1 = series(t, i, before, "cache.size", "gauge", 600);
        long g2 = series(t, i, after, "cache.size", "gauge", 600);
        gauge(t, g1, at(18, "12:00:00"), at(18, "23:50:00"), 600, 3.0);
        gauge(t, g2, at(19, "00:10:00"), at(19, "12:00:00"), 600, 7.0);
        RangeResult r = service.range(t.id(), query("cache.size", List.of(i), at(18, "12:00:00"), at(19, "12:00:01"),
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE));
        assertEquals(1, r.series().size(), "a re-target is one continuous instance line (M-Q20)");
        assertNull(r.series().get(0).ref().placementId());
        assertEquals(3.0, points(r, 0).get(0).value());
        assertEquals(7.0, points(r, 0).get(points(r, 0).size() - 1).value());
        assertEquals(1, r.series().get(0).replicasTotal(), "one live placement now");
    }

    @Test
    void aRetiredPlacementAndAnAbandonedInstanceAreNeverCollectedAndTheirHistoryStaysQueryable() {
        // S-K, the query half: a range over the retired placement's period returns its samples.
        Tenant t = new Tenant();
        UUID i = instance(t, "gone", "pending");
        UUID p = placement(t, i);
        long s = series(t, i, p, "cache.size", "gauge", 30);
        gauge(t, s, at(18, "10:00:00"), at(18, "10:30:00"), 30, 9.0);
        retire(t, p, at(18, "11:00:00"));
        jdbc.update("UPDATE sm_data.instance_state SET lifecycle = 'abandoned' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("t", t.id()).addValue("i", i));
        RangeResult r = service.range(t.id(), auto("cache.size", i, at(18, "10:00:00"), at(18, "11:00:00")));
        assertEquals(1, r.series().size());
        assertEquals(61, points(r, 0).stream().mapToLong(SeriesView.Point::sampleCount).sum());
        assertEquals(0, r.series().get(0).replicasTotal(), "no live placement any more");
        assertTrue(r.partial().unavailable().isEmpty());
    }

    @Test
    void gaugeAcrossInstancesIsSumOverCountNotAvgOfAvg() {
        Tenant t = new Tenant();
        UUID a = instance(t, "a");
        UUID b = instance(t, "b");
        long sa = series(t, a, placement(t, a), "cache.size", "gauge", 30);
        long sb = series(t, b, placement(t, b), "cache.size", "gauge", 10);
        raw(t, sa, at(20, "14:00:05"), 10.0, null);
        raw(t, sb, at(20, "14:00:02"), 1.0, null);
        raw(t, sb, at(20, "14:00:12"), 2.0, null);
        raw(t, sb, at(20, "14:00:22"), 3.0, null);
        RangeQuery q = query("cache.size", List.of(a, b), at(20, "14:00:00"), at(20, "14:01:00"), Resolution.AUTO,
                SeriesBy.INSTANCE, Across.AVG);
        RangeResult r = service.range(t.id(), q);
        assertEquals(1, r.series().size());
        assertNull(r.series().get(0).ref().instanceId(),
                "OR-112 (6), W4-06: an across-instances aggregate belongs to no instance, so it carries NO instance id (not the nil UUID)");
        assertNull(r.series().get(0).ref().placementId());
        assertEquals(4.0, points(r, 0).get(0).value(), 1e-9, "(10 + 1 + 2 + 3) / 4, where avg(avg) would be (10 + 2) / 2 = 6");
        assertEquals(4, points(r, 0).get(0).sampleCount());
        RangeResult sum = service.range(t.id(), new RangeQuery("cache.size", null, List.of(a, b), Map.of(), q.from(), q.to(),
                Resolution.AUTO, SeriesBy.INSTANCE, Across.SUM, List.of()));
        assertEquals(12.0, points(sum, 0).get(0).value(), 1e-9, "the sum of the instances' means");
    }

    // ---- gaps and caps ---------------------------------------------------------------------------------------------

    @Test
    void gapsAreDeclaredAndAbsentBucketsEmitNothing() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(20, "12:00:00"), at(20, "12:02:00"), 30, 1.0);
        gauge(t, s, at(20, "12:07:00"), at(20, "12:08:00"), 30, 1.0);
        RangeResult r = service.range(t.id(), auto("cache.size", i, at(20, "12:00:00"), at(20, "12:10:00")));
        assertEquals(60, r.gapThresholdSeconds(), "raw: 2 x the interval");
        assertEquals(8, points(r, 0).size());
        assertTrue(points(r, 0).stream().noneMatch(p -> p.ts().isAfter(at(20, "12:02:00")) && p.ts().isBefore(at(20, "12:07:00"))),
                "no tuple inside the gap");
        assertEquals(List.of(new SeriesView.Gap(at(20, "12:02:30"), at(20, "12:07:00"), "no-samples")), r.series().get(0).gaps());
    }

    /**
     * Fable MON-01. The collector re-arms a source from the dispatch tick that fired it, so a source declared at 30 s is
     * really polled every ~30.5 s and a closed hour holds ~118 of the 120 samples {@code expected_count} says. That shortfall
     * is not a gap: neither tier may draw a band for it. (The old rule, sample_count < expected_count, flagged every hour and
     * every day of every polled series.)
     */
    @Test
    void aCollectorPeriodLongerThanTheDeclaredIntervalIsNotAGapOnEitherRollupTier() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gaugeEvery(t, s, at(17, "00:00:00"), at(18, "23:59:59"), 30.5, 5.0);
        rollUpEveryHour(t, at(17, "01:00:00"));

        RangeResult hourly = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));
        assertEquals(Tier.HOURLY, hourly.resolution().served());
        SeriesView.Point hour = pointAt(hourly, 0, at(18, "10:00:00"));
        assertTrue(hour.sampleCount() < hour.expectedCount(), "the fixture really is short of the expected count: "
                + hour.sampleCount() + " of " + hour.expectedCount());
        assertEquals(List.of(), hourly.series().get(0).gaps(), "~118 of 120 samples at 30.5 s spacing is not a gap");

        RangeResult daily = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(120)), NOW));
        assertEquals(Tier.DAILY, daily.resolution().served());
        SeriesView.Point day = pointAt(daily, 0, at(18, "00:00:00"));
        assertTrue(day.sampleCount() < day.expectedCount());
        assertEquals(List.of(), daily.series().get(0).gaps(), "nor is the day's shortfall");

        // the stored facts behind the judgement
        Double hourGap = jdbc.queryForObject("SELECT max_gap_seconds FROM sm_metrics.metrics_rollup_1h WHERE tenant_id = :t"
                + " AND series_id = :s AND bucket = :b", new MapSqlParameterSource().addValue("t", t.id())
                .addValue("s", s).addValue("b", odt(at(18, "10:00:00"))), Double.class);
        assertEquals(30.5, hourGap, 1e-3);
        assertEquals(30, jdbc.queryForObject("SELECT interval_seconds FROM sm_metrics.metrics_rollup_1d WHERE tenant_id = :t"
                + " AND series_id = :s AND bucket = :b", new MapSqlParameterSource().addValue("t", t.id())
                .addValue("s", s).addValue("b", odt(at(18, "00:00:00"))), Integer.class));
    }

    @Test
    void anOutageInsideAnHourIsAPartialSamplesGapOnTheHourlyTier() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(18, "07:00:00"), at(18, "08:20:00"), 30, 5.0);
        gauge(t, s, at(18, "08:50:00"), at(18, "10:59:30"), 30, 5.0);   // 30 minutes of nothing inside the 08:00 hour
        rollUpEveryHour(t, at(18, "01:00:00"));

        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));

        assertEquals(List.of(new SeriesView.Gap(at(18, "08:00:00"), at(18, "09:00:00"), "partial-samples")),
                r.series().get(0).gaps(), "only the hour that holds the 30 minute spacing");
    }

    @Test
    void anOutageStraddlingTwoHoursIsSeenInTheHourItEndedIn() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(18, "11:00:00"), at(18, "12:50:00"), 30, 5.0);
        gauge(t, s, at(18, "13:10:00"), at(18, "14:59:30"), 30, 5.0);   // 12:50 to 13:10: neither hour holds the whole spacing
        rollUpEveryHour(t, at(18, "01:00:00"));

        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));

        assertEquals(List.of(new SeriesView.Gap(at(18, "13:00:00"), at(18, "14:00:00"), "partial-samples")),
                r.series().get(0).gaps());
    }

    @Test
    void aDayWithOneAbsentHourIsAGapOnTheDailyTier() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(16, "00:00:00"), at(16, "04:59:30"), 30, 5.0);
        gauge(t, s, at(16, "06:00:00"), at(17, "23:59:30"), 30, 5.0);   // 05:00-06:00 on day 16 has no samples
        rollUpEveryHour(t, at(16, "01:00:00"));

        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(120)), NOW));

        assertEquals(Tier.DAILY, r.resolution().served());
        assertEquals(List.of(new SeriesView.Gap(at(16, "00:00:00"), at(17, "00:00:00"), "partial-samples")),
                r.series().get(0).gaps(), "the absent hour added nothing to the day's expected count, but the spacing is real");
    }

    @Test
    void aGapIsJudgedByItsServedTiersOwnThreshold() {
        // Contiguous hourly buckets are NOT gaps: a threshold of 2 x the 600 s scrape interval would make every one a gap.
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 600);
        gauge(t, s, at(19, "08:00:00"), at(19, "11:50:00"), 600, 1.0);
        rollups().rollUp(new TenantId(t.id()), at(19, "12:30:00"));
        RangeResult r = service.range(t.id(), auto("cache.size", i, NOW.minus(Duration.ofDays(30)), NOW));
        assertEquals(Tier.HOURLY, r.resolution().served());
        assertEquals(7200, r.gapThresholdSeconds());
        assertTrue(points(r, 0).size() >= 3);
        assertEquals(List.of(), r.series().get(0).gaps());
    }

    @Test
    void theCapCountsRenderedSeries() {
        Tenant t = new Tenant();
        List<UUID> instances = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            UUID i = instance(t, "i" + n);
            UUID p1 = placement(t, i);
            UUID p2 = placement(t, i);
            long c1 = series(t, i, p1, "cache.hit_count", "counter", 30);
            long c2 = series(t, i, p2, "cache.hit_count", "counter", 30);
            counter(t, c1, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
            counter(t, c2, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
            instances.add(i);
        }
        Instant from = at(20, "14:00:00");
        // Counters sum across placements: six instances are six lines. Under REPLICA the same request is twelve.
        assertEquals(6, service.range(t.id(), query("cache.hit_count", instances, from, NOW, Resolution.AUTO, SeriesBy.INSTANCE,
                Across.NONE)).series().size());
        MetricsQueryRefusedException ex = refused(t, query("cache.hit_count", instances, from, NOW, Resolution.AUTO,
                SeriesBy.REPLICA, Across.NONE));
        assertEquals(QueryRefusal.SERIES_CAP_EXCEEDED, ex.refusal());
        assertEquals(12, ex.projected());
        assertEquals(10, ex.cap());
        assertEquals(SeriesBy.REPLICA, ex.seriesBy());
        // One aggregate line however many instances.
        assertEquals(1, service.range(t.id(), query("cache.hit_count", instances, from, NOW, Resolution.AUTO, SeriesBy.REPLICA,
                Across.SUM)).series().size());
    }

    @Test
    void aGaugeOnTwoOverlappingPlacementsCountsTwoTowardTheCap() {
        // Six instances, one of which has two overlapping live placements for a gauge: seven lines, not six.
        Tenant t = new Tenant();
        List<UUID> instances = new ArrayList<>();
        for (int n = 0; n < 6; n++) {
            UUID i = instance(t, "i" + n);
            long g = series(t, i, placement(t, i), "cache.size", "gauge", 30);
            gauge(t, g, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
            if (n == 0) {
                long second = series(t, i, placement(t, i), "cache.size", "gauge", 30);
                gauge(t, second, at(20, "14:00:00"), at(20, "14:10:00"), 30, 2.0);
            }
            instances.add(i);
        }
        MetricsQueryServiceImpl six = serviceOver(new JdbcMetricsQueryRepository(jdbc, transactions),
                props(6, Duration.ofSeconds(30), Duration.ofSeconds(10)), new MetricsDbBudget(4));
        MetricsQueryRefusedException ex = assertThrows(MetricsQueryRefusedException.class, () -> six.range(t.id(),
                query("cache.size", instances, at(20, "14:00:00"), NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE)));
        assertEquals(QueryRefusal.SERIES_CAP_EXCEEDED, ex.refusal());
        assertEquals(7, ex.projected());
        assertEquals(6, ex.cap());
    }

    @Test
    void eachRefusalIsProvoked() {
        Tenant t = new Tenant();
        UUID a = instance(t, "a");
        UUID b = instance(t, "b");
        long g = series(t, a, placement(t, a), "cache.size", "gauge", 30);
        series(t, b, placement(t, b), "cache.size", "gauge", 30);
        gauge(t, g, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        declare(t, definition("cache.latency", "cache-engine", "histogram"));
        Instant from = at(20, "14:00:00");

        assertEquals(QueryRefusal.GROUP_BY_REQUIRES_AGGREGATION, refused(t, new RangeQuery("cache.size", null, List.of(a), Map.of(),
                from, NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE, List.of("region"))).refusal());
        assertEquals(QueryRefusal.STAT_REQUIRES_AGGREGATION, assertThrows(MetricsQueryRefusedException.class, () -> service.instant(
                t.id(), query("cache.size", List.of(a, b), from, NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE))).refusal());
        assertEquals(QueryRefusal.AGGREGATION_NOT_APPLICABLE, refused(t, auto("cache.latency", a, from, NOW)).refusal(),
                "a histogram is not plottable in v1");
        // W3-07: console.yaml says METRIC_UNTYPED has no v1 trigger (undeclared names are dropped and counted, M-Q6): a metric
        // nothing declares and nothing collected is an empty, degraded chart, never an error
        RangeResult nothing = service.range(t.id(), auto("cache.nothing", a, from, NOW));
        assertEquals(List.of(), nothing.series());
        assertTrue(nothing.partial().degraded());
        assertEquals(List.of(new RangeResult.Unavailable(a, "a", RangeResult.Reason.NOT_COLLECTING)), nothing.partial().unavailable());
        assertEquals("cache.nothing", nothing.metric().name());
        // The instant query over ONE instance needs no aggregation.
        assertEquals(1, service.instant(t.id(), auto("cache.size", a, from, NOW)).series().size());
    }

    @Test
    void anOverlappingGaugeCannotBeAggregatedAcrossInstances() {
        Tenant t = new Tenant();
        UUID a = instance(t, "a");
        UUID b = instance(t, "b");
        for (int n = 0; n < 2; n++) {
            long g = series(t, a, placement(t, a), "cache.size", "gauge", 30);
            gauge(t, g, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0 + n);
        }
        long gb = series(t, b, placement(t, b), "cache.size", "gauge", 30);
        gauge(t, gb, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        assertEquals(QueryRefusal.AGGREGATION_NOT_APPLICABLE, refused(t, query("cache.size", List.of(a, b), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.AVG)).refusal(), "M-Q20: no cross-placement gauge aggregation");
    }

    @Test
    void malformedRequestsAreRejectedBeforeTheyReachTheDatabase() {
        Tenant t = new Tenant();
        UUID a = instance(t, "a");
        assertThrows(IllegalArgumentException.class, () -> service.range(t.id(), new RangeQuery("cache.size", null, List.of(a),
                Map.of("region", "eu"), at(20, "14:00:00"), NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE, List.of())));
        assertThrows(IllegalArgumentException.class, () -> service.range(t.id(), auto("cache.size", a, NOW, NOW)));
        assertThrows(IllegalArgumentException.class, () -> service.range(t.id(), query("cache.size", List.of(), at(20, "14:00:00"),
                NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE)));
        assertThrows(IllegalArgumentException.class, () -> service.range(t.id(), new RangeQuery("cache.size", null, List.of(a),
                Map.of(), at(20, "14:00:00"), NOW, Resolution.AUTO, SeriesBy.INSTANCE, Across.SUM, List.of("region"))),
                "with the label allowlist empty, a groupBy key is a 400");
    }

    // ---- I5, reach and partial -------------------------------------------------------------------------------------

    @Test
    void anotherOrganizationsInstanceIdFindsNothingAndIsNeverNamed() {
        Tenant mine = new Tenant();
        Tenant theirs = new Tenant();
        UUID mineInstance = instance(mine, "mine");
        UUID theirInstance = instance(theirs, "theirs");
        long theirSeries = series(theirs, theirInstance, placement(theirs, theirInstance), "cache.size", "gauge", 30);
        gauge(theirs, theirSeries, at(20, "14:00:00"), at(20, "14:10:00"), 30, 99.0);
        long mineSeries = series(mine, mineInstance, placement(mine, mineInstance), "cache.size", "gauge", 30);
        gauge(mine, mineSeries, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        RangeResult r = service.range(mine.id(), query("cache.size", List.of(mineInstance, theirInstance), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE));
        assertEquals(1, r.series().size());
        assertEquals(mineInstance, r.series().get(0).ref().instanceId());
        assertTrue(r.partial().unavailable().isEmpty(), "the foreign id is not named, exactly as if it did not exist");
        assertEquals(List.of(), service.observed(mine.id(), null, Set.of(theirInstance)));
        assertEquals(List.of(), service.activeMetrics(mine.id(), theirInstance));
    }

    @Test
    void anInstanceWithNoDataIsNotCollectingAndOneWithFailuresIsCollectorDegraded() {
        Tenant t = new Tenant();
        UUID quiet = instance(t, "quiet");
        UUID failing = instance(t, "failing");
        UUID fine = instance(t, "fine");
        placement(t, quiet);
        UUID failingPlacement = placement(t, failing);
        long s = series(t, fine, placement(t, fine), "cache.size", "gauge", 30);
        gauge(t, s, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        new JdbcIngestCounterRepository(jdbc).add(t.id(), failingPlacement, CollectorReason.SCRAPE_FAILED, MONTH.atDay(20), 5);
        declare(t, definition("cache.size", "cache-engine", "gauge"));
        RangeResult r = service.range(t.id(), query("cache.size", List.of(quiet, failing, fine), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE));
        assertEquals(1, r.series().size());
        assertTrue(r.partial().degraded());
        assertEquals(Map.of("quiet", RangeResult.Reason.NOT_COLLECTING, "failing", RangeResult.Reason.COLLECTOR_DEGRADED),
                r.partial().unavailable().stream().collect(java.util.stream.Collectors.toMap(RangeResult.Unavailable::label,
                        RangeResult.Unavailable::reason)));
    }

    @Test
    void theEntityTypeKeepsOnlyInstancesWhoseComponentDeclaresTheMetric() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        declare(t, definition("cache.size", "cache-engine", "gauge"));
        RangeQuery other = new RangeQuery("cache.size", "file-mesh-adapter", List.of(i), Map.of(), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE, List.of());
        RangeResult notDeclared = service.range(t.id(), other);   // W3-07: no METRIC_UNTYPED in v1
        assertEquals(List.of(), notDeclared.series(), "this component does not declare the metric");
        assertEquals(List.of(new RangeResult.Unavailable(i, "alpha", RangeResult.Reason.NOT_COLLECTING)),
                notDeclared.partial().unavailable());
        RangeQuery own = new RangeQuery("cache.size", "cache-engine", List.of(i), Map.of(), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE, List.of());
        assertEquals(1, service.range(t.id(), own).series().size());
    }

    @Test
    void anInstantQueryIsTheLatestPointOfEachSeries() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        raw(t, s, at(20, "14:00:10"), 1.0, null);
        raw(t, s, at(20, "14:05:10"), 2.0, null);
        raw(t, s, at(20, "14:09:10"), 3.0, null);
        RangeResult r = service.instant(t.id(), auto("cache.size", i, at(20, "14:00:00"), at(20, "14:10:00")));
        assertEquals(1, r.series().size());
        assertEquals(1, points(r, 0).size());
        assertEquals(3.0, points(r, 0).get(0).value());
        assertEquals(List.of(), r.series().get(0).gaps());
    }

    // ---- the budget, the timeout and the tenant bind -----------------------------------------------------------------

    @Test
    @Timeout(60)
    void aFifthConcurrentQueryWaitsForTheBudget() throws Exception {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        MetricsDbBudget budget = new MetricsDbBudget(4);
        MetricsQueryServiceImpl own = serviceOver(new JdbcMetricsQueryRepository(jdbc, transactions),
                props(10, Duration.ofSeconds(30), Duration.ofSeconds(20)), budget);
        List<MetricsDbBudget.Permit> held = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            held.add(budget.acquire(Duration.ofSeconds(1)));
        }
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<RangeResult> fifth = pool.submit(() -> own.range(t.id(), auto("cache.size", i, at(20, "14:00:00"), NOW)));
            assertThrows(TimeoutException.class, () -> fifth.get(600, TimeUnit.MILLISECONDS), "the fifth waits");
            held.get(0).close();
            assertEquals(1, fifth.get(20, TimeUnit.SECONDS).series().size(), "and runs once a permit is free");
            assertEquals(1, budget.available(), "the fifth took one and gave it back: three are still held");
        } finally {
            held.forEach(MetricsDbBudget.Permit::close);
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void aSpentBudgetIsBusyNotAnError() {
        MetricsDbBudget budget = new MetricsDbBudget(1);
        MetricsQueryServiceImpl own = serviceOver(new JdbcMetricsQueryRepository(jdbc, transactions),
                props(10, Duration.ofSeconds(30), Duration.ofMillis(200)), budget);
        try (MetricsDbBudget.Permit ignored = budget.acquire(Duration.ofSeconds(1))) {
            Tenant t = new Tenant();
            UUID i = instance(t, "alpha");
            MetricsQueryUnavailableException ex = assertThrows(MetricsQueryUnavailableException.class,
                    () -> own.range(t.id(), auto("cache.size", i, at(20, "14:00:00"), NOW)));
            assertEquals(MetricsQueryUnavailableException.Reason.BUSY, ex.reason());
        }
    }

    @Test
    @Timeout(60)
    void aSlowQueryHitsTheStatementTimeout() throws Exception {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        long s = series(t, i, placement(t, i), "cache.size", "gauge", 30);
        gauge(t, s, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        MetricsQueryServiceImpl impatient = serviceOver(new JdbcMetricsQueryRepository(jdbc, transactions),
                props(10, Duration.ofMillis(500), Duration.ofSeconds(10)), new MetricsDbBudget(4));
        // A held ACCESS EXCLUSIVE lock makes the series read wait, and waiting counts against statement_timeout.
        try (Connection locker = db.superuser().getConnection(); Statement st = locker.createStatement()) {
            locker.setAutoCommit(false);
            st.execute("LOCK TABLE sm_metrics.series IN ACCESS EXCLUSIVE MODE");
            // A safety valve, so a missing timeout FAILS the assertion below instead of hanging the build: the lock is let go
            // after 12 s, far past the 500 ms the service is told to wait.
            ScheduledThreadPoolExecutor valve = new ScheduledThreadPoolExecutor(1);
            valve.schedule(() -> {
                try {
                    locker.rollback();
                } catch (java.sql.SQLException ignored) {
                    // the test is over
                }
            }, 12, TimeUnit.SECONDS);
            long started = System.nanoTime();
            MetricsQueryUnavailableException ex = assertThrows(MetricsQueryUnavailableException.class,
                    () -> impatient.range(t.id(), auto("cache.size", i, at(20, "14:00:00"), NOW)));
            assertEquals(MetricsQueryUnavailableException.Reason.TIMEOUT, ex.reason());
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(millis >= 400 && millis < 10_000, "cut at about the timeout, took " + millis + " ms");
            valve.shutdownNow();
            locker.rollback();
        }
        assertEquals(1, impatient.range(t.id(), auto("cache.size", i, at(20, "14:00:00"), NOW)).series().size(),
                "and the next query is not poisoned by the cancelled one");
    }

    @Test
    void everyStatementBindsTheTenant() {
        Tenant t = new Tenant();
        UUID a = instance(t, "a");
        UUID b = instance(t, "b");
        UUID pa = placement(t, a);
        long ga = series(t, a, pa, "cache.size", "gauge", 30);
        long cb = series(t, b, placement(t, b), "cache.hit_count", "counter", 30);
        gauge(t, ga, at(20, "14:00:00"), at(20, "14:10:00"), 30, 1.0);
        counter(t, cb, at(20, "14:00:00"), at(20, "14:10:00"), 30, 3.0);
        declare(t, definition("cache.size", "cache-engine", "gauge"), definition("cache.hit_count", "cache-engine", "counter"));
        new JdbcIngestCounterRepository(jdbc).add(t.id(), pa, CollectorReason.PARSE_FAILED, MONTH.atDay(20), 2);

        List<SqlRecorder.Executed> log = new ArrayList<>();
        DataSource recorded = SqlRecorder.recording(log).open(db.url(), "sm_dp_runtime", SmDataPostgres.RUNTIME_PASSWORD);
        NamedParameterJdbcTemplate recordedJdbc = new NamedParameterJdbcTemplate(recorded);
        MetricsQueryServiceImpl watched = serviceOver(new JdbcMetricsQueryRepository(recordedJdbc,
                new DataSourceTransactionManager(recorded)), QueryProperties.defaults(), new MetricsDbBudget(4));
        watched.range(t.id(), new RangeQuery("cache.size", "cache-engine", List.of(a, b), Map.of(), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE, List.of()));
        watched.range(t.id(), query("cache.hit_count", List.of(b), at(20, "14:00:00"), NOW, Resolution.AUTO, SeriesBy.INSTANCE,
                Across.SUM));
        watched.range(t.id(), query("cache.hit_count", List.of(b), NOW.minus(Duration.ofDays(30)), NOW, Resolution.AUTO,
                SeriesBy.INSTANCE, Across.NONE));
        watched.range(t.id(), query("cache.hit_count", List.of(b), NOW.minus(Duration.ofDays(120)), NOW, Resolution.AUTO,
                SeriesBy.INSTANCE, Across.NONE));
        // an instance that does not exist: an empty, degraded result since W3-07 (it was METRIC_UNTYPED), the same statements
        assertEquals(List.of(), watched.range(t.id(), query("cache.size", List.of(UUID.randomUUID()), at(20, "14:00:00"), NOW,
                Resolution.AUTO, SeriesBy.INSTANCE, Across.NONE)).series());
        watched.instant(t.id(), auto("cache.size", a, at(20, "14:00:00"), NOW));
        watched.observed(t.id(), "cache-engine", Set.of(a, b));
        watched.collectorHealth(t.id());

        Pattern tableRef = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+sm_(?:data|metrics)\\.(?!partition_freeze_log)\\w+");
        Pattern bind = Pattern.compile("(?i)\\btenant_id\\s*=\\s*");
        Pattern literalUuid = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-");
        int checked = 0;
        for (SqlRecorder.Executed executed : log) {
            String sql = executed.sql();
            if (sql.startsWith("SELECT set_config") || sql.startsWith("BEGIN") || sql.startsWith("COMMIT")) {
                continue;
            }
            int tables = count(tableRef, sql);
            int binds = count(bind, sql);
            assertTrue(binds >= tables, "every tenant-bearing table in a statement is tenant-scoped (" + tables + " tables, "
                    + binds + " tenant_id comparisons): " + sql);
            assertFalse(literalUuid.matcher(sql).find(), "no value is interpolated: " + sql);
            if (tables > 0) {
                assertTrue(sql.contains("?"), "values are bound: " + sql);
                checked++;
            }
        }
        assertTrue(checked >= 12, "the statements of every read ran and were checked: " + checked);
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // ---- the catalog's half, the active list and the collector's health ---------------------------------------------

    @Test
    void observedReportsWhatCollectionProducedAndThePlacementsFailures() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        UUID p = placement(t, i);
        long s = series(t, i, p, "cache.size", "gauge", 30);
        raw(t, s, at(20, "14:00:00"), 1.0, null);
        raw(t, s, at(20, "14:09:00"), 2.0, null);
        UUID other = instance(t, "beta");
        series(t, other, placement(t, other), "cache.hit_count", "counter", 30);
        JdbcIngestCounterRepository counters = new JdbcIngestCounterRepository(jdbc);
        counters.add(t.id(), p, CollectorReason.SCRAPE_FAILED, MONTH.atDay(20), 3);
        counters.add(t.id(), p, CollectorReason.UNDECLARED_DROPPED, MONTH.atDay(20), 40);
        counters.add(t.id(), p, CollectorReason.PARSE_FAILED, MONTH.atDay(1), 9);
        List<ObservedMetric> observed = service.observed(t.id(), null, Set.of(i, other));
        assertEquals(List.of("cache.hit_count", "cache.size"), observed.stream().map(ObservedMetric::name).toList());
        ObservedMetric size = observed.get(1);
        assertEquals(RangeResult.MetricType.GAUGE, size.metricType());
        assertEquals(at(20, "14:09:00"), size.lastSeen());
        assertEquals(List.of(new CollectorHealthView.ReasonCount(CollectorReason.SCRAPE_FAILED, 3)), size.reasons(),
                "the last 24 h of the placements serving this metric, never undeclared_dropped");
        assertNull(observed.get(0).lastSeen());
    }

    @Test
    void activeMetricsIsTheRestartObservedGenerationsList() {
        Tenant t = new Tenant();
        UUID i = instance(t, "alpha");
        definitions.insertActiveMetrics(t.id(), i, 0, List.of("cache.size"), Instant.now());
        definitions.insertActiveMetrics(t.id(), i, 1, List.of("cache.size", "cache.hit_count"), Instant.now());
        definitions.insertActiveMetrics(t.id(), i, 2, List.of("cache.hit_count"), Instant.now());
        assertEquals(List.of("cache.size"), service.activeMetrics(t.id(), i), "no generation reached restart-observed: generation 0");
        OBSERVED_GENERATION.put(i, 1L);
        assertEquals(List.of("cache.size", "cache.hit_count"), service.activeMetrics(t.id(), i),
                "generation 2 is staged and not restarted: generation 1's list still rules");
        assertEquals(List.of(), service.activeMetrics(t.id(), UUID.randomUUID()));
    }

    @Test
    void unfrozenClosedPartitionsCountsClosedMonthsWithNoFreezeRecord() {
        MetricsDb own = MetricsDb.fresh("query_service_unfrozen");
        NamedParameterJdbcTemplate ownJdbc = new NamedParameterJdbcTemplate(own.runtime());
        MetricsQueryServiceImpl onOwn = serviceOver(new JdbcMetricsQueryRepository(ownJdbc,
                new DataSourceTransactionManager(own.runtime())), QueryProperties.defaults(), new MetricsDbBudget(4));
        assertEquals(0, onOwn.collectorHealth(UUID.randomUUID()).unfrozenClosedPartitions(), "the open month and the future never count");
        own.attachPastMonth("2020_01", "2020-01-01", "2020-02-01");
        own.attachPastMonth("2020_02", "2020-02-01", "2020-03-01");
        assertEquals(2, onOwn.collectorHealth(UUID.randomUUID()).unfrozenClosedPartitions());
        own.superuserJdbc().update("INSERT INTO sm_metrics.partition_freeze_log (partition_name, frozen_at) VALUES"
                + " ('metrics_raw_2020_01', now())", new MapSqlParameterSource());
        assertEquals(1, onOwn.collectorHealth(UUID.randomUUID()).unfrozenClosedPartitions(), "two closed months, one frozen");
    }

    @Test
    void collectorReasonsAreTheCallersOrganizationOnly() {
        Tenant a = new Tenant();
        Tenant b = new Tenant();
        UUID ia = instance(a, "a");
        UUID ib = instance(b, "b");
        UUID pa = placement(a, ia);
        UUID pb = placement(b, ib);
        JdbcIngestCounterRepository counters = new JdbcIngestCounterRepository(jdbc);
        counters.add(a.id(), pa, CollectorReason.SCRAPE_FAILED, MONTH.atDay(20), 3);
        counters.add(a.id(), pa, CollectorReason.BUFFER_DROPPED, MONTH.atDay(19), 1);
        counters.add(b.id(), pb, CollectorReason.SCRAPE_FAILED, MONTH.atDay(20), 7);
        counters.add(b.id(), pb, CollectorReason.CAP_REJECTED, MONTH.atDay(20), 5);
        counters.add(a.id(), pa, CollectorReason.PARSE_FAILED, MONTH.atDay(10), 100);   // older than the last 24 h
        CollectorHealthView forA = service.collectorHealth(a.id());
        assertEquals(List.of(new CollectorHealthView.ReasonCount(CollectorReason.SCRAPE_FAILED, 3),
                new CollectorHealthView.ReasonCount(CollectorReason.BUFFER_DROPPED, 1)), forA.reasons(),
                "tenant B's counters never appear in A's view, and a count older than a day does not either");
        CollectorHealthView forB = service.collectorHealth(b.id());
        assertEquals(12, forB.reasons().stream().mapToLong(CollectorHealthView.ReasonCount::last24h).sum());
        // install-level fields
        assertNotNull(forA.runwayEnd(), "the keeper ran at start");
        assertEquals(CollectorHealthView.State.LIVE, forA.state());
        assertEquals(com.solace.sm.dp.api.metrics.health.DiskAlarmView.State.UNCONFIGURED, forA.disk().state());
    }

    /**
     * W3-03 (OR-107 OQ-20 (b)): a drop counted at the BROKER CONNECTION (no single placement to charge) is surfaced with
     * the collector reasons, under the caller's organization only, summed with the per-placement counter of the same reason.
     */
    @Test
    void connectionLevelDropsAreSurfacedWithTheCollectorReasonsForTheCallersOrganizationOnly() {
        Tenant a = new Tenant();
        Tenant b = new Tenant();
        UUID pa = placement(a, instance(a, "a"));
        JdbcBrokerCounterRepository broker = new JdbcBrokerCounterRepository(jdbc);
        UUID connectionA = UUID.randomUUID();
        UUID connectionB = UUID.randomUUID();
        broker.add(a.id(), connectionA, CollectorReason.UNDECLARED_DROPPED, MONTH.atDay(20), 7);
        broker.add(a.id(), UUID.randomUUID(), CollectorReason.UNDECLARED_DROPPED, MONTH.atDay(20), 1);   // a second connection
        broker.add(a.id(), connectionA, CollectorReason.AMBIGUOUS_PLACEMENT, MONTH.atDay(20), 3);
        broker.add(a.id(), connectionA, CollectorReason.PARSE_FAILED, MONTH.atDay(10), 50);              // older than a day
        broker.add(b.id(), connectionB, CollectorReason.UNDECLARED_DROPPED, MONTH.atDay(20), 100);
        new JdbcIngestCounterRepository(jdbc).add(a.id(), pa, CollectorReason.UNDECLARED_DROPPED, MONTH.atDay(20), 2);

        CollectorHealthView forA = service.collectorHealth(a.id());
        assertEquals(List.of(new CollectorHealthView.ReasonCount(CollectorReason.UNDECLARED_DROPPED, 10),
                new CollectorHealthView.ReasonCount(CollectorReason.AMBIGUOUS_PLACEMENT, 3)), forA.reasons(),
                "7 + 1 at two connections and 2 at a placement; never B's 100, and nothing older than a day");
        assertEquals(List.of(new CollectorHealthView.ReasonCount(CollectorReason.UNDECLARED_DROPPED, 100)),
                service.collectorHealth(b.id()).reasons());
    }

    @Test
    void aShortRunwayMakesTheCollectorDegraded() {
        PartitionRunway never = new PartitionRunway(() -> {
            throw new IllegalStateException("no runway");
        }, new ScheduledThreadPoolExecutor(1), ROLLUP, CLOCK, () -> { });
        never.keep();
        MetricsQueryServiceImpl degraded = new MetricsQueryServiceImpl(new JdbcMetricsQueryRepository(jdbc, transactions),
                new MetricsDbBudget(4), QueryProperties.defaults(), ROLLUP, CLOCK, (tenant, instance) -> OptionalLong.empty(),
                new MetricDefinitionCollectionRows(definitions), never, disk);
        CollectorHealthView view = degraded.collectorHealth(UUID.randomUUID());
        assertEquals(CollectorHealthView.State.DEGRADED, view.state(), "no runway confirmed");
        assertNull(view.runwayEnd());
    }
}
