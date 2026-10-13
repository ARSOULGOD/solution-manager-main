package com.solace.sm.dp.api.inventory;

/**
 * A target's connection record changed under a registration (or a rotation) that was writing it: a second concurrent
 * request won twice, or a row moved between the read and the write (plan 013 review {@code W3C-4}). Not "a different
 * connection record" than the one the caller sent: the caller did nothing wrong and may simply retry.
 */
public final class TargetConnectionChangedException extends IllegalStateException {

    public static final String MESSAGE = "the connection record changed while it was being written";

    private static final long serialVersionUID = 1L;

    public TargetConnectionChangedException() {
        super(MESSAGE, null);
    }
}
