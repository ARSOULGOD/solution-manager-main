package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.function.Executable;
import org.postgresql.util.PSQLException;

/**
 * Asserts a refusal <b>by name</b>, per statement.
 *
 * <p>The design's harness rule, measured and stated in §3.3: "every refusal is asserted per-statement
 * with its constraint/index/trigger NAME — a batched psql script mis-attributes errors (the execution
 * review's own first run read clean while an obligation was unproven)". The rule earned itself twice:
 * a {@code 42703} from a broken trigger function was once read as the refusal it was meant to prove,
 * and an {@code op_known} probe was refused by {@code apply_has_generation} instead because the probe
 * row violated both.
 *
 * <p>So: never {@code assertThrows(DataAccessException.class, …)} alone. SQLSTATE always; the
 * constraint name whenever PostgreSQL supplies one.
 */
final class Refusals {

    /** {@code unique_violation} — a UNIQUE constraint or a (partial) unique index. */
    static final String UNIQUE_VIOLATION = "23505";
    /** {@code check_violation}. */
    static final String CHECK_VIOLATION = "23514";
    /** {@code not_null_violation}. */
    static final String NOT_NULL_VIOLATION = "23502";
    /** {@code foreign_key_violation}. */
    static final String FOREIGN_KEY_VIOLATION = "23503";
    /** {@code insufficient_privilege} — the whole DCL half of §3.1 lands here. */
    static final String INSUFFICIENT_PRIVILEGE = "42501";
    /** {@code raise_exception} — every PL/pgSQL {@code RAISE EXCEPTION} in §3.1. */
    static final String RAISE_EXCEPTION = "P0001";

    private Refusals() {
    }

    /**
     * Runs {@code e}, requires it to fail, and checks the PostgreSQL error.
     *
     * @param expectedSqlState          the five-character SQLSTATE, e.g. {@link #UNIQUE_VIOLATION}
     * @param expectedConstraintOrNull  the constraint/index name PostgreSQL must name, or null when
     *                                  the error carries none (privilege errors never do)
     * @return the unwrapped driver exception, so a caller can assert more
     */
    static PSQLException assertRefused(String expectedSqlState, String expectedConstraintOrNull,
            Executable e) {
        return assertRefused(expectedSqlState, expectedConstraintOrNull, e, null);
    }

    /**
     * The same, with a caller-supplied sentence saying WHICH of several probes this one is.
     *
     * @param because prefixed to every failure message; null for none
     */
    static PSQLException assertRefused(String expectedSqlState, String expectedConstraintOrNull,
            Executable e, String because) {
        String prefix = because == null ? "" : because + " — ";
        Throwable thrown = assertThrows(Throwable.class, e, prefix + "the statement must be REFUSED");
        PSQLException pg = unwrap(thrown);
        assertNotNull(pg, prefix + "expected a PSQLException in the cause chain, got: " + thrown);
        assertEquals(expectedSqlState, pg.getSQLState(),
                prefix + "wrong SQLSTATE — the statement failed, but not for the reason under test. "
                        + "Was: " + pg.getMessage());
        if (expectedConstraintOrNull != null) {
            assertNotNull(pg.getServerErrorMessage(), "no server error message to read a name from");
            assertEquals(expectedConstraintOrNull, pg.getServerErrorMessage().getConstraint(),
                    prefix + "wrong constraint name — a refusal that fires for the wrong reason is "
                            + "not a proof. Was: " + pg.getMessage());
        }
        return pg;
    }

    /**
     * The same assertion for a call that went through a repository, which reshapes class-23 refusals
     * into {@link PgConstraintViolation}.
     *
     * <p>Asserts the reshaping too, not only the underlying error: a repository that let a raw
     * {@code DataAccessException} escape would still satisfy {@link #assertRefused}, and the engine's
     * classify-by-name logic would then never see the name it branches on. And asserts the reshaping is
     * COMPLETE — the driver exception is not carried along as a cause — because every repository
     * refusal in this suite passes through here, which makes this one line the standing regression
     * check for the row-echo leak {@code AttemptIT} measures in full.
     *
     * @return the reshaped exception, so a caller can read {@code table()} / {@code column()}
     */
    static PgConstraintViolation assertViolation(String expectedSqlState,
            String expectedConstraintOrNull, Executable e) {
        PgConstraintViolation v = assertThrows(PgConstraintViolation.class, e,
                "the repository must reshape a class-23 refusal into a NAMED PgConstraintViolation");
        assertEquals(expectedSqlState, v.sqlState(),
                "wrong SQLSTATE — the statement failed, but not for the reason under test. Was: "
                        + v.getMessage());
        if (expectedConstraintOrNull != null) {
            assertEquals(expectedConstraintOrNull, v.constraint(),
                    "wrong constraint name — a refusal that fires for the wrong reason is not a "
                            + "proof. Was: " + v.getMessage());
        }
        assertNull(v.getCause(), "no driver exception may cross the persistence boundary as a cause — "
                + "PostgreSQL's DETAIL echoes the rejected row, and Logback prints cause chains (I6)");
        return v;
    }

    /** A PL/pgSQL {@code RAISE EXCEPTION}: no constraint name exists, so the message carries it. */
    static PSQLException assertRaised(String expectedMessageSubstring, Executable e) {
        PSQLException pg = assertRefused(RAISE_EXCEPTION, null, e);
        assertTrue(pg.getMessage().contains(expectedMessageSubstring),
                "the trigger must refuse with its own wording, or a different trigger fired. "
                        + "Expected to contain: " + expectedMessageSubstring + "\nWas: " + pg.getMessage());
        return pg;
    }

    static PSQLException unwrap(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PSQLException pg) {
                return pg;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }
}
