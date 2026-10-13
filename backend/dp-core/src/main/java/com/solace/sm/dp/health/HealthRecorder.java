package com.solace.sm.dp.health;

import com.solace.sm.dp.api.health.HealthState;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists one verdict and, when the state changed, publishes {@code instance.health} - the publish is registered
 * inside the transaction and leaves after it commits. One transaction per placement: a failure on one placement
 * never rolls back the others.
 */
public final class HealthRecorder {

    private final TransactionTemplate transactions;
    private final PlacementHealthRows rows;
    private final ApplyEventEmitter events;
    private final Clock clock;

    public HealthRecorder(TransactionTemplate transactions, PlacementHealthRows rows, ApplyEventEmitter events,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PlacementHealthRows.Recorded record(UUID tenantId, UUID instanceId, UUID placementId, HealthState state) {
        return transactions.execute(status -> {
            PlacementHealthRows.Recorded recorded = rows.record(tenantId, instanceId, placementId, state,
                    clock.instant());
            if (recorded.changed()) {
                events.onHealthTransition(new TenantId(tenantId), instanceId, placementId, state,
                        recorded.stateVersion());
            }
            return recorded;
        });
    }
}
