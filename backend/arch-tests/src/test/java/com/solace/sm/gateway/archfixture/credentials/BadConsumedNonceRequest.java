package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: a field ending in {@code Nonce} with the generated toString. Never executed. */
public record BadConsumedNonceRequest(String consumedNonce, Long baseStateVersion) {
}
