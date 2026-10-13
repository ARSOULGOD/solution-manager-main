package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code SolutionPackageDocument} ({@code Versioned} + the raw document). {@code
 * solutionId} echoes the path; {@code packageVersion} is the single member this response resolved to — the
 * request's own, or the entry's {@code latestPackageVersion} when the request omitted one — never the whole
 * array ({@link Solution#packageVersions} carries that). {@code pkg} is the raw document exactly as loaded.
 */
public record SolutionPackageDocument(long stateVersion, @WireOptional Availability availability, UUID solutionId,
        Object packageVersion, @JsonProperty("package") JsonNode pkg) {

    public SolutionPackageDocument {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(packageVersion, "packageVersion");
        Objects.requireNonNull(pkg, "pkg");
    }
}
