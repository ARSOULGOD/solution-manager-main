package com.solace.sm.dp.api.inventory;

/**
 * The fields {@code editTargetConnection} may change (plan 013 T1.5, D-REG section 5.1 T4): the host, the port, the SSH
 * user and the pinned host key. Each is {@code null} when the caller does not change it. The envelope
 * ({@code allowedConfigRoot}, {@code allowedContainerPrefix}) is deliberately not here: it is immutable once registered
 * ({@code OR-52}), and the type has nowhere to put it. The private key is not here either: it is replaced by the
 * rotation, never by an edit.
 *
 * <p>A value equal to the stored one is not a change. {@link #isEmpty} says nothing was named at all.
 */
public record TargetConnectionEdit(String host, Integer port, String sshUser, String hostKeyFingerprint) {

    public boolean isEmpty() {
        return host == null && port == null && sshUser == null && hostKeyFingerprint == null;
    }
}
