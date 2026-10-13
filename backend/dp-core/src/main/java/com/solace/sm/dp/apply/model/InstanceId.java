package com.solace.sm.dp.apply.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A managed instance — the unit the {@code apply_scope} lock row and the instance watermark are keyed
 * by.
 *
 * @param value the instance's UUID, never null
 */
public record InstanceId(UUID value) {

    public InstanceId {
        Objects.requireNonNull(value, "instance id");
    }
}
