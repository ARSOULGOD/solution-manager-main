package com.solace.sm.dp.secret;

/**
 * A secret-store failure, classified. The message is a stable code, optionally followed by a key id (a
 * label) and never by a secret id, a tenant, ciphertext, plaintext or the AAD. No cause is chained: the
 * provider's own message (or an {@code AEADBadTagException}'s) is not ours to forward, and a chained
 * cause's message is a leak path (D-KEY 3.3).
 */
public final class SecretException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The closed set of classifiers. */
    public enum Code {
        AUTHENTICATION_FAILED("secret.authentication.failed"),
        KEY_UNAVAILABLE("secret.key.unavailable"),
        NOT_FOUND("secret.not.found"),
        PURPOSE_MISMATCH("secret.purpose.mismatch");

        private final String id;

        Code(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final Code code;

    SecretException(Code code) {
        super(code.id());
        this.code = code;
    }

    SecretException(Code code, KeyId keyId) {
        super(code.id() + ": " + keyId);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
