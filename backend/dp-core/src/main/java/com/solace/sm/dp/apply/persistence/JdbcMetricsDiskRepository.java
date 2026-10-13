package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.rollup.DiskRows;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The metrics disk alarm's two bound catalog reads (plan 011 P11-3.2, OQ-16 (a)), as the runtime role (MF19:
 * {@code pg_database_size} needs {@code CONNECT} on the database, which is the default, or {@code pg_read_all_stats}; the
 * relation size functions state no privilege and are measured in {@code MetricsDiskAlarmIT} as {@code sm_dp_runtime}).
 * No filesystem access, no shell, no tenant column.
 */
public final class JdbcMetricsDiskRepository implements DiskRows {

    private static final String DATABASE = "SELECT pg_database_size(current_database())";
    private static final String METRICS = "SELECT coalesce(sum(pg_total_relation_size(c.oid)), 0)::bigint"
            + " FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace"
            + " WHERE n.nspname = :schema AND c.relkind = 'r'";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMetricsDiskRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Sizes read() {
        Long database = jdbc.queryForObject(DATABASE, Map.of(), Long.class);
        Long metrics = jdbc.queryForObject(METRICS, new MapSqlParameterSource("schema", "sm_metrics"), Long.class);
        return new Sizes(database == null ? 0 : database, metrics == null ? 0 : metrics);
    }
}
