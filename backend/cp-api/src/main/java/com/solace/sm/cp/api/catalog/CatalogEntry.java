package com.solace.sm.cp.api.catalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One catalog solution of an organization, as {@code listSolutions} returns it.
 *
 * @param solutionId           the catalog row's own id ({@code OR-41})
 * @param solution             the package's own declared {@code solution} string
 * @param name                 the display name; a package declares none, so this is {@code solution}
 * @param packageVersions      every package version id loaded for this solution across every load, each once, in
 *                             the order first loaded
 * @param latestPackageVersion the last-listed member of the most recently loaded package's {@code packageVersion}
 * @param stateVersion         the highest {@code load_seq} among the entry's packages
 */
public record CatalogEntry(UUID solutionId, String solution, String name, List<PackageVersionId> packageVersions,
        PackageVersionId latestPackageVersion, String signature, long stateVersion) {

    public CatalogEntry {
        Objects.requireNonNull(solutionId, "solutionId");
        Objects.requireNonNull(solution, "solution");
        Objects.requireNonNull(name, "name");
        packageVersions = List.copyOf(Objects.requireNonNull(packageVersions, "packageVersions"));
        Objects.requireNonNull(latestPackageVersion, "latestPackageVersion");
        if (!packageVersions.contains(latestPackageVersion)) {
            throw new IllegalArgumentException("the latest package version is a member of packageVersions");
        }
        Objects.requireNonNull(signature, "signature");
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion is not negative");
        }
    }
}
