package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.metrics.ingest.IngestRows;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_metrics.series}, {@code series_segment} and {@code series_reset} for the ingest path (plan 011 P11-3.1).
 * All three are INSERT-only for the runtime role, so this class has no update method because there is no update.
 * Every statement binds its values, names {@code tenant_id} first and is schema-qualified (the {@code sm-data} pool pins
 * {@code sm_data} as its schema).
 */
public final class JdbcSeriesRepository implements IngestRows.Series {

    private static final String IDENTITY = " WHERE tenant_id = :tenant AND instance_id = :instance AND placement_id = :placement"
            + " AND metric_name = :name AND metric_type = :type AND unit = :unit AND source_discriminator = :source"
            + " AND labels_fingerprint = :labels";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSeriesRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int countForInstance(UUID tenantId, UUID instanceId, String packageVersion) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sm_metrics.series s"
                        + " JOIN sm_data.placement p ON p.tenant_id = s.tenant_id AND p.placement_id = s.placement_id"
                        + " WHERE s.tenant_id = :tenant AND s.instance_id = :instance AND p.retired_at IS NULL"
                        + " AND coalesce((SELECT g.package_version FROM sm_metrics.series_segment g"
                        + " WHERE g.tenant_id = s.tenant_id AND g.series_id = s.series_id"
                        + " ORDER BY g.effective_from DESC LIMIT 1), :version) = :version",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("version", packageVersion),
                Integer.class);
        return n == null ? 0 : n;
    }

    private static MapSqlParameterSource identity(IngestRows.SeriesKey key) {
        return new MapSqlParameterSource().addValue("tenant", key.tenantId()).addValue("instance", key.instanceId())
                .addValue("placement", key.placementId()).addValue("name", key.metricName())
                .addValue("type", key.metricType()).addValue("unit", key.unit())
                .addValue("source", key.discriminator().wire()).addValue("labels", key.labelsFingerprint());
    }

    @Override
    public Optional<IngestRows.StoredSeries> find(IngestRows.SeriesKey key) {
        return jdbc.query("SELECT series_id FROM sm_metrics.series" + IDENTITY, identity(key),
                (rs, i) -> new IngestRows.StoredSeries(rs.getLong("series_id"), key)).stream().findFirst();
    }

    @Override
    public IngestRows.StoredSeries create(IngestRows.SeriesKey key, Instant createdAt) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.series (tenant_id, instance_id, placement_id, metric_name,"
                + " metric_type, unit, source_discriminator, labels_fingerprint, created_at) VALUES (:tenant, :instance,"
                + " :placement, :name, :type, :unit, :source, :labels, :at)"
                + " ON CONFLICT ON CONSTRAINT series_identity DO NOTHING",
                identity(key).addValue("at", Sql.at(createdAt))));
        return find(key).orElseThrow(() -> new IllegalStateException("a series just inserted cannot be read back"));
    }

    @Override
    public Optional<IngestRows.Segment> latestSegment(UUID tenantId, long seriesId) {
        return jdbc.query("SELECT effective_from, package_version, interval_seconds FROM sm_metrics.series_segment"
                        + " WHERE tenant_id = :tenant AND series_id = :series ORDER BY effective_from DESC LIMIT 1",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId), (rs, i) -> {
                    int interval = rs.getInt("interval_seconds");
                    Integer nullable = rs.wasNull() ? null : interval;
                    return new IngestRows.Segment(Sql.instant(rs, "effective_from"), rs.getString("package_version"),
                            nullable);
                }).stream().findFirst();
    }

    @Override
    public void addSegment(UUID tenantId, long seriesId, Instant effectiveFrom, String packageVersion,
            Integer intervalSeconds) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.series_segment (tenant_id, series_id, effective_from,"
                + " package_version, interval_seconds) VALUES (:tenant, :series, :from, :version, :interval)"
                + " ON CONFLICT (tenant_id, series_id, effective_from) DO NOTHING",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId)
                        .addValue("from", Sql.at(effectiveFrom)).addValue("version", packageVersion)
                        .addValue("interval", intervalSeconds, Types.INTEGER)));
    }

    @Override
    public void addReset(UUID tenantId, long seriesId, Instant at, String signal) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_metrics.series_reset (tenant_id, series_id, at, signal)"
                + " VALUES (:tenant, :series, :at, :signal) ON CONFLICT (tenant_id, series_id, at) DO NOTHING",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("series", seriesId)
                        .addValue("at", Sql.at(at)).addValue("signal", signal)));
    }
}
