package com.solace.sm.gateway.archfixture.credentials;

import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.CredentialBearing;

/** The legal form: a handler that takes a private key and carries the mark. Never executed. */
public final class MarkedCredentialHandler {

    @CredentialBearing
    @ConsoleOperation("registerTarget")
    public Object register(GoodCredentialRequest request) {
        return request;
    }
}
