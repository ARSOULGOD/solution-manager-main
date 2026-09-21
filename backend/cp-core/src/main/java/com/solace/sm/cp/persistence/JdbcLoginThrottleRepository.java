package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.LoginRateLimitPolicy;
import com.solace.sm.cp.identity.LoginThrottleRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL implementation of the configurable local-login failure throttle. */
public final class JdbcLoginThrottleRepository implements LoginThrottleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLoginThrottleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean isLocked(String subjectHash, Instant now) {
        Boolean locked = jdbc.query("""
                SELECT locked_until > :now
                  FROM sm_control.login_throttle
                 WHERE subject_hash = :subjectHash
                """, parameters(subjectHash, now),
                (ResultSetExtractor<Boolean>) resultSet -> resultSet.next() && resultSet.getBoolean(1));
        return Boolean.TRUE.equals(locked);
    }

    @Override
    public void recordFailure(String subjectHash, LoginRateLimitPolicy policy, Instant now) {
        Instant windowStart = now.minus(policy.window());
        Instant lockoutUntil = now.plus(policy.lockout());
        jdbc.update("""
                INSERT INTO sm_control.login_throttle (subject_hash, window_started_at, failure_count, locked_until)
                VALUES (:subjectHash, :now, 1,
                        CASE WHEN :threshold = 1 THEN CAST(:lockoutUntil AS timestamptz) ELSE NULL END)
                ON CONFLICT (subject_hash) DO UPDATE
                   SET window_started_at = CASE
                           WHEN login_throttle.locked_until > :now THEN login_throttle.window_started_at
                           WHEN login_throttle.window_started_at <= :windowStart THEN :now
                           ELSE login_throttle.window_started_at
                       END,
                       failure_count = CASE
                           WHEN login_throttle.locked_until > :now THEN login_throttle.failure_count
                           WHEN login_throttle.window_started_at <= :windowStart THEN 1
                           ELSE login_throttle.failure_count + 1
                       END,
                       locked_until = CASE
                           WHEN login_throttle.locked_until > :now THEN login_throttle.locked_until
                           WHEN login_throttle.window_started_at <= :windowStart
                            AND :threshold = 1 THEN CAST(:lockoutUntil AS timestamptz)
                           WHEN login_throttle.window_started_at <= :windowStart THEN NULL
                           WHEN login_throttle.failure_count + 1 >= :threshold THEN CAST(:lockoutUntil AS timestamptz)
                           ELSE NULL
                       END
                """, parameters(subjectHash, now).addValue("windowStart", Timestamp.from(windowStart))
                .addValue("lockoutUntil", Timestamp.from(lockoutUntil)).addValue("threshold", policy.failureThreshold()));
    }

    @Override
    public void clearFailures(String subjectHash, Instant now) {
        jdbc.update("""
                UPDATE sm_control.login_throttle
                   SET window_started_at = :now,
                       failure_count = 0,
                       locked_until = NULL
                 WHERE subject_hash = :subjectHash
                """, parameters(subjectHash, now));
    }

    private static MapSqlParameterSource parameters(String subjectHash, Instant now) {
        return new MapSqlParameterSource().addValue("subjectHash", subjectHash).addValue("now", Timestamp.from(now));
    }
}
