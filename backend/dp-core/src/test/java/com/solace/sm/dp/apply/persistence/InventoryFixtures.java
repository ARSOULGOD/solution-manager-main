package com.solace.sm.dp.apply.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The minimal legal inventory rows of {@code V007}, in one place, so an IT that needs "an instance the data plane
 * has declared" says so in one line instead of re-deriving which CHECK wants what (plan 010 T2.2).
 *
 * <p>Every statement runs as {@code sm_dp_runtime} — the credential the application holds — and binds its
 * parameters ({@code I5}). {@link #declare} is IDEMPOTENT (an {@code ON CONFLICT DO NOTHING} on the primary key
 * only), because the engine ITs name an instance from several places and a second declaration must be a no-op, not
 * a failure; a caller that needs a particular lifecycle declares it FIRST.
 */
final class InventoryFixtures {

    private InventoryFixtures() {
    }

    /** As {@link #declare(UUID, UUID)}, for the model's typed ids. */
    static void declare(com.solace.sm.dp.apply.model.TenantId tenant, com.solace.sm.dp.apply.model.InstanceId instance) {
        declare(tenant.value(), instance.value());
    }

    /** Declares the instance a scope names and returns the scope — the one line an admitting IT needs. */
    static com.solace.sm.dp.apply.model.ScopeKey declared(com.solace.sm.dp.apply.model.ScopeKey scope) {
        declare(scope.tenant(), scope.instance());
        return scope;
    }

    /**
     * Declares the instance a scope names AND registers the scope's placement on its own fresh target (plan 008 S4,
     * {@code BE-D48}): after {@code V024} an {@code apply_request} can only name a placement that exists, of its own
     * instance, so an IT that admits a request must register the placement it names. The instance-wide sentinel names
     * no placement, so it registers none. IDEMPOTENT: a second call for the same scope is a no-op, and the placement
     * is never re-registered on a second target.
     */
    static com.solace.sm.dp.apply.model.ScopeKey registered(com.solace.sm.dp.apply.model.ScopeKey scope) {
        registered(scope.tenant().value(), scope.instance().value(), scope.placement().value());
        return scope;
    }

    /** As {@link #registered(com.solace.sm.dp.apply.model.ScopeKey)}, for raw ids. */
    static void registered(UUID tenant, UUID instance, UUID placement) {
        SmDataPostgres.bootstrap();
        registered(SmDataPostgres.runtimeJdbc(), tenant, instance, placement);
    }

    /**
     * As above, over a database the caller names (an upgrade IT's own database): declares the instance if need be, then
     * registers the placement on a fresh target of its own unless the registry already holds it. The sentinel registers
     * nothing.
     */
    static void registered(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID placement) {
        declare(jdbc, tenant, instance, "active");
        if (ApplyRows.INSTANCE_WIDE.equals(placement)) {
            return;
        }
        Integer present = jdbc.queryForObject(
                "SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement), Integer.class);
        if (present != null && present == 0) {
            placement(jdbc, tenant, instance, target(jdbc, tenant), placement);
        }
    }

    /** An {@code active} {@code via-ssh} instance — the only lifecycle admission accepts. */
    static void declare(UUID tenant, UUID instance) {
        declare(tenant, instance, "active");
    }

    /** As {@link #declare(UUID, UUID)}, in the named lifecycle ({@code pending}, {@code active}, {@code abandoned}). */
    static void declare(UUID tenant, UUID instance, String lifecycle) {
        SmDataPostgres.bootstrap();
        declare(SmDataPostgres.runtimeJdbc(), tenant, instance, lifecycle);
    }

    /** As above, over a data source the caller names — for a test that owns its own database or Spring context. */
    static void declare(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, String lifecycle) {
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id,"
                        + " catalog_solution_id, solution, declared_package_version, name, lifecycle,"
                        + " acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, :l, 'via-ssh', :u, :now)"
                        + " ON CONFLICT (tenant_id, instance_id) DO NOTHING",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.nameUUIDFromBytes(("env-" + tenant).getBytes()))
                        .addValue("c", UUID.nameUUIDFromBytes(("catalog-" + tenant).getBytes()))
                        .addValue("n", "instance-" + instance).addValue("l", lifecycle)
                        .addValue("u", UUID.nameUUIDFromBytes(("user-" + tenant).getBytes()))
                        .addValue("now", OffsetDateTime.now()));
    }

    /** A registered target inside the envelope {@code /srv/sm} / {@code sm-}, with a host key no other row has. */
    static UUID target(UUID tenant) {
        SmDataPostgres.bootstrap();
        return target(SmDataPostgres.runtimeJdbc(), tenant);
    }

    /** As {@link #target(UUID)}, over a database the caller names. */
    static UUID target(NamedParameterJdbcTemplate jdbc, UUID tenant) {
        UUID target = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport,"
                        + " host, port, ssh_user, host_key_fingerprint, credential_ref, allowed_config_root,"
                        + " allowed_container_prefix, created_by, created_at)"
                        + " VALUES (:t, :x, 'ssh-docker', 'vm-1.corp.example', 22, 'ec2-user', :fp, :cred,"
                        + " '/srv/sm', 'sm-', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                        .addValue("fp", "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 11))
                        .addValue("cred", UUID.randomUUID()).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        return target;
    }

    /** A live placement of {@code instance} on {@code target}, inside {@link #target}'s envelope, with no probe. */
    static UUID placement(UUID tenant, UUID instance, UUID target) {
        return placement(tenant, instance, target, UUID.randomUUID());
    }

    /** As {@link #placement(UUID, UUID, UUID)}, under the id the caller names (the one an apply request will carry). */
    static UUID placement(UUID tenant, UUID instance, UUID target, UUID placement) {
        SmDataPostgres.bootstrap();
        return placement(SmDataPostgres.runtimeJdbc(), tenant, instance, target, placement);
    }

    /** As above, over a database the caller names. */
    static UUID placement(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, UUID placement) {
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id,"
                        + " target_id, container_name, remote_config_directory, container_config_path, file_name,"
                        + " mechanism, retained_generations, created_at)"
                        + " VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement)
                        .addValue("i", instance).addValue("x", target)
                        .addValue("c", "sm-" + placement).addValue("d", "/srv/sm/" + placement)
                        .addValue("now", OffsetDateTime.now()));
        return placement;
    }

    // ---- plan 015 T2.1b (HF11): an agent's target, and a placement on it ---------------------------------------------

    /**
     * An enrolled agent: its {@code agent} target ({@code V028}) and its {@code agent_identity}, inserted in ONE transaction
     * because each references the other (the target's reference is deferred). Capabilities {@code apply-config} and
     * {@code lifecycle}, both epochs 1, not revoked; the target sits in the envelope {@code /srv/sm} / {@code sm-}. Returns the
     * target id. Run it over a data source the caller owns (the template's own), as the owner or the runtime role.
     */
    static UUID agentTarget(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID agent, String thumbprint) {
        UUID target = UUID.randomUUID();
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id,"
                            + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                            + " VALUES (:t, :x, 'agent', :a, '/srv/sm', 'sm-', :u, :now)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("x", target).addValue("a", agent)
                            .addValue("u", UUID.nameUUIDFromBytes(("issuer-" + tenant).getBytes()))
                            .addValue("now", OffsetDateTime.now()));
            jdbc.update("INSERT INTO sm_data.agent_identity (tenant_id, agent_id, environment_id, catalog_solution_id, solution,"
                            + " declared_package_version, target_id, certificate_thumbprint, agent_epoch, fleet_epoch,"
                            + " capabilities, created_at) VALUES (:t, :a, :e, :c, 'cache', '2.4', :x, :th, 1, 1,"
                            + " ARRAY['apply-config', 'lifecycle'], :now)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("a", agent)
                            .addValue("e", UUID.nameUUIDFromBytes(("env-" + tenant).getBytes()))
                            .addValue("c", UUID.nameUUIDFromBytes(("catalog-" + tenant).getBytes()))
                            .addValue("x", target).addValue("th", thumbprint).addValue("now", OffsetDateTime.now()));
        });
        return target;
    }

    /** As above with a thumbprint of its own. */
    static UUID agentTarget(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID agent) {
        return agentTarget(jdbc, tenant, agent, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64));
    }

    /** A live placement of {@code instance} on the agent's {@code target} (the instance declared and {@code active} first). */
    static UUID agentPlacement(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, UUID placement) {
        declare(jdbc, tenant, instance, "active");
        return placement(jdbc, tenant, instance, target, placement);
    }
}
