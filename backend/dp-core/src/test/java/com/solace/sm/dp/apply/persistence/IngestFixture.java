package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.metrics.ingest.IngestProperties;
import com.solace.sm.dp.metrics.ingest.IngestService;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 011 P11-3.x: an {@link IngestService} over real PostgreSQL, on a database of its own ({@link MetricsDb}), as
 * the runtime credential, with the one thing a container cannot give a shared test JVM: a database that can be made
 * to vanish. {@link #outage} makes every {@code getConnection} fail the way a stopped server would; the shared
 * container is never stopped because every other IT in the JVM is using it.
 *
 * <p>Only the I/O is faked. The service, the repositories, the SQL, the transaction manager and the constraints are
 * the production ones.
 */
final class IngestFixture implements AutoCloseable {

    /** Short bounds so a held batch is retried within a test's patience; production's defaults are seconds. */
    static IngestProperties fast(int bufferTicks, int maxSeries) {
        return new IngestProperties(bufferTicks, maxSeries, 5000, 30, Duration.ofMillis(250), Duration.ofSeconds(20),
                Duration.ofMillis(40), Duration.ofMinutes(5), Duration.ofMillis(250));
    }

    /** A DataSource a test can take down. */
    static final class FlakyDataSource implements DataSource {
        private final DataSource delegate;
        private final AtomicBoolean down = new AtomicBoolean();
        private volatile long slowMillis;

        FlakyDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        /** Every connection takes this long to obtain: a database that is up but slow (W3-14). */
        void slow(long millis) {
            slowMillis = millis;
        }

        void down(boolean value) {
            down.set(value);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down.get()) {
                throw new SQLException("the database is down (test)", "08006");
            }
            if (slowMillis > 0) {
                try {
                    Thread.sleep(slowMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted (test)", "08006");
                }
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    /** One instance with one live placement and its declared package version. */
    record Site(UUID tenant, UUID instance, UUID placement, UUID solution, String version) {
    }

    final MetricsDb db;
    final FlakyDataSource dataSource;
    final NamedParameterJdbcTemplate jdbc;
    final NamedParameterJdbcTemplate superuser;
    final TransactionTemplate transactions;
    private final List<IngestService> services = new ArrayList<>();

    private IngestFixture(MetricsDb db) {
        this.db = db;
        this.dataSource = new FlakyDataSource(db.runtime());
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.superuser = db.superuserJdbc();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    static IngestFixture create(String databaseName) {
        return new IngestFixture(MetricsDb.fresh(databaseName));
    }

    /** A fixture over a database somebody else already created. */
    static IngestFixture on(MetricsDb db) {
        return new IngestFixture(db);
    }

    /**
     * W2-10: the data plane's clock for these tests. Ingest drops a sample whose timestamp is more than five minutes past
     * the clock, and {@link #base()} is day 2 of the month at 12:00, which is in the future on the 1st. So the tests run on
     * a real clock that ticks at the real rate but starts one hour after {@code base()}: every fixture timestamp is in the
     * past on any date, and the clock stays in the same UTC month as the database's partitions.
     */
    static Clock testClock() {
        return Clock.offset(Clock.systemUTC(), Duration.between(Instant.now(), base().plus(Duration.ofHours(1))));
    }

    /** A service built the way production wires it, over this fixture's database. */
    IngestService service(IngestProperties properties) {
        return service(properties, testClock());
    }

    IngestService service(IngestProperties properties, Clock clock) {
        IngestService service = new IngestService(transactions, new JdbcIngestContextRepository(jdbc),
                new JdbcSeriesRepository(jdbc), new JdbcRawSampleRepository(jdbc),
                new JdbcIngestCounterRepository(jdbc), new JdbcRestartMarkerRepository(jdbc), properties, clock);
        services.add(service);
        return service;
    }

    void outage(boolean down) {
        dataSource.down(down);
    }

    // ---- sites and definitions ----------------------------------------------------------------------------------

    static MetricDefinitionSpec metric(String name, String type, String unit, String component, Integer interval) {
        return new MetricDefinitionSpec(name, component, type, unit, "http_json",
                "{\"port\":8081,\"path\":\"/api/cache/stats\",\"select\":\"$.x\"}", interval, true);
    }

    /**
     * A tenant of its own: an active instance ({@code acquisitionMode} {@code via-ssh} or {@code direct-http}), one
     * live placement, and {@code definitions} copied for the instance's package version. A restart signal is declared
     * for each of {@code signalComponents}.
     */
    Site site(String acquisitionMode, String version, List<MetricDefinitionSpec> definitions, List<String> signalComponents) {
        UUID tenant = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', :v, :n, 'active', :m, :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("e", UUID.randomUUID())
                        .addValue("c", solution).addValue("v", version).addValue("n", "instance-" + instance)
                        .addValue("m", acquisitionMode).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', 'vm-1.corp.example', 22, 'ec2-user',"
                        + " :fp, :cred, '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                        .addValue("x", target).addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("now", OffsetDateTime.now()));
        declare(tenant, solution, version, definitions, signalComponents);
        return new Site(tenant, instance, placement, solution, version);
    }

    /**
     * W2-11: re-targets the instance. The live placement is retired and a new one is declared on the same target, so the
     * instance keeps one live placement and a retired one that still owns series.
     */
    Site retarget(Site site) {
        UUID placement = UUID.randomUUID();
        UUID target = superuser.queryForObject("SELECT target_id FROM sm_data.placement WHERE tenant_id = :t"
                        + " AND placement_id = :p", new MapSqlParameterSource().addValue("t", site.tenant())
                        .addValue("p", site.placement()), UUID.class);
        superuser.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement())
                        .addValue("now", OffsetDateTime.now()));
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", placement)
                        .addValue("i", site.instance()).addValue("x", target).addValue("c", "sm-" + placement)
                        .addValue("d", "/srv/sm/" + placement).addValue("now", OffsetDateTime.now()));
        return new Site(site.tenant(), site.instance(), placement, site.solution(), site.version());
    }

    /** Copies a package version's definitions once, as declare does. */
    void declare(UUID tenant, UUID solution, String version, List<MetricDefinitionSpec> definitions,
            List<String> signalComponents) {
        List<RestartSignalSpec> signals = signalComponents.stream()
                .map(c -> new RestartSignalSpec(c, "{\"kind\":\"http_json\",\"path\":\"/actuator/info\",\"port\":8081}")).toList();
        new JdbcMetricDefinitionRepository(jdbc).insertDefinitionsIfAbsent(tenant, solution, version,
                new MetricsDeclaration("sha256:" + UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""), definitions, signals, Map.of(), List.of()),
                Instant.now());
    }

    /** The instance moves to another declared version (done as the superuser: the upgrade operation is routed, M-Q5). */
    void moveToVersion(Site site, String version) {
        superuser.update("UPDATE sm_data.instance_state SET declared_package_version = :v WHERE tenant_id = :t"
                        + " AND instance_id = :i",
                new MapSqlParameterSource().addValue("v", version).addValue("t", site.tenant())
                        .addValue("i", site.instance()));
    }

    // ---- batches and observation ----------------------------------------------------------------------------------

    /** Day 2 of this UTC month at 12:00: inside the migration's partitions however close to a month end the run is. */
    static Instant base() {
        return YearMonth.now(ZoneOffset.UTC).atDay(2).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    static IngestSample sample(String name, Instant ts, double value, String token) {
        return new IngestSample(name, ts, value, ts, token);
    }

    static IngestBatch batch(Site site, IngestSample... samples) {
        return IngestBatch.polled(site.tenant(), site.placement(), List.of(samples));
    }

    record Point(Instant ts, double value, Double increment) {
    }

    List<Point> points(Site site, String metricName) {
        return superuser.query("SELECT r.ts, r.value, r.increment FROM sm_metrics.metrics_raw r JOIN sm_metrics.series s"
                        + " ON s.tenant_id = r.tenant_id AND s.series_id = r.series_id WHERE s.tenant_id = :t"
                        + " AND s.placement_id = :p AND s.metric_name = :n ORDER BY r.ts",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement())
                        .addValue("n", metricName), (rs, i) -> {
                    double inc = rs.getDouble("increment");
                    Double increment = rs.wasNull() ? null : inc;
                    return new Point(rs.getObject("ts", OffsetDateTime.class).toInstant(), rs.getDouble("value"),
                            increment);
                });
    }

    int resets(Site site, String metricName) {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_metrics.series_reset x JOIN sm_metrics.series s"
                        + " ON s.tenant_id = x.tenant_id AND s.series_id = x.series_id WHERE s.tenant_id = :t"
                        + " AND s.placement_id = :p AND s.metric_name = :n",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement())
                        .addValue("n", metricName), Integer.class);
    }

    long counted(Site site, String reason) {
        Long n = superuser.queryForObject("SELECT coalesce(sum(count), 0) FROM sm_metrics.ingest_counter"
                        + " WHERE tenant_id = :t AND placement_id = :p AND reason = :r",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement())
                        .addValue("r", reason), Long.class);
        return n == null ? 0 : n;
    }

    int seriesCount(Site site) {
        return superuser.queryForObject("SELECT count(*)::int FROM sm_metrics.series WHERE tenant_id = :t",
                new MapSqlParameterSource("t", site.tenant()), Integer.class);
    }

    String markerToken(Site site) {
        return superuser.queryForList("SELECT token FROM sm_metrics.restart_marker WHERE tenant_id = :t"
                        + " AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", site.tenant()).addValue("p", site.placement()),
                String.class).stream().findFirst().orElse(null);
    }

    @Override
    public void close() {
        services.forEach(IngestService::close);
    }
}
