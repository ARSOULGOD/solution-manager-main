package com.solace.sm.dp.api.agw;

/** Why the gateway closes a claimed session ({@link AgentSessionPort#close}). */
public enum CloseReason {
    /** The stream ended (completed, errored, torn down) — the default. */
    STREAM_CLOSED,
    /** A newer session of the same agent owns it. */
    SUPERSEDED,
    /** The identity was revoked. */
    REVOKED,
    /** A planned drain ended it. */
    DRAINED,
    /** The open was refused or never completed. */
    OPEN_REFUSED
}
