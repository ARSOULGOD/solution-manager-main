package com.solace.sm.dp.api.inventory;

/**
 * A private key that cannot be used as a target credential: it is not text sshj can open as an UNENCRYPTED private key
 * (plan 013 T1.1, MF-R1). The transport opens a stored key with no passphrase finder, so a key that needs one could
 * never be used; it is refused before it is stored.
 *
 * <p><b>The text is fixed and there is no cause.</b> A parser's message can quote key text ({@code SshjDockerTransport
 * .loadKey}'s standing rule), so nothing the parser said is kept, not even as a cause (I6).
 *
 * <p>An {@link IllegalArgumentException}: the caller supplied something unacceptable, and the operator command's
 * existing handling of that class (exit code 2, "was not accepted") applies unchanged.
 */
public final class CredentialInvalidException extends IllegalArgumentException {

    public static final String MESSAGE = "the private key is not an unencrypted SSH private key that can be opened";

    private static final long serialVersionUID = 1L;

    public CredentialInvalidException() {
        super(MESSAGE);
    }
}
