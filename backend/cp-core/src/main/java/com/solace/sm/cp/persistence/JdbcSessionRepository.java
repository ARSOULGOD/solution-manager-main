package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.identity.SessionRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter for durable sessions. */
public final class JdbcSessionRepository implements SessionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean createForActivePrincipal(Session session, String tokenHash) {
        return jdbc.update("""
                WITH active_principal AS (
                    SELECT id
                     FROM sm_control.principal
                     WHERE id = :principalId
                       AND status = 'active'
                )
                INSERT INTO sm_control.session
                    (session_id, principal_id, token_hash, created_at, last_used_at, idle_expires_at, absolute_expires_at)
                SELECT :sessionId, id, :tokenHash, :createdAt, :lastUsedAt, :idleExpiresAt, :absoluteExpiresAt
                  FROM active_principal
                """,
                new MapSqlParameterSource().addValue("sessionId", session.id().value())
                        .addValue("principalId", session.principal().id()).addValue("tokenHash", tokenHash)
                        .addValue("createdAt", Timestamp.from(session.createdAt()))
                        .addValue("lastUsedAt", Timestamp.from(session.lastUsedAt()))
                        .addValue("idleExpiresAt", Timestamp.from(session.idleExpiresAt()))
                        .addValue("absoluteExpiresAt", Timestamp.from(session.absoluteExpiresAt()))) == 1;
    }

    @Override
    public Optional<Session> resolveAndTouch(String tokenHash, Instant now, Duration idleTimeout) {
        Instant proposedIdleExpiry = now.plus(idleTimeout);
        return jdbc.query("""
                UPDATE sm_control.session s
                   SET last_used_at = :now,
                       idle_expires_at = LEAST(:proposedIdleExpiry, s.absolute_expires_at)
                  FROM sm_control.principal p
                 WHERE s.token_hash = :tokenHash
                   AND s.principal_id = p.id
                   AND p.status = 'active'
                   AND s.revoked_at IS NULL
                   AND s.idle_expires_at > :now
                   AND s.absolute_expires_at > :now
                RETURNING s.session_id, s.principal_id, p.organization_id, p.must_rotate, s.created_at,
                          s.last_used_at, s.idle_expires_at, s.absolute_expires_at
                """, new MapSqlParameterSource().addValue("tokenHash", tokenHash)
                .addValue("now", Timestamp.from(now)).addValue("proposedIdleExpiry", Timestamp.from(proposedIdleExpiry)),
                resultSet -> resultSet.next() ? Optional.of(new Session(
                        new SessionId(resultSet.getObject("session_id", UUID.class)),
                        new PrincipalRef(resultSet.getObject("principal_id", UUID.class)),
                        resultSet.getObject("organization_id", UUID.class), resultSet.getBoolean("must_rotate"),
                        instant(resultSet.getTimestamp("created_at")), instant(resultSet.getTimestamp("last_used_at")),
                        instant(resultSet.getTimestamp("idle_expires_at")),
                        instant(resultSet.getTimestamp("absolute_expires_at")))) : Optional.empty());
    }

    @Override
    public void revoke(SessionId id, Instant now) {
        jdbc.update("UPDATE sm_control.session SET revoked_at = COALESCE(revoked_at, :now) WHERE session_id = :id",
                Map.of("id", id.value(), "now", Timestamp.from(now)));
    }

    @Override
    public void revokeAllFor(PrincipalRef principal, Instant now) {
        jdbc.update("UPDATE sm_control.session SET revoked_at = COALESCE(revoked_at, :now) WHERE principal_id = :id",
                Map.of("id", principal.id(), "now", Timestamp.from(now)));
    }

    @Override
    public void revokeAllExceptFor(PrincipalRef principal, SessionId retainedSession, Instant now) {
        jdbc.update("""
                UPDATE sm_control.session
                   SET revoked_at = COALESCE(revoked_at, :now)
                 WHERE principal_id = :principalId
                   AND session_id <> :retainedSessionId
                """, new MapSqlParameterSource().addValue("principalId", principal.id())
                .addValue("retainedSessionId", retainedSession.value()).addValue("now", Timestamp.from(now)));
    }

    @Override
    public boolean lockActiveFor(SessionId id, PrincipalRef principal, Instant now) {
        return jdbc.query("""
                SELECT s.session_id
                  FROM sm_control.session s
                  JOIN sm_control.principal p ON p.id = s.principal_id
                 WHERE s.session_id = :sessionId
                   AND s.principal_id = :principalId
                   AND p.status = 'active'
                   AND s.revoked_at IS NULL
                   AND s.idle_expires_at > :now
                   AND s.absolute_expires_at > :now
                 FOR UPDATE OF s
                """, new MapSqlParameterSource().addValue("sessionId", id.value())
                .addValue("principalId", principal.id()).addValue("now", Timestamp.from(now)),
                (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) resultSet -> resultSet.next());
    }

    @Override
    public Optional<String> activeTokenHash(SessionId id, Instant now) {
        return jdbc.query("""
                SELECT s.token_hash
                  FROM sm_control.session s
                  JOIN sm_control.principal p ON p.id = s.principal_id
                 WHERE s.session_id = :id
                   AND p.status = 'active'
                   AND s.revoked_at IS NULL
                   AND s.idle_expires_at > :now
                   AND s.absolute_expires_at > :now
                """, new MapSqlParameterSource().addValue("id", id.value()).addValue("now", Timestamp.from(now)),
                resultSet -> resultSet.next() ? Optional.of(resultSet.getString("token_hash")) : Optional.empty());
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }
}
