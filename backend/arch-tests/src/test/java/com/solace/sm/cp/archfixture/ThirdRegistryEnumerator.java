package com.solace.sm.cp.archfixture;

import com.solace.sm.cp.agent.AgentRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Planted violation (plan 015 T4.6, {@code OR-140}): a second caller of {@code AgentRegistry#organizationsWithAbandonable}, the
 * registry's cross-organization read; its one named caller is the sweep service that answers the port the job calls.
 * {@code TenantEnumerationBoundaryTest} must report this class.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class ThirdRegistryEnumerator {

    private ThirdRegistryEnumerator() {
    }

    public static List<UUID> thirdCaller(AgentRegistry registry) {
        return registry.organizationsWithAbandonable(Instant.now());
    }
}
