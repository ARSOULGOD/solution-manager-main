package com.solace.sm.dp.apply.runtime.ssh.archfixture;

import com.solace.sm.dp.api.agent.AgentSessionFacts;

/**
 * A planted violation of {@code theDpRemoteAdaptersReachNoAgentType} (plan 015 T4.3): a class in the dp-remote adapter's package
 * naming a type of {@code dp.api.agent}, the package of the console's agent reads.
 *
 * <p>Excluded from the real {@code @ArchTest} run by {@code ExcludeArchFixtures}.
 */
public final class BadSshAdapterSessionFactsClass {

    private AgentSessionFacts facts;

    public AgentSessionFacts facts() {
        return facts;
    }
}
