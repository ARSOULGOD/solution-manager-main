package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * A database of its own inside the shared Testcontainers cluster, for the {@code sm_metrics} ITs that change
 * partitions (plan 011 P11-2.1). The shared database ({@link SmDataPostgres#bootstrap()}) must keep its runway
 * untouched for every other IT, so anything that detaches, drops, locks or plants into it runs here.
 *
 * <p>Phase 0's ROLES are cluster-wide and exist once; its SCHEMA half is replayed from the shipped script's own
 * statements (not retyped), so a schema Phase 0 gains is a schema this database gains.
 */
final class MetricsDb {

    private final String url;

    private MetricsDb(String url) {
        this.url = url;
    }

    /** What {@link #fresh(String, Migrated)} leaves behind. */
    enum Migrated {
        /** Phase 0's schema half only: nothing migrated. */
        NOTHING,
        /** {@code sm_data} migrated, {@code sm_metrics} not (an install that skipped the new step). */
        SM_DATA_ONLY,
        /** Both, through {@link SmDataMigrationMain#run}. */
        BOTH
    }

    /** A new, fully bootstrapped database: Phase 0's schema half, then {@link SmDataMigrationMain#run}. */
    static MetricsDb fresh(String name) {
        return fresh(name, Migrated.BOTH);
    }

    static MetricsDb fresh(String name, Migrated migrated) {
        String database = "sm_met_" + name;
        SmDataPostgres.bootstrap();
        SqlScript.execute(SmDataPostgres.superuser(), "DROP DATABASE IF EXISTS " + database);
        SqlScript.execute(SmDataPostgres.superuser(), "CREATE DATABASE " + database);
        MetricsDb db = new MetricsDb(SmDataPostgres.jdbcUrl(database));
        for (String statement : SqlStatements.of(SmDataPostgres.phase0Sql())) {
            if (statement.startsWith("CREATE SCHEMA") || statement.startsWith("GRANT USAGE ON SCHEMA")) {
                SqlScript.execute(db.superuser(), statement);
            }
        }
        switch (migrated) {
            case BOTH -> SmDataMigrationMain.run(db.url, "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD);
            case SM_DATA_ONLY -> SmDataMigrator.migrate(
                    SmDataPostgres.dataSource(db.url, "sm_dp_migrator", SmDataPostgres.MIGRATOR_PASSWORD));
            case NOTHING -> { }
        }
        return db;
    }

    /** The shared database every other IT uses, already bootstrapped; for tests that change nothing about its runway. */
    static MetricsDb shared() {
        SmDataPostgres.bootstrap();
        return new MetricsDb(SmDataPostgres.jdbcUrl());
    }

    String url() {
        return url;
    }

    DataSource superuser() {
        return SmDataPostgres.dataSource(url, SmDataPostgres.container().getUsername(),
                SmDataPostgres.container().getPassword());
    }

    DataSource runtime() {
        return SmDataPostgres.dataSource(url, "sm_dp_runtime", SmDataPostgres.RUNTIME_PASSWORD);
    }

    NamedParameterJdbcTemplate superuserJdbc() {
        return new NamedParameterJdbcTemplate(superuser());
    }

    NamedParameterJdbcTemplate runtimeJdbc() {
        return new NamedParameterJdbcTemplate(runtime());
    }

    /** Runs statements on ONE superuser connection, as {@code sm_dp_owner}, so what they create is owner-owned. */
    void asOwner(String... statements) {
        try (Connection c = superuser().getConnection(); Statement s = c.createStatement()) {
            s.execute("SET ROLE sm_dp_owner");
            for (String sql : statements) {
                s.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("owner statement failed", e);
        }
    }

    /** Attaches a CLOSED (past) month the way the runway function would have, had it run then. */
    void attachPastMonth(String yyyyMm, String lowInclusive, String highExclusive) {
        String child = "metrics_raw_" + yyyyMm;
        asOwner("CREATE TABLE sm_metrics." + child + " (LIKE sm_metrics.metrics_raw INCLUDING DEFAULTS INCLUDING CONSTRAINTS)",
                "ALTER TABLE sm_metrics." + child + " ADD CONSTRAINT " + child + "_bound CHECK (ts >= '" + lowInclusive
                        + " 00:00:00+00'::timestamptz AND ts < '" + highExclusive + " 00:00:00+00'::timestamptz)",
                "ALTER TABLE sm_metrics.metrics_raw ATTACH PARTITION sm_metrics." + child + " FOR VALUES FROM ('"
                        + lowInclusive + " 00:00:00+00') TO ('" + highExclusive + " 00:00:00+00')");
    }

    /** An active instance with one live placement; the minimal legal rows of V007. */
    Placement placement(UUID tenant) {
        NamedParameterJdbcTemplate jdbc = runtimeJdbc();
        UUID instance = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        InventoryFixtures.declare(jdbc, tenant, instance, "active");
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
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement)
                        .addValue("i", instance).addValue("x", target).addValue("c", "sm-" + placement)
                        .addValue("d", "/srv/sm/" + placement).addValue("now", OffsetDateTime.now()));
        return new Placement(tenant, instance, placement);
    }

    /** A second live placement of the SAME instance on a target of its own (the multi-site case). */
    Placement secondPlacementOfTheSameInstance(Placement first) {
        NamedParameterJdbcTemplate jdbc = runtimeJdbc();
        UUID target = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', 'vm-2.corp.example', 22, 'ec2-user',"
                        + " :fp, :cred, '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", first.tenant()).addValue("x", target)
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", first.tenant()).addValue("p", placement)
                        .addValue("i", first.instance()).addValue("x", target).addValue("c", "sm-" + placement)
                        .addValue("d", "/srv/sm/" + placement).addValue("now", OffsetDateTime.now()));
        return new Placement(first.tenant(), first.instance(), placement);
    }

    /** Inserts a series (as the runtime role would) and returns its generated id. */
    long series(Placement p, String metricName, String metricType) {
        return runtimeJdbc().queryForObject(
                "INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name, metric_type, unit,"
                        + " source_discriminator, labels_fingerprint, created_at) VALUES (:t, :i, :p, :n, :mt, 'count',"
                        + " 'dp-poll', :fp, :now) RETURNING series_id",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("i", p.instance())
                        .addValue("p", p.placement()).addValue("n", metricName).addValue("mt", metricType)
                        .addValue("fp", "sha256:" + "0".repeat(64)).addValue("now", OffsetDateTime.now()),
                Long.class);
    }

    void raw(Placement p, long seriesId, String ts, double value, Double increment) {
        runtimeJdbc().update("INSERT INTO sm_metrics.metrics_raw (tenant_id, series_id, ts, value, increment,"
                        + " received_at) VALUES (:t, :s, :ts::timestamptz, :v, :inc, :ts::timestamptz)",
                new MapSqlParameterSource().addValue("t", p.tenant()).addValue("s", seriesId)
                        .addValue("ts", ts).addValue("v", value).addValue("inc", increment));
    }

    /** The children of {@code metrics_raw}, read through pg_catalog as the superuser. */
    List<String> children() {
        return superuserJdbc().queryForList(
                "SELECT c.relname FROM pg_catalog.pg_inherits h JOIN pg_catalog.pg_class c ON c.oid = h.inhrelid"
                        + " WHERE h.inhparent = 'sm_metrics.metrics_raw'::regclass ORDER BY c.relname",
                new MapSqlParameterSource(), String.class);
    }

    record Placement(UUID tenant, UUID instance, UUID placement) {
    }
}
