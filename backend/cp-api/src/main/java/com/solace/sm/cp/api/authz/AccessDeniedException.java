package com.solace.sm.cp.api.authz;

/**
 * The principal is authenticated and does not hold the required verb at the required scope.
 *
 * <p>⚠️ Maps to {@code 403}. Distinct from {@link ResourceNotFoundException}, and the
 * difference is a confidentiality decision rather than a cosmetic one — see that class.
 */
public final class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(Permission permission) {
        super("not permitted: " + permission.id());
    }
}
