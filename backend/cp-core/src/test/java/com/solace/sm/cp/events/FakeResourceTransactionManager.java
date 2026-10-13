package com.solace.sm.cp.events;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A transaction manager over an in-memory "resource", with no database: it binds its resource key to the thread for
 * the life of a transaction exactly as {@code DataSourceTransactionManager} binds its data source, which is the only
 * thing {@link CpTransactionBoundary} looks at. Two instances with two keys stand in for the two planes.
 */
final class FakeResourceTransactionManager extends AbstractPlatformTransactionManager
        implements ResourceTransactionManager {

    private final Object resource = new Object();

    @Override
    public Object getResourceFactory() {
        return resource;
    }

    @Override
    protected Object doGetTransaction() {
        return resource;      // non-null: a null object would read as "no transaction" to the synchronization manager
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return TransactionSynchronizationManager.hasResource(resource);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TransactionSynchronizationManager.bindResource(resource, new Object());
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        // nothing durable to do
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        // nothing durable to do
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        TransactionSynchronizationManager.unbindResource(resource);
    }
}
