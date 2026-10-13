package com.solace.sm.cp.connection;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.connection.ConnectionConflictException;
import com.solace.sm.cp.api.connection.ConnectionConflictException.Kind;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.connection.ConnectionRegistry;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.identity.AuditContext;
import com.solace.sm.cp.identity.AuditOperations;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Connection governance (plan 013 T2, {@code BE-D66}, {@code OR-130}): the read side the gateway lists from and
 * the write side {@code createConnection}/{@code updateConnection}/{@code deactivateConnection}/{@code
 * deleteConnection} call. Shaped exactly like {@link com.solace.sm.cp.target.TargetService}: a Connection carries
 * its own {@code ownershipScope}, outside the ownership tree, same as a Target ({@code SM-D23}'s pattern, extended
 * by {@code OR-130}).
 *
 * <p><b>Verb-before-read.</b> Every write checks its permission at the resource's scope first: {@code create} at
 * the body's {@code ownershipScope}, the other three at the connection's own scope — before the row is read, so a
 * caller who may not act never learns whether the name is taken or the record exists.
 *
 * <p><b>Optimistic concurrency without a client-visible revision.</b> {@code updateConnection} exposes no revision
 * to the caller (unlike the per-instance dashboard): {@code update}/{@code deactivate} read the current revision,
 * apply the change, and write conditioned on that revision inside one transaction, retrying the whole
 * read-apply-write cycle exactly once on a lost race (the {@link com.solace.sm.cp.target.TargetService} registration
 * race's shape); a second loss is a genuine concurrent conflict and answers {@code STALE_WRITE}.
 *
 * <p>⛔ {@code I6}: {@code attributes} never carries a secret-typed value. This service persists exactly the
 * {@code attributes}/{@code secretStatus} it is given; splitting a secret-typed attribute out of the caller's
 * request and recording only its presence is the caller's job (Task 3/4), not this one's.
 */
public final class ConnectionService implements ConnectionDirectory, ConnectionRegistry {

    private static final int MAX_NAME = 100;

    private final ConnectionRepository connections;
    private final AuthorizationService authorization;
    private final AuditContext auditContext;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ConnectionService(ConnectionRepository connections, AuthorizationService authorization,
            AuditContext auditContext, TransactionOperations transactions, Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.auditContext = Objects.requireNonNull(auditContext, "auditContext");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<ConnectionView> list(UUID organizationId) {
        return connections.list(Objects.requireNonNull(organizationId, "organizationId"));
    }

    @Override
    public Optional<ConnectionView> find(UUID organizationId, UUID connectionId) {
        return connections.findById(Objects.requireNonNull(organizationId, "organizationId"),
                Objects.requireNonNull(connectionId, "connectionId"));
    }

    @Override
    public ConnectionView create(PrincipalRef actor, UUID organizationId, CreateRequest request,
            RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");

        String name = validatedName(request.name());
        ScopePath ownership = request.ownershipScope();
        ResourceRef owner = ownerResource(organizationId, ownership);

        // The verb at the scope the connection will be owned at, never a role (OR-8). Before anything is read.
        authorization.require(actor, Permission.CONNECTION_CREATE, owner);

        return transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.CONNECTION_CREATED, origin.correlationId(),
                    origin.clientAddress());
            if (connections.findByName(organizationId, ownership, name).isPresent()) {
                throw nameTaken();
            }
            UUID connectionId = UUID.randomUUID();
            Map<String, Object> attributes = copy(request.attributes());
            Map<String, Boolean> secretStatus = copy(request.secretStatus());
            connections.insert(organizationId, connectionId, name, request.connectionType(),
                    request.descriptorVersion(), ownership, attributes, secretStatus, actor.id(), clock.instant());
            return connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new IllegalStateException("connection insert completed without a readable row"));
        });
    }

    @Override
    public ConnectionView update(PrincipalRef actor, UUID organizationId, UUID connectionId, UpdateRequest request,
            RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(origin, "origin");

        ResourceRef resource = ResourceRef.of(ResourceKind.CONNECTION, connectionId, new TenantId(organizationId));
        // The verb at the connection's OWN scope (SM-D23's pattern), never a role (OR-8), before the row is read.
        authorization.require(actor, Permission.CONNECTION_EDIT, resource);

        return attemptTwice(() -> transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.CONNECTION_UPDATED, origin.correlationId(),
                    origin.clientAddress());
            ConnectionView current = connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));

            String name = request.name() != null ? validatedName(request.name()) : current.name();
            if (!name.equals(current.name())
                    && connections.findByName(organizationId, current.ownershipScope(), name)
                            .filter(other -> !other.connectionId().equals(connectionId)).isPresent()) {
                throw nameTaken();
            }
            Map<String, Object> attributes = merge(current.attributes(), request.attributes());
            Map<String, Boolean> secretStatus = merge(current.secretStatus(), request.secretStatus());

            boolean written = connections.update(organizationId, connectionId, current.revision(), name, attributes,
                    secretStatus, actor.id(), clock.instant());
            if (!written) {
                return null; // raced: the outer retry re-reads and tries again
            }
            return connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new IllegalStateException("connection update completed without a readable row"));
        }));
    }

    @Override
    public ConnectionView deactivate(PrincipalRef actor, UUID organizationId, UUID connectionId,
            RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(origin, "origin");

        ResourceRef resource = ResourceRef.of(ResourceKind.CONNECTION, connectionId, new TenantId(organizationId));
        authorization.require(actor, Permission.CONNECTION_EDIT, resource);

        return attemptTwice(() -> transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.CONNECTION_DEACTIVATED, origin.correlationId(),
                    origin.clientAddress());
            ConnectionView current = connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));
            if (!current.active()) {
                return current; // already inactive: an answer, writes nothing (contract: 200, idempotent)
            }
            boolean written = connections.deactivate(organizationId, connectionId, current.revision(), actor.id(),
                    clock.instant());
            if (!written) {
                return null; // raced: the outer retry re-reads (which may now see it already inactive)
            }
            return connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new IllegalStateException("connection deactivate completed without a readable row"));
        }));
    }

    @Override
    public void delete(PrincipalRef actor, UUID organizationId, UUID connectionId, RequestOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(origin, "origin");

        ResourceRef resource = ResourceRef.of(ResourceKind.CONNECTION, connectionId, new TenantId(organizationId));
        // The verb at the connection's OWN scope, before the row is read: a caller who may not delete learns
        // nothing about whether it exists or how many records reference it.
        authorization.require(actor, Permission.CONNECTION_DELETE, resource);

        transactions.execute(status -> {
            auditContext.set(actor.id(), AuditOperations.CONNECTION_DELETED, origin.correlationId(),
                    origin.clientAddress());
            ConnectionView current = connections.findById(organizationId, connectionId)
                    .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));
            if (current.usedByCount() > 0) {
                throw new ConnectionConflictException(Kind.IN_USE,
                        "This connection is still referenced and cannot be deleted.");
            }
            if (connections.delete(organizationId, connectionId) != 1) {
                // Deleted (or moved to another organization, which I5 makes as absent as never existing) by a
                // concurrent request between the read and the write.
                throw new ResourceNotFoundException(ResourceKind.CONNECTION);
            }
            return null;
        });
    }

    /** Runs {@code attempt} once; on a {@code null} (lost race) result, runs it again; a second loss is answered as stale. */
    private static ConnectionView attemptTwice(Supplier<ConnectionView> attempt) {
        ConnectionView first = attempt.get();
        if (first != null) {
            return first;
        }
        ConnectionView second = attempt.get();
        if (second != null) {
            return second;
        }
        throw new ConnectionConflictException(Kind.STALE_WRITE, "The connection changed since it was read.");
    }

    private static String validatedName(String name) {
        Objects.requireNonNull(name, "name");
        String stripped = name.strip();
        if (stripped.isEmpty() || stripped.length() > MAX_NAME) {
            throw new IllegalArgumentException("a connection name is 1 to " + MAX_NAME + " characters");
        }
        return stripped;
    }

    private static ConnectionConflictException nameTaken() {
        return new ConnectionConflictException(Kind.NAME_TAKEN,
                "A connection with this name already exists in this scope.");
    }

    private static <V> Map<String, V> copy(Map<String, V> source) {
        return source == null ? Map.of() : Map.copyOf(source);
    }

    /** New-over-existing merge ({@code updateConnection}'s contract): {@code null} leaves the stored map untouched. */
    private static <V> Map<String, V> merge(Map<String, V> existing, Map<String, V> overlay) {
        if (overlay == null) {
            return existing;
        }
        Map<String, V> merged = new LinkedHashMap<>(existing);
        merged.putAll(overlay);
        return Map.copyOf(merged);
    }

    /** The hierarchy node the connection is owned at, as the resource {@code connection.create} is checked against. */
    private static ResourceRef ownerResource(UUID organizationId, ScopePath ownership) {
        int depth = ownership.depth();
        if (depth < 1 || depth > 3) {
            throw new IllegalArgumentException(
                    "a connection is owned by an organization, a sub-organization or an environment");
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
