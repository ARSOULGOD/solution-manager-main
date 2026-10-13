package com.solace.sm.cp.api.target;

import com.solace.sm.cp.api.authz.ScopePath;
import java.util.Objects;
import java.util.UUID;

/**
 * One deployment target's governance row (OR-28): who may see and use it ({@code ownershipScope}) and what it is.
 * Connection details live in the data plane and are deliberately absent ({@code I7}).
 *
 * <p>{@code kind} is the MI runtime SM manages and {@code transport} is how SM reaches it (Q-C3 (a)); both are
 * the closed vocabularies {@code sm_control.target}'s CHECKs enforce. v1 has exactly one pair, {@code docker} over
 * {@code ssh-docker}.
 */
public record TargetView(UUID targetId, String name, String kind, String transport, ScopePath ownershipScope,
        UUID connectionId, String runtime, String runtimeSettingsJson) {

    /** Legacy target shape. */
    public TargetView(UUID targetId, String name, String kind, String transport, ScopePath ownershipScope) {
        this(targetId, name, kind, transport, ownershipScope, null, kind, "{}");
    }

    public TargetView {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(ownershipScope, "ownershipScope");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(runtimeSettingsJson, "runtimeSettingsJson");
    }
}
