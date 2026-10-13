package com.solace.sm.launcher;

import com.solace.sm.dp.apply.model.ScopeKey;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Declares the instance an IT is about to admit against (plan 010 T2.2): admission refuses an instance the data
 * plane has not declared and held {@code active}, and an {@code apply_scope} row may only exist for one.
 *
 * <p>Written as the owner/superuser handle the provisioned database already hands these ITs
 * ({@link TwoSchemaTestDatabase#ownerJdbc}) — the ITs seed state around the application, not through it. Idempotent
 * ({@code ON CONFLICT DO NOTHING} on the primary key), so a seed and a later admission may both name the instance.
 * {@code state_version} is stamped by the table's trigger; nothing here sets it. The helper stays in the launcher's
 * own test tree on purpose: {@code TwoSchemaTestDatabase} belongs to other tasks.
 */
final class DeclaredInstances {

    private DeclaredInstances() {
    }

    static void declare(TwoSchemaTestDatabase db, ScopeKey scope) {
        declare(db, scope.tenant().value(), scope.instance().value());
    }

    static void declare(TwoSchemaTestDatabase db, UUID tenant, UUID instance) {
        db.ownerJdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id,"
                        + " catalog_solution_id, solution, declared_package_version, name, lifecycle,"
                        + " acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, 'active', 'via-ssh', :u, :now)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.nameUUIDFromBytes(("env-" + tenant).getBytes()))
                        .addValue("c", UUID.nameUUIDFromBytes(("catalog-" + tenant).getBytes()))
                        .addValue("n", "instance-" + instance)
                        .addValue("u", UUID.nameUUIDFromBytes(("user-" + tenant).getBytes()))
                        .addValue("now", OffsetDateTime.now()));
    }

    /**
     * Declares the instance AND registers the scope's placement (plan 008 S4, {@code BE-D48}): after {@code V024} an
     * {@code apply_request} can only name a placement the registry holds for its own instance, so an IT that admits or
     * seeds a request must register the one it names. The sentinel registers none.
     */
    static void registered(TwoSchemaTestDatabase db, ScopeKey scope) {
        registered(db, scope.tenant().value(), scope.instance().value(), scope.placement().value());
    }

    static void registered(TwoSchemaTestDatabase db, UUID tenant, UUID instance, UUID placement) {
        declare(db, tenant, instance);
        registerPlacement(db.ownerJdbc, tenant, instance, placement);
    }

    /**
     * Registers {@code placement} for an instance that already exists, on a fresh target of its own (container name,
     * config directory and fingerprint all unique per placement). IDEMPOTENT; the instance-wide sentinel registers none.
     */
    static void registerPlacement(NamedParameterJdbcTemplate owner, UUID tenant, UUID instance, UUID placement) {
        if (new UUID(0L, 0L).equals(placement)) {
            return;
        }
        Integer present = owner.queryForObject("SELECT count(*)::int FROM sm_data.placement"
                + " WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement), Integer.class);
        if (present != null && present > 0) {
            return;
        }
        UUID target = UUID.randomUUID();
        owner.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at)"
                        + " VALUES (:t, :x, 'ssh-docker', 'vm-1.corp.example', 22, 'ec2-user', :fp, :cred, '/srv/sm',"
                        + " 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        owner.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at)"
                        + " VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                        .addValue("x", target).addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("now", OffsetDateTime.now()));
    }
}
