package com.solace.sm.dp.apply.persistence.agent.archfixture;

import com.solace.sm.dp.apply.persistence.agent.JdbcAgentIdentityRepository;

/**
 * A planted SECOND caller of the one named unbound read, {@code JdbcAgentIdentityRepository#resolveByThumbprint} (plan 015
 * T2.1b, {@code I5}, P15-19): only the session and enrolment repositories may resolve a certificate to an identity.
 */
public final class BadThumbprintResolver {

    private BadThumbprintResolver() {
    }

    public static Object resolve(JdbcAgentIdentityRepository identities, String thumbprint) {
        return identities.resolveByThumbprint(thumbprint);
    }
}
