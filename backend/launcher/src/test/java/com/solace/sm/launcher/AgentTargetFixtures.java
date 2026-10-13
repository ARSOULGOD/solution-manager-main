package com.solace.sm.launcher;

import com.solace.sm.launcher.DeclareFixtures.World;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * An agent target for the endpoint ITs (plan 015 T3.3, T3.5), seeded straight to the database as the owner like every other
 * fixture here: the control plane's governance row (transport {@code agent}), the data plane's connection row (V028: no host, user,
 * pin or credential) and the agent's identity, which each references the other for and so are written in one transaction.
 * Enrolment itself (the token, the CSR, the certificate) is the gateway's and is exercised by {@code AgentEnrollmentTargetIT}.
 */
final class AgentTargetFixtures {

    private AgentTargetFixtures() {
    }

    /** What {@link #seed} wrote. */
    record AgentTarget(UUID targetId, UUID agentId, String name) {
    }

    /** A live agent target owned at the world's first environment, with the envelope {@code /srv/sm} / {@code sm-}. */
    static AgentTarget seed(NamedParameterJdbcTemplate jdbc, World world, String name) {
        UUID target = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID scope = jdbc.queryForObject("SELECT scope_id FROM sm_control.scope WHERE kind = 'environment'"
                + " AND organization_id = :o AND environment_id = :e",
                new MapSqlParameterSource().addValue("o", world.organization()).addValue("e", world.envOne()), UUID.class);
        UUID creator = jdbc.queryForObject("SELECT id FROM sm_control.principal WHERE organization_id = :o AND username = :u",
                new MapSqlParameterSource().addValue("o", world.organization()).addValue("u", DeclareFixtures.ADMIN),
                UUID.class);
        jdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind, transport,"
                        + " created_by, created_at) VALUES (:t, :o, :s, :n, 'docker', 'agent', :c, now())",
                new MapSqlParameterSource().addValue("t", target).addValue("o", world.organization())
                        .addValue("s", scope).addValue("n", name).addValue("c", creator));
        dataPlane(jdbc, world.organization(), target, agent, creator, world.envOne());
        return new AgentTarget(target, agent, name);
    }

    /** The data plane's half only: what a crash between the two planes' writes leaves is the other way round, see the IT. */
    static void dataPlane(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID target, UUID agent, UUID creator, UUID environment) {
        org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        String thumbprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id, allowed_config_root,"
                            + " allowed_container_prefix, created_by, created_at) VALUES (:t, :x, 'agent', :a, '/srv/sm', 'sm-',"
                            + " :c, now())", new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                            .addValue("a", agent).addValue("c", creator));
            jdbc.update("INSERT INTO sm_data.agent_identity (tenant_id, agent_id, environment_id, catalog_solution_id, solution,"
                            + " declared_package_version, target_id, certificate_thumbprint, agent_epoch, fleet_epoch,"
                            + " capabilities, created_at) VALUES (:t, :a, :e, :cs, 'cache', '2.4', :x, :th, 1, 1,"
                            + " ARRAY['apply-config', 'lifecycle'], now())",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("a", agent).addValue("e", environment)
                            .addValue("cs", UUID.randomUUID()).addValue("x", target).addValue("th", thumbprint));
        });
    }

    /** A live placement of {@code instance} on the agent target (only discovery makes one in production, so the test writes it). */
    static UUID placementOn(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID instance, UUID target, String container) {
        UUID placement = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations, created_at)"
                        + " VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("i", instance)
                        .addValue("x", target).addValue("c", container).addValue("d", "/srv/sm/" + container));
        return placement;
    }
}
