package com.solace.sm.dp.api.inventory;

/**
 * A registration that names an already registered target with the same connection record but a DIFFERENT private key
 * (plan 013 T1.1; D-REG section 8). Over HTTP, silently keeping the stored key would drop the key the user just sent;
 * this is the {@code 409}. The key is compared by the fingerprint of its PUBLIC half, so a re-export of the same key
 * (another file format, other line ends, a trailing newline) is a replay and never raises this.
 *
 * <p>The text is fixed: it names the remedy, never the key or either fingerprint.
 */
public final class TargetCredentialConflictException extends IllegalStateException {

    public static final String MESSAGE =
            "the target is registered with a different private key; rotate its credential instead";

    private static final long serialVersionUID = 1L;

    public TargetCredentialConflictException() {
        super(MESSAGE);
    }
}
