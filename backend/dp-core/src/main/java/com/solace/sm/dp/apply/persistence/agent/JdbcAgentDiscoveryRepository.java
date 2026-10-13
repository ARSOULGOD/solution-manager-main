package com.solace.sm.dp.apply.persistence.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.agent_discovery} and the few reads the discovery intake makes beside it (plan 015 T4.1, {@code AGW-D30}). The
 * table is this class's own: {@code T2.1b} shipped only the stub. Every method takes the tenant and binds it ({@code I5}); the
 * tenant and agent a caller passes were derived by the data plane from the session, never taken from a report. None of them opens
 * a transaction: the caller's ({@code AgentDiscoveryService}) holds the session's {@code FOR SHARE} lock to its end, so a
 * tombstone and the instance it names commit together.
 *
 * <p><b>Terminal states are final</b> ({@code agent_discovery_terminal_is_final}, V028; V031 adds the one release move). The
 * state is changed by exactly these methods, and each states the only state it may start from, so a re-report can never reopen a
 * tombstone. The writes an undeclare, a placement retirement and the {@code BE-D32} sweep cause are V031's trigger on
 * {@code placement.retired_at}, not a method here: they commit with the retirement whoever retires the placement.
 */
public final class JdbcAgentDiscoveryRepository {

    /** What the data plane recorded at enrolment: the environment, the solution and the envelope the agent's instances go into. */
    public record Enrolment(UUID environmentId, UUID catalogSolutionId, String solution, String packageVersion, UUID targetId,
            String allowedConfigRoot, String allowedContainerPrefix, UUID issuedBy) {
    }

    /** One tombstone row. */
    public record Row(String containerId, String state, UUID instanceId, UUID placementId, String refusalCode, Instant firstSeenAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentDiscoveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    /** The agent's enrolment record, joined to its target's envelope and the principal who issued the token. */
    public Optional<Enrolment> enrolment(UUID tenant, UUID agent) {
        return jdbc.query("SELECT i.environment_id, i.catalog_solution_id, i.solution, i.declared_package_version, i.target_id,"
                + " t.allowed_config_root, t.allowed_container_prefix, t.created_by"
                + " FROM sm_data.agent_identity i JOIN sm_data.target_connection t"
                + " ON t.tenant_id = i.tenant_id AND t.target_id = i.target_id"
                + " WHERE i.tenant_id = :tenant AND i.agent_id = :agent AND t.retired_at IS NULL",
                new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", agent),
                (rs, n) -> new Enrolment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, UUID.class)))
                .stream().findFirst();
    }

    /** The key's row, locked: two reports of one key serialize here, and the second finds what the first wrote. */
    public Optional<Row> find(UUID tenant, UUID agent, String label, String containerName) {
        return jdbc.query("SELECT container_id, state, instance_id, placement_id, refusal_code, first_seen_at FROM sm_data.agent_discovery"
                + " WHERE tenant_id = :tenant AND agent_id = :agent AND instance_label = :label AND container_name = :name"
                + " FOR UPDATE", key(tenant, agent, label, containerName),
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), AgentSql.instant(rs, "first_seen_at"))).stream().findFirst();
    }

    /** Live (not retired) placements on the agent's target: what the per-target cap counts. */
    public int livePlacements(UUID tenant, UUID targetId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM sm_data.placement WHERE tenant_id = :tenant AND target_id = :target"
                + " AND retired_at IS NULL", new MapSqlParameterSource().addValue("tenant", tenant).addValue("target", targetId),
                Integer.class);
        return n == null ? 0 : n;
    }

    public boolean containerNameLive(UUID tenant, UUID targetId, String containerName) {
        return exists("SELECT 1 FROM sm_data.placement WHERE tenant_id = :tenant AND target_id = :target"
                + " AND container_name = :v AND retired_at IS NULL", tenant, targetId, containerName);
    }

    public boolean configDirectoryLive(UUID tenant, UUID targetId, String directory) {
        return exists("SELECT 1 FROM sm_data.placement WHERE tenant_id = :tenant AND target_id = :target"
                + " AND remote_config_directory = :v AND retired_at IS NULL", tenant, targetId, directory);
    }

    public boolean healthPortLive(UUID tenant, UUID targetId, int hostPort) {
        return exists("SELECT 1 FROM sm_data.placement WHERE tenant_id = :tenant AND target_id = :target"
                + " AND health_port = :v AND retired_at IS NULL", tenant, targetId, hostPort);
    }

    /** V023's {@code instance_name_once}: a name is held by every instance of the environment that is not abandoned or retired. */
    public boolean instanceNameLive(UUID tenant, UUID environmentId, String name) {
        return exists("SELECT 1 FROM sm_data.instance_state WHERE tenant_id = :tenant AND environment_id = :target"
                + " AND name = :v AND lifecycle NOT IN ('abandoned', 'retired')", tenant, environmentId, name);
    }

    private boolean exists(String sql, UUID tenant, UUID second, Object value) {
        return !jdbc.queryForList(sql, new MapSqlParameterSource().addValue("tenant", tenant).addValue("target", second)
                .addValue("v", value), Integer.class).isEmpty();
    }

    // ---- writes ---------------------------------------------------------------------------------------------------

    /** A new key, PENDING, naming the instance and placement just reserved. */
    public void insertPending(UUID tenant, UUID agent, String label, String containerName, String containerId, UUID instanceId,
            UUID placementId, Instant now) {
        AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_discovery (tenant_id, agent_id, instance_label, container_name,"
                + " container_id, state, instance_id, placement_id, first_seen_at) VALUES (:tenant, :agent, :label, :name, :id,"
                + " 'PENDING', :instance, :placement, :now)", key(tenant, agent, label, containerName)
                .addValue("id", containerId).addValue("instance", instanceId).addValue("placement", placementId)
                .addValue("now", AgentSql.at(now))));
    }

    /** A new key refused at once: a tombstone, so a re-report answers it and mints nothing. */
    public void insertRefused(UUID tenant, UUID agent, String label, String containerName, String containerId, String code,
            Instant now) {
        AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_discovery (tenant_id, agent_id, instance_label, container_name,"
                + " container_id, state, refusal_code, first_seen_at, settled_at) VALUES (:tenant, :agent, :label, :name, :id,"
                + " 'REFUSED', :code, :now, :now)", key(tenant, agent, label, containerName)
                .addValue("id", containerId).addValue("code", code).addValue("now", AgentSql.at(now))));
    }

    /**
     * The release (V031): a REFUSED or ABANDONED key whose container was re-created is PENDING again, naming the instance and
     * placement just reserved. Guarded by the state it starts from and by a container id that differs.
     */
    public boolean release(UUID tenant, UUID agent, String label, String containerName, String newContainerId, UUID instanceId,
            UUID placementId, Instant now) {
        return AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_discovery SET state = 'PENDING', container_id = :id,"
                + " instance_id = :instance, placement_id = :placement, refusal_code = NULL, settled_at = NULL, first_seen_at = :now"
                + " WHERE tenant_id = :tenant AND agent_id = :agent AND instance_label = :label AND container_name = :name"
                + " AND state IN ('REFUSED', 'ABANDONED') AND container_id <> :id",
                key(tenant, agent, label, containerName).addValue("id", newContainerId).addValue("instance", instanceId)
                        .addValue("placement", placementId).addValue("now", AgentSql.at(now)))) == 1;
    }

    /**
     * A refused retry: the key stays terminal and the container under it is the one just refused. A REFUSED key records the new
     * code; an ABANDONED one has no code (its CHECK) and records only the container. So the report that re-refuses a released ABANDONED
     * key answers REFUSED with its code, while the NEXT report of the same container answers from the row: ABANDONED, no code (stated,
     * not a defect: both are terminal and both mint nothing).
     */
    public void refuseAgain(UUID tenant, UUID agent, String label, String containerName, String newContainerId, String code,
            Instant now) {
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_discovery SET container_id = :id,"
                + " refusal_code = CASE WHEN state = 'REFUSED' THEN :code ELSE refusal_code END,"
                + " settled_at = CASE WHEN state = 'REFUSED' THEN :now ELSE settled_at END"
                + " WHERE tenant_id = :tenant AND agent_id = :agent AND instance_label = :label"
                + " AND container_name = :name AND state IN ('REFUSED', 'ABANDONED')", key(tenant, agent, label, containerName)
                .addValue("id", newContainerId).addValue("code", code).addValue("now", AgentSql.at(now))));
    }

    /** A re-created container of a key that is registered or pending keeps its row and placement: only the id it is recorded under moves. */
    public void recordContainerId(UUID tenant, UUID agent, String label, String containerName, String containerId) {
        AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_discovery SET container_id = :id WHERE tenant_id = :tenant"
                + " AND agent_id = :agent AND instance_label = :label AND container_name = :name"
                + " AND state IN ('PENDING', 'REGISTERED') AND container_id <> :id", key(tenant, agent, label, containerName)
                .addValue("id", containerId)));
    }

    /** PENDING to REGISTERED, for the row that names this instance. @return whether a PENDING row moved */
    public boolean registered(UUID tenant, UUID agent, UUID instanceId, Instant now) {
        return AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_discovery SET state = 'REGISTERED', settled_at = :now"
                + " WHERE tenant_id = :tenant AND agent_id = :agent AND instance_id = :instance AND state = 'PENDING'",
                new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", agent).addValue("instance", instanceId)
                        .addValue("now", AgentSql.at(now)))) == 1;
    }

    /** PENDING to REFUSED with a code, for the row that names this instance. @return whether a PENDING row moved */
    public boolean refused(UUID tenant, UUID agent, UUID instanceId, String code, Instant now) {
        return AgentSql.write(() -> jdbc.update("UPDATE sm_data.agent_discovery SET state = 'REFUSED', refusal_code = :code,"
                + " settled_at = :now WHERE tenant_id = :tenant AND agent_id = :agent AND instance_id = :instance"
                + " AND state = 'PENDING'", new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", agent)
                .addValue("instance", instanceId).addValue("code", code).addValue("now", AgentSql.at(now)))) == 1;
    }

    /**
     * The instance's lifecycle, read under {@code FOR NO KEY UPDATE}: the FIRST lock of every writer that ends or settles an agent
     * instance (the sweep and undeclare take the instance, then its placements, then this table's row; so must the completion's
     * settlement, or two of them deadlock). Empty when the tenant has no such instance.
     */
    public Optional<String> lockInstance(UUID tenant, UUID instanceId) {
        return jdbc.query("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :tenant AND instance_id = :instance"
                + " FOR NO KEY UPDATE", new MapSqlParameterSource().addValue("tenant", tenant).addValue("instance", instanceId),
                (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    /** The current state of the row naming this instance, if there is one. */
    public Optional<Row> byInstance(UUID tenant, UUID agent, UUID instanceId) {
        return jdbc.query("SELECT container_id, state, instance_id, placement_id, refusal_code, first_seen_at FROM sm_data.agent_discovery"
                + " WHERE tenant_id = :tenant AND agent_id = :agent AND instance_id = :instance",
                new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", agent).addValue("instance", instanceId),
                (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), AgentSql.instant(rs, "first_seen_at"))).stream().findFirst();
    }

    private static MapSqlParameterSource key(UUID tenant, UUID agent, String label, String containerName) {
        return new MapSqlParameterSource().addValue("tenant", tenant).addValue("agent", agent).addValue("label", label)
                .addValue("name", containerName);
    }
}
