package com.solace.sm.demo;

import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the one browser-declared demo target after validated registration. */
final class DemoPlacementCatalog implements SshDockerPlacementCatalog {
    private final AtomicReference<SshDockerPlacement> placement = new AtomicReference<>();

    void register(SshDockerPlacement declared) {
        placement.set(declared);
    }

    @Override
    public SshDockerPlacement lookup(PlacementRef requested) {
        if (!DemoConfiguration.DEMO_PLACEMENT.equals(requested) || placement.get() == null) {
            throw new IllegalArgumentException("the demo instance is not registered");
        }
        return placement.get();
    }
}
