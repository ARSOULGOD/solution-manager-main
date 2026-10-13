package com.solace.sm.dp.apply.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.SqlValue;

/**
 * The four mechanical facts every repository in this package repeats, factored out once.
 *
 * <p><b>Time.</b> The model speaks {@link Instant}; the pgjdbc driver does not bind one. Its
 * {@code setObject} handles {@code OffsetDateTime}, {@code LocalDateTime} and friends, and hands an
 * {@code Instant} to its generic object path where it fails to infer a SQL type. So every
 * {@code timestamptz} crosses the seam as an {@code OffsetDateTime} at UTC, converted here rather
 * than in eight call sites.
 *
 * <p><b>Content columns are bound redacted.</b> Spring's {@code StatementCreatorUtils} logs EVERY bind
 * value at TRACE — {@code Setting SQL statement parameter value: column index 7, parameter value
 * [{…}]} (measured in {@code AttemptIT}) — so the moment an operator turns TRACE on to diagnose an
 * incident, every jsonb evidence document would be printed. {@link #redacted} wraps such a value in a
 * {@link SqlValue} whose {@code toString} is a length, not the text: Spring renders the wrapper in its
 * TRACE line and calls {@link SqlValue#setValue} to bind the real string. The control is structural —
 * it does not depend on a logger level anyone can change — and is applied to every jsonb bind in this
 * package. Identifiers, enums, hashes and timestamps stay plain: they ARE what a diagnostician needs.
 *
 * <p><b>Refusal reshaping.</b> {@link #write} is the only place a write is issued: it turns a
 * {@link DataAccessException} whose chain holds a class-23 {@code PSQLException} into a
 * {@link PgConstraintViolation} carrying the constraint NAME, and rethrows everything else
 * untouched. Repositories that hand-rolled this would each be one {@code catch} away from swallowing
 * a privilege error as an integrity one.
 */
final class Sql {

    private Sql() {
    }

    /** Runs a write, reshaping integrity refusals into a named {@link PgConstraintViolation}. */
    static int write(IntSupplierThrowingDataAccess body) {
        try {
            return body.get();
        } catch (DataAccessException e) {
            throw PgConstraintViolation.from(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    /**
     * As {@link #write}, for a statement that returns a value ({@code INSERT ... RETURNING}): integrity refusals
     * are reshaped into a named {@link PgConstraintViolation} and everything else is rethrown untouched.
     */
    static <T> T writeReturning(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (DataAccessException e) {
            throw PgConstraintViolation.from(e).map(RuntimeException.class::cast).orElse(e);
        }
    }

    /**
     * Binds {@code text} so it reaches the statement but never Spring's TRACE bind-value line.
     *
     * @return null for null (so Spring binds SQL NULL exactly as for a plain null), else a wrapper
     */
    static Object redacted(String text) {
        return text == null ? null : new RedactedText(text);
    }

    /** A bound string that renders as its length. Package-private so a test can name the class. */
    static final class RedactedText implements SqlValue {

        private final String text;

        RedactedText(String text) {
            this.text = text;
        }

        @Override
        public void setValue(PreparedStatement ps, int paramIndex) throws SQLException {
            ps.setString(paramIndex, text);
        }

        @Override
        public void cleanup() {
        }

        @Override
        public String toString() {
            return "[redacted text, " + text.length() + " chars]";
        }
    }

    static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    /** A {@code bigint} column that may be SQL NULL — {@code getLong} would silently return 0. */
    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    /** {@code IntSupplier} that may throw Spring's unchecked data-access exceptions. */
    @FunctionalInterface
    interface IntSupplierThrowingDataAccess {
        int get();
    }
}
