package com.solace.sm.cp.api.authz;

/**
 * Thrown when a verb id names nothing in the catalog.
 *
 * <p>⛔ This is deliberately an exception rather than an empty {@code Optional}. A miss means
 * the caller is naming a verb the owner did not approve, and the operation must answer
 * {@code 501} rather than quietly evaluating to "not permitted".
 */
public final class UnknownPermissionException extends RuntimeException {

    private final String id;

    public UnknownPermissionException(String id) {
        super("'" + id + "' is not a member of the ratified 38-verb catalog. "
                + "An operation needing it answers 501, not 403: widening the catalog is an "
                + "owner ruling, not a code change.");
        this.id = id;
    }

    public String id() {
        return id;
    }
}
