package com.solace.sm.cp.api.identity;

import java.util.Arrays;
import java.util.Objects;

/**
 * Local-login credentials: the organization the user signs in to, the username within it, and the credential. The
 * credential is never rendered by {@link #toString()}.
 *
 * <p>{@code organization} is what the user typed: an organization's id (a canonical UUID) or its name (compared without
 * regard to case). It is deliberately <b>unresolved</b> here: a name that matches no organization, an empty one and a
 * well-formed id that names nothing are all refused exactly as a wrong credential is ({@code OR-125 (4)}), so nothing
 * about which organizations exist can be learned from a login. A username is unique per organization, not install-wide.
 *
 * <p>{@code organization} may be {@code null}: OMITTED ({@code OR-131} Q3). On an install holding exactly one
 * organization that is the organization; with none or several the login is refused as
 * {@link LoginOrganizationRequiredException}.
 */
public final class LocalCredentials {

    private final String organization;
    private final String username;
    private final char[] credential;

    public LocalCredentials(String organization, String username, char[] credential) {
        this.organization = organization;
        this.username = Objects.requireNonNull(username, "username");
        this.credential = Arrays.copyOf(Objects.requireNonNull(credential, "credential"), credential.length);
    }

    /** What the user typed, untrimmed (W5D-7), or {@code null} when omitted. */
    public String organization() {
        return organization;
    }

    public String username() {
        return username;
    }

    public char[] credential() {
        return Arrays.copyOf(credential, credential.length);
    }

    @Override
    public String toString() {
        return "LocalCredentials[organization=" + organization + ", username=" + username
                + ", credential=[redacted]]";
    }
}
