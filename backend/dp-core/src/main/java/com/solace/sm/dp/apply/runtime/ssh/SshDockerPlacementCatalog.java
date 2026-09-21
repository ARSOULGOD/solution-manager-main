package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.apply.runtime.PlacementRef;

/** Resolves a placement to one declared SSH Docker host binding. */
@FunctionalInterface
public interface SshDockerPlacementCatalog {
    SshDockerPlacement lookup(PlacementRef placement);
}
