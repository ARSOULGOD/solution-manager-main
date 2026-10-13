package com.solace.sm.gateway.http;

/** Request body for the OpenAPI {@code rotateOwnCredential} operation. */
public record RotateOwnCredentialRequest(String currentCredential, String newCredential) {

    public RotateOwnCredentialRequest {
        LoginRequest.requireLength(currentCredential, 0, Integer.MAX_VALUE, "currentCredential");
        LoginRequest.requireLength(newCredential, 12, Integer.MAX_VALUE, "newCredential");
    }

    @Override
    public String toString() {
        return "RotateOwnCredentialRequest[currentCredential=[REDACTED], newCredential=[REDACTED]]";
    }
}
