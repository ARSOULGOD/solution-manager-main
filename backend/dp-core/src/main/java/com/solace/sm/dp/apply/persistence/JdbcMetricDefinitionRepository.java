package com.solace.sm.dp.apply.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The metric-definition tables of plan 011 P11-2.2 over the runtime credential: per-package-version definitions
 * (M-Q5 (a')), the host-side metrics ports of a placement (M-Q14 (a)) and the per-generation active lists (M-Q15 (a)).
 *
 * <p>Every method is meant to run INSIDE declare's reserve transaction (the plan 010 T4.4 pattern): this class opens no
 * transaction of its own, so a refusal anywhere rolls the definitions back with the placement. Every statement binds
 * its parameters and names {@code tenant_id} (I5). Every {@code jsonb} bind goes through {@link Sql#redacted}, and a
 * source that is not JSON is refused here, before PostgreSQL's class-22 error would quote it back.
 *
 * <p>Every table is INSERT-only for the runtime role. There is no update method because there is no update.
 */
public final class JdbcMetricDefinitionRepository implements MetricDeclarationRows {

    /** One parser with the one I6 setting: a failure must never quote the document it was parsing. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build();

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcMetricDefinitionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- M-Q5 -------------------------------------------------------------------------------------------------

    /**
     * Copies a package version's definitions and restart signals ONCE: INSERT-if-absent, never UPDATE. The first declare
     * of a version records its digest and writes the rest; a later declare of the same version with the same digest
     * writes nothing; a later declare with a DIFFERENT digest fails closed with {@link PackageVersionMismatchException}
     * before anything is written (the trigger on the child tables is the second enforcer, for every other writer).
     *
     * @return the number of definition and restart-signal rows newly written; 0 on an idempotent retry
     */
    @Override
    public int insertDefinitionsIfAbsent(UUID tenantId, UUID catalogSolutionId, String packageVersion,
            MetricsDeclaration declaration, Instant at) {
        MapSqlParameterSource version = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("solution", catalogSolutionId).addValue("version", packageVersion);
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.package_version_digest (tenant_id, catalog_solution_id,"
                + " package_version, package_digest, created_at) VALUES (:tenant, :solution, :version, :digest, :at)"
                + " ON CONFLICT (tenant_id, catalog_solution_id, package_version) DO NOTHING",
                new MapSqlParameterSource().addValues(version.getValues()).addValue("digest", declaration.packageDigest())
                        .addValue("at", Sql.at(at))));
        String recorded = jdbc.queryForObject("SELECT package_digest FROM sm_data.package_version_digest"
                + " WHERE tenant_id = :tenant AND catalog_solution_id = :solution AND package_version = :version",
                version, String.class);
        if (!declaration.packageDigest().equals(recorded)) {
            throw new PackageVersionMismatchException();
        }
        int written = 0;
        for (MetricDefinitionSpec d : declaration.definitions()) {
            requireJson(d.sourceJson());
            written += Sql.write(() -> jdbc.update("INSERT INTO sm_data.metric_definition (tenant_id,"
                    + " catalog_solution_id, package_version, metric_name, component_type, metric_type, unit,"
                    + " source_kind, source, interval_seconds, default_chart, package_digest) VALUES (:tenant,"
                    + " :solution, :version, :name, :component, :type, :unit, :kind, CAST(:source AS jsonb),"
                    + " :interval, :chart, :digest)"
                    + " ON CONFLICT (tenant_id, catalog_solution_id, package_version, metric_name) DO NOTHING",
                    new MapSqlParameterSource().addValues(version.getValues()).addValue("name", d.metricName())
                            .addValue("component", d.componentType()).addValue("type", d.metricType())
                            .addValue("unit", d.unit()).addValue("kind", d.sourceKind())
                            .addValue("source", Sql.redacted(d.sourceJson()))
                            .addValue("interval", d.intervalSeconds(), Types.INTEGER)
                            .addValue("chart", d.defaultChart()).addValue("digest", declaration.packageDigest())));
        }
        for (RestartSignalSpec s : declaration.restartSignals()) {
            requireJson(s.sourceJson());
            written += Sql.write(() -> jdbc.update("INSERT INTO sm_data.metric_restart_signal (tenant_id,"
                    + " catalog_solution_id, package_version, component_type, source, package_digest) VALUES"
                    + " (:tenant, :solution, :version, :component, CAST(:source AS jsonb), :digest)"
                    + " ON CONFLICT (tenant_id, catalog_solution_id, package_version, component_type) DO NOTHING",
                    new MapSqlParameterSource().addValues(version.getValues())
                            .addValue("component", s.componentType()).addValue("source", Sql.redacted(s.sourceJson()))
                            .addValue("digest", declaration.packageDigest())));
        }
        return written;
    }

    /** The version's definitions, by name. Another tenant's version reads as nothing. */
    public List<MetricDefinitionSpec> definitionsFor(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return jdbc.query("SELECT metric_name, component_type, metric_type, unit, source_kind, source::text AS source,"
                        + " interval_seconds, default_chart FROM sm_data.metric_definition WHERE tenant_id = :tenant"
                        + " AND catalog_solution_id = :solution AND package_version = :version ORDER BY metric_name",
                versionKey(tenantId, catalogSolutionId, packageVersion), JdbcMetricDefinitionRepository::definition);
    }

    /** The version's restart signals, by component. */
    public List<RestartSignalSpec> restartSignalsFor(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return jdbc.query("SELECT component_type, source::text AS source FROM sm_data.metric_restart_signal"
                        + " WHERE tenant_id = :tenant AND catalog_solution_id = :solution"
                        + " AND package_version = :version ORDER BY component_type",
                versionKey(tenantId, catalogSolutionId, packageVersion),
                (rs, i) -> new RestartSignalSpec(rs.getString("component_type"), rs.getString("source")));
    }

    // ---- M-Q14 ------------------------------------------------------------------------------------------------

    /**
     * Writes a placement's source-port to host-port map in ONE statement, so a refused map leaves not even its first row.
     * A host port another live placement on the target already uses, or one named twice, is a
     * {@link MetricsPortCollisionException} naming the class only. An empty map writes nothing.
     */
    @Override
    public void insertEndpoints(UUID tenantId, UUID placementId, UUID targetId, Map<Integer, Integer> metricsPorts,
            Instant at) {
        if (metricsPorts.isEmpty()) {
            return;
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("placement", placementId).addValue("target", targetId).addValue("at", Sql.at(at));
        StringBuilder values = new StringBuilder();
        int i = 0;
        for (Map.Entry<Integer, Integer> port : new TreeMap<>(metricsPorts).entrySet()) {
            values.append(i == 0 ? "" : ", ").append("(:tenant, :placement, :target, :src").append(i)
                    .append(", :host").append(i).append(", :at)");
            p.addValue("src" + i, port.getKey()).addValue("host" + i, port.getValue());
            i++;
        }
        try {
            Sql.write(() -> jdbc.update("INSERT INTO sm_data.placement_metrics_endpoint (tenant_id, placement_id,"
                    + " target_id, source_port, host_port, created_at) VALUES " + values, p));
        } catch (PgConstraintViolation e) {
            throw collisionOf(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    /** The placement's map, source port to host port. Another tenant's placement reads as nothing. */
    public Map<Integer, Integer> endpointsFor(UUID tenantId, UUID placementId) {
        Map<Integer, Integer> ports = new TreeMap<>();
        jdbc.query("SELECT source_port, host_port FROM sm_data.placement_metrics_endpoint"
                        + " WHERE tenant_id = :tenant AND placement_id = :placement",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("placement", placementId),
                rs -> {
                    ports.put(rs.getInt("source_port"), rs.getInt("host_port"));
                });
        return ports;
    }

    /**
     * The port collisions the database names, as the class they are. A static, because the collision a NEW PLACEMENT
     * causes (its health port equals another placement's metrics host port) surfaces from plan 010's placement insert as
     * a raw {@link PgConstraintViolation}; the declare maps it with this (P11-4.1).
     */
    public static Optional<MetricsPortCollisionException> collisionOf(PgConstraintViolation violation) {
        if (violation.namesAnyOf("metrics_host_port_in_use")) {
            return Optional.of(new MetricsPortCollisionException(MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IN_USE));
        }
        if (violation.namesAnyOf("metrics_host_port_is_a_health_port")) {
            return Optional.of(new MetricsPortCollisionException(
                    MetricsPortCollisionException.Kind.METRICS_HOST_PORT_IS_ANOTHER_PLACEMENTS_HEALTH_PORT));
        }
        if (violation.namesAnyOf("health_port_is_a_metrics_port")) {
            return Optional.of(new MetricsPortCollisionException(
                    MetricsPortCollisionException.Kind.HEALTH_PORT_IS_ANOTHER_PLACEMENTS_METRICS_PORT));
        }
        if (violation.namesAnyOf("placement_metrics_host_port_once")) {
            return Optional.of(new MetricsPortCollisionException(
                    MetricsPortCollisionException.Kind.TWO_SOURCE_PORTS_ONE_HOST_PORT));
        }
        return Optional.empty();
    }

    // ---- M-Q15 ------------------------------------------------------------------------------------------------

    /**
     * Records the metric names active for a generation, once. A second write for the same generation is a 23505, not a
     * silent no-op: the list is born with its generation and never edited, so a different list is a defect to see.
     */
    @Override
    public void insertActiveMetrics(UUID tenantId, UUID instanceId, long generation, List<String> names, Instant at) {
        Sql.write(() -> jdbc.update("INSERT INTO sm_data.generation_active_metrics (tenant_id, instance_id,"
                        + " generation, metric_names, created_at) VALUES (:tenant, :instance, :generation, :names, :at)",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("generation", generation).addValue("names", names.toArray(new String[0]))
                        .addValue("at", Sql.at(at))));
    }

    /** The names active at that generation, or empty when none was recorded. */
    @Override
    public Optional<List<String>> activeMetricsFor(UUID tenantId, UUID instanceId, long generation) {
        return jdbc.query("SELECT metric_names FROM sm_data.generation_active_metrics WHERE tenant_id = :tenant"
                        + " AND instance_id = :instance AND generation = :generation",
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("instance", instanceId)
                        .addValue("generation", generation),
                (rs, i) -> Arrays.asList((String[]) rs.getArray("metric_names").getArray())).stream().findFirst();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static MapSqlParameterSource versionKey(UUID tenantId, UUID catalogSolutionId, String packageVersion) {
        return new MapSqlParameterSource().addValue("tenant", tenantId).addValue("solution", catalogSolutionId)
                .addValue("version", packageVersion);
    }

    private static MetricDefinitionSpec definition(ResultSet rs, int i) throws SQLException {
        int interval = rs.getInt("interval_seconds");
        Integer intervalOrNull = rs.wasNull() ? null : interval;
        return new MetricDefinitionSpec(rs.getString("metric_name"), rs.getString("component_type"),
                rs.getString("metric_type"), rs.getString("unit"), rs.getString("source_kind"), rs.getString("source"),
                intervalOrNull, rs.getBoolean("default_chart"));
    }

    /** A source must be JSON before it is bound; the refusal says so and quotes nothing. */
    private static void requireJson(String text) {
        try {
            JSON.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a metric source is not valid JSON");
        }
    }
}
