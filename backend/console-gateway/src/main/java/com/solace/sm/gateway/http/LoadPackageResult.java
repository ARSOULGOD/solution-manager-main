package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code LoadPackageResult} ({@code Versioned} + the loaded entry). {@code solutionId}
 * is the catalog row's own id, never the package's declared {@code solution}; {@code packageVersion} is the
 * document's own array. {@code signature} is the closed placeholder {@code "unsigned"}: v1 loads no signed
 * packages ({@code OR-40}).
 */
public record LoadPackageResult(long stateVersion, @WireOptional Availability availability, UUID solutionId,
        List<Object> packageVersion, String signature) {

    public LoadPackageResult {
        Objects.requireNonNull(solutionId, "solutionId");
        packageVersion = List.copyOf(Objects.requireNonNull(packageVersion, "packageVersion"));
        Objects.requireNonNull(signature, "signature");
    }
}
