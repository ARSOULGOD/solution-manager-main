package com.solace.sm.events.publisher;

/**
 * The transaction seam {@link AfterCommitEventPublisher} defers through — deliberately not a Spring
 * type, so that {@code mesh-events} stays framework-free and plane-neutral. The Spring adapter
 * ({@code SpringTransactionBoundary}, dp-core) is one small class; a second plane or a second
 * framework writes its own adapter instead of dragging a framework into the contract module.
 *
 * <p>Contract: {@link #afterCommit(Runnable)} may only be called while {@link #inTransaction()} is
 * true. The boundary runs the action exactly once, after the surrounding transaction commits
 * successfully, and never runs it if the transaction rolls back — a rolled-back write happened to
 * nobody, so no notice is owed ({@code BE-D1}).
 */
public interface TransactionBoundary {

    /** Whether the calling thread is inside a transaction whose completion can be hooked. */
    boolean inTransaction();

    /**
     * Runs {@code action} after the current transaction commits; discards it on rollback.
     * Only meaningful while {@link #inTransaction()} is true.
     */
    void afterCommit(Runnable action);
}
