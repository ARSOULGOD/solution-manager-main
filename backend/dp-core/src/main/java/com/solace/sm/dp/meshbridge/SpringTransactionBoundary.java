package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.publisher.TransactionBoundary;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TransactionBoundary} over Spring's per-thread transaction synchronization — the only class
 * in the backend that knows the decorator's "after commit" is Spring-managed.
 *
 * <p><b>Why the conjunction.</b> {@code inTransaction()} requires BOTH an actual transaction and
 * active synchronization: registration is only legal while synchronization is active, and deferral
 * is only <i>meaningful</i> while a real transaction's commit is pending. In an empty synchronized
 * scope (e.g. {@code PROPAGATION_SUPPORTS} with no transaction) there is no commit to order against,
 * so publishing immediately is correct, not a shortcut.
 *
 * <p><b>{@code SYNCHRONIZATION_NEVER}, as measured</b> (not as first guessed — see
 * {@code SpringTransactionBoundaryDataSourceIT}): under that mode a real transaction is opened and
 * committed, yet <i>both</i> conjuncts read false, not merely the synchronization one.
 * {@code AbstractPlatformTransactionManager.prepareSynchronization} is the only place that sets the
 * actual-transaction flag and it is skipped entirely, so the open transaction is invisible to the
 * whole {@code TransactionSynchronizationManager} API. Calling {@link #afterCommit} anyway throws
 * ("Transaction synchronization is not active"); the {@link #inTransaction()} guard is what keeps
 * the decorator off that path. The outcome is still immediate publish — today's pre-decorator
 * behaviour, stated rather than silent — but by a different mechanism than the conjunction's
 * first-conjunct reading suggests.
 *
 * <p><b>Rollback discard is inherited, not implemented:</b> only {@code afterCommit} is overridden,
 * so a rolled-back transaction simply never invokes the action — no compensation logic to get wrong.
 */
public final class SpringTransactionBoundary implements TransactionBoundary {

    @Override
    public boolean inTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
