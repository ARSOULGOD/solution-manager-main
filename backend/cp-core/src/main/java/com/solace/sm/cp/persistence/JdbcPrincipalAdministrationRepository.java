package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.identity.PrincipalSource;
import com.solace.sm.cp.api.identity.UserView;
import com.solace.sm.cp.identity.PrincipalAdministrationRepository;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@link PrincipalAdministrationRepository} over {@code sm_control} as the runtime credential (plan 013 T3.1). The role
 * holds {@code INSERT} on {@code principal}, {@code UPDATE (status, deactivated_at)}, {@code UPDATE (credential_hash,
 * must_rotate)} and {@code UPDATE (credential_failure_count)}, and no {@code DELETE} (V006 revoked it; V018 did not give
 * it back): a principal is deactivated, never deleted.
 *
 * <p>Every statement binds its parameters ({@code I5}, no interpolation) and names {@code organization_id}. No statement
 * selects {@code credential_hash}: a {@link UserView} has no field for it, and the only place a hash is written is the
 * two writes that take it as a bind parameter.
 */
public final class JdbcPrincipalAdministrationRepository implements PrincipalAdministrationRepository {

    private static final String USERNAME_CONSTRAINT = "principal_org_username_ci_uq";

    private static final String COLUMNS = "id, username, source, status, must_rotate, created_at";

    private static final RowMapper<UserView> VIEW = (rs, row) -> new UserView(
            rs.getObject("id", UUID.class),
            rs.getString("username"),
            PrincipalSource.valueOf(rs.getString("source").toUpperCase(Locale.ROOT)),
            UserView.Status.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
            rs.getBoolean("must_rotate"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPrincipalAdministrationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public UserView insertLocal(UUID id, UUID organizationId, String username, String credentialHash) {
        try {
            return jdbc.queryForObject("INSERT INTO sm_control.principal"
                            + " (id, organization_id, source, username, credential_hash, must_rotate)"
                            + " VALUES (:id, :org, 'local', :username, :hash, true) RETURNING " + COLUMNS,
                    new MapSqlParameterSource().addValue("id", id).addValue("org", organizationId)
                            .addValue("username", username).addValue("hash", credentialHash), VIEW);
        } catch (DuplicateKeyException duplicate) {
            if (USERNAME_CONSTRAINT.equals(constraintOf(duplicate))) {
                // Deliberately NOT chained: the driver's text carries the key (see UsernameTakenException).
                throw new UsernameTakenException();
            }
            throw duplicate;
        }
    }

    @Override
    public List<UserView> list(UUID organizationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_control.principal WHERE organization_id = :org"
                        + " ORDER BY username COLLATE \"C\", id",
                new MapSqlParameterSource().addValue("org", organizationId), VIEW);
    }

    @Override
    public Optional<UserView> find(UUID organizationId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_control.principal WHERE organization_id = :org AND id = :id",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id), VIEW)
                .stream().findFirst();
    }

    @Override
    public Optional<UserView> lock(UUID organizationId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sm_control.principal WHERE organization_id = :org AND id = :id"
                        + " FOR UPDATE",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id), VIEW)
                .stream().findFirst();
    }

    @Override
    public boolean holdsRole(UUID principalId, UUID roleId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_control.role_binding"
                        + " WHERE principal_id = :principal AND role_id = :role)",
                new MapSqlParameterSource().addValue("principal", principalId).addValue("role", roleId),
                Boolean.class));
    }

    @Override
    public boolean deactivate(UUID organizationId, UUID id) {
        return jdbc.update("UPDATE sm_control.principal SET status = 'deactivated', deactivated_at = now()"
                        + " WHERE organization_id = :org AND id = :id AND status = 'active'",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)) == 1;
    }

    @Override
    public boolean resetCredential(UUID organizationId, UUID id, String credentialHash) {
        return jdbc.update("UPDATE sm_control.principal SET credential_hash = :hash, must_rotate = true,"
                        + " credential_failure_count = 0"
                        + " WHERE organization_id = :org AND id = :id AND source = 'local' AND status = 'active'",
                new MapSqlParameterSource().addValue("org", organizationId).addValue("id", id)
                        .addValue("hash", credentialHash)) == 1;
    }

    private static String constraintOf(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                ServerErrorMessage message = psql.getServerErrorMessage();
                return message == null ? null : message.getConstraint();
            }
        }
        return null;
    }
}
