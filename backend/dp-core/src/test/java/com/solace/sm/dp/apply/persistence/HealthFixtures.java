package com.solace.sm.dp.apply.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Rows for the health tests (plan 010 T7.2): an ACTIVE instance in a chosen acquisition mode, a registered target at a
 * chosen host, and live placements with (or without) a declared probe. Every statement runs as {@code sm_dp_runtime}
 * over the template the caller names, and binds its parameters.
 */
final class HealthFixtures {

    private HealthFixtures() {
    }

    record Live(UUID tenant, UUID instance, UUID placement, UUID target) {
    }

    /** A fresh tenant with one active instance and one live placement carrying the named probe. */
    static Live livePlacement(NamedParameterJdbcTemplate jdbc, String acquisition, String kind, String path,
            Integer port) {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = activeInstance(jdbc, tenant, acquisition);
        UUID target = target(jdbc, tenant, "vm-" + UUID.randomUUID().toString().substring(0, 8) + ".corp.example");
        UUID placement = placement(jdbc, tenant, instance, target, kind, path, port);
        return new Live(tenant, instance, placement, target);
    }

    static UUID activeInstance(NamedParameterJdbcTemplate jdbc, UUID tenant, String acquisition) {
        return instance(jdbc, tenant, acquisition, "active");
    }

    /** {@code lifecycle} is the state it is INSERTED in; a pending row can then be moved forward (V007's trigger). */
    static UUID instance(NamedParameterJdbcTemplate jdbc, UUID tenant, String acquisition, String lifecycle) {
        UUID instance = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by,"
                        + " created_at) VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, :l, :a, :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.randomUUID()).addValue("c", UUID.randomUUID())
                        .addValue("n", "instance-" + instance).addValue("l", lifecycle).addValue("a", acquisition)
                        .addValue("u", UUID.randomUUID()).addValue("now", OffsetDateTime.now()));
        return instance;
    }

    static UUID target(NamedParameterJdbcTemplate jdbc, UUID tenant, String host) {
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

    /** {@code kind} null declares no probe (all four probe columns NULL, as V007 requires). */
    static UUID placement(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, String kind,
            String path, Integer port) {
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism,"
                        + " retained_generations, health_kind, health_path, health_port, health_strength,"
                        + " created_at) VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :k, :hp, :hport, :hs, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                        .addValue("x", target).addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("k", kind, java.sql.Types.VARCHAR).addValue("hp", kind == null ? null : path,
                                java.sql.Types.VARCHAR)
                        .addValue("hport", kind == null ? null : port, java.sql.Types.INTEGER)
                        .addValue("hs", kind == null ? null : "liveness", java.sql.Types.VARCHAR)
                        .addValue("now", OffsetDateTime.now()));
        return placement;
    }
}
