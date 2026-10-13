package com.solace.sm.gateway.http;

/**
 * Wire shape of {@code setConfigSecret}'s 200: console.yaml's plain {@code StagedConfig} ({@code Versioned} +
 * {@code generation}), WITHOUT {@code unchanged}, which only {@code stageConfig} adds in its own {@code allOf}
 * ({@link StagedConfig}). Storing a secret always mints a new reference and so always a new generation.
 *
 * <p>⛔ {@code I6}: this operation never returns the value, a reference or a field. A caller who wants to see the field
 * as it now is refetches the designer ({@code kind: secretRef}).
 */
public record ConfigSecretResult(long stateVersion, @WireOptional Availability availability, long generation) {
}
