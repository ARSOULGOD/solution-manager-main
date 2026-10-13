package com.solace.sm.gateway.http;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Request body of {@code createUser} (console.yaml {@code CreateUserRequest}; {@code additionalProperties: false}):
 * the new user's username and provisional credential (plan 013 T3.2; D-REG 5.4 U2).
 *
 * <p>The SHAPE is checked here so a bad request is {@code 400} and reaches no port: a username of 1 to 64 characters
 * (code points, as the schema counts them) and a credential of 12 to 1024 (the floor {@code rotateOwnCredential} applies).
 * What a username may CONTAIN (no whitespace, no zero-width or bidi character, NFC) is {@code PrincipalAdministration}'s
 * rule, and UNIQUENESS is the database's ({@code principal_org_username_ci_uq}, per organization): neither is repeated
 * here. There is no organization in the body ({@code I5}).
 *
 * <p>Both components are {@link JsonNode}s on purpose ({@code NodeNameRequest} is the precedent): a {@code String}
 * component would let Jackson coerce the number {@code 123456789012} to a twelve-character credential, and the contract
 * says a credential is a string. Spring Boot disables Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} globally, so the
 * any-setter that refuses is what makes {@code additionalProperties: false} real.
 *
 * <p>⛔ {@code I6}: {@code initialCredential} is write-only. It appears in no response, no exception message, no audit
 * row and no log line, {@link #toString} never prints it, and {@link #credential()} hands out a fresh {@code char[]} the
 * caller zeroises. (The request's own {@code String} is the JDK's to reclaim; no code here can scrub it.)
 */
public record CreateUserRequest(JsonNode username, JsonNode initialCredential) {

    public static final int MAX_USERNAME_LENGTH = 64;
    public static final int MIN_CREDENTIAL_LENGTH = 12;
    public static final int MAX_CREDENTIAL_LENGTH = 1024;

    public CreateUserRequest {
        requireText(username, 1, MAX_USERNAME_LENGTH, "username");
        requireText(initialCredential, MIN_CREDENTIAL_LENGTH, MAX_CREDENTIAL_LENGTH, "initialCredential");
    }

    static void requireText(JsonNode node, int minimum, int maximum, String field) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " is required and a string");
        }
        String text = node.textValue();
        int length = text.codePointCount(0, text.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " is " + minimum + " to " + maximum + " characters");
        }
    }

    /** The username exactly as received (the service decides what it may contain). */
    public String usernameValue() {
        return username.textValue();
    }

    /** A fresh array each call; the caller zeroises it. */
    public char[] credential() {
        return initialCredential.textValue().toCharArray();
    }

    @JsonAnySetter
    void refuseUnknown(String name, Object value) {
        throw new IllegalArgumentException("unknown property");
    }

    @Override
    public String toString() {
        return "CreateUserRequest[username=" + (username == null ? null : username.asText())
                + ", initialCredential=[REDACTED]]";
    }
}
