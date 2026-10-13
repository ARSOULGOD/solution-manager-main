package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The durable identity of one operator decision — {@code apply_request.apply_id}.
 *
 * @param value a UUIDv7 (see {@link Uuid7}); never null
 */
public record ApplyId(UUID value) {

    public ApplyId {
        Objects.requireNonNull(value, "apply id");
    }

    /** A fresh, time-ordered id. */
    public static ApplyId next() {
        return new ApplyId(Uuid7.next());
    }
}
