package com.solace.sm.dp.secret;

/** Test helper: {@code SecretException}'s constructors are package-private, as they should be in production. */
public final class SecretFailures {
    private SecretFailures() {}

    public static SecretException notFound() {
        return new SecretException(SecretException.Code.NOT_FOUND);
    }
}
