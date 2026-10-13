package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire shape of console.yaml's {@code Solution}: one catalog entry. {@code solutionId} is the catalog row's own id
 * ({@code OR-41}); {@code stateVersion} is the entry's, and rises on every load into it.
 *
 * <p>{@code packageVersions} and {@code latestPackageVersion} carry {@code PackageVersionId}s - an integer
 * {@code >= 1} or a non-empty string - as the bare {@link Long} or {@link String} Jackson writes as itself.
 */
public record Solution(UUID solutionId, String solution, String name, List<Object> packageVersions,
        Object latestPackageVersion, String signature, long stateVersion) {

    public Solution {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(name, "name");
        packageVersions = List.copyOf(Objects.requireNonNull(packageVersions, "packageVersions"));
        Objects.requireNonNull(latestPackageVersion, "latestPackageVersion");
        Objects.requireNonNull(signature, "signature");
    }
}
