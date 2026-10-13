package com.solace.sm.cp.api.authz;

/**
 * The resource does not exist, <b>or</b> exists outside every scope the caller holds.
 *
 * <p>⛔ <b>The two cases are deliberately indistinguishable.</b> Answering {@code 403} for
 * "exists but not yours" and {@code 404} for "does not exist" tells an unauthorized caller
 * which instance ids are real — existence leaks across a scope boundary, which is exactly
 * what the boundary is for. Both answer {@code 404}.
 */
public final class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(ResourceKind kind) {
        super(kind.name().toLowerCase().replace('_', ' ') + " not found");
    }

    /**
     * For a thing that has no {@link ResourceKind} because it is not itself authorized as a resource (a role
     * assignment is authorized through the scope it binds). {@code subject} is a fixed noun chosen by the caller's
     * code, never a value from the request. The gateway renders every not-found as the same generic {@code NOT_FOUND}.
     */
    public ResourceNotFoundException(String subject) {
        super(java.util.Objects.requireNonNull(subject, "subject") + " not found");
    }
}
