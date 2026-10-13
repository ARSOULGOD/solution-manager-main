package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.connection.ConnectionRegistry;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.connection.ConnectionCredentialService;
import com.solace.sm.dp.api.connection.ConnectionTestOutcome;
import com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration;
import com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code listConnections}, {@code createConnection}, {@code updateConnection}, {@code deactivateConnection} and
 * {@code deleteConnection} (plan 013 T4, {@code BE-D66}, {@code OR-130}) — a translator and a sequencer, shaped
 * exactly like {@link TargetRegistrationOrchestrator}/{@link TargetManagementOrchestrator}: every rule is the
 * control plane's or the data plane's, behind their ports.
 *
 * <p><b>{@code createConnection}/{@code updateConnection}, in {@code BE-D66}'s order.</b> (1) The interceptor has
 * authorized {@code connection.create} at the body's {@code ownershipScope} (create) or {@code connection.edit} at
 * the connection's own scope (update), and written nothing. (2) The submitted attributes are validated against the
 * connection type's descriptor and split: secret-typed values are SEALED into the data plane
 * ({@link ConnectionCredentialService#sealCredential}) — a write — and, when {@code testBeforeSave: true}, the
 * connectivity test runs over the effective (merged, for an update) attribute set. A failed test stops here: when
 * the probe never reached a peer, {@code 503 SERVICE_UNAVAILABLE} (nothing written, retry); otherwise
 * {@code 422 CONNECTION_TEST_FAILED} with the non-secret fields of the submission echoed back (fix round 1, item
 * 4 — {@link #refusalFor}). Nothing is written to {@code cp-core} either way. (3) The control plane's row is
 * written (create/update/deactivate/delete).
 *
 * <p><b>{@code I5}.</b> The organization is {@code Session.organizationId()} on every call to both planes,
 * including the credential service. <b>{@code I6}.</b> A sealed secret's plaintext is a {@code char[]} zeroised in
 * a {@code finally}; {@code cp-core}'s {@code attributes} only ever carries a secret-typed key's REFERENCE (the
 * sealed id's string form), never a value.
 */
@Component
public class ConnectionOrchestrator {

    /** solace-broker.json / remote-server.json's shared and type-specific secret-typed attribute keys. */
    private static final String PASSWORD = "password";
    private static final String PRIVATE_KEY = "privateKey";
    private static final String PASSPHRASE = "passphrase";

    private final ConnectionDirectory connections;
    private final ConnectionRegistry registry;
    private final ReachAuthorization reachAuthorization;
    private final ConnectionAttributeCodec codec;
    private final ConnectionTypeCatalog catalog;
    private final ConnectionCredentialService credentials;
    private final HierarchyDirectory hierarchy;
    private final RequestOriginFactory origins;
    private final ScanRateGate testRate;
    private final ConnectionRuntimeAdministration connectionRuntime;

    @Autowired
    public ConnectionOrchestrator(ConnectionDirectory connections, ConnectionRegistry registry,
            ReachAuthorization reachAuthorization, ConnectionAttributeCodec codec, ConnectionTypeCatalog catalog,
            ConnectionCredentialService credentials, HierarchyDirectory hierarchy, RequestOriginFactory origins,
            GatewayProperties properties, ConnectionRuntimeAdministration connectionRuntime) {
        this(connections, registry, reachAuthorization, codec, catalog, credentials, hierarchy, origins,
                new ScanRateGate(properties.getConnectionTest().getPermitsPerMinute(), System::nanoTime),
                connectionRuntime);
    }

    ConnectionOrchestrator(ConnectionDirectory connections, ConnectionRegistry registry,
            ReachAuthorization reachAuthorization, ConnectionAttributeCodec codec, ConnectionTypeCatalog catalog,
            ConnectionCredentialService credentials, HierarchyDirectory hierarchy, RequestOriginFactory origins,
            ScanRateGate testRate, ConnectionRuntimeAdministration connectionRuntime) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.reachAuthorization = Objects.requireNonNull(reachAuthorization, "reachAuthorization");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.origins = Objects.requireNonNull(origins, "origins");
        this.testRate = Objects.requireNonNull(testRate, "testRate");
        this.connectionRuntime = Objects.requireNonNull(connectionRuntime, "connectionRuntime");
    }

    // ---- listConnections (x-sm-scope: reach) ---------------------------------------------------------------

    public ConnectionList list(Session session) {
        List<ConnectionView> all = connections.list(session.organizationId());
        Set<UUID> reachable = reachAuthorization.authorizedSubset(session.principal(), Permission.CONNECTION_VIEW,
                ResourceKind.CONNECTION, all.stream().map(ConnectionView::connectionId).toList());
        List<Connection> items = all.stream().filter(c -> reachable.contains(c.connectionId())).map(this::wire)
                .toList();
        return new ConnectionList(null, items);
    }

    // ---- getConnectionTypes ----------------------------------------------------------------------------------

    public List<ConnectionType> connectionTypes() {
        return catalog.all();
    }

    // ---- createConnection -----------------------------------------------------------------------------------

    public Connection create(Session session, CreateConnectionRequest request) {
        UUID organization = session.organizationId();
        OwnershipScopeResolver.requireAuthorized(request.ownershipScope(), organization);
        ScopePath ownership = scopePath(session, request.ownershipScope());
        ConnectionType descriptor = catalog.byConnectionType(request.connectionType())
                .orElseThrow(() -> new ConnectionValidationException("connectionType"));
        ConnectionAttributeCodec.Split split = codec.validateCreate(request.connectionType(), request.attributes());
        RequestOrigin origin = origins.create();
        UUID actor = session.principal().id();
        boolean testing = request.testBeforeSaveOrDefault();
        if (testing) {
            testRate.check(session.id());      // 429 before anything is sealed or written
        }

        Sealed sealed = seal(organization, actor, split);
        if (testing) {
            Map<String, Object> effective = withDefaults(descriptor, split.nonSecretAttributes());
            ConnectionTestOutcome outcome = test(organization, request.connectionType(), effective, sealed.refs());
            if (!outcome.success()) {
                throw refusalFor(outcome, request.name(), split.nonSecretAttributes());
            }
        }

        ConnectionRegistry.CreateRequest cpRequest = new ConnectionRegistry.CreateRequest(request.name(),
                request.connectionType(), descriptor.descriptorVersion(), ownership, sealed.attributes(),
                sealed.secretStatus());
        ConnectionView view = registry.create(session.principal(), organization, cpRequest, origin);
        if ("remote-server".equals(request.connectionType())) {
            upsertRuntime(organization, view.connectionId(), descriptor, sealed, actor);
        }
        return wire(view);
    }

    // ---- updateConnection -------------------------------------------------------------------------------------

    public Connection update(Session session, UUID connectionId, UpdateConnectionRequest request) {
        UUID organization = session.organizationId();
        ConnectionView current = currentOf(organization, connectionId);
        Map<String, Object> submittedAttributes = request.attributes() == null ? Map.of() : request.attributes();
        ConnectionAttributeCodec.Split split = codec.validateUpdate(current.connectionType(), submittedAttributes);
        ConnectionType descriptor = catalog.byConnectionType(current.connectionType())
                .orElseThrow(() -> new ConnectionValidationException("connectionType"));
        RequestOrigin origin = origins.create();
        UUID actor = session.principal().id();
        boolean testing = request.testBeforeSaveOrDefault();
        if (testing) {
            testRate.check(session.id());      // 429 before anything is sealed or written
        }

        Sealed sealed = seal(organization, actor, split);
        if (testing) {
            Map<String, Object> effective = withDefaults(descriptor,
                    merged(current.attributes(), current.secretStatus(), split.nonSecretAttributes()));
            Map<String, UUID> refs = mergedSecretRefs(current, sealed.refs());
            ConnectionTestOutcome outcome = test(organization, current.connectionType(), effective, refs);
            if (!outcome.success()) {
                throw refusalFor(outcome, request.name(), split.nonSecretAttributes());
            }
        }

        ConnectionRegistry.UpdateRequest cpRequest = new ConnectionRegistry.UpdateRequest(request.name(),
                sealed.attributes().isEmpty() ? null : sealed.attributes(),
                sealed.secretStatus().isEmpty() ? null : sealed.secretStatus());
        ConnectionView view = registry.update(session.principal(), organization, connectionId, cpRequest, origin);
        if ("remote-server".equals(current.connectionType())) {
            Map<String, Object> effective = withDefaults(descriptor,
                    merged(current.attributes(), current.secretStatus(), split.nonSecretAttributes()));
            Map<String, UUID> refs = mergedSecretRefs(current, sealed.refs());
            upsertRuntime(organization, connectionId, effective, refs, actor);
        }
        return wire(view);
    }

    // ---- deactivateConnection / deleteConnection ----------------------------------------------------------

    public Connection deactivate(Session session, UUID connectionId) {
        RequestOrigin origin = origins.create();
        connectionRuntime.retire(session.organizationId(), connectionId, session.principal().id());
        ConnectionView view = registry.deactivate(session.principal(), session.organizationId(), connectionId,
                origin);
        return wire(view);
    }

    public void delete(Session session, UUID connectionId) {
        RequestOrigin origin = origins.create();
        connectionRuntime.retire(session.organizationId(), connectionId, session.principal().id());
        registry.delete(session.principal(), session.organizationId(), connectionId, origin);
    }

    // ---- composition --------------------------------------------------------------------------------------

    /** The non-secret attributes to persist, the secretStatus to persist, and the fresh secret refs sealed. */
    private record Sealed(Map<String, Object> attributes, Map<String, Boolean> secretStatus,
                          Map<String, UUID> refs) {
    }

    /** Seals every secret-typed value the submission carried; the plaintext is zeroised before this returns. */
    private Sealed seal(UUID organization, UUID actor, ConnectionAttributeCodec.Split split) {
        Map<String, Object> attributes = new LinkedHashMap<>(split.nonSecretAttributes());
        Map<String, Boolean> secretStatus = new LinkedHashMap<>(split.secretStatus());
        Map<String, UUID> refs = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : split.secretPlaintext().entrySet()) {
            char[] value = entry.getValue().toCharArray();
            try {
                UUID secretId = credentials.sealCredential(organization, value, actor);
                refs.put(entry.getKey(), secretId);
                attributes.put(entry.getKey(), secretId.toString());
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        return new Sealed(Map.copyOf(attributes), Map.copyOf(secretStatus), Map.copyOf(refs));
    }

    /**
     * Fix round 1, item 4: a failed test is {@code 503 SERVICE_UNAVAILABLE} when the probe never reached a peer
     * to be accepted or refused by (nothing written, retry — no echo, matching {@code scanHostKeys}'s own
     * {@code HOST_UNREACHABLE} shape), and {@code 422 CONNECTION_TEST_FAILED} with the echo otherwise — matching
     * the contract's two documented failure codes for {@code createConnection}/{@code updateConnection}.
     */
    private static RuntimeException refusalFor(ConnectionTestOutcome outcome, String echoedName,
            Map<String, Object> echoedAttributes) {
        if (!outcome.reachable()) {
            return new GatewayRejectionException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return new ConnectionTestFailedException(outcome.rejectionReason(), echoedName, echoedAttributes);
    }

    private ConnectionTestOutcome test(UUID organization, String connectionType, Map<String, Object> nonSecret,
            Map<String, UUID> refs) {
        return switch (connectionType) {
            case "solace-broker" -> credentials.testSolaceBroker(organization, string(nonSecret, "host"),
                    integer(nonSecret, "port"), string(nonSecret, "vpn"), string(nonSecret, "username"),
                    refs.get(PASSWORD));
            case "remote-server" -> credentials.testRemoteServer(organization, string(nonSecret, "host"),
                    integer(nonSecret, "port"), string(nonSecret, "username"),
                    requireKnownAuthMethod(nonSecret, refs), refs.get(PASSWORD), refs.get(PRIVATE_KEY),
                    refs.get(PASSPHRASE));
            default -> throw new ConnectionValidationException("connectionType");
        };
    }

    /**
     * Fix round 2, items 2-3: {@code remote-server.json}'s {@code authMethod} discriminator must be a value
     * {@code ConnectionCredentialServiceImpl} actually knows, and a {@code private-key} choice must carry a
     * sealed key ref — both were previously left to throw an unchecked {@code IllegalArgumentException} /
     * {@code NullPointerException} out of {@code Binding}'s construction, surfacing as an uncaught {@code 500}
     * instead of this feature's own {@code 422 VALIDATION_FAILED} shape.
     */
    private static String requireKnownAuthMethod(Map<String, Object> nonSecret, Map<String, UUID> refs) {
        String authMethod = string(nonSecret, "authMethod");
        if (!"basic".equals(authMethod) && !"private-key".equals(authMethod)) {
            throw new ConnectionValidationException("authMethod");
        }
        if ("private-key".equals(authMethod) && refs.get(PRIVATE_KEY) == null) {
            throw new ConnectionValidationException(PRIVATE_KEY);
        }
        return authMethod;
    }

    /** Fills in the descriptor's defaults for every key the submission left absent. */
    private static Map<String, Object> withDefaults(ConnectionType descriptor, Map<String, Object> values) {
        Map<String, Object> effective = new LinkedHashMap<>(values);
        for (ConnectionTypeAttribute attribute : descriptor.attributes()) {
            if (!attribute.isSecret() && !effective.containsKey(attribute.key()) && attribute.defaultValue() != null) {
                effective.put(attribute.key(), attribute.defaultValue());
            }
        }
        return effective;
    }

    /** New-over-existing merge of non-secret attributes, for building an update's effective test set. */
    private static Map<String, Object> merged(Map<String, Object> currentAttributes,
            Map<String, Boolean> currentSecretStatus, Map<String, Object> overlay) {
        Map<String, Object> merged = new LinkedHashMap<>();
        currentAttributes.forEach((key, value) -> {
            if (!Boolean.TRUE.equals(currentSecretStatus.get(key))) {
                merged.put(key, value);
            }
        });
        merged.putAll(overlay);
        return merged;
    }

    /** The secret refs an update's test runs with: freshly sealed ones win, else the connection's existing ref. */
    private static Map<String, UUID> mergedSecretRefs(ConnectionView current, Map<String, UUID> fresh) {
        Map<String, UUID> refs = new LinkedHashMap<>();
        current.secretStatus().forEach((key, isSecret) -> {
            if (Boolean.TRUE.equals(isSecret) && current.attributes().get(key) instanceof String ref) {
                refs.put(key, UUID.fromString(ref));
            }
        });
        refs.putAll(fresh);
        return refs;
    }

    /**
     * Plan 015: keeps {@code sm_data.connection_runtime} (I7's data-plane projection) in step with a remote-server
     * Connection's cp-core row. {@code create}'s submission carries no defaults (port's default, in particular),
     * so this applies the descriptor's defaults the same way {@link #withDefaults} already does for a connectivity
     * test.
     */
    private void upsertRuntime(UUID organization, UUID connectionId, ConnectionType descriptor, Sealed sealed,
            UUID actor) {
        upsertRuntime(organization, connectionId, withDefaults(descriptor, sealed.attributes()), sealed.refs(),
                actor);
    }

    /**
     * {@code update}'s submission is an overlay: the caller already merged it over the current connection's
     * attributes before calling this. {@code credentialRef} is null when this update touched neither the
     * connection's active credential nor its existing sealed ref for one — nothing changed, so the existing
     * projection row (if any) is left exactly as it is, including its own stored {@code credentialRef}.
     */
    private void upsertRuntime(UUID organization, UUID connectionId, Map<String, Object> attributes,
            Map<String, UUID> refs, UUID actor) {
        UUID credentialRef = "private-key".equals(attributes.get("authMethod")) ? refs.get(PRIVATE_KEY)
                : refs.get(PASSWORD);
        UUID passphraseRef = "private-key".equals(attributes.get("authMethod")) ? refs.get(PASSPHRASE) : null;
        // Runtime-specific envelope fields are owned by DeploymentTarget. A remote Connection still gets a complete
        // access projection as soon as its host key and credential are available; no path or container prefix is
        // invented here.
        if (credentialRef == null || string(attributes, "hostKeyFingerprint") == null) {
            return;
        }
        ConnectionRuntimeSpec spec = new ConnectionRuntimeSpec(connectionId, string(attributes, "host"),
                integer(attributes, "port"), string(attributes, "username"),
                string(attributes, "hostKeyFingerprint"), credentialRef, passphraseRef,
                string(attributes, "authMethod"), string(attributes, "allowedConfigRoot"),
                string(attributes, "allowedContainerPrefix"));
        connectionRuntime.upsert(organization, spec, actor);
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            // Fix round 2, item 1: a non-numeric port must be the same 422 VALIDATION_FAILED shape as every other
            // rejected attribute, not an uncaught NumberFormatException surfacing as 500.
            throw new ConnectionValidationException(key);
        }
    }

    private ConnectionView currentOf(UUID organization, UUID connectionId) {
        return connections.list(organization).stream().filter(c -> c.connectionId().equals(connectionId))
                .findFirst().orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));
    }

    private Connection wire(ConnectionView view) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        view.attributes().forEach((key, value) -> redacted.put(key,
                Boolean.TRUE.equals(view.secretStatus().get(key)) ? Map.of("set", true) : value));
        return new Connection(view.connectionId(), view.name(), view.connectionType(), toWire(view.ownershipScope()),
                redacted, view.active(), view.usedByCount(), view.createdAt().toString(),
                view.updatedAt().toString());
    }

    private static com.solace.sm.gateway.http.ScopePath toWire(ScopePath path) {
        return new com.solace.sm.gateway.http.ScopePath(path.organization(), path.subOrganization(),
                path.environment(), path.solution(), path.instance());
    }

    /**
     * The full path of the node the body names, derived from the tree ({@code I5}: the organization is the
     * session's, an environment's sub-organization is looked up). Shaped exactly like
     * {@code TargetRegistrationOrchestrator.scopePath} — a Connection is owned by an organization, a
     * sub-organization or an environment only ({@code ConnectionService.ownerResource}'s own depth check), never a
     * solution or an instance.
     */
    private ScopePath scopePath(Session session, ScopeRef ref) {
        UUID organization = session.organizationId();
        OwnershipScope scope = OwnershipScope.of(ref, organization);
        return switch (scope.kind()) {
            case ORGANIZATION -> ScopePath.organization(organization);
            case SUB_ORGANIZATION -> {
                requireSubOrganization(session, scope.id());
                yield ScopePath.subOrganization(organization, scope.id());
            }
            case ENVIRONMENT -> ScopePath.environment(organization, subOrganizationOf(session, ref), scope.id());
            default -> throw new ResourceNotFoundException(ResourceKind.ENVIRONMENT);
        };
    }

    private void requireSubOrganization(Session session, UUID subOrganization) {
        boolean present = hierarchy.listSubOrganizations(session, session.organizationId()).stream()
                .anyMatch(node -> node.id().equals(subOrganization));
        if (!present) {
            throw new ResourceNotFoundException(ResourceKind.SUB_ORGANIZATION);
        }
    }

    private UUID subOrganizationOf(Session session, ScopeRef ref) {
        UUID organization = session.organizationId();
        if (ref.subOrganizationId() != null) {
            boolean holds = hierarchy.listEnvironments(session, organization, ref.subOrganizationId()).stream()
                    .anyMatch(node -> node.id().equals(ref.environmentId()));
            if (!holds) {
                throw new ResourceNotFoundException(ResourceKind.ENVIRONMENT);
            }
            return ref.subOrganizationId();
        }
        for (var sub : hierarchy.listSubOrganizations(session, organization)) {
            boolean holds = hierarchy.listEnvironments(session, organization, sub.id()).stream()
                    .anyMatch(node -> node.id().equals(ref.environmentId()));
            if (holds) {
                return sub.id();
            }
        }
        throw new ResourceNotFoundException(ResourceKind.ENVIRONMENT);
    }
}
