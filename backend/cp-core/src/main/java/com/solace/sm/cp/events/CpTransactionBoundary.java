package com.solace.sm.cp.events;

import com.solace.sm.events.publisher.TransactionBoundary;
import java.util.Objects;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link TransactionBoundary} for the control plane: "after commit" means after the commit of a
 * transaction on the {@code sm_control} resource, and of nothing else ({@code BE-D1} rule 4, plan 010 T3.3).
 *
 * <p>The data plane has its own boundary ({@code SpringTransactionBoundary}, in {@code dp-core}, which {@code
 * cpNeverImportsDp} keeps this module from touching). Spring's synchronization registry is one per thread, shared
 * by every transaction manager, so a boundary that asked only "is a transaction active?" would call a DATA PLANE
 * transaction a control-plane one and hold a catalog notice until the data plane's commit. This boundary therefore
 * also requires the control plane's own resource (the {@code sm_control} data source, read from its transaction
 * manager's {@link ResourceTransactionManager#getResourceFactory() resource factory}) to be bound to the thread:
 * inside a data-plane-only transaction there is no control-plane commit to order against, so the publish is
 * immediate - exactly as outside any transaction.
 *
 * <p>Known, deliberate limit: a control-plane transaction nested INSIDE a data-plane one on the same thread joins
 * the thread's one synchronization set, so the notice fires when the outermost transaction completes - later than
 * the inner commit, never earlier, and discarded if the outer one rolls back (a lost freshness notice, the loss
 * {@code BE-D1} accepts). No production path nests them: a two-plane composition commits one plane, then the other.
 *
 * <p>Rollback discard is inherited, not implemented: only {@code afterCommit} is overridden, so a rolled-back
 * transaction simply never runs the action.
 */
public final class CpTransactionBoundary implements TransactionBoundary {

    private final Object controlPlaneResource;

    public CpTransactionBoundary(ResourceTransactionManager controlPlaneTransactionManager) {
        this.controlPlaneResource = Objects.requireNonNull(
                Objects.requireNonNull(controlPlaneTransactionManager, "controlPlaneTransactionManager")
                        .getResourceFactory(), "the control-plane transaction manager's resource factory");
    }

    @Override
    public boolean inTransaction() {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.hasResource(controlPlaneResource);
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
