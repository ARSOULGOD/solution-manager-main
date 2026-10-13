package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code EntitlementList}: one item per entitled solution, by solution name. */
public record EntitlementList(List<Entitlement> items) {

    public EntitlementList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
