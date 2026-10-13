package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;

/** Wire shape of console.yaml's {@code UserList}. Carries no {@code stateVersion}: a user has none. */
public record UserList(List<User> items) {

    public UserList {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
