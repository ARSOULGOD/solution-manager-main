package com.solace.sm.gateway.archfixture.credentials;

/** The legal form: a credential-bearing record that overrides {@code toString} and redacts. Never executed. */
public record GoodCredentialRequest(String name, String privateKey) {

    @Override
    public String toString() {
        return "GoodCredentialRequest[name=" + name + ", privateKey=[REDACTED]]";
    }
}
