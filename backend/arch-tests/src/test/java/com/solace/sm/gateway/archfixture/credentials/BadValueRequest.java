package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: the secret literal field is named {@code value} and the record prints it. Never executed. */
public record BadValueRequest(String value, Long baseStateVersion) {
}
