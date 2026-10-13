package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;

/**
 * Request body of {@code rotateTargetCredential} (console.yaml {@code RotateTargetCredentialRequest}; {@code
 * additionalProperties: false}). A structural fault (a missing or empty key, a key past its length, a stray property) is
 * {@code 400}; whether the key OPENS as an unencrypted private key is the data plane's and is {@code 422
 * CREDENTIAL_INVALID}.
 *
 * <p>⛔ {@code I6}: {@code privateKey} is the key file's text. It is write-only: it appears in no response, no exception
 * message, no audit row and no log line, and {@link #toString} never prints it. The handler that takes this record is
 * {@link CredentialBearing}, so a channel that is not secure was refused before this body was read.
 */
public record RotateTargetCredentialRequest(String privateKey) {

    public RotateTargetCredentialRequest {
        if (privateKey == null || privateKey.isEmpty() || privateKey.length() > RegisterTargetRequest.MAX_PRIVATE_KEY_CHARS) {
            throw new IllegalArgumentException("privateKey is required and at most 16384 characters");
        }
    }

    @Override
    public String toString() {
        return "RotateTargetCredentialRequest[privateKey=[REDACTED]]";
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }
}
