package com.solace.sm.gateway.http;

/**
 * An undeclare found its own data-plane record older than half the roll-back window, so it did NOT write the control plane
 * ({@code ops-501.md} section 4.4, "the window guard"): the lazy sweep may roll the record back at any moment now, and a
 * control-plane release that landed after a roll-back would leave a retired governance node over an {@code active}
 * instance. Answered {@code 503}; the row is the sweep's. Once the whole window has passed, a retry of the DELETE itself rolls the row back
 * (W5 review W5B-4) and proceeds; until then the caller waits. Carries no id.
 */
public final class UndeclareWindowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UndeclareWindowException() {
        super("the undeclare's record is too old to continue safely");
    }
}
