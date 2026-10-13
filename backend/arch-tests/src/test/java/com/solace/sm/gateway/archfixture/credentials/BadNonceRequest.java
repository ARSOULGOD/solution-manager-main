package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: a nonce-named String field with the generated toString. Never executed. */
public record BadNonceRequest(String nonce, Long baseStateVersion) {
}
