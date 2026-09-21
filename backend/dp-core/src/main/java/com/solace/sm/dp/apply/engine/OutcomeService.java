package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionEvidence;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.RestartResolutionEvidence;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * State walks on {@code apply_request}, each with its watermark maintenance in the SAME transaction —
 * design §2.2 rule 1: "the same transaction that lands {@code STAGED}/{@code APPLIED}/resolved-applied
 * updates the corresponding watermark".
 *
 * <p><b>Two enforcers, on purpose.</b> The Java state machine below refuses an illegal walk with an
 * {@link IllegalStateException} and a sentence; the {@code apply_request_terminal_guard} trigger
 * refuses it with P0001. The Java half exists so a bug is diagnosable, the SQL half so a bug is
 * impossible — {@code OutcomeTransitionsIT} proves both fire, because a Java-only guard is one
 * refactor from not existing and a SQL-only guard produces a stack trace nobody can read.
 *
 * <p>The read that classifies the current state is inside the same transaction as the write, so
 * "check then act" is not a race: the row is read and updated under one snapshot, and the UPDATE
 * carries {@code WHERE state = :from} as the compare-and-set anyway.
 *
 * <p><b>Every state-changing operation on a scope takes that scope's {@code apply_scope} row lock,
 * explicitly, first.</b> {@link #start}, {@link #land} and {@link #resolveIndeterminate} all call
 * {@code lockScope} immediately after reading the request and before writing anything, so admission
 * and landing serialize on the same row by design — one mechanism, named at both ends, rather than
 * two paths that happen to collide. The row always exists to be locked: {@code request_has_scope} is a
 * FOREIGN KEY, so a request cannot exist without it.
 *
 * <p>The external review's finding 3 is why the sentence above is not an embellishment. The previous
 * account rested on the watermark raise conflicting with {@code admit}'s {@code SELECT … FOR UPDATE}:
 * an instance-wide raise UPDATEs the scope row, and a placement raise inserts a
 * {@code placement_watermark} row whose FOREIGN KEY takes {@code FOR KEY SHARE} on it. Measured with
 * {@code pgrowlocks}: that holds for the INSERT branch of the upsert only. When the placement already
 * has a watermark row the statement takes the {@code DO UPDATE} branch, no FK check runs against the
 * parent, and NO lock is taken on {@code apply_scope} at all — so the serialization the design claimed
 * held for a placement's FIRST landing and quietly stopped afterwards. The explicit lock closes that;
 * the FK's {@code KEY SHARE} on the insert branch is now incidental and no longer load-bearing.
 *
 * <p>The {@code blocksScope()} backstop stays stated, and stays true: every request whose landing
 * raises a watermark is in a {@code blocksScope()} state until that landing commits, so even an
 * unlocked admission would read the in-flight row and refuse {@code SCOPE_BUSY}. It is the reason a
 * lock-ordering mistake here is a performance question rather than a correctness one. A change to which
 * states {@code land} accepts as SOURCE, or to {@code RequestState.blocksScope()}, must re-check that
 * sentence. (Monitoring note: the wait surfaces as {@code wait_event = 'transactionid'}, not a
 * {@code tuple} lock.)
 */
public final class OutcomeService {

    private final TransactionTemplate tx;
    private final ApplyScopeRepository scopes;
    private final ApplyRequestRepository requests;
    private final Clock clock;

    public OutcomeService(TransactionTemplate tx, ApplyScopeRepository scopes,
            ApplyRequestRepository requests, Clock clock) {
        this.tx = EngineTransactions.requiresNew(tx);
        this.scopes = scopes;
        this.requests = requests;
        this.clock = clock;
    }

    /**
     * Every request of this tenant that resume has to think about — {@code REQUESTED},
     * {@code RUNNING} and {@code APPLY_INDETERMINATE}, which is the set that blocks a scope.
     */
    public java.util.List<ApplyRequestRow> nonTerminal(TenantId t) {
        return tx.execute(status -> requests.nonTerminal(t));
    }

    /**
     * The request's state as the database currently holds it — a read, taking no lock and changing
     * nothing.
     *
     * <p>Exists for one caller and one reason: {@code ApplyExecutor} re-reads before entering the
     * {@code BE-D22} exhaustion journal, because {@link StepIntentService#ensureIntent} takes no
     * {@code apply_scope} row lock and a concurrent {@link #land} can therefore commit a terminal
     * state underneath a step loop already in flight. {@code EnsureIntentRaceIT} measures that
     * window rather than arguing about it. The alternative — giving {@code ensureIntent} the scope
     * lock — would widen plan 004's admission lock to cover every step of every apply, serializing
     * unrelated instances behind one scope row.
     */
    public RequestState stateOf(TenantId t, ApplyId id) {
        return tx.execute(status -> require(t, id).state());
    }

    /** {@code REQUESTED → RUNNING}, under the scope lock like every other state change here. */
    public void start(TenantId t, ApplyId id) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            if (row.state() != RequestState.REQUESTED) {
                throw new IllegalStateException(
                        "start() walks REQUESTED → RUNNING; " + id.value() + " is " + row.state());
            }
            apply(requests.transition(t, id, RequestState.REQUESTED, RequestState.RUNNING, null),
                    id, RequestState.REQUESTED, RequestState.RUNNING);
        });
    }

    /**
     * Lands a request on a terminal state — or on {@code APPLY_INDETERMINATE}, which is a landing
     * place without being terminal (§3.2: it BLOCKS the scope until an operator resolves it).
     *
     * <p>{@code APPLY_INDETERMINATE} is also a legal SOURCE, and that is design §3.4's agent-replay
     * path: the {@code host-local} flavour resolves deterministically by journal replay and the DP
     * records a DIRECT transition to {@code APPLIED}/{@code FAILED_*}/{@code SUPERSEDED} with all four
     * resolution columns left NULL, because no operator confirmed anything.
     *
     * <p>{@code INDETERMINATE_RESOLVED} is NOT reachable here — {@link #resolveIndeterminate} is the
     * only path to it, because it is the only one that writes the four evidence columns
     * {@code resolution_is_evidence} demands together. And the target must fit the request's operation:
     * {@link RequestState#validFor} mirrors {@code state_fits_operation}, so landing an {@code apply}
     * on {@code STOPPED} is refused in Java with the rule named, before any write, instead of surfacing
     * as a 23514 out of a repository.
     *
     * <p><b>{@code BE-D16}: {@code STAGED} implies {@code strategy = MANUAL}, with no exception.</b>
     * {@code RequestState.validFor} cannot express this — it receives no strategy — so the guard lives
     * here, beside the {@code state_fits_operation} mirror above it, reading {@code row.strategy()}
     * before any write: a non-manual apply landing on {@code STAGED} is refused in Java, naming
     * {@code staged_requires_manual}, the same rule the CHECK of the same name enforces (design §3.2).
     */
    public void land(TenantId t, ApplyId id, RequestState terminal) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            RequestState from = row.state();
            if (from.isTerminal()) {
                throw new IllegalStateException("apply_request " + id.value() + " is terminal ("
                        + from + ") and immutable — land() may not walk out of it");
            }
            if (terminal == from || terminal == RequestState.REQUESTED
                    || terminal == RequestState.RUNNING) {
                throw new IllegalStateException(
                        "land() walks to a terminal state or APPLY_INDETERMINATE; " + from + " → "
                                + terminal + " is not a landing");
            }
            if (terminal == RequestState.INDETERMINATE_RESOLVED) {
                throw new IllegalStateException("land() may not reach INDETERMINATE_RESOLVED: only "
                        + "resolveIndeterminate() does, because resolution_is_evidence requires the "
                        + "four resolution columns land() does not write");
            }
            if (!terminal.validFor(row.operation())) {
                throw new IllegalStateException("state_fits_operation: a " + row.operation().sql()
                        + " request may not hold " + terminal + " (apply_request " + id.value()
                        + ") — the CHECK would refuse this with 23514");
            }
            if (terminal == RequestState.STAGED && row.strategy() != Strategy.MANUAL) {
                // row.strategy() is null whenever operation is 'stop' (lifecycle_has_strategy) —
                // unreachable here today because validFor's guard above already refused a stop
                // landing on STAGED, but the message must not re-dereference a null strategy if a
                // future in-memory row ever reaches this line by another path.
                throw new IllegalStateException("staged_requires_manual: a "
                        + (row.strategy() == null ? "null" : row.strategy().sql())
                        + " apply may not land on STAGED (apply_request " + id.value()
                        + ") — the CHECK would refuse this with 23514");
            }
            Instant now = clock.instant();
            apply(requests.transition(t, id, from, terminal,
                    terminal.isTerminal() ? now : null), id, from, terminal);

            if (row.operation() == Operation.APPLY
                    && (terminal == RequestState.STAGED || terminal == RequestState.APPLIED)) {
                raiseWatermark(row);
            }
        });
    }

    /**
     * {@code APPLY_INDETERMINATE → INDETERMINATE_RESOLVED} — design §3.4's operator-confirmation
     * flavour, the only path that writes the four evidence columns.
     *
     * <p>{@code outcome = applied} raises the watermark in the same transaction, because §2.2 rule 1
     * names {@code resolved-'applied'} alongside {@code STAGED}/{@code APPLIED} as a generation that
     * counts. {@code not_applied} raises nothing: nothing landed.
     *
     * <p><b>{@code BE-D17}: the evidence must fit the request's own operation, before any write.</b>
     * {@link #requireEvidenceFitsOperation} mirrors {@code resolution_is_evidence}'s CASE, naming the
     * rule exactly as the {@code state_fits_operation}/{@code staged_requires_manual} guards in
     * {@link #land} do — a wrong-shaped evidence value, or an apply evidence naming a generation other
     * than the request's own, is refused here with a readable sentence rather than surfacing as a
     * 23514 out of a repository. This is the Java-boundary half design §3.4 names by method: this is
     * the only method that ever writes the four resolution columns, so it is the only place that can
     * check the match against the row it already holds.
     */
    public void resolveIndeterminate(TenantId t, ApplyId id, Resolution r) {
        tx.executeWithoutResult(status -> {
            ApplyRequestRow row = require(t, id);
            lockScope(row);
            if (row.state() != RequestState.APPLY_INDETERMINATE) {
                throw new IllegalStateException("resolveIndeterminate() walks APPLY_INDETERMINATE → "
                        + "INDETERMINATE_RESOLVED; " + id.value() + " is " + row.state());
            }
            requireEvidenceFitsOperation(row, r.evidence());

            apply(requests.resolveIndeterminate(t, id, r, clock.instant()), id,
                    RequestState.APPLY_INDETERMINATE, RequestState.INDETERMINATE_RESOLVED);

            if (row.operation() == Operation.APPLY && r.outcome() == ResolutionOutcome.APPLIED) {
                raiseWatermark(row);
            }
        });
    }

    /**
     * {@code resolution_is_evidence}'s Java mirror for the operation↔evidence match ({@code BE-D17},
     * §3.4, rejection classes (i) and (ii)). Classes (iii) (missing observations) and (iv) (an invented
     * restart staged generation) are refused earlier still — by {@link ApplyResolutionEvidence}'s and
     * {@link RestartResolutionEvidence}'s own constructors, and by {@code RestartResolutionEvidence}
     * having no {@code stagedGeneration} component to invent one in, respectively — so they never reach
     * this method at all.
     */
    private static void requireEvidenceFitsOperation(ApplyRequestRow row, ResolutionEvidence evidence) {
        switch (row.operation()) {
            case APPLY:
                if (!(evidence instanceof ApplyResolutionEvidence apply)) {
                    throw new IllegalStateException("resolution_is_evidence: apply_request "
                            + row.applyId().value() + " is an apply request and may only be resolved "
                            + "with apply-shaped evidence (stagedGeneration + replicas) — the CHECK "
                            + "would refuse " + evidence.getClass().getSimpleName() + " with 23514");
                }
                // row.generation() is a boxed Long that ApplyRequestRow's compact constructor does
                // not require non-null for APPLY (apply_has_generation enforces that at the DB level
                // only) — checked explicitly rather than auto-unboxed in the != below, so a future
                // in-memory row with a null generation refuses readably instead of NPEing one line
                // below the carry-in that fixed the identical class of defect in land().
                if (row.generation() == null || apply.stagedGeneration() != row.generation()) {
                    throw new IllegalStateException("resolution_is_evidence: apply_request "
                            + row.applyId().value() + " — stagedGeneration=" + apply.stagedGeneration()
                            + " does not equal this request's own generation="
                            + (row.generation() == null ? "null" : row.generation())
                            + " — the CHECK would refuse this with 23514");
                }
                break;
            case RESTART:
                if (!(evidence instanceof RestartResolutionEvidence)) {
                    throw new IllegalStateException("resolution_is_evidence: apply_request "
                            + row.applyId().value() + " is a restart request and may only be resolved "
                            + "with restart-shaped evidence (restartsObserved, no stagedGeneration) — "
                            + "the CHECK would refuse " + evidence.getClass().getSimpleName()
                            + " with 23514");
                }
                break;
            default:
                // state_fits_operation's stop branch already excludes APPLY_INDETERMINATE for
                // operation = 'stop', so resolveIndeterminate() can never reach this line with a stop
                // request — the CHECK's own ELSE branch is the same defensive backstop, not a live path.
                throw new IllegalStateException("resolution_is_evidence: a " + row.operation().sql()
                        + " request can never reach INDETERMINATE_RESOLVED (apply_request "
                        + row.applyId().value() + ")");
        }
    }

    /**
     * The serialization point, taken explicitly — finding 3.
     *
     * <p>Always the INSTANCE's scope row, whatever the request's placement: that is the row
     * {@code AdmissionService} locks, and two writers serialize only if they lock the same thing.
     */
    private void lockScope(ApplyRequestRow row) {
        scopes.lockScope(row.scope().tenant(), row.scope().instance());
    }

    /** Instance-wide lands on {@code apply_scope}; a placement lands on {@code placement_watermark}. */
    private void raiseWatermark(ApplyRequestRow row) {
        if (row.scope().placement().isInstanceWide()) {
            scopes.raiseInstanceWatermark(row.scope().tenant(), row.scope().instance(),
                    row.generation());
        } else {
            scopes.raisePlacementWatermark(row.scope().tenant(), row.scope().instance(),
                    row.scope().placement(), row.generation());
        }
    }

    private ApplyRequestRow require(TenantId t, ApplyId id) {
        return requests.find(t, id).orElseThrow(() -> new IllegalStateException(
                "no apply_request " + id.value() + " for tenant " + t.value()));
    }

    private static void apply(int updated, ApplyId id, RequestState from, RequestState to) {
        if (updated != 1) {
            throw new IllegalStateException("the " + from + " → " + to + " walk of " + id.value()
                    + " updated " + updated + " rows — another writer moved it first");
        }
    }
}
