package com.solace.sm.events;

/**
 * The entity an event is about (backend design §3, {@code BE-D4}).
 *
 * <p>Mirrors {@code contracts/events/envelope.schema.json}'s {@code entity} object exactly: field
 * names are the JSON names, {@code kind} is required, and the three id fields are nullable because
 * which of them applies depends on {@code kind} — {@code instance}/{@code placement} events carry
 * {@code instanceId} (and {@code placementId} for the placement-scoped ones, {@code SM-D23}),
 * {@code solution} events carry {@code solution}. The schema is {@code additionalProperties: false},
 * so this record is closed the same way; the per-event required-id combinations are the notify
 * schema's business, not the envelope's.
 *
 * <p>Nulls are omitted on the wire by {@link EventJson#mapper()}'s {@code NON_NULL} inclusion, which
 * is what keeps a round-trip byte-equivalent against fixtures that simply leave them out.
 *
 * @param kind       {@code instance} | {@code placement} | {@code solution} — required
 * @param instanceId nullable
 * @param placementId nullable
 * @param solution   nullable
 */
public record EntityRef(String kind, String instanceId, String placementId, String solution) {

    public EntityRef {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("entity.kind must be present and non-blank");
        }
    }
}
