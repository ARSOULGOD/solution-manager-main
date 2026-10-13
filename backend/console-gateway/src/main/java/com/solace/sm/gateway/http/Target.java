package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Target}. The two envelope fields are optional: they come from the data
 * plane's connection record (B2), and a target with no such record is listed without them (and the list is marked
 * degraded), never hidden. {@code retiredAt} is present only in {@code retireTarget}'s answer (plan 013 T1.5): a retired
 * target is never listed, so no list ever carries it.
 */
public record Target(UUID targetId, String name, TargetKind kind, TargetTransport transport,
                     ScopePath ownershipScope, @WireOptional String allowedConfigRoot,
                     @WireOptional String allowedContainerPrefix, @WireOptional String retiredAt,
                     @WireOptional UUID connectionId, @WireOptional String runtime,
                     @WireOptional String runtimeSettingsJson) {

    /** A live target: what every caller but {@code retireTarget} builds. */
    public Target(UUID targetId, String name, TargetKind kind, TargetTransport transport, ScopePath ownershipScope,
            String allowedConfigRoot, String allowedContainerPrefix) {
        this(targetId, name, kind, transport, ownershipScope, allowedConfigRoot, allowedContainerPrefix, null,
                null, kind.wireValue(), "{}");
    }

    public Target(UUID targetId, String name, TargetKind kind, TargetTransport transport, ScopePath ownershipScope,
            String allowedConfigRoot, String allowedContainerPrefix, String retiredAt) {
        this(targetId, name, kind, transport, ownershipScope, allowedConfigRoot, allowedContainerPrefix, retiredAt,
                null, kind.wireValue(), "{}");
    }

    public Target {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(ownershipScope, "ownershipScope");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(runtimeSettingsJson, "runtimeSettingsJson");
    }
}
