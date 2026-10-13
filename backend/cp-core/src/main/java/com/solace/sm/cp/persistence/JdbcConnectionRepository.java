package com.solace.sm.cp.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.connection.ConnectionConflictException;
import com.solace.sm.cp.api.connection.ConnectionConflictException.Kind;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.authz.ScopeId;
import com.solace.sm.cp.connection.ConnectionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_control.connection} over the runtime credential (plan 013 T2, {@code BE-D66}, {@code OR-130}). Every
 * statement binds its parameters and names {@code organization_id} ({@code I5}); the connection's ownership path is
 * read from its canonical scope row, exactly as {@link JdbcTargetRepository} reads a target's.
 *
 * <p>The runtime role holds {@code INSERT}, {@code DELETE} and {@code UPDATE (name, attributes, secret_status,
 * status, used_by_count, revision, updated_by, updated_at)} only (V021): nothing here can rewrite a connection's
 * type, descriptor version or owner.
 */
public final class JdbcConnectionRepository implements ConnectionRepository {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final String SELECT = """
            SELECT c.connection_id, c.name, c.connection_type, c.descriptor_version, c.attributes::text AS attributes,
                   c.secret_status::text AS secret_status, c.status, c.used_by_count, c.revision, c.created_at,
                   c.updated_at,
                   s.organization_id, s.organization_unit_id, s.environment_id, s.solution_id, s.instance_id
              FROM sm_control.connection c
              JOIN sm_control.scope s ON s.scope_id = c.ownership_scope_id
            """;

    private final RowMapper<ConnectionView> VIEW = this::view;

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcScopeStore scopes;

    public JdbcConnectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.scopes = new JdbcScopeStore(jdbc);
    }

    @Override
    public List<ConnectionView> list(UUID organizationId) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org ORDER BY c.name, c.connection_id",
                new MapSqlParameterSource().addValue("org", organizationId), VIEW);
    }

    @Override
    public Optional<ConnectionView> findById(UUID organizationId, UUID connectionId) {
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.connection_id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", connectionId), VIEW)
                .stream().findFirst();
    }

    @Override
    public Optional<ConnectionView> findByName(UUID organizationId, ScopePath ownershipScope, String name) {
        ScopeId scope = scopes.canonicalize(ownershipScope);
        return jdbc.query(SELECT + " WHERE c.organization_id = :org AND c.ownership_scope_id = :scope"
                        + " AND c.name = :name",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("scope", scope.value())
                        .addValue("name", name), VIEW)
                .stream().findFirst();
    }

    @Override
    public void insert(UUID organizationId, UUID connectionId, String name, String connectionType,
            int descriptorVersion, ScopePath ownershipScope, Map<String, Object> attributes,
            Map<String, Boolean> secretStatus, UUID createdBy, Instant createdAt) {
        ScopeId scope = scopes.canonicalize(ownershipScope);
        try {
            jdbc.update("INSERT INTO sm_control.connection (connection_id, organization_id, ownership_scope_id, name,"
                            + " connection_type, descriptor_version, attributes, secret_status, status, revision,"
                            + " used_by_count, created_by, created_at, updated_by, updated_at)"
                            + " VALUES (:id, :org, :scope, :name, :type, :descriptorVersion, CAST(:attributes AS jsonb),"
                            + " CAST(:secretStatus AS jsonb), 'active', 1, 0, :createdBy, :createdAt, :createdBy,"
                            + " :createdAt)",
                    new MapSqlParameterSource().addValue("id", connectionId).addValue("org", organizationId)
                            .addValue("scope", scope.value()).addValue("name", name).addValue("type", connectionType)
                            .addValue("descriptorVersion", descriptorVersion)
                            .addValue("attributes", writeJson(attributes))
                            .addValue("secretStatus", writeJson(secretStatus))
                            .addValue("createdBy", createdBy).addValue("createdAt", Timestamp.from(createdAt)));
        } catch (DuplicateKeyException lost) {
            throw new ConnectionConflictException(Kind.NAME_TAKEN,
                    "A connection with this name already exists in this scope.");
        }
    }

    @Override
    public boolean update(UUID organizationId, UUID connectionId, long expectedRevision, String name,
            Map<String, Object> attributes, Map<String, Boolean> secretStatus, UUID updatedBy, Instant updatedAt) {
        try {
            return jdbc.update("UPDATE sm_control.connection SET name = :name,"
                            + " attributes = CAST(:attributes AS jsonb), secret_status = CAST(:secretStatus AS jsonb),"
                            + " revision = revision + 1, updated_by = :by, updated_at = :at"
                            + " WHERE organization_id = :org AND connection_id = :id AND revision = :expected",
                    new MapSqlParameterSource().addValue("org", organizationId).addValue("id", connectionId)
                            .addValue("expected", expectedRevision).addValue("name", name)
                            .addValue("attributes", writeJson(attributes))
                            .addValue("secretStatus", writeJson(secretStatus)).addValue("by", updatedBy)
                            .addValue("at", Timestamp.from(updatedAt))) == 1;
        } catch (DuplicateKeyException lost) {
            throw new ConnectionConflictException(Kind.NAME_TAKEN,
                    "A connection with this name already exists in this scope.");
        }
    }

    @Override
    public boolean deactivate(UUID organizationId, UUID connectionId, long expectedRevision, UUID updatedBy,
            Instant updatedAt) {
        return jdbc.update("UPDATE sm_control.connection SET status = 'inactive', revision = revision + 1,"
                        + " updated_by = :by, updated_at = :at"
                        + " WHERE organization_id = :org AND connection_id = :id AND revision = :expected"
                        + " AND status = 'active'",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", connectionId)
                        .addValue("expected", expectedRevision).addValue("by", updatedBy)
                        .addValue("at", Timestamp.from(updatedAt))) == 1;
    }

    @Override
    public int delete(UUID organizationId, UUID connectionId) {
        return jdbc.update("DELETE FROM sm_control.connection WHERE organization_id = :org AND connection_id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", connectionId));
    }

    private ConnectionView view(ResultSet rs, int row) throws SQLException {
        ScopePath ownership = new ScopePath(
                rs.getObject("organization_id", UUID.class),
                rs.getObject("organization_unit_id", UUID.class),
                rs.getObject("environment_id", UUID.class),
                rs.getObject("solution_id", UUID.class),
                rs.getObject("instance_id", UUID.class));
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new ConnectionView(
                rs.getObject("connection_id", UUID.class),
                rs.getString("name"),
                rs.getString("connection_type"),
                rs.getInt("descriptor_version"),
                ownership,
                readJsonObject(rs.getString("attributes")),
                readJsonBooleans(rs.getString("secret_status")),
                "active".equals(rs.getString("status")),
                rs.getInt("used_by_count"),
                rs.getLong("revision"),
                createdAt.toInstant(),
                updatedAt.toInstant());
    }

    private static String writeJson(Map<String, ?> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new IllegalStateException("a connection's attributes/secretStatus map must be JSON-serializable",
                    malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJsonObject(String json) {
        try {
            return (Map<String, Object>) JSON.readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("a stored connection's attributes column is not a JSON object",
                    unreadable);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> readJsonBooleans(String json) {
        try {
            return (Map<String, Boolean>) JSON.readValue(json, Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("a stored connection's secret_status column is not a JSON object",
                    unreadable);
        }
    }
}
