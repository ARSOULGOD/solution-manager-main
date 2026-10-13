package com.solace.sm.dp.api.inventory;

/**
 * A host key of this machine is already registered to another live target of the organization (plan 013 T1.4, V007's
 * {@code target_connection_host_once} and V012's {@code target_host_key_once}): the same machine cannot be registered
 * twice, whichever of its key types is pinned. Raised by the pre-check's probe and, for the race between the probe and
 * the insert, by the insert itself. The message is fixed: it names neither the other target nor any fingerprint.
 */
public final class HostKeyHeldElsewhereException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HostKeyHeldElsewhereException() {
        super("a host key of this machine is registered to another target");
    }
}
