package com.solace.sm.dp.apply.model;

import java.time.Instant;

/**
 * The slice of {@code apply_reconciliation} that the engine's own transactions touch (plan 010 T6.8): the two writes that
 * must commit or roll back <b>with</b> a state walk of {@code apply_request}, so that a request and its bookkeeping can
 * never disagree about whether a give-up happened.
 *
 * <p>A port in {@code model} (framework-free, beneath both {@code engine} and {@code persistence}) for the same reason
 * {@code LadderRows} sits outside both: {@code persistence} may not reach up into {@code engine}, and {@code engine}
 * must not name a JDBC type. The reconciler's own scheduling reads and writes ({@code claimDue}, {@code enqueue},
 * {@code scheduleNext}) are not here: they run in the reconciler's transactions, not the engine's.
 *
 * <p>Every method is called from INSIDE the engine's {@code REQUIRES_NEW} transaction and so join it; none opens one.
 */
public interface ReconciliationLedger {

    /**
     * Counts one re-drive. Called in the same transaction that walks the request {@code APPLY_INDETERMINATE → RUNNING},
     * so a refused walk spends nothing and a committed one always has its count: the bound is never optimistic.
     * Creates the row if the request has none.
     */
    void countRedrive(TenantId tenant, ApplyId id, Instant now);

    /**
     * Closes the request's bookkeeping permanently: {@code gave_up_at} is set and the row is never due again. Used for a
     * give-up, for a reconciliation that is finished (confirmed), and for an operator who resolved first. Idempotent
     * (a row that is already closed keeps its first {@code gave_up_at} and classifier). Creates the row if the request
     * has none.
     *
     * @param lastClass a classifier token ({@code reconcile.foreign.content}, {@code resolved.by.operator}, ...)
     */
    void close(TenantId tenant, ApplyId id, Instant now, ErrorClass lastClass);

    /**
     * Records the {@code APPLY_INDETERMINATE} landing as the origin of the re-drive age bound (Q-R3 (a)): the row is
     * created due {@code now} with {@code first_indeterminate_at = now}. A row that exists is kept exactly as it is, so a
     * re-drive that is cut off again does not move the origin. Called in the landing transaction.
     */
    void recordIndeterminateLanding(TenantId tenant, ApplyId id, Instant now);

    /** What the re-drive bounds are decided from: the landing (age origin) and the re-drives already spent. */
    record RedriveBook(Instant firstIndeterminateAt, int redrives) {
    }

    /**
     * The request's bookkeeping as the re-drive bounds need it, read inside the caller's transaction (OR-105 b: the bounds
     * are re-checked under the scope lock, not only from the reconciler's earlier read). Empty when the request has no row.
     */
    java.util.Optional<RedriveBook> redriveBook(TenantId tenant, ApplyId id);

    /** A ledger that keeps nothing: the journal-only engine suites. Production wires the JDBC one. */
    static ReconciliationLedger none() {
        return new ReconciliationLedger() {
            @Override
            public void countRedrive(TenantId tenant, ApplyId id, Instant now) {
            }

            @Override
            public void close(TenantId tenant, ApplyId id, Instant now, ErrorClass lastClass) {
            }

            @Override
            public java.util.Optional<RedriveBook> redriveBook(TenantId tenant, ApplyId id) {
                return java.util.Optional.empty();
            }

            @Override
            public void recordIndeterminateLanding(TenantId tenant, ApplyId id, Instant now) {
            }
        };
    }
}
