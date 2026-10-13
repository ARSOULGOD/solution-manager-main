package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.target.TargetRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_control.target} over the runtime credential (plan 010 T2.7). Every statement binds its parameters and
 * names {@code organization_id} ({@code I5}); the target's ownership path is read from its canonical scope row, so
 * the path a list is filtered by is always the stored one.
 *
 * <p>The runtime role holds {@code INSERT} and {@code UPDATE (retired_at)} only (V013): nothing here can rewrite a
 * target's name, kind, transport or owner.
 */
public final class JdbcTargetRepository implements TargetRepository {

    private static final String SELECT = """
            SELECT t.target_id, t.name, t.kind, t.transport, t.retired_at, t.connection_id, t.runtime,
                   t.runtime_settings::text AS runtime_settings,
                   s.organization_id, s.organization_unit_id, s.environment_id, s.solution_id, s.instance_id
              FROM sm_control.target t
              JOIN sm_control.scope s ON s.scope_id = t.ownership_scope_id
            """;

    private static final RowMapper<Stored> STORED = JdbcTargetRepository::stored;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcScopeStore scopes;

    public JdbcTargetRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.scopes = new JdbcScopeStore(jdbc);
    }

    @Override
    public List<TargetView> listLive(UUID organizationId) {
        return jdbc.query(SELECT + " WHERE t.organization_id = :org AND t.retired_at IS NULL"
                        + " ORDER BY t.name, t.target_id",
                new MapSqlParameterSource().addValue("org", organizationId), STORED).stream()
                .map(Stored::view).toList();
    }

    @Override
    public Optional<Stored> findByName(UUID organizationId, String name) {
        // V018 frees a retired target's name, so several rows may share it: the live one first (at most one exists),
        // then the newest retired one.
        return jdbc.query(SELECT + " WHERE t.organization_id = :org AND t.name = :name"
                        + " ORDER BY (t.retired_at IS NULL) DESC, t.created_at DESC, t.target_id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name), STORED)
                .stream().findFirst();
    }

    @Override
    public boolean nameHeldByPendingEnrolment(UUID organizationId, String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_control.agent_registration"
                        + " WHERE organization_id = :org AND target_name = :name AND status = 'active'"
                        + " AND enrollment_used_at IS NULL)",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("name", name), Boolean.class));
    }

    @Override
    public boolean activeAgentRegistrationHolds(UUID organizationId, UUID targetId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_control.agent_registration"
                        + " WHERE organization_id = :org AND target_id = :target AND status = 'active')",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("target", targetId), Boolean.class));
    }

    @Override
    public void lockName(UUID organizationId, String name) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))::text",
                new MapSqlParameterSource("key", "target-name/" + organizationId + "/" + name), String.class);
    }

    @Override
    public Optional<Stored> findById(UUID organizationId, UUID targetId) {
        return jdbc.query(SELECT + " WHERE t.organization_id = :org AND t.target_id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", targetId), STORED)
                .stream().findFirst();
    }

    @Override
    public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
            ScopePath ownershipScope, UUID createdBy, Instant createdAt) {
        insert(organizationId, targetId, name, kind, transport, ownershipScope, createdBy, createdAt, null, kind, "{}");
    }

    @Override
    public void insert(UUID organizationId, UUID targetId, String name, String kind, String transport,
            ScopePath ownershipScope, UUID createdBy, Instant createdAt, UUID connectionId, String runtime,
            String runtimeSettingsJson) {
        ScopeId scope = scopes.canonicalize(ownershipScope);
        jdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                        + " transport, connection_id, runtime, runtime_settings, created_by, created_at)"
                        + " VALUES (:id, :org, :scope, :name, :kind, :transport, :connection, :runtime,"
                        + " CAST(:settings AS jsonb), :createdBy, :createdAt)",
                new MapSqlParameterSource().addValue("id", targetId).addValue("org", organizationId)
                        .addValue("scope", scope.value()).addValue("name", name).addValue("kind", kind)
                        .addValue("transport", transport).addValue("connection", connectionId)
                        .addValue("runtime", runtime).addValue("settings", runtimeSettingsJson)
                        .addValue("createdBy", createdBy)
                        .addValue("createdAt", Timestamp.from(createdAt)));
    }

    @Override
    public int markRetired(UUID organizationId, UUID targetId, Instant at) {
        // The organization is in the WHERE (I5); retired_at IS NULL makes a second retirement change nothing.
        return jdbc.update("UPDATE sm_control.target SET retired_at = :at"
                        + " WHERE organization_id = :org AND target_id = :id AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("at", Timestamp.from(at)).addValue("org", organizationId)
                        .addValue("id", targetId));
    }

    @Override
    public int updateMetadata(UUID organizationId, UUID targetId, String name, UUID connectionId, String runtime,
            String runtimeSettingsJson) {
        return jdbc.update("UPDATE sm_control.target SET name = :name, connection_id = :connection, runtime = :runtime,"
                        + " runtime_settings = CAST(:settings AS jsonb)"
                        + " WHERE organization_id = :org AND target_id = :id AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("name", name).addValue("connection", connectionId)
                        .addValue("runtime", runtime).addValue("settings", runtimeSettingsJson)
                        .addValue("org", organizationId).addValue("id", targetId));
    }

    private static Stored stored(ResultSet rs, int row) throws SQLException {
        ScopePath ownership = new ScopePath(
                rs.getObject("organization_id", UUID.class),
                rs.getObject("organization_unit_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("solution_id", UUID.class),
                rs.getObject("instance_id", UUID.class));
        TargetView view = new TargetView(rs.getObject("target_id", UUID.class), rs.getString("name"),
                rs.getString("kind"), rs.getString("transport"), ownership,
                rs.getObject("connection_id", UUID.class), rs.getString("runtime"), rs.getString("runtime_settings"));
        Timestamp retiredAt = rs.getTimestamp("retired_at");
        return new Stored(view, retiredAt == null ? null : retiredAt.toInstant());
    }
}
