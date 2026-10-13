package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.LocalOperation;
import com.solace.sm.cp.api.authz.Permission;
import java.util.Objects;

/**
 * How {@link OperationPolicy} classifies one {@code @ConsoleOperation} value.
 *
 * <p>Exactly four shippable kinds exist, mirroring {@code ConsoleOperations}' own javadoc: an
 * operation that establishes the session, one authorized by the session itself
 * ({@link LocalOperation}), one authorized by a catalog verb ({@link Permission}), or one that is
 * authenticated-only but NOT one of {@link LocalOperation}'s three session-exempt operations — the
 * three hierarchy reads (console-integration.md §5.2 rows 8-10), which derive their result from
 * the caller's own bindings rather than from a fixed verb or a fixed session field. Anything else
 * is not a member of this sealed hierarchy and {@link OperationPolicy#classify(String)} throws
 * instead of inventing a fifth kind.
 */
public sealed interface OperationClassification {

    /** {@code login} — no session exists yet, so nothing about a prior session can be checked. */
    record SessionEstablishing() implements OperationClassification {
    }

    /** Authorized by the session itself, regardless of any catalog binding. */
    record Local(LocalOperation operation) implements OperationClassification {
        public Local {
            Objects.requireNonNull(operation, "operation");
        }
    }

    /** Authorized by holding {@code permission} at the target resource's scope. */
    record Catalog(Permission permission) implements OperationClassification {
        public Catalog {
            Objects.requireNonNull(permission, "permission");
        }
    }

    /**
     * S0 point 24, {@code x-sm-scope: reach}: a list or fan-out over items at many scopes, which no
     * single path resource can authorize. The interceptor checks that {@code permission} is held at
     * one or more grants in the session's organization and resolves NO resource — so, like
     * {@link AuthenticationOnly}, it must never declare {@link ConsoleOperation#resource()}. The
     * handler is then obliged to filter its items through {@code ReachAuthorization#authorizedSubset}
     * (or, for a fan-out, to {@code require} each member at its own scope); the pre-check alone is
     * NOT an authorization of any item.
     */
    record Reach(Permission permission) implements OperationClassification {
        public Reach {
            Objects.requireNonNull(permission, "permission");
        }
    }

    /**
     * Authenticated only — no catalog verb, and NOT one of {@link LocalOperation}'s three
     * session-exempt operations, so it must NOT be reachable while {@code must_rotate} is set
     * ({@code ForcedRotationGate#check(Session)}'s fail-closed overload, not the local-operation
     * one). Never carries a {@code ConsoleOperation#resource()} — there is nothing for
     * {@link AuthenticationInterceptor} to resolve or authorize against; the handler derives its
     * own result from the session's bindings (e.g. {@code HierarchyDirectory}).
     */
    record AuthenticationOnly() implements OperationClassification {
    }
}
