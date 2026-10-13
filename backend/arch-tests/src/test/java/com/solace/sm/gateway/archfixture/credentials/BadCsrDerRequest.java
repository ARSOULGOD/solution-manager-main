package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: a csrDer byte[] with the generated toString. Never executed. */
public record BadCsrDerRequest(byte[] csrDer, Long baseStateVersion) {
}
