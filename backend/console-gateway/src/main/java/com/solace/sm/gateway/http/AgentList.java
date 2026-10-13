package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code AgentList} ({@code AvailabilityOnly} + {@code items}); versions are per item (OR-55). */
public record AgentList(@WireOptional Availability availability, List<Agent> items) {

    public AgentList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
