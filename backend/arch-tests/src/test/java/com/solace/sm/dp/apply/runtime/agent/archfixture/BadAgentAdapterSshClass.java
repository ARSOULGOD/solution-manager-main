package com.solace.sm.dp.apply.runtime.agent.archfixture;

import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;

/**
 * A planted violation of {@code theAgentAdapterReachesNoDpRemoteTransport} (plan 015 T3.1): a class in the agent adapter's package
 * naming a dp-remote transport type. An agent placement has no host address, user or key for SSH to use; the day the agent adapter
 * needs one is the day it has started doing the other adapter's job.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadAgentAdapterSshClass {

    private SshDockerPlacement placement;

    public SshDockerPlacement placement() {
        return placement;
    }
}
