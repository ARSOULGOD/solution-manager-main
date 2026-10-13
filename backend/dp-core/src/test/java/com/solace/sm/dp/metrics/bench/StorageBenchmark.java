package com.solace.sm.dp.metrics.bench;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.JdbcIngestContextRepository;
import com.solace.sm.dp.apply.persistence.JdbcIngestCounterRepository;
import com.solace.sm.dp.apply.persistence.JdbcMetricDefinitionRepository;
import com.solace.sm.dp.apply.persistence.JdbcRawSampleRepository;
import com.solace.sm.dp.apply.persistence.JdbcRestartMarkerRepository;
import com.solace.sm.dp.apply.persistence.JdbcRollupRepository;
import com.solace.sm.dp.apply.persistence.JdbcSeriesRepository;
import com.solace.sm.dp.apply.persistence.SmDataMigrationMain;
import com.solace.sm.dp.metrics.ingest.IngestProperties;
import com.solace.sm.dp.metrics.ingest.IngestService;
import com.solace.sm.dp.metrics.rollup.RollupProperties;
import com.solace.sm.dp.metrics.rollup.RollupRows;
import com.solace.sm.dp.metrics.rollup.RollupService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 011 P11-8.1: the data-plane half of the storage gate (Steps 0b, 1, 2, 2b, 5 and 6a). It calls data-plane services
 * and SQL and nothing else; the as-an-Operator query timings are {@code MetricsGateBenchmarkIT}'s (launcher).
 *
 * <p>Every figure it reports is a {@code MEASURED} line, or an {@code EXTRAPOLATED} one that says what it was
 * extrapolated from ({@link Evidence}); nothing is presented as a measurement that is arithmetic. Each step stores its
 * figures in {@link BenchResults}, so a later step may run in another JVM after the integrator has read an earlier one.
 *
 * <p><b>The load is server-side and ordered the way ingest writes:</b> one hour per statement, time-major (every
 * series' point at tick 0, then at tick 1, and so on), so the primary key's index grows the way it does in production
 * rather than in the friendlier series-major order. A hour already loaded is skipped, so a stopped load resumes.
 *
 * <p>Two credentials: the RUNTIME role does the work a production process does (seed, load, ingest, roll up, read); the
 * ADMIN role, which the operator who started the database holds, does only what the runtime role may not: create a past
 * month's partition, {@code VACUUM}, and read the WAL position.
 */
public final class StorageBenchmark implements AutoCloseable {

    static final String VERSION = "2.4";
    static final String SOLUTION = "cache";
    static final String STEP_PREPARE = "prepare";

    /** The metric mix of S-A: about two gauges and three counters in five (the Cache declares counters and gauges). */
    record MetricShape(String name, String type) {
    }

    private final BenchSettings s;
    private final BenchResults results;
    private final Evidence evidence;
    private HikariDataSource runtimeDs;
    private DataSource adminDs;
    private NamedParameterJdbcTemplate runtime;
    private NamedParameterJdbcTemplate admin;

    public StorageBenchmark(BenchSettings settings, PrintStream out) {
        this.s = settings;
        this.results = new BenchResults(settings.outDir());
        this.evidence = new Evidence(results, out);
        for (java.util.function.Supplier<String> password : List.<java.util.function.Supplier<String>>of(s::runtimePassword,
                s::adminPassword, s::migratorPassword)) {
            try {
                evidence.redact(password.get());
            } catch (IllegalStateException notSet) {
                // not needed by every step; the step that needs it names the variable
            }
        }
    }

    public Evidence evidence() {
        return evidence;
    }

    public BenchResults results() {
        return results;
    }

    private NamedParameterJdbcTemplate runtime() {
        if (runtime == null) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(s.url());
            config.setUsername(s.runtimeUser());
            config.setPassword(s.runtimePassword());
            config.setMaximumPoolSize(4);
            config.setPoolName("bench-runtime");
            runtimeDs = new HikariDataSource(config);
            runtime = new NamedParameterJdbcTemplate(runtimeDs);
        }
        return runtime;
    }

    private NamedParameterJdbcTemplate admin() {
        if (admin == null) {
            adminDs = new DriverManagerDataSource(s.url(), s.adminUser(), s.adminPassword());
            admin = new NamedParameterJdbcTemplate(adminDs);
        }
        return admin;
    }

    @Override
    public void close() {
        if (runtimeDs != null) {
            runtimeDs.close();
        }
    }

    // ---- prepare ---------------------------------------------------------------------------------------------------

    /**
     * Phase 0 (once per cluster: it creates roles) and the migration step, against a database the operator created. The
     * runtime and migrator passwords come from the environment and are set here, so nothing was typed into SQL by hand.
     */
    public void prepare() {
        boolean roles = Boolean.TRUE.equals(admin().getJdbcTemplate().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'sm_dp_owner')", Boolean.class));
        if (roles) {
            evidence.note(STEP_PREPARE, "Phase 0 already ran in this cluster (role sm_dp_owner exists): not repeated");
        } else {
            try (java.sql.Connection c = adminDs.getConnection()) {
                new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/sm_data_phase0.sql")).populate(c);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Phase 0 failed: " + e.getClass().getSimpleName() + " SQLSTATE " + e.getSQLState());
            }
            evidence.note(STEP_PREPARE, "Phase 0 ran (roles sm_dp_owner, sm_dp_migrator, sm_dp_runtime; schemas sm_data, sm_secret, sm_metrics)");
        }
        setPassword("sm_dp_runtime", s.runtimePassword());
        setPassword("sm_dp_migrator", s.migratorPassword());
        SmDataMigrationMain.run(s.url(), "sm_dp_migrator", s.migratorPassword());
        Integer children = runtime().getJdbcTemplate().queryForObject(
                "SELECT count(*)::int FROM pg_catalog.pg_inherits WHERE inhparent = 'sm_metrics.metrics_raw'::regclass", Integer.class);
        evidence.measured(STEP_PREPARE, "rawPartitions", children);
        if (children == null || children < 4) {
            throw new IllegalStateException("the sm_metrics migration left " + children + " raw partitions, expected this month and 3 ahead");
        }
    }

    private void setPassword(String role, String password) {
        // ALTER ROLE cannot bind a value; the statement text is built by the server's own quoting of the bound value (%L).
        String statement = admin().getJdbcTemplate().queryForObject(
                "SELECT format('ALTER ROLE %I PASSWORD %L', ?::text, ?::text)", String.class, role, password);
        admin().getJdbcTemplate().execute(statement);
    }

    // ---- sites -----------------------------------------------------------------------------------------------------

    /** One tenant's instances: ids persisted so a later step (possibly in another JVM) uses the same ones. */
    record Site(String role, UUID tenant, UUID environment, UUID solution, int instances) {
    }

    private static List<MetricShape> shapes(int metrics) {
        int gauges = Math.max(1, (int) Math.ceil(metrics * 2 / 5.0));
        List<MetricShape> out = new ArrayList<>();
        for (int i = 0; i < metrics; i++) {
            out.add(i < gauges ? new MetricShape("bench.gauge_" + (i + 1), "gauge")
                    : new MetricShape("bench.counter_" + (i - gauges + 1), "counter"));
        }
        return out;
    }

    private Site site(String role, int instances) {
        UUID tenant = id(role + ".tenant", role.equals("main") ? s.tenant().orElse(null) : null);
        UUID environment = id(role + ".environment", role.equals("main") ? s.environment().orElse(null) : null);
        UUID solution = id(role + ".solution", null);
        Site site = new Site(role, tenant, environment, solution, instances);
        seed(site);
        return site;
    }

    /**
     * The creation time every seeded row carries: BEFORE the loaded window, not "now" (W4-11). The query side bounds a series by the life of
     * its placement (it asks for placements created before the range ends), so a placement created at seed time would make every query
     * over the backdated samples answer 200 with no series, and the gate would be timing empty queries. Backdating is what a real
     * install that has been collecting for the window looks like.
     */
    private OffsetDateTime seededAt() {
        return OffsetDateTime.ofInstant(s.windowStart().minus(1, ChronoUnit.DAYS), ZoneOffset.UTC);
    }

    private UUID id(String key, UUID configured) {
        var stored = results.get("site." + key);
        if (stored.isPresent()) {
            return UUID.fromString(stored.get());
        }
        UUID value = configured != null ? configured : UUID.randomUUID();
        results.put("site." + key, value.toString());
        return value;
    }

    private void seed(Site site) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", site.tenant()).addValue("env", site.environment())
                .addValue("sol", site.solution()).addValue("n", site.instances()).addValue("v", VERSION)
                .addValue("by", UUID.nameUUIDFromBytes(("bench-" + site.tenant()).getBytes())).addValue("now", seededAt());
        Integer have = runtime().queryForObject("SELECT count(*)::int FROM sm_data.instance_state WHERE tenant_id = :t", p, Integer.class);
        if (have != null && have != 0 && have != site.instances()) {
            throw new IllegalStateException("tenant " + site.role() + " has " + have + " instances, expected " + site.instances()
                    + ": a different scale was loaded before; use a fresh database or the same sm.bench.instances");
        }
        if (have == null || have == 0) {
            runtime().update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                    + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                    + " SELECT :t, gen_random_uuid(), :env, :sol, 'cache', :v, 'bench-' || g, 'active', 'direct-http', :by, :now"
                    + " FROM generate_series(1, :n) g", p);
            UUID target = UUID.randomUUID();
            p.addValue("x", target).addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 11)).addValue("cred", UUID.randomUUID());
            runtime().update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                    + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix, created_by, created_at)"
                    + " VALUES (:t, :x, 'ssh-docker', 'bench-vm.example.test', 22, 'sm', :fp, :cred, '/srv/sm', 'sm-', :by, :now)", p);
            runtime().update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                    + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations, created_at)"
                    + " SELECT i.tenant_id, gen_random_uuid(), i.instance_id, :x, 'sm-' || i.name, '/srv/sm/' || i.name,"
                    + " '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, :now"
                    + " FROM sm_data.instance_state i WHERE i.tenant_id = :t", p);
        }
        Integer series = runtime().queryForObject("SELECT count(*)::int FROM sm_metrics.series WHERE tenant_id = :t", p, Integer.class);
        if (series == null || series == 0) {
            for (MetricShape m : shapes(s.metrics())) {
                p.addValue("name", m.name()).addValue("type", m.type()).addValue("fp", IngestService.EMPTY_LABELS_FINGERPRINT);
                runtime().update("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name, metric_type, unit,"
                        + " source_discriminator, labels_fingerprint, created_at) SELECT pl.tenant_id, pl.instance_id, pl.placement_id,"
                        + " :name, :type, 'count', 'dp-poll', :fp, :now FROM sm_data.placement pl WHERE pl.tenant_id = :t", p);
            }
            p.addValue("interval", site.role().equals("scaled") ? s.scaledIntervalSeconds() : s.intervalSeconds());
            runtime().update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from, package_version, interval_seconds)"
                    + " SELECT tenant_id, series_id, TIMESTAMPTZ '2000-01-01 00:00:00+00', :v, :interval FROM sm_metrics.series"
                    + " WHERE tenant_id = :t", p);
        }
    }

    private int seriesCount(UUID tenant) {
        return runtime().queryForObject("SELECT count(*)::int FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Integer.class);
    }

    // ---- partitions ------------------------------------------------------------------------------------------------

    /** The runway keeper's function covers this month and three ahead; a PAST month is attached here as the owner, as the tests do. */
    private void ensurePartitions(Instant from, Instant to) {
        runtime().getJdbcTemplate().queryForObject("SELECT sm_metrics.ensure_metric_partitions()", java.sql.Date.class);
        java.time.YearMonth month = java.time.YearMonth.from(from.atZone(ZoneOffset.UTC));
        java.time.YearMonth last = java.time.YearMonth.from(to.minusSeconds(1).atZone(ZoneOffset.UTC));
        for (; !month.isAfter(last); month = month.plusMonths(1)) {
            String child = "metrics_raw_" + String.format("%04d_%02d", month.getYear(), month.getMonthValue());
            Boolean exists = admin().getJdbcTemplate().queryForObject("SELECT to_regclass('sm_metrics." + child + "') IS NOT NULL", Boolean.class);
            if (Boolean.TRUE.equals(exists)) {
                continue;
            }
            String lo = month.atDay(1) + " 00:00:00+00";
            String hi = month.plusMonths(1).atDay(1) + " 00:00:00+00";
            adminOwnerDdl(
                    "CREATE TABLE sm_metrics." + child + " (LIKE sm_metrics.metrics_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS)",
                    "ALTER TABLE sm_metrics." + child + " ADD CONSTRAINT " + child + "_bound CHECK (ts >= '" + lo + "'::timestamptz AND ts < '" + hi + "'::timestamptz)",
                    "ALTER TABLE sm_metrics.metrics_raw ATTACH PARTITION sm_metrics." + child + " FOR VALUES FROM ('" + lo + "') TO ('" + hi + "')");
            evidence.note("1", "attached the past month's partition " + child + " as sm_dp_owner (the runway keeper creates only this month and three ahead)");
        }
    }

    private void adminOwnerDdl(String... statements) {
        try (java.sql.Connection c = adminDs.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("SET ROLE sm_dp_owner");
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("owner DDL failed: " + e.getClass().getSimpleName() + " SQLSTATE " + e.getSQLState());
        }
    }

    // ---- measuring -------------------------------------------------------------------------------------------------

    record PartitionSize(String name, long total, long heap, long index) {
    }

    record RawSize(long total, long heap, long index, List<PartitionSize> partitions) {
    }

    private RawSize rawSize() {
        List<PartitionSize> parts = admin().query("SELECT c.relname, pg_total_relation_size(c.oid) AS total, pg_relation_size(c.oid) AS heap,"
                        + " pg_indexes_size(c.oid) AS idx FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid"
                        + " WHERE h.inhparent = 'sm_metrics.metrics_raw'::regclass ORDER BY c.relname", new MapSqlParameterSource(),
                (rs, i) -> new PartitionSize(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
        return new RawSize(parts.stream().mapToLong(PartitionSize::total).sum(), parts.stream().mapToLong(PartitionSize::heap).sum(),
                parts.stream().mapToLong(PartitionSize::index).sum(), parts);
    }

    private String lsn() {
        return admin().getJdbcTemplate().queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
    }

    private long walSince(String lsn) {
        return admin().getJdbcTemplate().queryForObject("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), ?::pg_lsn)::bigint", Long.class, lsn);
    }

    private long databaseBytes() {
        return admin().getJdbcTemplate().queryForObject("SELECT pg_database_size(current_database())", Long.class);
    }

    private long metricsSchemaBytes() {
        return admin().getJdbcTemplate().queryForObject("SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint FROM pg_catalog.pg_class c"
                + " JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'sm_metrics' AND c.relkind IN ('r', 'm')"
                + " AND NOT c.relispartition", Long.class);
    }

    // ---- the load --------------------------------------------------------------------------------------------------

    record Load(long rows, long skipped, double seconds) {
    }

    /**
     * Loads {@code [from, to)} (both on the hour) for {@code tenant}, one hour per statement, time-major.
     *
     * @param tickOrigin the global tick index of {@code from}, so a counter keeps climbing across hours
     */
    private Load load(String step, UUID tenant, Instant from, Instant to, int intervalSeconds, long tickOrigin) {
        int series = seriesCount(tenant);
        int ticksPerHour = 3600 / intervalSeconds;
        long expected = (long) ticksPerHour * series;
        long rows = 0;
        long skipped = 0;
        long started = System.nanoTime();
        int hourIndex = 0;
        for (Instant hour = from; hour.isBefore(to); hour = hour.plus(1, ChronoUnit.HOURS), hourIndex++) {
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("t", tenant).addValue("h0", hour.toString())
                    .addValue("h1", hour.plus(1, ChronoUnit.HOURS).toString()).addValue("ticks", ticksPerHour)
                    .addValue("step", intervalSeconds + " seconds").addValue("base", tickOrigin + (long) hourIndex * ticksPerHour);
            Long have = runtime().queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw WHERE tenant_id = :t"
                    + " AND ts >= :h0::timestamptz AND ts < :h1::timestamptz", p, Long.class);
            if (have != null && have == expected) {
                skipped += expected;
                continue;
            }
            if (have != null && have != 0) {
                throw new IllegalStateException("hour " + hour + " holds " + have + " rows, expected 0 or " + expected + ": not guessing");
            }
            rows += runtime().update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment, received_at)"
                    + " SELECT s.tenant_id, s.series_id, :h0::timestamptz + k.k * (:step)::interval,"
                    + " CASE WHEN s.metric_type = 'counter' THEN (:base + k.k)::float8 ELSE ((s.series_id * 7 + :base + k.k) % 1000)::float8 END,"
                    + " CASE WHEN s.metric_type = 'counter' AND (:base + k.k) > 0 THEN 1.0::float8 ELSE NULL END,"
                    + " :h0::timestamptz + k.k * (:step)::interval"
                    + " FROM generate_series(0, :ticks - 1) AS k(k) CROSS JOIN sm_metrics.series s WHERE s.tenant_id = :t"
                    + " ORDER BY k.k, s.series_id", p);
            if (hourIndex % 24 == 23) {
                long dbBytes = databaseBytes();
                evidence.note(step, "loaded through " + hour.plus(1, ChronoUnit.HOURS) + " rows=" + rows + " databaseBytes=" + dbBytes
                        + " elapsedSeconds=" + String.format(Locale.ROOT, "%.0f", (System.nanoTime() - started) / 1e9));
                if (s.maxDbBytes().isPresent() && dbBytes >= s.maxDbBytes().getAsLong()) {
                    evidence.finding(step, "STOPPED: the database reached sm.bench.max-db-bytes=" + s.maxDbBytes().getAsLong()
                            + " (free disk minus the floor); the load stops here, take the scaled option to the owner");
                    throw new IllegalStateException("stopped at the disk ceiling after " + rows + " rows");
                }
            }
        }
        return new Load(rows, skipped, (System.nanoTime() - started) / 1e9);
    }

    private Instant day(int index) {
        return s.windowStart().plus(index, ChronoUnit.DAYS);
    }

    private static Instant monthStartPlus(int months) {
        return java.time.YearMonth.now(ZoneOffset.UTC).plusMonths(months).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private String scale() {
        boolean real = s.instances() == 200 && s.metrics() == 5 && s.intervalSeconds() == 30 && s.days() == 30;
        return (real ? "S-A (the plan's real month)" : "SCALED-DOWN, NOT S-A") + " instances=" + s.instances() + " metrics=" + s.metrics()
                + " intervalSeconds=" + s.intervalSeconds() + " days=" + s.days() + " rowsPerMonth=" + s.rowsPerMonth();
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ---- Step 0b ---------------------------------------------------------------------------------------------------

    /**
     * One day first, with indexes and WAL, projected to the month BEFORE the month is loaded (M-Q8's lesson, MF17). It loads
     * day 0 of the window the same way Step 1 will, so Step 1 reuses it; nothing is thrown away.
     */
    public void step0b() {
        evidence.note("0b", "scale: " + scale());
        Site site = site("main", s.instances());
        ensurePartitions(day(0), day(1));
        RawSize before = rawSize();
        String lsnBefore = lsn();
        Load day0 = load("0b", site.tenant(), day(0), day(1), s.intervalSeconds(), 0);
        if (day0.rows() == 0) {
            evidence.note("0b", "day 0 was already loaded (" + day0.skipped() + " rows): the figures of the run that loaded it are kept");
            return;
        }
        long wal = walSince(lsnBefore);
        RawSize after = rawSize();
        long bytes = after.total() - before.total();
        double bytesPerRow = (double) bytes / day0.rows();
        double walPerRow = (double) wal / day0.rows();
        evidence.measured("0b", "rows", day0.rows());
        evidence.measured("0b", "dayBytesWithIndexes", bytes);
        evidence.measured("0b", "heapBytes", after.heap() - before.heap());
        evidence.measured("0b", "indexBytes", after.index() - before.index());
        evidence.measured("0b", "bytesPerRow", f(bytesPerRow));
        evidence.measured("0b", "walBytes", wal);
        evidence.measured("0b", "walBytesPerRow", f(walPerRow));
        evidence.measured("0b", "loadSeconds", f(day0.seconds()));
        evidence.measured("0b", "rowsPerSecondDbOnly", f(day0.rows() / day0.seconds()));
        evidence.measured("0b", "dayBytes", bytes);
        evidence.measured("0b", "dayWalBytes", wal);
        long projection = GateMath.monthProjection(bytes, wal, s.days(), s.floorBytes());
        evidence.extrapolated("0b", "monthProjectionBytes", projection,
                "arithmetic: " + s.days() + " x the measured day's bytes + " + s.days() + " x its WAL + the " + s.floorBytes()
                        + " B floor; WAL counted in full as the plan says, though a checkpoint recycles it");
        if (s.freeDiskBytes().isPresent()) {
            long free = s.freeDiskBytes().getAsLong();
            boolean fits = GateMath.fits(projection, free);
            int days = GateMath.largestFittingDays(bytes, wal, free, s.floorBytes());
            results.put("0b.fits", String.valueOf(fits));
            results.put("0b.largestFittingDays", String.valueOf(days));
            evidence.measured("0b", "freeDiskBytes", free);
            if (fits) {
                evidence.note("0b", "VERDICT: the month FITS (projection " + projection + " <= free " + free + "): Step 1 may run");
            } else {
                evidence.finding("0b", "VERDICT: the month DOES NOT FIT (projection " + projection + " > free " + free + "). STOP and take the scaled"
                        + " option to the owner: " + days + " whole days fit; load them with sm.bench.days=" + days + " and LABEL the month extrapolated");
            }
        } else {
            evidence.note("0b", "free disk not given (sm.bench.free-disk-bytes, from df on the host): compare monthProjectionBytes with it BEFORE Step 1");
        }
    }

    // ---- Step 1 ----------------------------------------------------------------------------------------------------

    /** Loads the rest of the month, then measures bytes/row (heap and indexes), WAL and vacuum time. Fills MF17. */
    public void step1() {
        evidence.note("1", "scale: " + scale());
        refuseWhenStep0bSaidItDoesNotFit();
        Site site = site("main", s.instances());
        // W4-11: the console's query filters a series by the declared entity type of its metric (a metric_definition row), so the main site
        // declares its metrics too; without them every entity-typed query over the loaded month answers 200 with no series.
        declare(site);
        ensurePartitions(s.windowStart(), s.windowEnd());
        String lsnBefore = lsn();
        long started = System.nanoTime();
        Load loaded = load("1", site.tenant(), s.windowStart(), s.windowEnd(), s.intervalSeconds(), 0);
        double loadSeconds = (System.nanoTime() - started) / 1e9;
        long wal = walSince(lsnBefore);
        long total = runtime().queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), Long.class);
        evidence.measured("1", "rowsLoadedThisRun", loaded.rows());
        evidence.measured("1", "rowsAlreadyLoaded", loaded.skipped());
        evidence.measured("1", "rowsTotal", total);
        evidence.measured("1", "loadSeconds", f(loadSeconds));
        if (loaded.rows() > 0) {
            evidence.measured("1", "rowsPerSecondDbOnly", f(loaded.rows() / loadSeconds));
            evidence.measured("1", "walBytes", wal);
            evidence.measured("1", "walBytesPerRow", f((double) wal / loaded.rows()));
        }
        List<String> parts = rawSize().partitions().stream().map(PartitionSize::name).toList();
        double vacuumSeconds = 0;
        java.time.YearMonth thisMonth = java.time.YearMonth.now(ZoneOffset.UTC);
        for (String part : parts) {
            boolean closed = java.time.YearMonth.of(Integer.parseInt(part.substring(12, 16)), Integer.parseInt(part.substring(17, 19))).isBefore(thisMonth);
            long t0 = System.nanoTime();
            admin().getJdbcTemplate().execute("VACUUM (" + (closed ? "FREEZE, " : "") + "ANALYZE) sm_metrics." + quote(part));
            double seconds = (System.nanoTime() - t0) / 1e9;
            vacuumSeconds += seconds;
            evidence.measured("1", "vacuumSeconds." + part, f(seconds) + (closed ? " (FREEZE, a closed month: OQ-10)" : " (ANALYZE only, the open month)"));
        }
        evidence.measured("1", "vacuumSecondsTotal", f(vacuumSeconds));
        RawSize size = rawSize();
        for (PartitionSize p : size.partitions()) {
            if (p.total() > 64 * 1024) {
                evidence.measured("1", "partitionBytes." + p.name(), p.total());
            }
        }
        // Every tenant's rows share these partitions (the probes of Steps 2 and 5 may have run first), so the divisor is ALL rows.
        long allRows = runtime().getJdbcTemplate().queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw", Long.class);
        evidence.measured("1", "rowsAllTenants", allRows);
        double bytesPerRow = (double) size.total() / allRows;
        evidence.measured("1", "rawBytesWithIndexes", size.total());
        evidence.measured("1", "rawHeapBytes", size.heap());
        evidence.measured("1", "rawIndexBytes", size.index());
        evidence.measured("1", "bytesPerRow", f(bytesPerRow));
        evidence.measured("1", "heapBytesPerRow", f((double) size.heap() / allRows));
        evidence.measured("1", "indexBytesPerRow", f((double) size.index() / allRows));
        evidence.measured("1", "seriesAndSegmentBytes", admin().getJdbcTemplate().queryForObject(
                "SELECT (pg_total_relation_size('sm_metrics.series') + pg_total_relation_size('sm_metrics.series_segment'))::bigint", Long.class));
        evidence.measured("1", "databaseBytes", databaseBytes());
        long rowsPerDaySA = 200L * 5 * 2880;
        long sixMonthBytes = GateMath.sixMonths(Math.round(bytesPerRow * rowsPerDaySA * 30)).bytes();
        evidence.extrapolated("1", "sixMonthRawBytesAtSA", sixMonthBytes,
                "EXTRAPOLATED: measured bytes/row x S-A's 86.4 M rows/month x 6 (518.4 M rows), raw tier only; not a measurement of six months");
    }

    private void refuseWhenStep0bSaidItDoesNotFit() {
        var fits = results.get("0b.fits");
        if (fits.isPresent() && fits.get().equals("false")) {
            long allowed = results.getLong("0b.largestFittingDays").orElse(0);
            if (s.days() > allowed) {
                throw new IllegalStateException("Step 0b projected that " + s.days() + " days do not fit the free disk (only " + allowed
                        + " do). Put the scaled option to the owner; then run Step 1 with sm.bench.days=" + allowed
                        + " (or fewer) and label the month extrapolated");
            }
        }
    }

    private static String quote(String identifier) {
        if (!identifier.matches("metrics_raw_[0-9]{4}_[0-9]{2}")) {
            throw new IllegalArgumentException("not a metrics_raw partition name");
        }
        return identifier;       // resolved from the catalog and matched against the closed grammar: never from outside
    }

    // ---- Step 2 ----------------------------------------------------------------------------------------------------

    /** Insert rate: through the real IngestService batch path (client to database latency included), and server-side. */
    public void step2() {
        evidence.note("2", "scale: " + scale());
        ingestPath();
        serverSide();
    }

    private void ingestPath() {
        Site site = site("ingest", s.instances());
        declare(site);
        List<UUID> placements = runtime().queryForList("SELECT placement_id FROM sm_data.placement WHERE tenant_id = :t ORDER BY placement_id",
                new MapSqlParameterSource("t", site.tenant()), UUID.class);
        List<MetricShape> shapes = shapes(s.metrics());
        int ticks = s.ingestTicks();
        Instant start = Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds((long) (ticks + 2) * s.intervalSeconds());
        List<Long> latenciesMicros = new ArrayList<>();
        long committed = 0;
        long notCommitted = 0;
        long samples = 0;
        Map<String, Integer> outcomes = new java.util.TreeMap<>();
        NamedParameterJdbcTemplate jdbc = runtime();
        try (IngestService service = new IngestService(new TransactionTemplate(new DataSourceTransactionManager(runtimeDs)),
                new JdbcIngestContextRepository(jdbc), new JdbcSeriesRepository(jdbc), new JdbcRawSampleRepository(jdbc),
                new JdbcIngestCounterRepository(jdbc), new JdbcRestartMarkerRepository(jdbc), IngestProperties.defaults(), Clock.systemUTC())) {
            long started = System.nanoTime();
            outer:
            for (int k = 0; k < ticks; k++) {
                Instant ts = start.plusSeconds((long) k * s.intervalSeconds());
                for (UUID placement : placements) {
                    List<IngestSample> batch = new ArrayList<>();
                    for (MetricShape m : shapes) {
                        batch.add(new IngestSample(m.name(), ts, m.type().equals("counter") ? k : (k % 1000), ts, null));
                    }
                    long t0 = System.nanoTime();
                    IngestResult result = service.ingest(IngestBatch.polled(site.tenant(), placement, batch));
                    latenciesMicros.add((System.nanoTime() - t0) / 1000);
                    outcomes.merge(result.getClass().getSimpleName(), 1, Integer::sum);
                    if (result instanceof IngestResult.Committed c) {
                        committed++;
                        samples += c.accepted();
                    } else {
                        notCommitted++;
                    }
                    if ((System.nanoTime() - started) / 1e9 > s.ingestSeconds()) {
                        evidence.note("2", "stopped at sm.bench.ingest.seconds=" + s.ingestSeconds() + " after tick " + k);
                        break outer;
                    }
                }
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            evidence.measured("2", "ingest.batches", committed + notCommitted);
            evidence.measured("2", "ingest.batchOutcomes", outcomes);
            evidence.measured("2", "ingest.samplesCommitted", samples);
            evidence.measured("2", "ingest.seconds", f(seconds));
            evidence.measured("2", "ingest.samplesPerSecond", f(samples / seconds));
            evidence.measured("2", "ingest.batchesPerSecond", f((committed + notCommitted) / seconds));
            evidence.measured("2", "ingest.batchLatencyMillisP50", f(GateMath.percentile(latenciesMicros, 0.50) / 1000.0));
            evidence.measured("2", "ingest.batchLatencyMillisP95", f(GateMath.percentile(latenciesMicros, 0.95) / 1000.0));
            evidence.note("2", "LABEL: the ingest rate goes through IngestService from THIS JVM to the database URL it was given; over an SSH tunnel it"
                    + " INCLUDES network latency. A batch is one placement's " + shapes.size() + " samples, the shape the collector sends");
        }
    }

    private void declare(Site site) {
        List<MetricDefinitionSpec> definitions = new ArrayList<>();
        for (MetricShape m : shapes(s.metrics())) {
            definitions.add(new MetricDefinitionSpec(m.name(), "cache-engine", m.type(), "count", "http_json",
                    "{\"port\":8081,\"path\":\"/api/cache/stats\",\"select\":\"$.x\"}", s.intervalSeconds(), true));
        }
        new JdbcMetricDefinitionRepository(runtime()).insertDefinitionsIfAbsent(site.tenant(), site.solution(), VERSION,
                new MetricsDeclaration("sha256:" + "0".repeat(64), definitions, List.of(), Map.of(), List.of()), Instant.now());
    }

    private void serverSide() {
        Site site = site("server", s.instances());
        Instant from = monthStartPlus(2);
        ensurePartitions(from, from.plus(1, ChronoUnit.DAYS));
        Load load = load("2", site.tenant(), from, from.plus(1, ChronoUnit.DAYS), s.intervalSeconds(), 0);
        evidence.measured("2", "serverSide.rows", load.rows());
        evidence.measured("2", "serverSide.seconds", f(load.seconds()));
        if (load.rows() > 0) {
            evidence.measured("2", "serverSide.rowsPerSecond", f(load.rows() / load.seconds()));
        }
        evidence.note("2", "LABEL: server-side INSERT ... SELECT, DB only (no client, no network, no IngestService)");
    }

    // ---- Step 2b ---------------------------------------------------------------------------------------------------

    /** Rollup cost over the loaded month: a one-time chunked backfill, then the steady-state late-window run at 5 minutes and at 10 s. */
    public void step2b() {
        evidence.note("2b", "scale: " + scale());
        Site site = site("main", s.instances());
        Long raw = runtime().queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), Long.class);
        if (raw == null || raw == 0) {
            throw new IllegalStateException("nothing is loaded for the benchmark tenant: run Step 1 first");
        }
        TenantId tenant = new TenantId(site.tenant());
        long hourlyBefore = rollupRows("sm_metrics.metrics_rollup_1h", site.tenant());
        JdbcRollupRepository repository = new JdbcRollupRepository(runtime());
        long t0 = System.nanoTime();
        int written = 0;
        for (Instant from = s.windowStart(); from.isBefore(s.windowEnd()); from = from.plus(s.rollupChunkHours(), ChronoUnit.HOURS)) {
            Instant to = from.plus(s.rollupChunkHours(), ChronoUnit.HOURS);
            if (to.isAfter(s.windowEnd())) {
                to = s.windowEnd();
            }
            written += repository.rollUpHours(site.tenant(), from, to, Instant.now());
        }
        int days = repository.rollUpDays(site.tenant(), s.windowStart(), s.windowEnd(), Instant.now());
        double backfill = (System.nanoTime() - t0) / 1e9;
        evidence.measured("2b", "backfill.seconds", f(backfill));
        evidence.measured("2b", "backfill.rawRowsRead", raw);
        evidence.measured("2b", "backfill.hourlyRowsWritten", written);
        evidence.measured("2b", "backfill.dailyRowsWritten", days);
        evidence.measured("2b", "backfill.hourlyRowsBefore", hourlyBefore);
        evidence.note("2b", "LABEL: the backfill runs in chunks of " + s.rollupChunkHours() + " h through the same SQL port as RollupService (one"
                + " statement over the whole month would hold the host for its length); it is a ONE-TIME cost, not the steady state");
        evidence.measured("2b", "hourlyTableBytes", admin().getJdbcTemplate().queryForObject("SELECT pg_total_relation_size('sm_metrics.metrics_rollup_1h')", Long.class));
        evidence.measured("2b", "hourlyRows", rollupRows("sm_metrics.metrics_rollup_1h", site.tenant()));
        evidence.measured("2b", "dailyRows", rollupRows("sm_metrics.metrics_rollup_1d", site.tenant()));

        steady(tenant, "defaultCadence", RollupProperties.defaults());
        steady(tenant, "tenSecondCadence", new RollupProperties(Duration.ofSeconds(10), Duration.ofHours(2), Duration.ofHours(24)));
    }

    private long rollupRows(String table, UUID tenant) {
        if (!table.equals("sm_metrics.metrics_rollup_1h") && !table.equals("sm_metrics.metrics_rollup_1d")) {
            throw new IllegalArgumentException(table);
        }
        return runtime().queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant), Long.class);
    }

    /** What the background loop does every {@code interval}: recompute the late window. Runs at SIMULATED instants just past the data. */
    private void steady(TenantId tenant, String label, RollupProperties properties) {
        List<Double> seconds = new ArrayList<>();
        List<Long> rawRead = new ArrayList<>();
        RollupRows delegate = new JdbcRollupRepository(runtime());
        RollupRows timed = new RollupRows() {
            @Override
            public int rollUpHours(UUID tenantId, Instant from, Instant to, Instant computedAt) {
                rawRead.add(runtime().queryForObject("SELECT count(*) FROM sm_metrics.metrics_raw WHERE tenant_id = :t AND ts >= :from AND ts < :to",
                        new MapSqlParameterSource().addValue("t", tenantId).addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC)), Long.class));
                long t0 = System.nanoTime();
                int n = delegate.rollUpHours(tenantId, from, to, computedAt);
                seconds.add((System.nanoTime() - t0) / 1e9);
                return n;
            }

            @Override
            public int rollUpDays(UUID tenantId, Instant from, Instant to, Instant computedAt) {
                long t0 = System.nanoTime();
                int n = delegate.rollUpDays(tenantId, from, to, computedAt);
                seconds.set(seconds.size() - 1, seconds.get(seconds.size() - 1) + (System.nanoTime() - t0) / 1e9);
                return n;
            }
        };
        RollupService service = new RollupService(timed, properties);
        Instant now = s.windowEnd().plusSeconds(300);
        CpuSampler cpu = CpuSampler.start(s.cpuCommand());
        int ran = 0;
        try {
            for (int i = 0; i < s.rollupRuns(); i++) {
                if (service.rollUp(tenant, now.plus(properties.interval().multipliedBy(i)))) {
                    ran++;
                }
            }
        } finally {
            Vmstat.Summary summary = cpu.stop();
            report(label, properties, seconds, rawRead, ran, cpu.active() ? summary : null);
        }
    }

    private void report(String label, RollupProperties properties, List<Double> seconds, List<Long> rawRead, int ran, Vmstat.Summary cpu) {
        String key = "steady." + label + ".";
        evidence.measured("2b", key + "intervalSeconds", properties.interval().toSeconds());
        evidence.measured("2b", key + "runs", ran);
        double mean = seconds.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        double max = seconds.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
        evidence.measured("2b", key + "secondsEachRun", seconds.stream().map(v -> f(v)).toList());
        evidence.measured("2b", key + "secondsMean", f(mean));
        evidence.measured("2b", key + "rawRowsReadEachRun", rawRead);
        double duty = mean / properties.interval().toSeconds();
        evidence.measured("2b", key + "dutyCycle", f(duty));
        if (max > properties.interval().toSeconds()) {
            evidence.finding("2b", label + ": one run took " + f(max) + " s, longer than its " + properties.interval().toSeconds()
                    + " s interval: put the cadence to the owner");
        }
        if (cpu == null) {
            evidence.note("2b", label + ": host CPU not sampled (set sm.bench.cpu-command, for example 'ssh user@host vmstat 5')");
            return;
        }
        evidence.measured("2b", key + "hostCpuSamples", cpu.samples());
        evidence.measured("2b", key + "hostCpuBusyPercentMean", f(cpu.meanBusyPercent()));
        evidence.measured("2b", key + "hostCpuBusyPercentMax", f(cpu.maxBusyPercent()));
        evidence.measured("2b", key + "hostCpuStealPercentMean", f(cpu.meanStealPercent()));
        if (cpu.samples() > 0) {
            double sustained = cpu.meanBusyPercent() * duty;
            evidence.extrapolated("2b", key + "sustainedBusyPercentEstimate", f(sustained),
                    "mean busy while running x duty cycle, an estimate of the average load this cadence adds");
            if (sustained > s.cpuBaselinePercent()) {
                evidence.finding("2b", label + ": the estimated sustained CPU " + f(sustained) + " % exceeds the burst baseline "
                        + s.cpuBaselinePercent() + " %: put the cadence to the owner");
            }
        }
    }

    // ---- Step 5 ----------------------------------------------------------------------------------------------------

    /** The scaled 5 s point: one day at 5 s for 200 series (for the agent plan). Server-side, in a month of its own. */
    public void step5() {
        int perInstance = s.metrics();
        int instances = (int) Math.ceil(s.scaledSeries() / (double) perInstance);
        Site site = site("scaled", instances);
        Instant from = monthStartPlus(1);
        ensurePartitions(from, from.plus(1, ChronoUnit.DAYS));
        RawSize before = rawSize();
        String lsnBefore = lsn();
        Load load = load("5", site.tenant(), from, from.plus(1, ChronoUnit.DAYS), s.scaledIntervalSeconds(), 0);
        if (load.rows() == 0) {
            evidence.note("5", "the day was already loaded: the figures of the run that loaded it are kept");
            return;
        }
        long wal = walSince(lsnBefore);
        RawSize after = rawSize();
        long bytes = after.total() - before.total();
        int series = seriesCount(site.tenant());
        evidence.measured("5", "series", series);
        evidence.measured("5", "intervalSeconds", s.scaledIntervalSeconds());
        evidence.measured("5", "rows", load.rows());
        evidence.measured("5", "dayBytesWithIndexes", bytes);
        evidence.measured("5", "bytesPerRow", f((double) bytes / load.rows()));
        evidence.measured("5", "walBytesPerRow", f((double) wal / load.rows()));
        evidence.measured("5", "rowsPerSecondDbOnly", f(load.rows() / load.seconds()));
        long sixMonths = Math.round((double) bytes * 180);
        evidence.extrapolated("5", "sixMonthBytesFor" + series + "SeriesAt" + s.scaledIntervalSeconds() + "s", sixMonths,
                "SCALED: one measured day x 180, raw tier only, " + series + " series; not a measurement of six months");
    }

    // ---- Step 6a ---------------------------------------------------------------------------------------------------

    /** Disk arithmetic for the 105 M-row stream, BEFORE anything is loaded for it. */
    public void step6a() {
        double bytesPerRow = results.getDouble("1.bytesPerRow")
                .orElseThrow(() -> new IllegalStateException("Step 1's bytes/row is not recorded: run Step 1 first"));
        double walPerRow = results.getDouble("1.walBytesPerRow").or(() -> results.getDouble("0b.walBytesPerRow"))
                .orElseThrow(() -> new IllegalStateException("no WAL/row is recorded: run Step 0b or Step 1 first"));
        long free;
        String basis;
        if (s.freeDiskAfterDropBytes().isPresent()) {
            free = s.freeDiskAfterDropBytes().getAsLong();
            basis = "measured by the operator after the benchmark month was dropped (sm.bench.free-disk-after-drop-bytes)";
        } else if (s.freeDiskBytes().isPresent()) {
            free = s.freeDiskBytes().getAsLong() + metricsSchemaBytes() + sumRaw();
            basis = "ESTIMATE: free disk now + the bytes the benchmark data in sm_metrics holds (what dropping it returns)";
        } else {
            throw new IllegalStateException("give sm.bench.free-disk-bytes (df on the host) or sm.bench.free-disk-after-drop-bytes");
        }
        GateMath.StreamDisk d = GateMath.streamDisk(105_000_000L, bytesPerRow, walPerRow, s.floorBytes(), free);
        evidence.measured("6a", "rows", d.rows());
        evidence.measured("6a", "rowBytes", d.rowBytes());
        evidence.measured("6a", "walBytes", d.walBytes());
        evidence.measured("6a", "floorBytes", d.floorBytes());
        evidence.measured("6a", "requiredBytes", d.requiredBytes());
        evidence.measured("6a", "freeBytesAfterDrop", d.freeBytes());
        evidence.note("6a", "free-disk basis: " + basis);
        evidence.note("6a", "for scale: the discovery's floor of 120-140 B/row, before indexes and WAL, already gives "
                + 105_000_000L * 120 + " to " + 105_000_000L * 140 + " bytes");
        if (d.fits()) {
            evidence.note("6a", "VERDICT: FITS: run Step 6b on the EC2 host");
        } else {
            evidence.finding("6a", "VERDICT: DOES NOT FIT: run Step 6b locally in Testcontainers (M-Q18 requires the 105 M-row stream, not EC2);"
                    + " report the EC2 duration as NOT MEASURED, never extrapolated; if it does not fit locally either, STOP and put it to the owner");
        }
        results.put("6a.fits", String.valueOf(d.fits()));
    }

    private long sumRaw() {
        return rawSize().total();
    }
}
