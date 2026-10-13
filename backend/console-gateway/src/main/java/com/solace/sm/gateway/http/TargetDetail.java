package com.solace.sm.gateway.http;

import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code TargetDetail}: a {@link Target} and its {@link TargetConnection}. Only
 * {@code registerTarget} returns it, to a caller who holds {@code target.create} at the target's scope.
 */
public record TargetDetail(UUID targetId, String name, TargetKind kind, TargetTransport transport,
                           ScopePath ownershipScope, @WireOptional String allowedConfigRoot,
                           @WireOptional String allowedContainerPrefix, @WireOptional String retiredAt,
                           @WireOptional UUID connectionId, @WireOptional String runtime,
                           @WireOptional String runtimeSettingsJson, @WireOptional TargetConnection connection) {

    /**
     * A live target, which is every one {@code registerTarget} returns. {@code retiredAt} is a property of {@code Target}
     * (plan 013 T1.5) and so, the contract's {@code TargetDetail} being a {@code Target} and its connection, of this; it is
     * never present here.
     */
    public TargetDetail(UUID targetId, String name, TargetKind kind, TargetTransport transport,
            ScopePath ownershipScope, String allowedConfigRoot, String allowedContainerPrefix,
            TargetConnection connection) {
        this(targetId, name, kind, transport, ownershipScope, allowedConfigRoot, allowedContainerPrefix, null,
                null, null, null, connection);
    }
}
