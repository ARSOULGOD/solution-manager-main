package com.solace.sm.cp.api.session;

import java.util.Objects;
import java.util.UUID;

/** Durable session identity; unlike a bearer token, this value is not a credential. */
public record SessionId(UUID value) {
    public SessionId {
        Objects.requireNonNull(value, "value");
    }
}
