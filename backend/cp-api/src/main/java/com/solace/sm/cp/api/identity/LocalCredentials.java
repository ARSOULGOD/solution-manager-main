package com.solace.sm.cp.api.identity;

import java.util.Arrays;
import java.util.Objects;

/** Local-login credentials. The credential is never rendered by {@link #toString()}. */
public final class LocalCredentials {

    private final String username;
    private final char[] credential;

    public LocalCredentials(String username, char[] credential) {
        this.username = Objects.requireNonNull(username, "username");
        this.credential = Arrays.copyOf(Objects.requireNonNull(credential, "credential"), credential.length);
    }

    public String username() {
        return username;
    }

    public char[] credential() {
        return Arrays.copyOf(credential, credential.length);
    }

    @Override
    public String toString() {
        return "LocalCredentials[username=" + username + ", credential=[redacted]]";
    }
}
