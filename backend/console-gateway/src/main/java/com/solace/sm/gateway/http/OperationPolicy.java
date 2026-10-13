package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.ConsoleOperations;
import com.solace.sm.cp.api.authz.LocalOperation;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Classifies an OpenAPI {@code operationId} into exactly one {@link OperationClassification}.
 *
 * <p>{@code login} is the one session-establishing operation. {@code logout},
 * {@code getIdentity} and {@code rotateOwnCredential} are authorized by the session itself
 * ({@link LocalOperation}), never by a catalog verb — {@code ConsoleOperations}' own javadoc
 * says why. {@code listOrganizations}, {@code listSubOrganizations} and {@code listEnvironments}
 * (console-integration.md §5.2 rows 8-10) are authenticated-only but derive their result from the
 * caller's own bindings rather than a fixed verb, so they classify as
 * {@link OperationClassification.AuthenticationOnly} — deliberately NOT folded into
 * {@link LocalOperation}, whose three members are exempt from forced rotation; these three are
 * not; the same goes for {@code getCapabilities} (§5.4 row 53). The {@code x-sm-scope: reach} operations (S0 point 24,
 * and the seven metrics reads of §5.4 rows 54-60) are looked up in
 * {@link ConsoleOperations} for their verb and classify as {@link OperationClassification.Reach}.
 * Everything else is looked up in {@link ConsoleOperations}; a miss there is not "no verb
 * needed", it is "nobody approved a verb for this", and this method fails closed rather than
 * guessing.
 */
public final class OperationPolicy {

    private static final String LOGIN = "login";

    private static final Map<String, LocalOperation> LOCAL_OPERATIONS = Map.of(
            "logout", LocalOperation.LOGOUT,
            "getIdentity", LocalOperation.READ_OWN_IDENTITY,
            "rotateOwnCredential", LocalOperation.ROTATE_OWN_CREDENTIAL);

    /**
     * console-integration.md §5.2 rows 8-10 — see this class's javadoc for why not {@link LocalOperation} — and
     * §5.4 row 53, {@code getCapabilities} (plan 011 P11-1.2), which any authenticated principal reads.
     */
    private static final Set<String> AUTHENTICATION_ONLY_OPERATIONS =
            Set.of("listOrganizations", "listSubOrganizations", "listEnvironments", "getCapabilities",
                    // plan 013 T4, UI-D27: the connection type catalog is SM-owned, identical for every caller.
                    "getConnectionTypes");

    /**
     * S0 point 24: exactly the operations {@code console.yaml} marks {@code x-sm-scope: reach}.
     * {@code ReachOperationsMirrorTest} pins this set to the contract in both directions.
     */
    private static final Set<String> REACH_OPERATIONS = Set.of(
            "listSolutions", "getSolutionPackage", "listInstances", "listTargets", "listAgents", "getHomeSummary", "getHomeAttention",
            "submitFleetApply", "observeFleetApply",
            // console-integration.md §5.4 rows 54-60 (M-Q17, OR-79: OR-51's reach filtering governs metrics queries)
            "listEntityTypes", "listMetricInstances", "getMetricsCatalog", "getLabelValues", "queryRange",
            "queryInstant", "exportRange",
            // plan 013 T4, OR-130: shaped exactly like listTargets.
            "listConnections");

    private OperationPolicy() {
    }

    /** The operations classified {@link OperationClassification.Reach}; see {@code REACH_OPERATIONS}. */
    static Set<String> reachOperations() {
        return REACH_OPERATIONS;
    }

    /**
     * @throws UnclassifiableOperationException if {@code operationId} is none of: {@code login},
     *         a {@link LocalOperation}, one of the authentication-only hierarchy reads, or a
     *         registered catalog verb
     */
    public static OperationClassification classify(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        if (LOGIN.equals(operationId)) {
            return new OperationClassification.SessionEstablishing();
        }
        LocalOperation local = LOCAL_OPERATIONS.get(operationId);
        if (local != null) {
            return new OperationClassification.Local(local);
        }
        if (AUTHENTICATION_ONLY_OPERATIONS.contains(operationId)) {
            return new OperationClassification.AuthenticationOnly();
        }
        // A reach operation is still a registered catalog verb (its permission comes from the same
        // registry, so an unregistered one stays unclassifiable); only the CHECK differs.
        boolean reach = REACH_OPERATIONS.contains(operationId);
        return ConsoleOperations.permissionFor(operationId)
                .<OperationClassification>map(permission -> reach
                        ? new OperationClassification.Reach(permission)
                        : new OperationClassification.Catalog(permission))
                .orElseThrow(() -> new UnclassifiableOperationException(operationId));
    }
}
