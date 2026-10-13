package com.solace.sm.dp.apply.reconcile;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.engine.HostObservation;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.RedriveResult;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.ApplyResolutionEvidence;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.ReplicaObservation;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.Resolution;
import com.solace.sm.dp.apply.model.ResolutionOutcome;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.JdbcReconciliationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verify-then-retry (plan 010 T6.8, owner ruling 2026-09-29; {@code OR-59}, {@code OR-63}): reconciles an
 * {@code APPLY_INDETERMINATE} request against a FRESH observation of the host.
 *
 * <p><b>Supersedes</b> plan 005's rule that recovery never touches {@code APPLY_INDETERMINATE} (flagged, not ratified: it
 * rests on the owner's ruling, not on a ratified decision). The old concern - the engine confirming its own uncertainty - is
 * answered rather than ignored: no branch here rests on the journal alone. Each rests on what the host holds <i>now</i>
 * ({@link HostObservation}), and each either proves the state from that observation or refuses to act. Content SM did not
 * write is never overwritten.
 *
 * <p><b>Three phases, and the database lock is held across none of the slow ones</b> (M-4):
 * <ol>
 *   <li><b>Observe</b> - the host is read with no lock held, so an emergency stop is admitted at any moment.</li>
 *   <li><b>Decide and transition</b> - one short transaction under admission's lock order
 *       ({@code apply_scope → instance_state → row}), which re-checks that the request is still the indeterminate one that
 *       was observed and that no newer request exists on the scope, then moves it. The scope lock is released before any
 *       host call.</li>
 *   <li><b>Act</b> - through the engine's own step loop ({@link ApplyExecutor#redrive}), with the request {@code RUNNING}.</li>
 * </ol>
 *
 * <p><b>Reads no tenant list of its own.</b> The worker hands it a tenant from {@code OR-44}'s existing enumeration, which
 * already yields a tenant whose only open row is {@code APPLY_INDETERMINATE}; every statement below is bound to that tenant
 * ({@code I5}). Nothing here calls {@code tenantsWithActiveInstances}.
 *
 * <p><b>Reaches a host only through {@link ApplyExecutor}</b>, the one mutating apply path: this class holds no
 * {@code RuntimeAdapter}, which {@code ApplyPathBoundaryTest} enforces for every package outside the engine and the seam.
 */
public final class IndeterminateReconciler {

    private static final Logger log = LoggerFactory.getLogger(IndeterminateReconciler.class);

    /**
     * The identity recorded as {@code resolved_by} on a confirmation. A fixed, non-principal token: {@code
     * Resolution.resolvedBy} otherwise holds "the principal who confirmed", and a reconciler is none. Flagged as a new
     * meaning in the decision that added this task; it cannot collide with a principal id, which is a UUID.
     */
    public static final String RESOLVER = "sm-reconciler";

    /** How long a claimed row stays out of other pollers' sight while it is inspected; the real next attempt replaces it. */
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(2);

    /** The most requests one tick claims for one tenant; the rest are due next tick. */
    private static final int PER_TICK = 8;

    /** How one reconciliation of one request ended. */
    public enum Result {
        /** A read proved the request applied; it is now {@code INDETERMINATE_RESOLVED}. */
        CONFIRMED,
        /** A re-drive ran (it may itself have been cut off again, or been given up on mid-flight - see the request). */
        REDRIVEN,
        /** The inspection failed or was undecided; the next attempt is scheduled. */
        RETRY_SCHEDULED,
        /** A permanent give-up: the request stays {@code APPLY_INDETERMINATE} carrying the classifier, and an alert went out. */
        GAVE_UP,
        /** Nothing to do: an operator resolved the request first, or it was already closed. */
        DROPPED
    }

    private final ApplyExecutor executor;
    private final OutcomeService outcomes;
    private final JdbcReconciliationRepository ledger;
    private final ReconcileProperties props;
    private final Clock clock;

    public IndeterminateReconciler(ApplyExecutor executor, OutcomeService outcomes, JdbcReconciliationRepository ledger,
            ReconcileProperties props, Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.props = Objects.requireNonNull(props, "props");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Startup (MINOR 9): hands each of the tenant's {@code APPLY_INDETERMINATE} requests to the reconciler by giving it a
     * bookkeeping row due now, and returns. Never touches a host and never waits on one, so boot cannot be held up by an
     * unreachable machine. Idempotent: a request that has a row keeps it, counters and age origin included.
     *
     * @return how many rows it created
     */
    public int enqueueIndeterminate(TenantId t) {
        Instant now = clock.instant();
        return ledger.enqueueIndeterminate(t, now, now);
    }

    /**
     * Whether this tenant has anything the reconciler would act on right now: an {@code APPLY_INDETERMINATE} request with no
     * bookkeeping row yet, or one whose row is open and due. One indexed read, no host - the worker asks it on every tick
     * for every enumerated tenant and only dispatches {@link #runDue} when the answer is yes.
     */
    public boolean hasDue(TenantId t) {
        return ledger.hasDue(t, clock.instant());
    }

    /**
     * One poll tick for one tenant: makes sure every {@code APPLY_INDETERMINATE} request has a row (a request that landed
     * after boot has none yet), claims the due ones, and reconciles each. A given-up request is never due again, so a
     * tenant the worker keeps enumerating costs one indexed read.
     *
     * @return how many requests it handled
     */
    public int runDue(TenantId t) {
        Instant now = clock.instant();
        ledger.enqueueIndeterminate(t, now, now);
        int handled = 0;
        for (JdbcReconciliationRepository.Row due : ledger.claimDue(t, now, now.plus(CLAIM_LEASE), PER_TICK)) {
            try {
                reconcile(t, due.applyId(), due);
                handled++;
            } catch (RuntimeException e) {
                // One poisoned request must not stop the tick. Only the class is logged (I6): an adapter's or a driver's
                // message can carry a path or a value. The claim's lease expires and the request is inspected again.
                log.warn("reconciling apply {} for tenant {} failed unexpectedly ({}); it will be retried",
                        due.applyId().value(), t.value(), e.getClass().getSimpleName());
            }
        }
        return handled;
    }

    /** One full reconciliation of one request (observe, decide, act). Used by the poll and by tests. */
    public Result reconcileOne(TenantId t, ApplyId id) {
        ledger.ensureRow(t, id, clock.instant());
        return reconcile(t, id, ledger.find(t, id).orElseThrow());
    }

    private Result reconcile(TenantId t, ApplyId id, JdbcReconciliationRepository.Row book) {
        Instant now = clock.instant();
        if (book.gaveUpAt() != null) {
            return Result.DROPPED;
        }
        ApplyRequestRow row = outcomes.requestOf(t, id);
        if (row.state() == RequestState.RUNNING) {
            // A re-drive owned by another tick is in flight (the claim lease is shorter than a gated re-drive): its
            // bookkeeping is not ours to close. Only a terminal or resolved state closes the row.
            return Result.RETRY_SCHEDULED;
        }
        if (row.state() != RequestState.APPLY_INDETERMINATE) {
            // MINOR 10: an operator got there first. No host call is made.
            return dropped(t, id);
        }

        // ---- phase 1: observe, no lock held ---------------------------------------------------------
        HostObservation observation = executor.observeHost(t, row);
        ReconcileDecision.Context context = new ReconcileDecision.Context(
                outcomes.laterRequestExists(t, id),
                now.isAfter(book.firstIndeterminateAt().plus(props.maxRedriveAge())),
                book.redrives(), props.maxRedrives());
        ReconcileDecision decision = ReconcileDecision.decide(observation, context);

        // ---- phase 2 and 3: decide and transition, then act -----------------------------------------
        if (decision instanceof ReconcileDecision.Confirm confirm) {
            return confirm(t, row, (HostObservation.Observed) observation, confirm);
        }
        if (decision instanceof ReconcileDecision.Retry retry) {
            return retry(t, id, book, retry.why(), now);
        }
        if (decision instanceof ReconcileDecision.GiveUp giveUp) {
            return giveUp(t, id, giveUp.why());
        }
        return redrive(t, row, ((ReconcileDecision.Redrive) decision), now);
    }

    private Result confirm(TenantId t, ApplyRequestRow row, HostObservation.Observed observed,
            ReconcileDecision.Confirm confirm) {
        ApplyId id = row.applyId();
        if (confirm.gateProved()) {
            executor.recordGateProof(t, id, observed.replicas());
        }
        List<ReplicaObservation> replicas = observed.replicas().stream()
                .map(r -> new ReplicaObservation(r.replica().ref(), r.restartedByThisRequest(), r.currentHash()))
                .toList();
        try {
            outcomes.resolveIndeterminate(t, id, new Resolution(RESOLVER, ResolutionOutcome.APPLIED,
                    new ApplyResolutionEvidence(observed.generation(), replicas)));
        } catch (IllegalStateException lost) {
            if (outcomes.stateOf(t, id) != RequestState.APPLY_INDETERMINATE) {
                return dropped(t, id);   // the operator's resolution won the race; theirs stands
            }
            throw lost;
        }
        ledger.close(t, id, clock.instant(), new ErrorClass("reconcile.confirmed"));
        return Result.CONFIRMED;
    }

    private Result retry(TenantId t, ApplyId id, JdbcReconciliationRepository.Row book, ErrorClass why, Instant now) {
        int failures = book.inspections() + 1;
        int counted = ledger.recordFailedInspection(t, id, now.plus(props.backoffAfter(failures)), why);
        if (counted >= props.maxAttempts()) {
            return giveUp(t, id, new ErrorClass("reconcile.exhausted"));
        }
        return Result.RETRY_SCHEDULED;
    }

    private Result redrive(TenantId t, ApplyRequestRow row, ReconcileDecision.Redrive decision, Instant now) {
        ApplyId id = row.applyId();
        switch (outcomes.beginRedrive(t, id, row.stateVersion(),
                new OutcomeService.RedriveBounds(props.maxRedriveAge(), props.maxRedrives()))) {
            case NOT_INDETERMINATE:
                return dropped(t, id);
            case CHANGED:
                // Still indeterminate but not the request that was observed: look again, soon, without counting a failure.
                ledger.scheduleNext(t, id, now, null);
                return Result.RETRY_SCHEDULED;
            case LATER_REQUEST:
                return giveUp(t, id, new ErrorClass("reconcile.superseded.by.later.request"));
            case TOO_LATE:
                // The window closed while the host was being observed (OR-105 b): the decision above was made on an older clock.
                return giveUp(t, id, new ErrorClass("reconcile.redrive.too.late"));
            case EXHAUSTED:
                return giveUp(t, id, new ErrorClass("reconcile.redrive.exhausted"));
            default:
                break;
        }
        RedriveResult result = executor.redrive(t, id, decision.plan());
        if (result.gaveUp()) {
            log.warn("apply {} for tenant {}: the reconciler gave up mid-re-drive ({})", id.value(), t.value(),
                    result.why().token());
            return Result.GAVE_UP;
        }
        if (result.landed() == RequestState.APPLY_INDETERMINATE) {
            // Cut off again: inspected again, later. The re-drive is spent, so the next "would re-drive" gives up.
            ledger.scheduleNext(t, id, clock.instant().plus(props.initialBackoff()), result.why());
        } else {
            ledger.close(t, id, clock.instant(), new ErrorClass("reconcile.redriven"));
        }
        return Result.REDRIVEN;
    }

    /** A permanent give-up (M-1): the classifier on the request, the bookkeeping closed, one notice, one WARN. */
    private Result giveUp(TenantId t, ApplyId id, ErrorClass why) {
        if (!outcomes.recordReconcileGiveUp(t, id, why)) {
            return Result.DROPPED;   // an operator resolved it between the observation and now
        }
        log.warn("apply {} for tenant {}: the reconciler gave up ({})", id.value(), t.value(), why.token());
        return Result.GAVE_UP;
    }

    /** The request is no longer ours to reconcile: close the bookkeeping, note why, touch nothing else. */
    private Result dropped(TenantId t, ApplyId id) {
        ledger.close(t, id, clock.instant(), new ErrorClass("resolved.by.operator"));
        return Result.DROPPED;
    }
}
