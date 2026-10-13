package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.AttemptRow;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.PreBaseline;
import com.solace.sm.dp.apply.model.RuntimeRef;
import com.solace.sm.dp.apply.model.StepIntentRow;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.persistence.AttemptRepository;
import com.solace.sm.dp.apply.persistence.Constraints;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §2.2 rule 0 — write before act — and rule 4's cap, in the two methods that bracket every adapter
 * call.
 *
 * <p>{@link #open} COMMITS the attempt row and only then may the caller touch the adapter. That
 * ordering is architecture.md §16.1's verbatim obligation, and it is what makes resume readable:
 * an intent with no attempt row provably issued nothing.
 *
 * <p><b>Open-before-cap, read from ONE snapshot.</b> {@link #open} reads
 * {@link AttemptRepository#openState} — {@code hasOpen} AND {@code spent}, together, from a single
 * statement — and checks {@code hasOpen} BEFORE the cap against {@code spent}: an intent that already
 * has an open attempt is always refused as {@link ConcurrentAttempt}, never as
 * {@link RetryCapExceeded}, even when the cap has also been reached. (A prior ordering checked the cap
 * first, so a caller retrying against an intent whose FINAL allowed attempt was still open read "cap
 * exceeded" instead of "someone else is running this try" — which can drive a step loop toward retry
 * exhaustion while a legitimate attempt is still running.) {@link RetryCapExceeded} is raised only when
 * the pre-check finds no open row AND every consumed attempt is closed with the cap spent.
 *
 * <p><b>Why ONE statement, not two.</b> A version of this that read {@code hasOpen} and {@code spent}
 * as two SEPARATE statements let each take its own fresh READ COMMITTED snapshot: measured, on live
 * PostgreSQL 14, a transaction could be told "no open row" by the first statement and then, in the
 * second, count a row a concurrent committer landed in between — reaching {@link RetryCapExceeded}
 * for an intent that, in fact, still had a legitimate attempt open. {@link AttemptRepository#openState}
 * closes this by construction: PostgreSQL takes one snapshot per STATEMENT, so a single {@code SELECT}
 * computing both facts together cannot observe them at two different moments.
 *
 * <p>The pre-check is still advisory, not the guarantee: at READ COMMITTED it cannot see a second
 * opener's row that commits between {@code openState}'s own read and this call's insert (measured).
 * What stops a duplicate open regardless is two independent partial unique indexes ({@code BE-D20}):
 * {@code UNIQUE (intent_id, attempt_no)} refuses a same-number collision — exactly one of two openers
 * lands attempt {@code n + 1}, the other's insert is refused with 23505 on
 * {@code apply_attempt_intent_id_attempt_no_key} — and {@code attempt_open_fence} separately refuses
 * any second OPEN row for the intent regardless of what number the new insert computed, closing the
 * sequential-second-open gap the numbering UNIQUE alone leaves (measured: two commits at different,
 * non-colliding numbers land with no violation under the numbering UNIQUE by itself). Either name can
 * be the one PostgreSQL reports for the loser, and {@link #open} reports it — or, when its OWN
 * pre-check is what caught the conflict before any insert was attempted, reports a refusal that names
 * no PostgreSQL constraint at all (see {@link ConcurrentAttempt#provenance()}) — as
 * {@link ConcurrentAttempt} either way, so a caller can tell "someone else is running this try — do
 * not act" from a refused cap. No attempt number is ever duplicated, no two attempts are ever open at
 * once, and the cap is never breached; the pre-check is the fast, common-case path, the two indexes
 * are the guarantee underneath it.
 *
 * <p>Both methods run in a transaction of their own ({@link EngineTransactions}) — rule 0 is void if
 * the attempt row can roll back with a caller's transaction after {@code open()} has returned.
 *
 * <p><b>{@link #close} is fenced against a second call on the same attempt</b> (an external review
 * finding, R10): {@link AttemptRepository#close} now carries {@code AND outcome IS NULL} in its
 * {@code WHERE} clause, so a re-close reports 0 rows and this method refuses it with
 * {@link IllegalStateException} rather than silently overwriting a recorded outcome — the attempt
 * table is history (§2.2 rule 0), and the runtime credential's grant does not stop a second UPDATE on
 * its own. The same fix (R9) makes a {@code close} with no NEW runtime reference to report
 * ({@code runtimeRef == null}) preserve whatever {@code open} already wrote, rather than erasing it —
 * see {@link AttemptRepository#close}'s {@code coalesce}.
 */
public final class AttemptService {

    private final TransactionTemplate tx;
    private final AttemptRepository attempts;
    private final AttemptPolicy policy;
    private final Clock clock;

    public AttemptService(TransactionTemplate tx, AttemptRepository attempts, AttemptPolicy policy,
            Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.attempts = attempts;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Journals the try that is about to run.
     *
     * @param preBaseline the process/container start baseline — LIFECYCLE only
     *                    ({@code baseline_only_for_lifecycle}); process metadata, never config
     * @param stepBudget  how long this try may take; becomes {@code step_deadline}
     * @throws ConcurrentAttempt when the intent already has an open attempt. Two provenances, told
     *                           apart by {@link ConcurrentAttempt#provenance()}: this call's OWN
     *                           pre-check ({@link AttemptRepository#openState}) found the open row
     *                           before any insert was attempted — {@link
     *                           ConcurrentAttempt.Provenance#PRE_CHECK}, no PostgreSQL constraint
     *                           named — or a concurrent opener landed the row between the pre-check
     *                           and this call's own insert, which the database fence then refused —
     *                           {@link ConcurrentAttempt.Provenance#DATABASE_FENCE}, naming either the
     *                           {@code (intent_id, attempt_no)} UNIQUE (a same-number collision) or
     *                           {@code attempt_open_fence} (a second OPEN row under a different
     *                           number) per {@code BE-D20}
     * @throws RetryCapExceeded  when the pre-check finds no open attempt AND the configured cap is
     *                           already spent — obligation (m)
     */
    public AttemptRow open(TenantId t, ApplyId id, StepIntentRow intent, PreBaseline preBaseline,
            Duration stepBudget) {
        // Captured, not merely swallowed: BE-D20's own classification note states that WHICH of the
        // two fence names fires for a genuine race is measured, not asserted by design — the
        // concurrent-callers tests need this to "measure and record, by name" (design §3.4), exactly
        // as StepIntentService.arbiterReFinds is a measurement hook for the analogous ambiguity. The
        // caller-facing OUTCOME is unchanged either way (ConcurrentAttempt, same control flow). This
        // is only ever set on the DATABASE_FENCE path — the pre-check path throws directly, below,
        // with no name to capture.
        AtomicReference<String> refusedBy = new AtomicReference<>();
        AttemptRow opened = tx.execute(status -> {
            AttemptRepository.OpenState state = attempts.openState(t, intent.intentId());
            if (state.hasOpen()) {
                throw ConcurrentAttempt.preCheck("intent " + intent.intentId() + " already has an "
                        + "open attempt; do not act — resume will read its row");
            }
            if (state.spent() >= policy.maxAttempts()) {
                throw new RetryCapExceeded("intent " + intent.intentId() + " has spent all "
                        + policy.maxAttempts() + " attempts; the exhaustion path is "
                        + "rollbackOrMarkIndeterminate, not another try");
            }
            Instant now = clock.instant();
            AttemptRow row = new AttemptRow(Uuid7.next(), intent.intentId(), intent.step(),
                    (short) (state.spent() + 1), preBaseline, now, null, now.plus(stepBudget),
                    null, null, null, null);
            try {
                attempts.open(t, id, row);
                return row;
            } catch (PgConstraintViolation e) {
                if (e.namesAnyOf(Constraints.ATTEMPT_NO_UNIQUE, Constraints.ATTEMPT_OPEN_FENCE)) {
                    refusedBy.set(e.constraint());
                    status.setRollbackOnly();   // the transaction is aborted by the 23505 anyway
                    return null;
                }
                throw e;
            }
        });
        if (opened == null) {
            throw ConcurrentAttempt.databaseFence("another opener landed the next attempt of intent "
                    + intent.intentId() + " first; do not act — resume will read its row",
                    refusedBy.get());
        }
        return opened;
    }

    /** The newest try of one intent — the attempt half of design §3.1's resume read. */
    public java.util.Optional<AttemptRow> latest(TenantId t, java.util.UUID intentId) {
        return tx.execute(status -> attempts.latest(t, intentId));
    }

    /**
     * Records what the try did. Fails loudly if the attempt id names no row of this tenant — which
     * now includes a row that was already closed (R10): the repository's {@code WHERE} clause fences
     * on {@code outcome IS NULL}, so a second close reports 0 rows here exactly as a wrong tenant or a
     * wrong id would.
     *
     * <p>Both evidence arguments are TYPES ({@code I6}): there is no signature here that accepts a raw
     * error string or a hand-built jsonb document, so a caller cannot hand the journal a message it
     * caught from an adapter.
     */
    public void close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef) {
        close(t, attemptId, o, observedHash, errorClass, runtimeRef, null);
    }

    /**
     * {@link #close(TenantId, UUID, AttemptOutcome, String, ErrorClass, RuntimeRef)} for a {@code MATERIALIZE} attempt that
     * also keeps the container start the host reported at the read-back ({@code writeBaseline}, OR-112 (1)), in the same
     * statement as the close: a succeeded write never lacks it for want of a second call, and no other write has one.
     */
    public void close(TenantId t, UUID attemptId, AttemptOutcome o, String observedHash,
            ErrorClass errorClass, RuntimeRef runtimeRef, PreBaseline writeBaseline) {
        tx.executeWithoutResult(status -> {
            int updated = attempts.close(t, attemptId, o, observedHash, errorClass, runtimeRef,
                    clock.instant(), writeBaseline);
            if (updated != 1) {
                throw new IllegalStateException(
                        "closing attempt " + attemptId + " updated " + updated + " rows");
            }
        });
    }

    /**
     * Keeps, with a closed attempt, the declared paths a changed pre-image differs at ({@code OR-108} (1)); a no-op when
     * the attempt already carries a record. Its own transaction, like {@link #close}.
     */
    public void recordPreImageChange(TenantId t, UUID attemptId, ChangedContent change) {
        tx.executeWithoutResult(status -> attempts.recordPreImageChange(t, attemptId, change));
    }

    /**
     * {@code open()} refused an intent that already has (or, by the time the insert landed, gained) an
     * open attempt. Two distinct provenances, told apart by {@link #provenance()} — never conflate
     * them, because only one of the two ever carries a PostgreSQL constraint name:
     *
     * <ul>
     *   <li>{@link Provenance#PRE_CHECK} — this call's own {@link AttemptRepository#openState}
     *       found the open row BEFORE any insert was attempted. No statement was refused, so no
     *       PostgreSQL constraint fired; {@link #constraintName()} is {@code null}, and returning a
     *       name here would be a fabrication.
     *   <li>{@link Provenance#DATABASE_FENCE} — a concurrent opener's row landed between this call's
     *       pre-check and its own insert, and one of two races ({@code BE-D20}) refused that insert:
     *       the {@code (intent_id, attempt_no)} UNIQUE's same-number collision, or
     *       {@code attempt_open_fence}'s "another attempt for this intent is still open" refusal under
     *       a different, non-colliding number. {@link #constraintName()} names whichever fired.
     * </ul>
     *
     * <p>The caller-facing OUTCOME is identical either way — do not act, resume will read the row that
     * won — so this is a provenance distinction for diagnostics and tests, not a branch a caller must
     * take.
     */
    public static final class ConcurrentAttempt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** Where a {@link ConcurrentAttempt} refusal came from — see the class javadoc. */
        public enum Provenance {
            /** The service's own pre-check found an open row; no insert was ever attempted. */
            PRE_CHECK,
            /** PostgreSQL's own fence refused this call's insert, naming which one. */
            DATABASE_FENCE
        }

        private final Provenance provenance;
        private final String constraintName;

        private ConcurrentAttempt(String message, Provenance provenance, String constraintName) {
            super(message);
            this.provenance = provenance;
            this.constraintName = constraintName;
        }

        /** {@link Provenance#PRE_CHECK}: refused before any insert was attempted. */
        static ConcurrentAttempt preCheck(String message) {
            return new ConcurrentAttempt(message, Provenance.PRE_CHECK, null);
        }

        /**
         * {@link Provenance#DATABASE_FENCE}: PostgreSQL refused the insert, naming
         * {@code constraintName} — never {@code null} on this path; a database fence that fired
         * without a name to report would itself be the bug.
         */
        static ConcurrentAttempt databaseFence(String message, String constraintName) {
            return new ConcurrentAttempt(message, Provenance.DATABASE_FENCE,
                    Objects.requireNonNull(constraintName,
                            "a database-fence refusal must always carry the name PostgreSQL reported"));
        }

        /** Never {@code null}: which of the two provenances above produced this refusal. */
        public Provenance provenance() {
            return provenance;
        }

        /**
         * Which of the two fences ({@link Constraints#ATTEMPT_NO_UNIQUE} or
         * {@link Constraints#ATTEMPT_OPEN_FENCE}) PostgreSQL actually named for the loser, when
         * {@link #provenance()} is {@link Provenance#DATABASE_FENCE} — a measurement hook, exactly as
         * {@code StepIntentService.arbiterReFinds} is for the analogous ambiguity (design §3.4's
         * classification note): design deliberately does not assert which name a genuine same-number
         * race reports, only measures it. {@code null} when {@link #provenance()} is
         * {@link Provenance#PRE_CHECK}: this call refused before issuing any insert, so PostgreSQL
         * named nothing, and this must never return a fabricated or guessed name in that case. The
         * caller-facing control flow (this exception, unchanged) is identical regardless of provenance
         * or, within {@code DATABASE_FENCE}, of which name this returns.
         */
        public String constraintName() {
            return constraintName;
        }
    }

    /** Obligation (m)'s engine half: the cap is configuration, so the database cannot hold it. */
    public static final class RetryCapExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RetryCapExceeded(String message) {
            super(message);
        }
    }
}
