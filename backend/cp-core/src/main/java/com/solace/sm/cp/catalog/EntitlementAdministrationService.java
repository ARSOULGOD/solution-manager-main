package com.solace.sm.cp.catalog;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementAdministration;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.governance.GovernanceRepository;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Entitlements over the API (plan 013 T2.2; see {@link EntitlementAdministration}). The operator command
 * ({@link EntitlementService#set}) is a separate path and is unchanged.
 *
 * <p><b>Order of {@link #set}:</b> validate, {@code require(entitlement.set)} at install scope and then at the named
 * organization (both before any read; a foreign or nonexistent organization is a {@code 404}, {@code I5}), then
 * one transaction: audit context ({@code ENTITLEMENT_SET}, with the actor), the entitlement row locked
 * {@code FOR UPDATE} (the lock the declare path takes before it counts, {@code OR-40}), the declared count, the
 * refusal, the write. A declare that is waiting on the lock then counts against the new limit.
 *
 * <p><b>The install-scope resource.</b> {@code ResourceKind} has no install constant: the built-in
 * {@code Administrator} role definition is the install-wide resource ("Install-wide in v1: []",
 * {@code ResourceKind.ROLE_DEFINITION}), which resolves to {@code ScopePath.INSTALL}, so only a grant bound at install
 * authorizes it. The organization is never taken from a request ({@code I5}); the {@link TenantId} names the session's.
 *
 * <p>{@code I6}: no value from a request is placed in an exception message.
 */
public final class EntitlementAdministrationService implements EntitlementAdministration {

    /** V003's built-in Administrator role: the install-wide resource {@code entitlement.set} is evaluated against. */
    static final UUID INSTALL_ANCHOR_ROLE = EntitlementAdministration.INSTALL_ANCHOR_ROLE;

    private final EntitlementRepository entitlements;
    private final GovernanceRepository governance;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public EntitlementAdministrationService(EntitlementRepository entitlements, GovernanceRepository governance,
            AuthorizationService authorization, AuditContext auditContext, TransactionOperations transactions,
            Clock clock) {
        this.entitlements = Objects.requireNonNull(entitlements, "entitlements");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<Entitlement> list(PrincipalRef actor, UUID organizationId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        authorization.require(actor, Permission.SOLUTION_VIEW,
                ResourceRef.of(ResourceKind.ORGANIZATION, organizationId, new TenantId(organizationId)));
        return entitlements.list(organizationId).stream()
                .map(stored -> view(stored, governance.countInstances(organizationId, stored.solution())))
                .toList();
    }

    @Override
    public Entitlement set(PrincipalRef actor, RequestOrigin origin, UUID organizationId, String solution,
            int maxInstances) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(organizationId, "organizationId");
        if (!EntitlementService.isValidSolution(solution)) {
            throw new IllegalArgumentException("the solution is not a valid solution name");
        }
        if (maxInstances < 1 || maxInstances > MAX_INSTANCES_LIMIT) {
            throw new IllegalArgumentException("max instances is 1 to " + MAX_INSTANCES_LIMIT);
        }
        // Two requires, in this order. The install anchor decides who may set at all (an organization-bound grant is
        // refused here). It resolves to ScopePath.INSTALL, whose organization() is null, so it never compares the
        // organization the caller named with the caller's own; the organization-scoped require below does, and gives a
        // foreign or nonexistent organization the same 404 (before any lock, count or write, and before the foreign
        // key could answer 23503). An install grant authorizes downward, so a legitimate install Administrator passes.
        authorization.require(actor, Permission.ENTITLEMENT_SET,
                ResourceRef.of(ResourceKind.ROLE_DEFINITION, INSTALL_ANCHOR_ROLE, new TenantId(organizationId)));
        authorization.require(actor, Permission.ENTITLEMENT_SET,
                ResourceRef.of(ResourceKind.ORGANIZATION, organizationId, new TenantId(organizationId)));

        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.ENTITLEMENT_SET, origin.correlationId(),
                    origin.clientAddress());
            OptionalInt held = governance.lockEntitlement(organizationId, solution);
            long declared = governance.countInstances(organizationId, solution);
            if (maxInstances < declared) {
                throw new EntitlementRefusedException(EntitlementRefusedException.Reason.BELOW_DECLARED);
            }
            if (held.isPresent() && held.getAsInt() == maxInstances) {
                // Idempotent: the same limit again writes nothing, so a replay leaves set_by, set_at and the audit
                // trail exactly as they were.
                return view(entitlements.find(organizationId, solution).orElseThrow(), declared);
            }
            entitlements.upsert(organizationId, solution, maxInstances, actor.id(), clock.instant());
            return view(entitlements.find(organizationId, solution).orElseThrow(), declared);
        });
    }

    private static Entitlement view(EntitlementRepository.Stored stored, long declared) {
        return new Entitlement(stored.solution(), stored.maxInstances(), declared, stored.setAt());
    }
}
