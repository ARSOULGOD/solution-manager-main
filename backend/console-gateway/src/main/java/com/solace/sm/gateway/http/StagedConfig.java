package com.solace.sm.gateway.http;

/**
 * Wire shape of {@code stageConfig}'s 200: console.yaml's {@code StagedConfig} ({@code Versioned} + {@code generation})
 * plus {@code unchanged}, which the operation adds in its own {@code allOf}.
 *
 * <p>{@code stateVersion} is the instance's AFTER the call: the operator's base for the next stage. When
 * {@code unchanged} (OR-33 guard 1) nothing was written and it is the version the call started from.
 */
public record StagedConfig(long stateVersion, @WireOptional Availability availability, long generation,
        boolean unchanged) {
}
