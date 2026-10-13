package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code apply_attempt} — the execution journal of §2.2 rule 0, and the only one of the three tables
 * the runtime may DELETE from (§3.3's compaction).
 *
 * <p>{@link #open} and {@link #close} are two statements on purpose: the open row is what
 * "write before act" means, and it must be COMMITTED while the outcome is still unknown. A single
 * insert-with-outcome would journal the past tense of something that had not happened yet.
 *
 * <p>The three {@code I6} columns cross this seam as TYPES, not strings: {@code pre_baseline} and
 * {@code runtime_ref} are serialized by {@link Evidence} here inside the persistence package, and
 * {@code error_class} is an {@link ErrorClass} token. There is no overload that accepts free text —
 * the SHAPE of what reaches the journal is the code's; what an adapter chooses to put inside that
 * shape (a bare identifier-shaped token fits) is the adapter's, under its own sentinel tests.
 */
public final class AttemptRepository {

    private static final String COLUMNS =
            "attempt_id, tenant_id, apply_id, intent_id, step, attempt_no, pre_baseline, started_at,"
                    + " ended_at, step_deadline, outcome, observed_hash, error_class, runtime_ref";

    private static final String READ_COLUMNS =
            "attempt_id, intent_id, step, attempt_no, pre_baseline, started_at, ended_at,"
                    + " step_deadline, outcome, observed_hash, error_class, runtime_ref";

    private final NamedParameterJdbcTemplate jdbc;

    public AttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Journals a try that is ABOUT to happen.
     *
     * @throws IllegalArgumentException if the row already carries an outcome or an end time — the
     *                                  ordering rule is the point of this method
     */
    public void open(TenantId t, ApplyId id, AttemptRow row) {
        if (row.outcome() != null || row.endedAt() != null) {
            throw new IllegalArgumentException(
                    "write-before-act (§2.2 rule 0): an attempt is opened without an outcome; "
                            + "close() records what happened");
        }
        Sql.write(() -> jdbc.update(
                "INSERT INTO sm_data.apply_attempt (" + COLUMNS + ")"
                        + " VALUES (:attempt, :t, :a, :intent, :step, :no,"
                        + " CAST(:baseline AS jsonb), :started, :ended, :deadline, :outcome,"
                        + " :hash, :err, CAST(:runtimeRef AS jsonb))",
                new MapSqlParameterSource()
                        .addValue("attempt", row.attemptId())
                        .addValue("t", t.value())
                        .addValue("a", id.value())
                        .addValue("intent", row.intentId())
                        .addValue("step", row.step().name())
                        .addValue("no", row.attemptNo())
                        .addValue("baseline", Sql.redacted(Evidence.write(row.preBaseline())))
                        .addValue("started", Sql.at(row.startedAt()))
                        .addValue("ended", Sql.at(row.endedAt()))
                        .addValue("deadline", Sql.at(row.stepDeadline()))
                        .addValue("outcome", row.outcome() == null ? null : row.outcome().name())
                        .addValue("hash", row.observedHash())
                        .addValue("err", row.errorClass() == null ? null : row.errorClass().token())
                        .addValue("runtimeRef", Sql.redacted(Evidence.write(row.runtimeRef())))));
    }

    /**
     * Records what the try did.
     *
     * <p><b>Fenced against a second close (R10).</b> The {@code WHERE} clause requires
     * {@code outcome IS NULL}: nothing below stops the runtime credential's plain {@code UPDATE}
     * grant from rewriting an already-closed row (measured: a second {@code close} on a
     * {@code SUCCEEDED} attempt returned 1 row and silently overwrote it with {@code FAILED}), so the
     * fence is this statement's own {@code WHERE}, not the DCL. A caller sees the refusal as
     * {@code close} returning 0 rather than 1 — {@code AttemptService.close} treats that as a failure.
     *
     * <p><b>{@code runtime_ref} survives a reference-less close (R9).</b> {@code coalesce(CAST(
     * :runtimeRef AS jsonb), runtime_ref)}: a {@code close} call with no NEW reference to report
     * ({@code runtimeRef == null}) keeps whatever {@code open} (or an earlier writer) already stored,
     * instead of the plain assignment this method used to make, which set the column to SQL NULL and
     * erased a container id an operator would need to find the logs (measured in Task 3's review). A
     * caller that DOES have a new reference still overwrites, because {@code coalesce} only reaches
     * for the old value when the new one is NULL.
     *
     * <p><b>Consequence, stated rather than left implicit: there is now no way to CLEAR
     * {@code runtime_ref} through this API.</b> {@code coalesce} only ever keeps-or-replaces; a caller
     * that wanted the column back to NULL has no signature here that does it. The risk is zero TODAY
     * because {@code AttemptService.open} builds its row with {@code runtimeRef == null} always — no
     * production path writes a reference at open time, so {@code close} is the only writer this build
     * has, and {@code coalesce} is inert (there is nothing stored yet for it to preserve). R9's fix is
     * pre-emptive: it exists for Plan 005, once an adapter's {@code open()} call starts carrying an
     * initial reference and a later {@code close} might legitimately need to retract one — that plan
     * owns deciding whether "clear" needs its own explicit signature, or whether coalesce's
     * keep-or-replace shape is what it wants too.
     *
     * @param errorClass a CLASSIFIED token, never raw text — the type refuses one ({@code I6})
     * @param runtimeRef adapter-scoped identifiers, serialized here (design §2.3); {@code null} keeps
     *                   whatever is already stored
     * @return rows updated: 1 on success, 0 when the attempt id names no OPEN row of this tenant —
     *         wrong id, wrong tenant, or already closed
     */
    public int close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef, Instant endedAt) {
        return close(t, attemptId, o, observedHash, errorClass, runtimeRef, endedAt, null);
    }

    /**
     * {@link #close(TenantId, UUID, AttemptOutcome, String, ErrorClass, RuntimeRef, Instant)} that also keeps, in the same
     * statement, the container start the host reported when this write was read back ({@code write_baseline}, V020,
     * OR-112 (1)): the same-clock baseline the ungated route to {@code restart-observed} compares a later observed start
     * with. Only a {@code MATERIALIZE} attempt may carry one (the table refuses any other), and only a SUCCEEDED one is
     * given it by the engine; {@code null} leaves the column as it is.
     */
    public int close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef, Instant endedAt, PreBaseline writeBaseline) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_attempt SET outcome = :outcome, observed_hash = :hash,"
                        + " error_class = :err,"
                        + " runtime_ref = coalesce(CAST(:runtimeRef AS jsonb), runtime_ref),"
                        + " write_baseline = coalesce(CAST(:writeBaseline AS jsonb), write_baseline),"
                        + " ended_at = :ended"
                        + " WHERE tenant_id = :t AND attempt_id = :attempt AND outcome IS NULL",
                new MapSqlParameterSource()
                        .addValue("t", t.value()).addValue("attempt", attemptId)
                        .addValue("outcome", o.name()).addValue("hash", observedHash)
                        .addValue("err", errorClass == null ? null : errorClass.token())
                        .addValue("runtimeRef", Sql.redacted(Evidence.write(runtimeRef)))
                        .addValue("writeBaseline", Sql.redacted(Evidence.write(writeBaseline)))
                        .addValue("ended", Sql.at(endedAt))));
    }

    /**
     * Records, on an attempt that found it, which declared paths a retained pre-image differs at ({@code OR-108} (1)).
     * Typed: a {@link ChangedContent} is paths and two flags, so there is no signature here that takes content ({@code I6}).
     * Written once: {@code AND pre_image_change IS NULL} leaves the first record standing, so a replay of the report
     * (a resume that re-assesses) cannot rewrite what the console was already shown. The column is not fenced on
     * {@code outcome}: the engine records it just after closing the attempt that assessed or restored.
     *
     * @return 1 when recorded; 0 when the attempt is not this tenant's, or already carries a record
     */
    public int recordPreImageChange(TenantId t, UUID attemptId, ChangedContent change) {
        return Sql.write(() -> jdbc.update(
                "UPDATE sm_data.apply_attempt SET pre_image_change = CAST(:change AS jsonb)"
                        + " WHERE tenant_id = :t AND attempt_id = :attempt AND pre_image_change IS NULL",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("attempt", attemptId)
                        .addValue("change", Sql.redacted(Evidence.write(change)))));
    }

    /** The newest try of one intent — the resume read's "latest attempt per intent". */
    public Optional<AttemptRow> latest(TenantId t, UUID intentId) {
        return jdbc.query("SELECT " + READ_COLUMNS + " FROM sm_data.apply_attempt"
                        + " WHERE tenant_id = :t AND intent_id = :i"
                        + " ORDER BY attempt_no DESC LIMIT 1",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", intentId),
                MAPPER).stream().findFirst();
    }

    /**
     * Independent of {@link #openState}: several callers ({@code TenantIsolationIT}'s cross-tenant
     * probes, most of {@code AttemptIT}'s own assertions after a {@code service.open}/{@code close})
     * only ever need the total, not whether one of those attempts is open — kept as its own single-
     * column query rather than folded away, so those callers do not pay for (or depend on) the
     * {@code bool_or} their own call sites have no use for. ({@code AttemptRepository} itself still
     * needs {@code bool_or} very much — see {@link #openState} below — this is about {@code count}'s
     * OWN callers, not about this class.)
     */
    public int count(TenantId t, UUID intentId) {
        Integer n = jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_attempt"
                        + " WHERE tenant_id = :t AND intent_id = :i",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", intentId),
                Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * Both facts {@code AttemptService.open} needs before it may insert — the total spent so far
     * ({@code spent}, {@link #count}'s own answer) and whether one of them is still OPEN
     * ({@code hasOpen}) — from ONE statement, so they share ONE MVCC snapshot at READ COMMITTED.
     *
     * <p><b>Why one statement, stated as the defect it closes.</b> A version of this that issued two
     * separate statements — an {@code EXISTS} for {@code hasOpen} and then a {@code count(*)} for
     * {@code spent} — let each take its OWN fresh READ COMMITTED snapshot: measured, on live
     * PostgreSQL 14, a session that read {@code hasOpen = false} from the first statement could then
     * read a row landed by a concurrent committer in the SECOND, so {@code spent} counted an attempt
     * the first statement had just told the same transaction did not exist. At the cap boundary that
     * let a caller reach {@code RetryCapExceeded} while a legitimate attempt was, in fact, still open
     * — exactly the misclassification this method exists to rule out. One {@code SELECT} closes it:
     * every row {@code count(*)} and {@code bool_or} aggregate over is read at the SAME snapshot,
     * because PostgreSQL takes one snapshot per STATEMENT, not per row or per aggregate.
     *
     * <p>{@code coalesce(bool_or(outcome IS NULL), false)}: {@code bool_or} over zero rows is SQL
     * NULL, not {@code false}. That NULL is NOT what makes {@link OpenState#hasOpen} read
     * {@code false} for "no attempts at all" today — {@code rs.getBoolean("has_open")} already maps
     * a SQL NULL column to {@code false} on its own (measured: dropping the {@code coalesce} here
     * leaves every test in this module green). The {@code coalesce} is kept anyway, and is NOT
     * decorative: it is what keeps {@code hasOpen} correct the moment the mapper below ever changes
     * to {@code rs.getObject("has_open", Boolean.class)} (which does NOT coerce a SQL NULL to
     * {@code false} — it returns a null {@code Boolean}, unboxing to a {@code NullPointerException}
     * against this record's primitive {@code boolean}) — a change nothing here rules out, and this
     * comment is what a future editor of that line needs to read first.
     */
    public OpenState openState(TenantId t, UUID intentId) {
        return jdbc.queryForObject(
                "SELECT count(*)::int AS spent, coalesce(bool_or(outcome IS NULL), false) AS has_open"
                        + " FROM sm_data.apply_attempt WHERE tenant_id = :t AND intent_id = :i",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("i", intentId),
                (rs, n) -> new OpenState(rs.getInt("spent"), rs.getBoolean("has_open")));
    }

    /** {@link #openState}'s pair, read from one snapshot — see that method's javadoc for why. */
    public record OpenState(int spent, boolean hasOpen) {
    }

    /** The DELETE half of §3.3's compaction; {@code foldAttemptSummary} is the other half. */
    public int deleteForRequest(TenantId t, ApplyId id) {
        return Sql.write(() -> jdbc.update(
                "DELETE FROM sm_data.apply_attempt WHERE tenant_id = :t AND apply_id = :a",
                new MapSqlParameterSource().addValue("t", t.value()).addValue("a", id.value())));
    }

    private static ErrorClass errorClass(String token) {
        return token == null ? null : new ErrorClass(token);
    }

    private static final RowMapper<AttemptRow> MAPPER = (rs, n) -> {
        String outcome = rs.getString("outcome");
        return new AttemptRow(
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("intent_id", UUID.class),
                Step.valueOf(rs.getString("step")),
                rs.getShort("attempt_no"),
                Evidence.readPreBaseline(rs.getString("pre_baseline")),
                Sql.instant(rs, "started_at"),
                Sql.instant(rs, "ended_at"),
                Sql.instant(rs, "step_deadline"),
                outcome == null ? null : AttemptOutcome.valueOf(outcome),
                rs.getString("observed_hash"),
                errorClass(rs.getString("error_class")),
                Evidence.readRuntimeRef(rs.getString("runtime_ref")));
    };
}
