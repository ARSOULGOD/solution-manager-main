package com.solace.sm.dp.api.metrics.declare;

import java.util.Objects;

/** A component's restart signal (plan 011 M-Q9 (d'), OQ-1 (a)): per component, optional. {@code sourceJson} as in {@link MetricDefinitionSpec}. */
public record RestartSignalSpec(String componentType, String sourceJson) {

    public RestartSignalSpec {
        Objects.requireNonNull(componentType, "componentType");
        Objects.requireNonNull(sourceJson, "sourceJson");
    }
}
