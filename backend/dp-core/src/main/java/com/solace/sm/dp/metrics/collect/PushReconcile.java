package com.solace.sm.dp.metrics.collect;

import com.solace.sm.dp.apply.model.TenantId;
import java.util.Collection;

/**
 * The per-tenant step the metrics background loop runs for broker push (plan 011 P11-5.1; OQ-6 (a), {@code OR-92}): the
 * push-subscription reconcile is the THIRD per-tenant step of the one loop, beside scrape planning and the rollup, fed from the
 * same single {@code tenantsWithActiveInstances()} call. There is no fifth caller of that list: this interface is how the push
 * path gets its tenants without asking for them.
 *
 * <p>Implementations must not hold the loop's thread (a broker that does not answer is not the collector's problem).
 */
public interface PushReconcile {

    /** The step when push is withdrawn or not built: does nothing. */
    PushReconcile NONE = new PushReconcile() {
        @Override
        public void reconcile(TenantId tenant) {
        }

        @Override
        public void retainTenants(Collection<TenantId> active) {
        }

        @Override
        public void flushCounts() {
        }
    };

    /** States what this organization's broker connections should hold, from its rows. A failure keeps what it had. */
    void reconcile(TenantId tenant);

    /** An organization that left the active list holds no subscription. */
    void retainTenants(Collection<TenantId> active);

    /** Writes the broker-connection-level counts waiting, on the loop's thread, so no receive thread opens a connection. */
    void flushCounts();
}
