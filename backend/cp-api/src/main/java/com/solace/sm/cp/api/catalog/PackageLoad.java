package com.solace.sm.cp.api.catalog;

import java.util.Objects;

/**
 * The outcome of one {@link SolutionCatalog#load}: the stored package, and whether this call stored it.
 * {@code newlyLoaded == false} is the idempotent re-load of an identical {@code (solution, packageVersion)}
 * with an identical digest: nothing was written and nothing was audited.
 */
public record PackageLoad(LoadedPackage loaded, boolean newlyLoaded) {

    public PackageLoad {
        Objects.requireNonNull(loaded, "loaded");
    }
}
