package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body of {@code resetUserCredential} (console.yaml, an inline {@code required: [resetCredential]} object;
 * {@code additionalProperties: false}): the user's new provisional credential, 12 to 1024 characters (plan 013 T3.2;
 * D-REG 5.4 U3).
 *
 * <p>Shaped exactly as {@link CreateUserRequest}'s credential: a {@link JsonNode} so a number is not coerced into one, the
 * any-setter that refuses a stray key, and ⛔ {@code I6}: write-only, never printed, handed out as a fresh {@code char[]}.
 */
public record ResetUserCredentialRequest(JsonNode resetCredential) {

    public ResetUserCredentialRequest {
        CreateUserRequest.requireText(resetCredential, CreateUserRequest.MIN_CREDENTIAL_LENGTH,
                CreateUserRequest.MAX_CREDENTIAL_LENGTH, "resetCredential");
    }

    /** A fresh array each call; the caller zeroises it. */
    public char[] credential() {
        return resetCredential.textValue().toCharArray();
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    @Override
    public String toString() {
        return "ResetUserCredentialRequest[resetCredential=[REDACTED]]";
    }
}
