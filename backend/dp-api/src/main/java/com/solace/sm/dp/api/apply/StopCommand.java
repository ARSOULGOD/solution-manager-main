package com.solace.sm.dp.api.apply;

import java.util.Objects;
import java.util.UUID;

/**
 * A stop. There is no generation, no strategy and no outage confirmation: the engine rejects all three on a stop.
 *
 * @param placementId {@code null} means the whole instance (the engine's nil-UUID sentinel is its own business)
 * @param actor       the session's principal id - never a request field
 */
public record StopCommand(UUID placementId, UUID actor) {

    public StopCommand {
        Objects.requireNonNull(actor, "actor");
    }
}
