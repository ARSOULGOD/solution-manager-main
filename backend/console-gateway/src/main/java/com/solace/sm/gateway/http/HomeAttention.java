package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code HomeAttention} ({@code AvailabilityOnly} + {@code items}). A filtered
 * collection, not an entity: no top-level {@code stateVersion}; every item carries its own ({@code OR-60}).
 */
public record HomeAttention(@WireOptional Availability availability, List<Item> items) {

    public HomeAttention {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * One reason an instance needs attention. {@code state} is present exactly when {@code reason} is {@code health}
     * (the notify vocabulary); {@code stateVersion} is the source row's own stamp.
     */
    public record Item(UUID instanceId, @WireOptional UUID placementId, String reason, @WireOptional String state,
                       long stateVersion) {

        public Item {
            Objects.requireNonNull(instanceId, "instanceId");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
