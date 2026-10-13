package com.solace.sm.dp.api.inventory;

import java.util.UUID;

/**
 * Checks that a registered target can be reached, authenticated to and used for Docker (plan 013 T1.5, D-REG section 5.1
 * T6, OQ-9 (a)). Connects with the pinned host key (the address policy first), authenticates with the stored key and runs
 * ONE fixed command, {@code docker version --format '{{.Server.Version}}'}; no input reaches it. Writes nothing.
 */
public interface TargetConnectionTester {

    /**
     * @throws java.util.NoSuchElementException the target has no live connection record
     */
    TargetConnectionTestResult test(UUID organizationId, UUID targetId);
}
