package com.solace.sm.cp.persistence;

import com.solace.sm.cp.agent.AgentCertificateAuthority;
import com.solace.sm.cp.agent.AgentRegistry;
import com.solace.sm.cp.api.agent.AgentRegistration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_control.agent_registration} over the runtime credential (plan 015 T2.2). Every statement binds its
 * parameters (I5, I6); every write that is not the token issue is keyed by the agent id the registration lookup returned.
 * The runtime role can UPDATE only the consumption columns and the revocation columns (V021), and DELETE nothing.
 */
public final class JdbcAgentRegistry implements AgentRegistry {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAgentRegistry(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void insert(NewRegistration r) {
        jdbc.update("INSERT INTO sm_control.agent_registration (organization_id, organization_unit_id, environment_id,"
                        + " agent_id, catalog_solution_id, package_version, target_name, allowed_config_root,"
                        + " allowed_container_prefix, enrollment_token_digest, expires_at, created_by, created_at)"
                        + " VALUES (:org, :unit, :env, :agent, :solution, :version, :name, :root, :prefix, :digest,"
                        + " :expires, :createdBy, :createdAt)",
                new MapSqlParameterSource().addValue("org", r.organizationId()).addValue("unit", r.organizationUnitId())
                        .addValue("env", r.environmentId()).addValue("agent", r.agentId())
                        .addValue("solution", r.catalogSolutionId()).addValue("version", r.packageVersion())
                        .addValue("name", r.targetName()).addValue("root", r.allowedConfigRoot())
                        .addValue("prefix", r.allowedContainerPrefix()).addValue("digest", r.tokenDigest())
                        .addValue("expires", Timestamp.from(r.expiresAt())).addValue("createdBy", r.createdBy())
                        .addValue("createdAt", Timestamp.from(r.createdAt())));
    }

    @Override
    public Optional<Enrollment> lockByTokenDigest(byte[] tokenDigest) {
        return jdbc.query("SELECT r.*, s.solution AS solution_key FROM sm_control.agent_registration r"
                        + " JOIN sm_control.catalog_solution s ON s.organization_id = r.organization_id"
                        + " AND s.catalog_solution_id = r.catalog_solution_id"
                        + " WHERE r.enrollment_token_digest = :digest AND r.status = 'active' FOR UPDATE OF r",
                new MapSqlParameterSource("digest", tokenDigest), (rs, row) -> enrollment(rs))
                .stream().findFirst();
    }

    @Override
    public void consume(UUID agentId, byte[] csrDigest, UUID targetId,
            AgentCertificateAuthority.IssuedCertificate issued, Instant usedAt) {
        int changed = jdbc.update("UPDATE sm_control.agent_registration SET enrollment_used_at = :usedAt,"
                        + " enrollment_csr_digest = :csr, certificate_chain_der = :chain, trust_bundle_pem = :trust,"
                        + " valid_from = :from, valid_to = :to, target_id = :target"
                        + " WHERE agent_id = :agent AND enrollment_used_at IS NULL",
                new MapSqlParameterSource().addValue("usedAt", Timestamp.from(usedAt)).addValue("csr", csrDigest)
                        .addValue("chain", issued.chainDer().toArray(byte[][]::new))
                        .addValue("trust", issued.trustBundlePem().toArray(byte[][]::new))
                        .addValue("from", Timestamp.from(issued.validFrom()))
                        .addValue("to", Timestamp.from(issued.validTo())).addValue("target", targetId)
                        .addValue("agent", agentId));
        if (changed != 1) {
            // The row was locked by this transaction, so this is not a race: it is a bug or a revoked-and-reused row.
            throw new IllegalStateException("the enrolment registration could not be consumed");
        }
    }

    @Override
    public List<UUID> organizationsWithAbandonable(Instant expiredBefore) {
        return AgentSweepOrganizations.organizations(jdbc, expiredBefore);
    }

    @Override
    public List<Abandonable> abandonable(UUID organizationId, Instant expiredBefore, Instant afterExpiresAt, UUID afterAgentId,
            int limit) {
        return jdbc.query("SELECT r.agent_id, r.target_id, r.expires_at"
                        + " FROM sm_control.agent_registration r JOIN sm_control.target t"
                        + " ON t.organization_id = r.organization_id AND t.target_id = r.target_id"
                        + " WHERE r.organization_id = :org AND r.enrollment_used_at IS NOT NULL AND t.retired_at IS NULL"
                        + " AND t.transport = 'agent' AND r.expires_at < :cutoff"
                        + " AND (CAST(:afterAt AS timestamptz) IS NULL OR (r.expires_at, r.agent_id) > (CAST(:afterAt AS timestamptz),"
                        + " CAST(:afterAgent AS uuid)))"
                        + " ORDER BY r.expires_at, r.agent_id LIMIT :limit",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("cutoff", Timestamp.from(expiredBefore))
                        .addValue("afterAt", afterExpiresAt == null ? null : Timestamp.from(afterExpiresAt))
                        .addValue("afterAgent", afterAgentId == null ? null : afterAgentId.toString())
                        .addValue("limit", limit),
                (rs, row) -> new Abandonable(rs.getObject("agent_id", UUID.class), rs.getObject("target_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant()));
    }

    @Override
    public boolean revokeIfActive(UUID organizationId, UUID agentId, Instant at) {
        return jdbc.update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = :at,"
                        + " agent_revocation_epoch = agent_revocation_epoch + 1"
                        + " WHERE organization_id = :org AND agent_id = :agent AND status = 'active'",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("agent", agentId)
                        .addValue("at", Timestamp.from(at))) == 1;
    }

    @Override
    public Optional<Registered> find(UUID organizationId, UUID agentId) {
        return jdbc.query("SELECT * FROM sm_control.agent_registration WHERE organization_id = :org AND agent_id = :agent",
                new MapSqlParameterSource("org", organizationId).addValue("agent", agentId), (rs, row) -> {
                    List<byte[]> chain = byteArrays(rs, "certificate_chain_der");
                    Timestamp validTo = rs.getTimestamp("valid_to");
                    return new Registered(registration(rs), chain.isEmpty() ? null : chain.get(0),
                            validTo == null ? null : validTo.toInstant());
                }).stream().findFirst();
    }

    @Override
    public Map<UUID, AgentRegistration> findAll(Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) return Map.of();
        Map<UUID, AgentRegistration> result = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM sm_control.agent_registration WHERE agent_id IN (:agents)",
                new MapSqlParameterSource("agents", agentIds), (rs, row) -> registration(rs))
                .forEach(registration -> result.put(registration.agentId(), registration));
        return result;
    }

    private static final String DETAIL_COLUMNS = "SELECT agent_id, organization_unit_id, environment_id, target_id, target_name,"
            + " status, allowed_config_root, allowed_container_prefix, catalog_solution_id, package_version, capabilities,"
            + " created_at, expires_at, enrollment_used_at, revoked_at FROM sm_control.agent_registration";

    @Override
    public List<Detail> list(UUID organizationId) {
        return jdbc.query(DETAIL_COLUMNS + " WHERE organization_id = :org ORDER BY created_at, agent_id",
                new MapSqlParameterSource("org", organizationId), (rs, row) -> detail(rs));
    }

    @Override
    public Optional<Detail> detail(UUID organizationId, UUID agentId) {
        return jdbc.query(DETAIL_COLUMNS + " WHERE organization_id = :org AND agent_id = :agent",
                new MapSqlParameterSource("org", organizationId).addValue("agent", agentId), (rs, row) -> detail(rs))
                .stream().findFirst();
    }

    private static Detail detail(ResultSet rs) throws SQLException {
        return new Detail(rs.getObject("agent_id", UUID.class), rs.getObject("organization_unit_id", UUID.class),
                rs.getObject("environment_id", UUID.class), rs.getObject("target_id", UUID.class), rs.getString("target_name"),
                rs.getString("status"), rs.getString("allowed_config_root"), rs.getString("allowed_container_prefix"),
                rs.getObject("catalog_solution_id", UUID.class), rs.getString("package_version"), strings(rs, "capabilities"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                instant(rs, "enrollment_used_at"), instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Enrollment enrollment(ResultSet rs) throws SQLException {
        byte[] csr = rs.getBytes("enrollment_csr_digest");
        AgentCertificateAuthority.IssuedCertificate certificate = rs.getArray("certificate_chain_der") == null ? null
                : new AgentCertificateAuthority.IssuedCertificate(byteArrays(rs, "certificate_chain_der"),
                        byteArrays(rs, "trust_bundle_pem"), rs.getTimestamp("valid_from").toInstant(),
                        rs.getTimestamp("valid_to").toInstant());
        return new Enrollment(registration(rs), rs.getObject("organization_id", UUID.class),
                rs.getObject("organization_unit_id", UUID.class), rs.getObject("environment_id", UUID.class),
                rs.getString("target_name"), rs.getString("allowed_config_root"),
                rs.getString("allowed_container_prefix"), rs.getObject("created_by", UUID.class),
                rs.getTimestamp("expires_at").toInstant(), csr, certificate,
                rs.getObject("catalog_solution_id", UUID.class), rs.getString("solution_key"), rs.getString("package_version"));
    }

    private static AgentRegistration registration(ResultSet rs) throws SQLException {
        return new AgentRegistration(rs.getObject("agent_id", UUID.class), rs.getObject("target_id", UUID.class),
                strings(rs, "capabilities"), rs.getLong("agent_revocation_epoch"), rs.getLong("fleet_revocation_epoch"),
                Duration.ofSeconds(rs.getLong("lease_ttl_seconds")), rs.getString("audience"),
                Duration.ofSeconds(rs.getLong("admission_margin_seconds")), "active".equals(rs.getString("status")));
    }

    private static List<String> strings(ResultSet rs, String name) throws SQLException {
        java.sql.Array value = rs.getArray(name);
        if (value == null) return List.of();
        return value.getArray() instanceof String[] values ? Arrays.asList(values) : List.of();
    }

    /** A bytea[] element is bytes. Re-encoding anything else would hand back a different certificate than was issued. */
    static byte[] bytesOf(Object element) {
        if (!(element instanceof byte[] bytes)) {
            throw new IllegalStateException("agent registration holds a non-bytea certificate element");
        }
        return bytes;
    }

    private static List<byte[]> byteArrays(ResultSet rs, String name) throws SQLException {
        java.sql.Array value = rs.getArray(name);
        if (value == null || !(value.getArray() instanceof Object[] values)) return List.of();
        List<byte[]> result = new ArrayList<>();
        for (Object item : values) {
            result.add(bytesOf(item));
        }
        return result;
    }
}
