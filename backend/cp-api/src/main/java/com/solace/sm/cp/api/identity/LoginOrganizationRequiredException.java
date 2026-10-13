package com.solace.sm.cp.api.identity;

/**
 * A login omitted its organization on an install that does not hold exactly one ({@code OR-131} Q3): there is no
 * organization to default to, so the caller must name one. Deliberately NOT a {@link LoginRefusedException}: it is a
 * shape fault of the request (HTTP 400), raised before any credential is examined, and the owner accepted that it reveals
 * that the install holds more than one organization. An organization that was NAMED and does not exist is never this: it
 * is refused exactly as a wrong credential is.
 */
public final class LoginOrganizationRequiredException extends RuntimeException {

    public LoginOrganizationRequiredException() {
        super("organization is required");
    }
}
