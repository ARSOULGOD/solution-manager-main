package com.solace.sm.dp.apply.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Executes DDL/DCL scripts statement by statement and <b>collects the {@link SQLWarning}s</b>.
 *
 * <p>The warning capture is the whole reason this exists rather than Spring's {@code ScriptUtils}.
 * Obligation (s) turns on a warning: {@code REVOKE CREATE ON SCHEMA public FROM PUBLIC} is a
 * <i>silent</i> no-op for anyone but the grantor — PostgreSQL raises
 * {@code WARNING: no privileges could be revoked for "public"} and returns success. A harness that
 * only checks for exceptions cannot tell the superuser run from the installer run, which is exactly
 * the discrimination obligation (s)'s negative control demands.
 *
 * <p>These are scripts, not queries: no parameters, no user input, nothing to bind. Every statement
 * the application issues goes through {@code NamedParameterJdbcTemplate} instead ({@code I5}).
 */
final class SqlScript {

    private SqlScript() {
    }

    /**
     * Runs one statement; returns its warnings, oldest first.
     *
     * <p>On failure, the failing statement's text is echoed straight into the exception message —
     * safe ONLY because these are DDL/DCL scripts with no bind parameters (see the class javadoc): a
     * real or sentinel credential must never be routed through {@link #execute} or
     * {@link #executeEach}, or this echo would leak it exactly the way {@code I6} forbids.
     */
    static List<String> execute(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            List<String> warnings = new ArrayList<>();
            for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                warnings.add(w.getMessage());
            }
            return warnings;
        } catch (SQLException e) {
            throw new IllegalStateException("failed: " + sql, e);
        }
    }

    /**
     * Runs every statement of a script on ONE connection, in order, each on its own
     * {@link Statement} so warnings stay attributable to the statement that raised them.
     *
     * <p>Same echo-on-failure posture as {@link #execute}, and the same rule: never route a real or
     * sentinel credential through this method — a failing statement's text lands verbatim in the
     * thrown exception's message.
     */
    static List<SmDataPostgres.StatementOutcome> executeEach(DataSource ds, String script) {
        List<SmDataPostgres.StatementOutcome> outcomes = new ArrayList<>();
        try (Connection c = ds.getConnection()) {
            for (String sql : SqlStatements.of(script)) {
                try (Statement s = c.createStatement()) {
                    s.execute(sql);
                    List<String> warnings = new ArrayList<>();
                    for (SQLWarning w = s.getWarnings(); w != null; w = w.getNextWarning()) {
                        warnings.add(w.getMessage());
                    }
                    outcomes.add(new SmDataPostgres.StatementOutcome(sql, warnings));
                } catch (SQLException e) {
                    throw new IllegalStateException("script statement failed: " + sql, e);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not open a connection for the script", e);
        }
        return outcomes;
    }
}
