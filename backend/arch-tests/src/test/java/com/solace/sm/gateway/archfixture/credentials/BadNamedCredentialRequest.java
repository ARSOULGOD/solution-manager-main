package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: a field whose name ends in {@code Credential} that the record prints. Never executed. */
public record BadNamedCredentialRequest(String username, String initialCredential) {
}
