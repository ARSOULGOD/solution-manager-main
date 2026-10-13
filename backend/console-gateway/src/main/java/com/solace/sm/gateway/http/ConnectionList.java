package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code ConnectionList} ({@code AvailabilityOnly} + {@code items}). */
public record ConnectionList(@WireOptional Availability availability, List<Connection> items) {

    public ConnectionList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
