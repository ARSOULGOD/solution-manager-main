package com.solace.sm.events;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The common SM event envelope (backend design §3, {@code BE-D4}) — the Java half of the contract
 * whose canonical form is {@code contracts/events/envelope.schema.json}.
 *
 * <p>Field names are the JSON names, deliberately un-renamed: the contract fixtures are the golden
 * inputs for both this model and the Python validator, and a Jackson rename here would be exactly
 * the two-implementation drift {@code UI-D15}/{@code DM-D20} exist to prevent.
 *
 * <p><b>{@code stateVersion} is the only ordering input</b> ({@code B/D15} 🔒). {@code occurredAtUtc}
 * is display and diagnostics; never compare it to decide staleness.
 *
 * <p><b>Payloads carry identifiers only</b> ({@code I6}) — no config value, no secret, no metric
 * value, no label value. That rule is enforced by the notify schema's closed payload objects, not
 * by this type, which is generic over all six v1 events.
 *
 * <p>The compact constructor re-states the schema's own constraints so a Java-constructed event
 * cannot be invalid: required fields present, {@code tenant} non-blank, {@code stateVersion} not
 * negative (schema {@code minimum: 0}), and {@code producer} one of the schema's two enum members.
 *
 * @param eventId       unique id for this notice
 * @param occurredAtUtc display/diagnostics only — never an ordering input
 * @param producer      {@code cp-core} | {@code dp-core}
 * @param tenant        server-derived tenant ({@code I5}); also a topic level
 * @param entity        what the event is about
 * @param stateVersion  monotonic per entity; the sole ordering / stale-discard input
 * @param event         one of the six v1 event keys
 * @param payload       event-specific identifier fields
 */
public record EventEnvelope(
        UUID eventId,
        Instant occurredAtUtc,
        String producer,
        String tenant,
        EntityRef entity,
        long stateVersion,
        String event,
        Map<String, Object> payload) {

    /**
     * The {@code producer} enum, copied verbatim from {@code contracts/events/envelope.schema.json}
     * ({@code "producer": { "enum": ["cp-core", "dp-core"] }}) — never recalled ({@code I2}). A
     * {@link java.util.List} rather than a {@code Set} so the failure message orders the members the
     * same way on every JVM run.
     *
     * <p>Enforced here because this is the one place every Java-constructed and every
     * Jackson-deserialized envelope passes through. Before it was, {@code producer: "go-agent"}
     * constructed, serialized and published from Java while the Python validator rejected the same
     * document — a two-implementation divergence on a field that names who is allowed to emit at all.
     * Kept as a {@code String} rather than promoted to a Java {@code enum}: the field is the wire
     * form the fixtures are byte-frozen against, and a Java enum would put Jackson's naming rules
     * between this record and the contract.
     *
     * <p>Package-private for {@code SchemaMirrorLockTest}; not part of the public API.
     */
    static final List<String> PRODUCERS = List.of("cp-core", "dp-core");

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAtUtc, "occurredAtUtc");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(payload, "payload");
        if (!PRODUCERS.contains(producer)) {
            throw new IllegalArgumentException(
                    "producer must be one of " + PRODUCERS + ", was '" + producer + "'");
        }
        if (tenant == null || tenant.isBlank()) {
            throw new IllegalArgumentException("tenant must be present and non-blank");
        }
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must not be negative: " + stateVersion);
        }
        // Immutable, order-preserving, and null-value tolerant (Map.copyOf is not).
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
