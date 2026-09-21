package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;
import java.util.Objects;

/** What {@code materializeConfig} wrote, and where. */
public record MaterializeResult(String writtenHash, RuntimeRef runtimeRef) {

    public MaterializeResult {
        Objects.requireNonNull(writtenHash, "written hash");
    }
}
