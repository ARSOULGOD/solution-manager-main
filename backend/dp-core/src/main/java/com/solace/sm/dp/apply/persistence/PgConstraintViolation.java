package com.solace.sm.dp.apply.persistence;

import java.util.Optional;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * A PostgreSQL integrity refusal, carrying the NAME the design's harness rule insists on.
 *
 * <p>Design §3.3: "every refusal is asserted per-statement with its constraint/index/trigger NAME".
 * The engine needs the same discipline at runtime, not only in tests: §3.3's admission classifies a
 * fence 23505 by which fence it names, and §2.2 rule 2 decides whether a 23505 on the intent table
 * warrants a fresh-transaction re-read by whether it names one of the two intent UNIQUEs. Spring's
 * {@code DuplicateKeyException} carries no name at all.
 *
 * <p>Only SQLSTATE class {@code 23} (integrity constraint violation) becomes one of these. A
 * privilege refusal (42501), a serialization failure (40001) or a broken connection is NOT an
 * integrity refusal and must not be reshaped into one — {@link #from} returns empty and the caller
 * rethrows the original.
 *
 * <p>{@link #constraint()} can be null even inside class 23: a {@code 23502} names a COLUMN and no
 * constraint (measured in {@code RefusalShapesIT}). Callers switch on the name and must handle null.
 *
 * <p><b>No driver exception is attached — deliberately, and this is an {@code I6} rule, not a
 * tidiness one.</b> PostgreSQL's error text carries {@code DETAIL: Failing row contains (…)} (and, for
 * a unique violation, {@code Key (…)=(…) already exists}), which reproduces the rejected row — jsonb
 * columns included — and Logback prints the WHOLE cause chain of anything passed to
 * {@code log.error(msg, ex)}. A version of this class that kept the {@code PSQLException} as its cause
 * had a sanitized message and still leaked a sentinel written to a text column through ordinary
 * application logging (measured in {@code AttemptIT}). So this exception is built from four
 * classifiers — SQLSTATE, constraint, table, column — and nothing else: no cause, no driver message.
 * The stack trace is this exception's own (it is raised inside the repository's write), which is where
 * a debugger needs to look anyway; the rejected values are what the caller just tried to write, and
 * are in the caller's hands.
 *
 * <p>What this does NOT cover, stated: {@link #from} reshapes class 23 only. A refusal in any other
 * class — {@code 42501} privilege, {@code 40001} serialization, class {@code 08} connection, and
 * class {@code P0} for the design's PL/pgSQL triggers — propagates from {@code Sql.write} as Spring's
 * {@code DataAccessException} WITH the driver exception attached. PostgreSQL's primary message for
 * those classes names objects (a table, a role, a host, a function), and the design §3.1 triggers echo
 * generations, an {@code apply_id} and a state — identifiers, never row text. A class {@code 22} data
 * exception CAN echo an input value; every column here is bound as a typed parameter (uuid, timestamptz,
 * jsonb from Jackson), so a class-22 refusal would be a repository defect rather than a data path, and
 * no test here claims otherwise.
 */
public final class PgConstraintViolation extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** SQLSTATE class 23 — integrity_constraint_violation and its five members. */
    private static final String INTEGRITY_CLASS = "23";

    private final String sqlState;
    private final String constraint;
    private final String table;
    private final String column;

    private PgConstraintViolation(String sqlState, String constraint, String table, String column) {
        // Classifiers ONLY, and no cause: see the class javadoc for the measured leak that rules out
        // attaching the driver exception.
        super(sqlState + (constraint == null ? "" : " on " + constraint)
                + (table == null ? "" : " in " + table)
                + (column == null ? "" : " (column " + column + ")"));
        this.sqlState = sqlState;
        this.constraint = constraint;
        this.table = table;
        this.column = column;
    }

    public String sqlState() {
        return sqlState;
    }

    /** The constraint/index name PostgreSQL reported, or null (a 23502 reports a column instead). */
    public String constraint() {
        return constraint;
    }

    public String table() {
        return table;
    }

    /** The column, for the refusals that name one — {@code 23502} does, a unique violation does not. */
    public String column() {
        return column;
    }

    /** True when {@link #constraint()} equals any of {@code names}; null-safe. */
    public boolean namesAnyOf(String... names) {
        for (String n : names) {
            if (n.equals(constraint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the cause chain for a {@link PSQLException} and reshapes it — but only for SQLSTATE class
     * {@code 23}.
     *
     * <p>The walk is necessary because the driver exception arrives wrapped: Spring's
     * {@code SQLExceptionSubclassTranslator} produces a {@code DataIntegrityViolationException} whose
     * cause is the {@code PSQLException}. Reading {@code getMessage()} of the wrapper would give the
     * text and lose the structured fields. Only the four structured fields are read; the driver
     * exception is then dropped.
     */
    public static Optional<PgConstraintViolation> from(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException pg) {
                String state = pg.getSQLState();
                if (state == null || !state.startsWith(INTEGRITY_CLASS)) {
                    return Optional.empty();
                }
                ServerErrorMessage m = pg.getServerErrorMessage();
                return Optional.of(new PgConstraintViolation(state,
                        m == null ? null : m.getConstraint(),
                        m == null ? null : m.getTable(),
                        m == null ? null : m.getColumn()));
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return Optional.empty();
    }
}
