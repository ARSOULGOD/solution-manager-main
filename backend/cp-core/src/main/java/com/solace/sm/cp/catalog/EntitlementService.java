package com.solace.sm.cp.catalog;

import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.PrincipalRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Entitlements ({@code OR-40}): which solutions an organization may run, and how many instances of each.
 *
 * <p>The only write path is the {@code EntitlementMain} operator command ({@code OR-48}); nothing over HTTP sets
 * an entitlement in v1. It writes with audit actor {@code NULL}, which V013 admits for {@code ENTITLEMENT_SET}
 * on the {@code entitlement} table only. The row's own {@code set_by} must still name a principal, so the
 * command names one: the person on whose standing the operator acts, recorded on the row and never as the audit
 * actor.
 */
public final class EntitlementService {

    /** The same shape {@code catalog_solution_name} enforces, so an entitlement can name a loadable solution. */
    private static final Pattern SOLUTION = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    /** Whether {@code solution} can name a catalog entry; the operator command asks this before it connects. */
    public static boolean isValidSolution(String solution) {
        return solution != null && SOLUTION.matcher(solution).matches();
    }

    /** The principal an entitlement is attributed to does not exist in the organization, or cannot be one. */
    public static final class UnknownSetByPrincipalException extends RuntimeException {
        public UnknownSetByPrincipalException() {
            super("no active local principal of that organization has that username");
        }
    }

    private final EntitlementRepository entitlements;
    private final PrincipalRepository principals;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public EntitlementService(EntitlementRepository entitlements, PrincipalRepository principals,
            AuditContext auditContext, TransactionOperations transactions, Clock clock) {
        this.entitlements = Objects.requireNonNull(entitlements, "entitlements");
        this.principals = Objects.requireNonNull(principals, "principals");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean isEntitled(UUID organizationId, String solution) {
        return entitlements.exists(Objects.requireNonNull(organizationId, "organizationId"),
                Objects.requireNonNull(solution, "solution"));
    }

    /**
     * Sets (or changes) the entitlement, audited as {@code ENTITLEMENT_SET} with no actor.
     *
     * @param setByUsername a local, active principal of {@code organizationId}
     * @param correlationId the command's own fresh correlation id
     * @param clientAddress {@code operator-cli}, the recovery command's own constant
     * @throws IllegalArgumentException      the solution name or the limit is out of range
     * @throws UnknownSetByPrincipalException {@code setByUsername} is not such a principal
     */
    public void set(UUID organizationId, String solution, int maxInstances, String setByUsername, UUID correlationId,
            String clientAddress) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (!isValidSolution(solution)) {
            throw new IllegalArgumentException("the solution must match " + SOLUTION.pattern());
        }
        if (maxInstances < 1) {
            throw new IllegalArgumentException("max instances must be at least 1");
        }
        UUID setBy = principals.findLocalByUsername(organizationId,
                        Objects.requireNonNull(setByUsername, "setByUsername"))
                .filter(p -> p.status() == PrincipalRepository.Status.ACTIVE)
                .map(PrincipalRepository.Principal::id)
                .orElseThrow(UnknownSetByPrincipalException::new);
        transactions.executeWithoutResult(status -> {
            auditContext.set(null, AuditOperations.ENTITLEMENT_SET, correlationId, clientAddress);
            entitlements.upsert(organizationId, solution, maxInstances, setBy, clock.instant());
        });
    }

    /**
     * OR-148: creates the entitlement when none exists, and never touches an existing one. The CALLER owns the
     * transaction and the audit context ({@code ENTITLEMENT_SET} with the acting principal); this only writes.
     *
     * @return whether a row was created
     */
    boolean createIfAbsent(UUID organizationId, String solution, int maxInstances, UUID setBy) {
        return entitlements.insertIfAbsent(Objects.requireNonNull(organizationId, "organizationId"),
                Objects.requireNonNull(solution, "solution"), maxInstances, Objects.requireNonNull(setBy, "setBy"),
                clock.instant());
    }
}
