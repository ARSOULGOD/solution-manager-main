package com.solace.sm.gateway.archfixture.credentials;

/** Planted violation: an enrollmentToken field with the generated toString. Never executed. */
public record BadEnrollmentTokenRequest(String enrollmentToken, Long baseStateVersion) {
}
