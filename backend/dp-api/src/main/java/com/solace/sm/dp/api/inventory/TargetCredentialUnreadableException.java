package com.solace.sm.dp.api.inventory;

/**
 * A registered target's stored private key could not be OPENED at all (a missing secret row, a key the ring no longer
 * holds), so a replayed registration has nothing to compare the sent key with (plan 013 review {@code W3C-4}; W1 review
 * C-6). That is not "a different key" ({@link TargetCredentialConflictException}): answering {@code 409} "the key
 * differs" would steer the caller away from the one thing that repairs it, rotating the credential, which a rotation
 * accepts exactly because it replaces what nobody can open.
 *
 * <p>The text is fixed and carries no cause: a secret-store message is not ours to forward ({@code I6}).
 */
public final class TargetCredentialUnreadableException extends IllegalStateException {

    public static final String MESSAGE = "the stored credential could not be opened";

    private static final long serialVersionUID = 1L;

    public TargetCredentialUnreadableException() {
        super(MESSAGE, null);
    }
}
