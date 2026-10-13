package com.solace.sm.cp.api.catalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One package as stored. {@code solutionId} is the catalog row's own id ({@code OR-41}), never the package's
 * declared {@code solution} string. {@code stateVersion} is the catalog entry's, not the package's: the highest
 * {@code load_seq} among the entry's packages, so it rises on every load.
 *
 * @param packageVersion the document's own {@code packageVersion} array, in the document's order
 * @param digest         {@code sha256:} + the hex digest of the canonical document text
 * @param signature      {@code "unsigned"} in v1 ({@code OR-40}): nothing here verifies a signature
 */
public record LoadedPackage(UUID solutionId, String solution, List<PackageVersionId> packageVersion, String digest,
        String signature, long stateVersion) {

    public LoadedPackage {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(solution, "solution");
        packageVersion = List.copyOf(Objects.requireNonNull(packageVersion, "packageVersion"));
        if (packageVersion.isEmpty()) {
            throw new IllegalArgumentException("a package serves at least one package version");
        }
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(signature, "signature");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion is not negative");
        }
    }
}
