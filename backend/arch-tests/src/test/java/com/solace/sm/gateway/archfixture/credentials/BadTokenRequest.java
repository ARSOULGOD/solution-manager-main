package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: a token-named String field with the generated toString. Never executed. */
public record BadTokenRequest(String token, Long baseStateVersion) {
}
