package com.solace.sm.dp.meshbridge;

import com.solace.sm.events.contracttest.AbstractEventPublisherContractTest;
import com.solace.sm.events.contracttest.CapturingTransport;
import com.solace.sm.events.publisher.AfterCommitEventPublisher;
import com.solace.sm.events.publisher.EventPublisher;
import com.solace.sm.events.publisher.TransactionBoundary;

/**
 * The second {@link EventPublisher} implementation's half of the publisher contract (design §5.2) —
 * run over the COMPOSED stack {@code AfterCommit(Jcsmp(transport))}, because that is the object
 * graph {@code MeshBridgeConfiguration} wires: certifying the decorator around a toy delegate would
 * certify a graph production never builds.
 *
 * <p>The boundary reports no transaction, so every kit publish takes the immediate path — which is
 * the path whose wire output the kit can observe synchronously. The deferred path's ordering and
 * discard semantics are {@code AfterCommitEventPublisherTest}'s (mesh-events) and
 * {@code SpringTransactionBoundaryTest}'s (Task 4) job; the kit's job here is proving the decorator
 * launders none of the seam's guarantees: derive-and-serialize, both loud guards, the swallow-and-
 * count paths, and whole-body fixture equality all hold through the extra layer.
 *
 * <p>Lives in this package for the same reason {@code EventPublisherContractTest} does: the
 * {@code JcsmpEventPublisher} seam constructor is package-private.
 */
class AfterCommitEventPublisherContractTest extends AbstractEventPublisherContractTest {

    private static final TransactionBoundary NOT_IN_TRANSACTION = new TransactionBoundary() {
        @Override
        public boolean inTransaction() {
            return false;
        }

        @Override
        public void afterCommit(Runnable action) {
            throw new IllegalStateException("afterCommit outside a transaction is a decorator bug");
        }
    };

    @Override
    protected EventPublisher publisher(CapturingTransport transport) {
        return new AfterCommitEventPublisher(new JcsmpEventPublisher(transport), NOT_IN_TRANSACTION);
    }

    @Override
    protected long droppedCount(EventPublisher publisher) {
        // The decorator is not a drop site (its javadoc argues why); drops live on the wrapped
        // JCSMP publisher, reached through the decorator's own composition accessor.
        return ((JcsmpEventPublisher) ((AfterCommitEventPublisher) publisher).delegate()).droppedCount();
    }
}
