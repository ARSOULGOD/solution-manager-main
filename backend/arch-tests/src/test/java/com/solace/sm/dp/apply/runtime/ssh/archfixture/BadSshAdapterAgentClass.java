package com.solace.sm.dp.apply.runtime.ssh.archfixture;

import com.solace.sm.dp.apply.runtime.agent.AgentHashBridge;

/**
 * A planted violation of {@code theDpRemoteAdaptersReachNoAgentType} (plan 015 T3.1): a class in the dp-remote adapter's package
 * naming an agent-path type. The two transports meet only in {@code RoutingRuntimeAdapter}, which names neither's internals.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadSshAdapterAgentClass {

    private AgentHashBridge bridge;

    public AgentHashBridge bridge() {
        return bridge;
    }
}
