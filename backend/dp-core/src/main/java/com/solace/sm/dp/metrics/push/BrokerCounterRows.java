package com.solace.sm.dp.metrics.push;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The broker-connection-level counter (plan 011 P11-5.1; OR-107 OQ-20 (b)): {@code sm_metrics.broker_ingest_counter}, the
 * second key beside {@code ingest_counter}'s per-placement one, for a push drop that has no single placement to be charged to.
 * Counts only (I6).
 */
public interface BrokerCounterRows {

    /**
     * Adds {@code n} to the (tenant, connection, reason, day) count, creating it. The table has no foreign key (M-Q19): the
     * caller counts only for connections it holds, under the tenant that owns them.
     */
    void add(UUID tenantId, UUID brokerConnectionId, CollectorReason reason, LocalDate day, long n);
}
