package com.solace.sm.gateway.archfixture.credentials;

/** The legal form for the plan 015 names: a token / nonce / enrollmentToken / csrDer record that redacts. Never executed. */
public record GoodTokenRequest(String token, String nonce, String enrollmentToken, byte[] csrDer) {

    @Override
    public String toString() {
        return "GoodTokenRequest[REDACTED]";
    }
}
