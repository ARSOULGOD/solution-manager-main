package com.solace.sm.gateway.archfixture.credentials;

import com.solace.sm.gateway.http.ConsoleOperation;

/**
 * Planted violation (plan 013 OQ-14 (b)): a handler that takes a request carrying a private key and is NOT marked
 * {@code @CredentialBearing}, so the transport check would never see it. Never executed: it exists only to be analysed.
 * <b>Do not copy this pattern into production code.</b>
 */
public final class UnmarkedCredentialHandler {

    @ConsoleOperation("registerTarget")
    public Object register(GoodCredentialRequest request) {
        return request;
    }
}
