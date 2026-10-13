package com.solace.sm.cp.target;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Target governance (OR-28; plan 010 T2.7): the read side the gateway lists from and the write side the
 * plane-local registration command calls.
 *
 * <p><b>Targets are outside the ownership tree (SM-D23)</b>: each carries its own {@code ownershipScope}, which is
 * where {@code target.view} and {@code target.use} are evaluated. That scope is an organization, an organization
 * unit or an environment, never a solution, an instance, or the install, and the composite foreign key on
 * {@code target(organization_id, ownership_scope_id)} makes a cross-organization owner unrepresentable.
 *
 * <p><b>Registration order.</b> Validate the request, then {@code require(TARGET_CREATE)} at the ownership scope
 * (so a caller who may not create never learns whether the name is taken), then one transaction: audit context
 * ({@code TARGET_REGISTERED}, OR-25), the idempotency check, the insert. The HTTP registration (plan 013 T1.4) calls it
 * too, so a unique violation from a racing identical request is retried once and answers as the replay it is.
 */
public final class TargetService implements TargetDirectory, TargetRegistration {

    /** v1's one pair: the MI runtime {@code docker}, reached over {@code ssh-docker} (Q-C3 (a)). */
    static final String V1_KIND = "docker";
    static final String V1_TRANSPORT = "ssh-docker";

    private static final int MAX_NAME = 128;

    private final TargetRepository targets;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public TargetService(TargetRepository targets, AuthorizationService authorization, AuditContext auditContext,
            TransactionOperations transactions, Clock clock) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<TargetView> listLive(UUID organizationId) {
        return targets.listLive(Objects.requireNonNull(organizationId, "organizationId"));
    }

    @Override
    public TargetView register(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");

        String name = request.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("a target name is 1 to " + MAX_NAME + " characters");
        }
        boolean linked = request.connectionId() != null;
        if (linked) {
            if (!Set.of("docker", "systemd").contains(request.runtime())) {
                throw new IllegalArgumentException("runtime must be docker or systemd");
            }
            if (!"ssh".equals(request.transport()) && !V1_TRANSPORT.equals(request.transport())) {
                throw new IllegalArgumentException("linked deployment targets must use SSH transport");
            }
        } else if (!V1_KIND.equals(request.kind()) || !V1_TRANSPORT.equals(request.transport())) {
            throw new IllegalArgumentException("legacy targets support kind '" + V1_KIND
                    + "' with transport '" + V1_TRANSPORT + "'");
        }
        ScopePath ownership = request.ownershipScope();
        ResourceRef owner = ownerResource(organizationId, ownership);

        // A verb at the scope the target will be owned at, never a role (OR-8). Before any write.
        authorization.require(actor, Permission.TARGET_CREATE, owner);

        // Two identical requests at once both find no row, and one loses the insert on the partial unique index
        // (target_name_once). The loser's transaction is rolled back whole and run again: the second run finds the
        // winner's row and takes the replay path (D-REG section 8). A second loss cannot be the same race (the winner is
        // committed and visible by then), so it is not retried.
        try {
            return registerOnce(actor, organizationId, request, origin, name, ownership);
        } catch (DuplicateKeyException lost) {
            return registerOnce(actor, organizationId, request, origin, name, ownership);
        }
    }

    @Override
    public TargetView update(PrincipalRef actor, UUID organizationId, UUID targetId,
            TargetRegistration.UpdateRequest request,
            RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");
        String name = request.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new IllegalArgumentException("a target name is 1 to " + MAX_NAME + " characters");
        }
        if (!Set.of("docker", "systemd").contains(request.runtime())) {
            throw new IllegalArgumentException("runtime must be docker or systemd");
        }
        ResourceRef target = ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, targetId, new TenantId(organizationId));
        authorization.require(actor, Permission.TARGET_EDIT, target);
        return transactions.execute(status -> {
            TargetRepository.Stored stored = targets.findById(organizationId, targetId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET));
            if (stored.retired()) {
                throw new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET);
            }
            TargetView current = stored.view();
            if (current.connectionId() == null || !current.connectionId().equals(request.connectionId())
                    || !current.runtime().equals(request.runtime())) {
                throw new IllegalArgumentException("parent connection and runtime cannot be changed");
            }
            Optional<TargetRepository.Stored> sameName = targets.findByName(organizationId, name)
                    .filter(row -> !row.retired());
            if (sameName.isPresent() && !sameName.get().view().targetId().equals(targetId)) {
                throw new IllegalStateException("a target with this name is already registered");
            }
            auditContext.set(actor.id(), AuditOperations.TARGET_REGISTERED, origin.correlationId(),
                    origin.clientAddress());
            if (targets.updateMetadata(organizationId, targetId, name, request.connectionId(), request.runtime(),
                    request.runtimeSettingsJson()) != 1) {
                throw new IllegalStateException("the target changed while it was being updated");
            }
            return new TargetView(targetId, name, current.kind(), current.transport(), current.ownershipScope(),
                    request.connectionId(), request.runtime(), request.runtimeSettingsJson());
        });
    }

    private TargetView registerOnce(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin,
            String name, ScopePath ownership) {
        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.TARGET_REGISTERED, origin.correlationId(),
                    origin.clientAddress());
            targets.lockName(organizationId, name);       // plan 015 T3.5: the same lock token issue and enrolment take
            // V018 (OQ-11 (b)): a retired target frees its name, so only a LIVE row of this name is a replay or a
            // clash; a retired one is history and does not block a new registration.
            Optional<TargetRepository.Stored> byName = targets.findByName(organizationId, name)
                    .filter(stored -> !stored.retired());
            if (byName.isPresent()) {
                return existing(byName.get(), request, name, ownership);
            }
            // plan 015 T3.5 (T2.2 review 7): a name an unconsumed enrolment token holds is not free. In THIS transaction, beside
            // the insert, so the check and the write see one organization's state.
            if (targets.nameHeldByPendingEnrolment(organizationId, name)) {
                throw new IllegalStateException("the target name is already reserved by a pending enrolment");
            }
            UUID targetId = request.targetId() != null ? request.targetId() : UUID.randomUUID();
            if (targets.findById(organizationId, targetId).isPresent()) {
                throw new IllegalStateException("the requested target id is already used by a different target");
            }
            targets.insert(organizationId, targetId, name, request.kind(), request.transport(), ownership,
                    actor.id(), clock.instant(), request.connectionId(), request.runtime(), request.runtimeSettingsJson());
            return new TargetView(targetId, name, request.kind(), request.transport(), ownership,
                    request.connectionId(), request.runtime(), request.runtimeSettingsJson());
        });
    }

    @Override
    public Retired retire(PrincipalRef actor, UUID organizationId, UUID targetId, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(origin, "origin");
        // A verb at the target's OWN scope (SM-D23), never a role (OR-8), before the row is read: a caller who may not edit
        // it learns nothing about whether it exists. A retired target still resolves for this check (authorization is
        // about scope, liveness is the caller's concern), which is what lets a retry of a retirement answer 200.
        authorization.require(actor, Permission.TARGET_EDIT,
                ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, targetId, new TenantId(organizationId)));
        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.TARGET_RETIRED, origin.correlationId(), origin.clientAddress());
            TargetRepository.Stored stored = targets.findById(organizationId, targetId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET));
            if (stored.retired()) {
                return new Retired(stored.view(), stored.retiredAt(), true);
            }
            // plan 015 T3.5: an agent whose registration is still active could replay its enrolment into a retired target
            if (targets.activeAgentRegistrationHolds(organizationId, targetId)) {
                throw new com.solace.sm.cp.api.target.TargetHeldByAgentException();
            }
            Instant now = clock.instant();
            if (targets.markRetired(organizationId, targetId, now) != 1) {
                // Retired by a concurrent request between the read and the write: that request wrote the audit row.
                TargetRepository.Stored current = targets.findById(organizationId, targetId)
                        .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET));
                if (current.retired()) {
                    return new Retired(current.view(), current.retiredAt(), true);
                }
                throw new IllegalStateException("the target changed while it was being retired");
            }
            return new Retired(stored.view(), now, false);
        });
    }

    /** The same registration repeated writes nothing; a differing one under the same name is refused. */
    private static TargetView existing(TargetRepository.Stored stored, Request request, String name,
            ScopePath ownership) {
        TargetView view = stored.view();
        boolean same = !stored.retired() && view.name().equals(name) && view.kind().equals(request.kind())
                && view.transport().equals(request.transport()) && view.ownershipScope().equals(ownership)
                && (request.targetId() == null || request.targetId().equals(view.targetId()))
                && Objects.equals(view.connectionId(), request.connectionId())
                && Objects.equals(view.runtime(), request.runtime())
                && Objects.equals(view.runtimeSettingsJson(), request.runtimeSettingsJson());
        if (!same) {
            throw new IllegalStateException("a target with this name is already registered differently");
        }
        return view;
    }

    /** The hierarchy node the target is owned at, as the resource {@code target.create} is checked against. */
    private static ResourceRef ownerResource(UUID organizationId, ScopePath ownership) {
        int depth = ownership.depth();
        if (depth < 1 || depth > 3) {
            throw new IllegalArgumentException(
                    "a target is owned by an organization, an organization unit or an environment");
        }
        if (!organizationId.equals(ownership.organization())) {
            // Another organization's node is exactly as absent as a nonexistent one (I5).
            throw new ResourceNotFoundException(ResourceKind.ORGANIZATION);
        }
        TenantId tenant = new TenantId(organizationId);
        return switch (depth) {
            case 1 -> ResourceRef.of(ResourceKind.ORGANIZATION, ownership.organization(), tenant);
            case 2 -> ResourceRef.of(ResourceKind.SUB_ORGANIZATION, ownership.subOrganization(), tenant);
            default -> ResourceRef.of(ResourceKind.ENVIRONMENT, ownership.environment(), tenant);
        };
    }
}
