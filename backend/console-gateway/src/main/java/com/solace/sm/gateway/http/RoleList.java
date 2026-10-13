package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code RoleList}. */
public record RoleList(List<Role> items) {

    public RoleList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
