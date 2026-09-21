package com.solace.sm.cp.api.authz;

import java.util.Objects;
import java.util.UUID;

/**
 * Who is acting. A typed operand, not a string with parsing rules.
 *
 * <p>⚠️ {@code id()} is a {@link UUID}. Where a downstream contract wants a {@code String}
 * principal id — the apply engine's {@code ApplyCommand.requestedBy}, for one — convert
 * explicitly at the boundary.
 */
public record PrincipalRef(UUID id) {

    public PrincipalRef {
        Objects.requireNonNull(id, "principal id");
    }
}
