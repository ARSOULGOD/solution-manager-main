package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The system-wide guard R8's ruling rests on: restart-only reclaim (no {@code claimed_by}, no
 * lease) is safe rather than merely cheap ONLY because at most one worker pool, system-wide, ever
 * calls {@link ApplyClaimRepository#claimNextAsStarted}. A Java-level singleton — one
 * {@code @Bean} per {@code ApplicationContext} — cannot see a SECOND process; a PostgreSQL
 * session-level advisory lock can, because it lives on the database connection, not in this JVM's
 * heap, and a second process talking to the same database sees the same lock table.
 *
 * <p><b>Lives in this package, not {@code com.solace.sm.dp.apply.worker}</b>, because
 * {@code jdbcIsConfinedToPersistencePackages} (arch-tests) confines every {@code java.sql}/
 * {@code javax.sql} dependency to exactly two packages, this one and {@code cp.persistence} — a
 * session-level advisory lock held over a raw JDBC {@link Connection} is squarely that. The
 * {@code worker} package (the caller) never touches JDBC itself; it holds this class as a plain
 * collaborator.
 *
 * <p><b>Why an advisory lock and not a row lock.</b> Design §3.1 rejects advisory locks for SCOPE
 * serialization because their keys are hashed into one database-wide namespace and collide across
 * tenants (measured). That objection does not apply here: this lock has exactly ONE key, a fixed
 * constant never derived from tenant data, so there is nothing for it to collide with — it names
 * "the apply worker pool", full stop, the same way a well-known port number names a service.
 *
 * <p><b>Session-level, not transaction-level</b> ({@code pg_try_advisory_lock}, not the
 * {@code _xact_} variant): it must outlive any one transaction and be held for the process's whole
 * life, released only when the holding connection closes (or explicitly unlocks).
 *
 * <p><b>MINOR fix (post-review), and a correction to what this javadoc used to claim.</b> The
 * {@code DataSource} this class is built over ({@code ApplyPersistenceConfiguration.workerSingletonGuard})
 * is {@code smDataDataSource} — the SAME pooled {@code HikariDataSource} every repository in this
 * package uses, not a private, non-pooling connection as an earlier version of this paragraph
 * asserted without checking. {@code Connection.close()} on a Hikari-issued connection returns the
 * physical connection to the pool; it does not ask PostgreSQL to release the advisory lock, and
 * Hikari never resets one on borrow. So {@link #release()} now issues {@code pg_advisory_unlock}
 * explicitly, BEFORE the {@code close()} that returns the connection to the pool — the lock is
 * genuinely gone at the database level once {@link #release()} returns, regardless of what happens
 * to the physical connection afterwards. This does not add periodic re-verification: if the
 * connection silently drops while {@link #held}, the guarantee still disappears until the next
 * acquisition attempt notices (a live health check is a larger change, tracked separately, not
 * folded into this fix).
 */
public final class WorkerSingletonGuard {

    /**
     * A fixed, arbitrary key — the ASCII bytes of {@code "SM_APPLY"} read as a signed {@code bigint}.
     * Never derived from tenant, instance or placement data (see the class javadoc).
     */
    static final long LOCK_KEY = 0x534D5F4150504C59L;

    private final DataSource dataSource;
    private Connection held;

    public WorkerSingletonGuard(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Acquires the lock or throws — refusing to start is the point, not a fallback. Idempotent: a
     * second call on a guard that already holds the lock is a no-op (the SAME session re-acquiring
     * its own advisory lock always succeeds in PostgreSQL).
     *
     * @throws IllegalStateException if another session (this process's earlier attempt aside, or
     *                                 — the property this class exists for — a second process) already
     *                                 holds the lock
     */
    public synchronized void acquireOrRefuseToStart() {
        if (held != null) {
            return;
        }
        Connection candidate = null;
        try {
            candidate = dataSource.getConnection();
            boolean acquired;
            try (PreparedStatement ps = candidate.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    acquired = rs.getBoolean(1);
                }
            }
            if (!acquired) {
                Connection toClose = candidate;
                candidate = null;
                toClose.close();
                throw new IllegalStateException("refusing to start a second apply worker pool: the "
                        + "single worker pool this build's crash-safety rests on (R8: restart-only "
                        + "reclaim, no lease) is already held by another process against this "
                        + "database");
            }
            held = candidate;
            candidate = null;
        } catch (SQLException e) {
            throw new IllegalStateException("could not acquire the apply worker's singleton lock", e);
        } finally {
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (SQLException ignored) {
                    // best effort -- the acquisition itself already failed or threw
                }
            }
        }
    }

    /**
     * Releases the lock — explicitly, at the database level, before the connection is returned —
     * and then closes the connection. A no-op if never acquired.
     */
    public synchronized void release() {
        if (held == null) {
            return;
        }
        try {
            try (PreparedStatement ps = held.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, LOCK_KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    // Best effort either way: whether this reports true (unlocked) or false (this
                    // session did not hold it -- should not happen, but is not this method's job to
                    // adjudicate), the connection is closed below regardless, exactly as before this
                    // fix. A failed unlock leaves the session-level lock exactly where it already
                    // was: on a connection this method is about to close either way.
                }
            }
        } catch (SQLException ignored) {
            // best effort: the connection may already be broken, in which case Postgres itself
            // drops the session-level lock when the backend session ends -- closing below still
            // returns (or discards) the JDBC-level connection object either way.
        } finally {
            try {
                held.close();
            } catch (SQLException ignored) {
                // best effort: the process is shutting this down anyway
            } finally {
                held = null;
            }
        }
    }
}
