package com.solace.sm.cp.api.authz;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The API-operation → verb map, and the gate that keeps an unauthorizable operation from
 * shipping.
 *
 * <p><b>What this exists to prevent.</b> Three things, each of which has actually happened in
 * a console somewhere:
 * <ol>
 *   <li>an operation with <i>no</i> verb, reachable because nobody wired a check;</li>
 *   <li>an operation mapped to a verb nobody ruled on, defaulting to {@code STANDARD};</li>
 *   <li>two operations claiming the same id, so the map silently loses one.</li>
 * </ol>
 * Registration refuses all three.
 *
 * <p>⚠️ This is a <b>map</b>, not an enforcer. {@link AuthorizationService} decides. Keeping
 * them apart is what lets the registry be asserted exhaustively in a unit test while
 * enforcement is asserted against a real principal.
 */
public final class OperationRegistry {

    private final Map<String, Permission> byOperation;

    private OperationRegistry(Map<String, Permission> byOperation) {
        this.byOperation = Collections.unmodifiableMap(byOperation);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * The verb that authorizes {@code operationId}.
     *
     * @return empty when the operation is not registered — ⚠️ which the caller must treat as
     *         {@code 501}, not as {@code 403}. "No verb" and "verb not held" are different
     *         answers to the operator.
     */
    public Optional<Permission> permissionFor(String operationId) {
        return Optional.ofNullable(byOperation.get(operationId));
    }

    public Map<String, Permission> all() {
        return byOperation;
    }

    public int size() {
        return byOperation.size();
    }

    public static final class Builder {

        private final Map<String, Permission> map = new LinkedHashMap<>();

        /**
         * @throws UnshippableOperationException if the verb's class is {@code UNDECIDED}
         * @throws IllegalStateException         if the operation id is already registered
         */
        public Builder register(String operationId, Permission permission) {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(permission, "permission");
            if (operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
            }
            if (permission.consequence() == ConsequenceClass.UNDECIDED) {
                throw new UnshippableOperationException(operationId, permission);
            }
            Permission existing = map.putIfAbsent(operationId, permission);
            if (existing != null) {
                throw new IllegalStateException("operation '" + operationId
                        + "' is already registered against '" + existing.id()
                        + "'; a second registration would silently replace the first");
            }
            return this;
        }

        public OperationRegistry build() {
            return new OperationRegistry(new LinkedHashMap<>(map));
        }
    }
}
