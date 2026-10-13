package com.solace.sm.gateway.http;

/**
 * Request body for the OpenAPI {@code login} operation.
 *
 * <p>{@code organization} is what the user chooses to sign in to: an organization's id or its name (compared without
 * regard to case). A username is unique per organization ({@code OR-125 (4)}), so a login names both. Its SHAPE is
 * checked here ({@code 400}: absent, empty or longer than 256, like the username); whether it names an organization is
 * not: an unknown organization is refused exactly as a wrong credential is, {@code 401 AUTHENTICATION_FAILED}, so a
 * login cannot be used to learn which organizations exist.
 *
 * <p>{@code organization} is OPTIONAL ({@code OR-131} Q3): omitted, it is the install's only organization, and a
 * {@code 400} when the install holds none or several. When present it is taken as typed: surrounding whitespace is a
 * shape fault ({@code 400}), not trimmed away (the username is not trimmed either), so what the client sent is what the
 * throttle counts.
 */
public record LoginRequest(@WireOptional String organization, String username, String credential) {

    public LoginRequest {
        if (organization != null) {
            requireLength(organization, 1, 256, "organization");
            if (!organization.equals(organization.strip())) {
                throw new IllegalArgumentException("organization must not have leading or trailing whitespace");
            }
        }
        requireLength(username, 1, 256, "username");
        requireLength(credential, 1, Integer.MAX_VALUE, "credential");
    }

    static void requireLength(String value, int minimum, int maximum, String field) {
        if (value == null || value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(field + " length must be between " + minimum + " and " + maximum);
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[organization=" + organization + ", username=" + username + ", credential=[REDACTED]]";
    }
}
