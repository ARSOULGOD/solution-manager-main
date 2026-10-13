package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.RestartSignalSpec;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 011 P11-4.3 test arrangement over a real {@code sm_data}: tenants, instances in any lifecycle and either
 * acquisition mode, targets with a host of the test's choosing, placements with a metrics-port map, definitions
 * carrying the REAL source shape ({@code kind}, {@code path}, {@code port}, {@code pointer}, {@code queryParams}), and
 * per-generation active lists. Every write is the production repository's or a runtime-role statement the production
 * code also uses; the few superuser statements say why.
 */
final class CollectFixture {

    /** The Cache package's metrics, as its fixture declares them: four metrics on one URL, one on another. */
    static List<MetricDefinitionSpec> cacheMetrics() {
        return List.of(
                http("cache.hit_rate", "gauge", "cache-engine", "/api/cache/stats", 8081, "/hitRate", Map.of(), null),
                http("cache.hit_count", "counter", "cache-engine", "/api/cache/stats", 8081, "/hitCount", Map.of(), null),
                http("cache.miss_count", "counter", "cache-engine", "/api/cache/stats", 8081, "/missCount", Map.of(), null),
                http("cache.size", "gauge", "cache-engine", "/api/cache/size", 8081, null,
                        Map.of("filterByNamespace", "false"), null));
    }

    static MetricDefinitionSpec http(String name, String type, String component, String path, Integer port,
            String pointer, Map<String, String> queryParams, Integer interval) {
        StringBuilder json = new StringBuilder("{\"kind\":\"http_json\",\"path\":\"").append(path).append('"');
        if (port != null) {
            json.append(",\"port\":").append(port);
        }
        if (pointer != null) {
            json.append(",\"pointer\":\"").append(pointer).append('"');
        }
        if (!queryParams.isEmpty()) {
            json.append(",\"queryParams\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : new TreeMap<>(queryParams).entrySet()) {
                json.append(first ? "" : ",").append('"').append(e.getKey()).append("\":\"").append(e.getValue())
                        .append('"');
                first = false;
            }
            json.append('}');
        }
        json.append(",\"onUnavailable\":\"degrade\"");
        if (interval != null) {
            json.append(",\"intervalSeconds\":").append(interval);
        }
        json.append('}');
        return new MetricDefinitionSpec(name, component, type, "events", "http_json", json.toString(), interval, true);
    }

    static MetricDefinitionSpec otherKind(String name, String kind) {
        return new MetricDefinitionSpec(name, "cache-engine", "gauge", "events", kind,
                "{\"kind\":\"" + kind + "\",\"onUnavailable\":\"degrade\"}", null, true);
    }

    static RestartSignalSpec startTimeSignal(String component, int port) {
        return new RestartSignalSpec(component, "{\"kind\":\"http_json\",\"path\":\"/actuator/metrics/process.start.time\","
                + "\"port\":" + port + ",\"pointer\":\"/measurements/0/value\",\"onUnavailable\":\"degrade\"}");
    }

    /** What a test needs to name an instance's rows. */
    record Instance(UUID tenant, UUID instance, UUID solution, String version, String mode) {
        IngestFixture.Site asSite(UUID placement) {
            return new IngestFixture.Site(tenant, instance, placement, solution, version);
        }
    }

    final NamedParameterJdbcTemplate jdbc;
    final NamedParameterJdbcTemplate superuser;
    final JdbcMetricDefinitionRepository definitions;
    final JdbcInstanceStateRepository instances;
    final JdbcPlacementRepository placements;
    final JdbcTargetConnectionRepository targets;

    CollectFixture(IngestFixture ingest) {
        this.jdbc = ingest.jdbc;
        this.superuser = ingest.superuser;
        this.definitions = new JdbcMetricDefinitionRepository(jdbc);
        this.instances = new JdbcInstanceStateRepository(jdbc);
        this.placements = new JdbcPlacementRepository(jdbc);
        this.targets = new JdbcTargetConnectionRepository(jdbc);
    }

    UUID tenant() {
        return UUID.randomUUID();
    }

    /** Copies a version's definitions and restart signals once, as declare does. */
    void declareVersion(UUID tenant, UUID solution, String version, List<MetricDefinitionSpec> defs,
            List<RestartSignalSpec> signals) {
        definitions.insertDefinitionsIfAbsent(tenant, solution, version, new MetricsDeclaration(
                "sha256:" + UUID.nameUUIDFromBytes((tenant + version).getBytes()).toString().replace("-", "")
                        + UUID.nameUUIDFromBytes(version.getBytes()).toString().replace("-", ""),
                defs, signals, Map.of(), List.of()), Instant.now());
    }

    /** An instance in {@code lifecycle}; its version must already be declared for the tenant and solution. */
    Instance instance(UUID tenant, UUID solution, String version, String mode, String lifecycle) {
        UUID instance = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', :v, :n, :l, :m, :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.randomUUID()).addValue("c", solution).addValue("v", version)
                        .addValue("n", "instance-" + instance).addValue("l", lifecycle).addValue("m", mode)
                        .addValue("u", UUID.randomUUID()).addValue("now", OffsetDateTime.now()));
        return new Instance(tenant, instance, solution, version, mode);
    }

    UUID target(UUID tenant, String host) {
        UUID target = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', :h, 22, 'ec2-user', :fp, :cred,"
                        + " '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target).addValue("h", host)
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        return target;
    }

    /** A live placement on {@code target} with its metrics-port map (source port to HOST port). */
    UUID placement(Instance instance, UUID target, Map<Integer, Integer> metricsPorts) {
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", instance.tenant()).addValue("p", placement)
                        .addValue("i", instance.instance()).addValue("x", target)
                        .addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("now", OffsetDateTime.now()));
        definitions.insertEndpoints(instance.tenant(), placement, target, metricsPorts, Instant.now());
        return placement;
    }

    void activeList(Instance instance, long generation, String... names) {
        definitions.insertActiveMetrics(instance.tenant(), instance.instance(), generation, List.of(names),
                Instant.now());
    }

    void retire(UUID tenant, UUID placement) {
        jdbc.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement));
    }

    void lifecycle(Instance instance, String lifecycle) {
        jdbc.update("UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource().addValue("l", lifecycle).addValue("t", instance.tenant())
                        .addValue("i", instance.instance()));
    }

    /** An active Cache-shaped instance: version declared, generation-0 list holding every Cache metric, one placement. */
    record Cache(Instance instance, UUID target, UUID placement) {
        IngestFixture.Site site() {
            return instance.asSite(placement);
        }
    }

    List<String> cacheMetricNames() {
        List<String> names = new ArrayList<>();
        cacheMetrics().forEach(m -> names.add(m.metricName()));
        return names;
    }

    /**
     * A tenant of its own with one active Cache-shaped instance on a target at {@code host}. The restart signal is
     * declared for {@code cache-engine} only when {@code withSignal} (direct mode's {@code process.start.time}).
     */
    Cache cache(String mode, String host, boolean withSignal) {
        UUID tenant = tenant();
        UUID solution = UUID.randomUUID();
        declareVersion(tenant, solution, "2.4", cacheMetrics(),
                withSignal ? List.of(startTimeSignal("cache-engine", 8081)) : List.of());
        Instance instance = instance(tenant, solution, "2.4", mode, "active");
        UUID target = target(tenant, host);
        UUID placement = placement(instance, target, Map.of(8081, 18081));
        activeList(instance, 0, cacheMetricNames().toArray(String[]::new));
        return new Cache(instance, target, placement);
    }

    /** One more active Cache-shaped instance of the SAME tenant on the SAME target, on its own host port. */
    Cache anotherCacheOn(Cache existing, int hostPort) {
        Instance instance = instance(existing.instance().tenant(), existing.instance().solution(),
                existing.instance().version(), existing.instance().mode(), "active");
        UUID placement = placement(instance, existing.target(), Map.of(8081, hostPort));
        activeList(instance, 0, cacheMetricNames().toArray(String[]::new));
        return new Cache(instance, existing.target(), placement);
    }
}
