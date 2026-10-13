package com.solace.sm.dp.inventory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One row of {@code sm_data.target_connection_event} (plan 013 T1.3, D-REG section 9, {@code BE-D56}): the data plane's
 * change audit for a connection record, written in the SAME transaction as the change.
 *
 * <p>⛔ {@code I6}: it carries a kind, an actor, field NAMES and a PUBLIC fingerprint, and has nowhere to put a key, a
 * {@code credential_ref}, or an old or new host value.
 */
public record TargetConnectionEvent(UUID tenantId, UUID targetId, UUID eventId, Kind kind, UUID actor,
        UUID correlationId, String clientAddress, List<String> changedFields, String publicKeyFingerprint,
        Instant recordedAt) {

    /** The table's closed {@code kind} vocabulary. */
    public enum Kind {
        REGISTERED("registered"), EDITED("edited"), REPINNED("repinned"), CREDENTIAL_ROTATED("credential_rotated"),
        RETIRED("retired");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** The value stored in {@code kind}. */
        public String wire() {
            return wire;
        }
    }

    public TargetConnectionEvent {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(clientAddress, "clientAddress");
        changedFields = List.copyOf(Objects.requireNonNull(changedFields, "changedFields"));
        Objects.requireNonNull(recordedAt, "recordedAt");
    }
}
