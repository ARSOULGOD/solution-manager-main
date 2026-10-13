package com.solace.sm.dp.api.readback;

/** Why a node of the host's file was not adopted (D-ACCEPT §5). The wire spellings are the contract's closed set. */
public enum NotAcceptedReason {
    /** A secret field: its value is never stored. The draft keeps the current secret reference. */
    SECRET_NOT_READ("secret-not-read"),
    /** The package does not declare this path (or declares it spelled differently; exact kebab paths only, {@code M-1}). */
    UNDECLARED_KEY("undeclared-key"),
    /** One of SM's own overlay keys; SM writes it, so a host edit of it is not adopted. */
    SM_OWNED_OVERLAY("sm-owned-overlay"),
    /** A declared field that is inactive under the accepted values: carried into the draft, not rendered. */
    INACTIVE_FIELD("inactive-field"),
    /** A {@code yaml}-typed field: it can carry credentials the walker cannot identify (open item 22). */
    YAML_FIELD_NOT_ACCEPTED("yaml-field-not-accepted");

    private final String wire;

    NotAcceptedReason(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
