package com.solace.sm.cp.authz;

import java.util.Objects;
import java.util.UUID;

/** Database identity of an immutable canonical {@code sm_control.scope} row. */
public record ScopeId(UUID value) {

    public ScopeId {
        Objects.requireNonNull(value, "scope id");
    }
}
