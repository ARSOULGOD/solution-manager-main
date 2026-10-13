package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code AssignmentList}. */
public record AssignmentList(List<Assignment> items) {

    public AssignmentList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
