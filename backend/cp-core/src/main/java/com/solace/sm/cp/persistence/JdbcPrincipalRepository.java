package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.PrincipalRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter for principals; a principal is deactivated rather than deleted. */
public final class JdbcPrincipalRepository implements PrincipalRepository {

    private static final String SELECT_BY_ID = """
            SELECT id, organization_id, source, username, credential_hash, status, must_rotate, created_at, deactivated_at
              FROM sm_control.principal
             WHERE id = :id
            """;
    private static final String SELECT_LOCAL_BY_USERNAME = """
            SELECT id, organization_id, source, username, credential_hash, status, must_rotate, created_at, deactivated_at
              FROM sm_control.principal
             WHERE organization_id = :organizationId
               AND lower(username) = lower(:username)
               AND source = 'local'
            """;
    /** A canonical UUID only: {@code UUID.fromString} also accepts shorter, partly-zero-padded spellings. */
    private static final java.util.regex.Pattern CANONICAL_UUID = java.util.regex.Pattern
            .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** An organization's name is 1 to 100 characters (V015's {@code organization_name_shape}). */
    private static final int MAX_ORGANIZATION_NAME = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPrincipalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UUID createLocal(UUID organizationId, String username, String credentialHash, boolean mustRotate) {
        return create(organizationId, Source.LOCAL, username, Objects.requireNonNull(credentialHash, "credential hash"),
                mustRotate);
    }

    @Override
    public UUID createIdp(UUID organizationId, String username) {
        return create(organizationId, Source.IDP, username, null, false);
    }

    @Override
    public Optional<Principal> find(UUID id) {
        return find(SELECT_BY_ID, Map.of("id", id));
    }

    /**
     * ONE statement on every path, whatever the input (W5D-1): a missing, malformed or over-long value still runs the same
     * query (it simply matches nothing), so a login costs the same round trips whether or not the organization resolves.
     * The id wins over a name that happens to spell another organization's id; bind parameters only. A {@code null}
     * value is an OMITTED organization (OR-131 Q3): it resolves to the install's only organization, and to nothing when
     * there are none or several.
     */
    @Override
    public Optional<UUID> resolveOrganization(String idOrName) {
        boolean omitted = idOrName == null;
        UUID id = !omitted && CANONICAL_UUID.matcher(idOrName).matches() ? UUID.fromString(idOrName) : PrincipalRepository.NO_ORGANIZATION;
        String name = omitted || idOrName.length() > MAX_ORGANIZATION_NAME ? "" : idOrName;
        return jdbc.query("""
                SELECT organization_id
                  FROM sm_control.organization
                 WHERE (:omitted AND (SELECT count(*) FROM sm_control.organization) = 1)
                    OR organization_id = :id
                    OR (:name <> '' AND lower(name) = lower(:name))
                 ORDER BY (organization_id = :id) DESC
                 LIMIT 1
                """, new MapSqlParameterSource().addValue("omitted", omitted).addValue("id", id).addValue("name", name),
                resultSet -> resultSet.next()
                        ? Optional.of(resultSet.getObject("organization_id", UUID.class))
                        : Optional.<UUID>empty());
    }

    @Override
    public Optional<Principal> findLocalByUsername(UUID organizationId, String username) {
        return find(SELECT_LOCAL_BY_USERNAME, Map.of(
                "organizationId", Objects.requireNonNull(organizationId, "organization id"),
                "username", Objects.requireNonNull(username, "username")));
    }

    @Override
    public boolean rotateCredential(UUID id, String expectedCredentialHash, String replacementCredentialHash) {
        return jdbc.update("""
                UPDATE sm_control.principal
                   SET credential_hash = :replacementCredentialHash,
                       must_rotate = false
                 WHERE id = :id
                   AND source = 'local'
                   AND status = 'active'
                   AND must_rotate
                   AND credential_hash = :expectedCredentialHash
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("expectedCredentialHash", expectedCredentialHash)
                .addValue("replacementCredentialHash", replacementCredentialHash)) == 1;
    }

    @Override
    public void recordCredentialFailure(UUID id) {
        jdbc.update("""
                UPDATE sm_control.principal
                   SET credential_failure_count = credential_failure_count + 1
                 WHERE id = :id
                   AND source = 'local'
                   AND status = 'active'
                   AND must_rotate
                """, Map.of("id", id));
    }

    @Override
    public void deactivate(UUID id) {
        jdbc.update("UPDATE sm_control.principal SET status = 'deactivated', deactivated_at = now() WHERE id = :id",
                Map.of("id", id));
    }

    private UUID create(UUID organizationId, Source source, String username, String credentialHash, boolean mustRotate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal "
                        + "(id, organization_id, source, username, credential_hash, must_rotate) "
                        + "VALUES (:id, :organizationId, :source, :username, :credentialHash, :mustRotate)",
                new MapSqlParameterSource().addValue("id", id)
                        .addValue("organizationId", Objects.requireNonNull(organizationId, "organization id"))
                        .addValue("source", source.name().toLowerCase(Locale.ROOT))
                        .addValue("username", Objects.requireNonNull(username, "username"))
                        .addValue("credentialHash", credentialHash).addValue("mustRotate", mustRotate));
        return id;
    }

    private Optional<Principal> find(String sql, Map<String, ?> parameters) {
        return jdbc.query(sql, parameters, resultSet -> resultSet.next()
                ? Optional.of(new Principal(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("organization_id", UUID.class),
                        Source.valueOf(resultSet.getString("source").toUpperCase(Locale.ROOT)),
                        resultSet.getString("username"),
                        resultSet.getString("credential_hash"),
                        Status.valueOf(resultSet.getString("status").toUpperCase(Locale.ROOT)),
                        resultSet.getBoolean("must_rotate"),
                        instant(resultSet.getTimestamp("created_at")),
                        instant(resultSet.getTimestamp("deactivated_at"))))
                : Optional.empty());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
