package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.audit.TargetProbeAudit.ScanResult;
import com.solace.sm.cp.api.audit.TargetProbeAudit.TestResult;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.connection.ConnectionView;
import com.solace.sm.cp.api.hierarchy.HierarchyDirectory;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.RegistrationPrecheck;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionAdministration;
import com.solace.sm.dp.api.inventory.TargetConnectionChangedException;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.api.inventory.TargetCredentialUnreadableException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The registration API's two composed calls (plan 013 T1.4; D-REG sections 5.1, 7.1, 8; {@code BE-D54}):
 * {@code scanHostKeys} and {@code registerTarget}. A translator and a sequencer: every rule is the data plane's or the
 * control plane's, behind their ports.
 *
 * <p><b>{@code registerTarget}, in D-REG section 8's order.</b> (1) The interceptor has authorized {@code target.create}
 * at the body's scope and written nothing. (2) {@link TargetConnectionAdministration#precheckRegistration}: the grammar,
 * the key parse, the address policy, a fresh scan that must offer the pin, a host-key uniqueness probe; no write, no
 * secret. (3) The control plane's row. (4) The data plane's record, its sealed key, its host keys and its event, in one
 * transaction. Every refusal that can be decided at (2) is, so it leaves NOTHING in either plane and a corrected retry
 * (a different {@code kind}, a fixed key) succeeds. (2)'s scan is a scan: it draws on the session's {@link ScanRateGate}
 * budget and leaves the {@link TargetProbeAudit} row {@code scanHostKeys} would, so a probe is not free through this
 * route; that row is the one thing a refused registration leaves, and it is not registration state (review
 * {@code W3C-2}). The only way to stop between (3) and (4) is a crash, a timeout, or a
 * race on a host key; the control-plane row is then listed degraded and the same {@code POST} completes it.
 *
 * <p><b>{@code I5}.</b> The organization is {@code Session.organizationId()} on every call to both planes; the body names a
 * node, never an organization, and the sub-organization of an environment is derived from the tree, never trusted.
 * <b>{@code I6}.</b> The key is a {@code char[]} zeroised in a {@code finally}; no refusal here carries anything the
 * request carried.
 */
@Component
public class TargetRegistrationOrchestrator {

    private final TargetDirectory targets;
    private final TargetRegistration registration;
    private final TargetConnectionAdministration connections;
    private final HostKeyScanService scanner;
    private final TargetProbeAudit probeAudit;
    private final HierarchyDirectory hierarchy;
    private final RequestOriginFactory origins;
    private final ScanRateGate scanRate;
    private final ConnectionDirectory connectionDirectory;
    private final com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeAdministration deploymentTargets;
    private final ObjectMapper objectMapper;

    @Autowired
    public TargetRegistrationOrchestrator(TargetDirectory targets, TargetRegistration registration,
            TargetConnectionAdministration connections, HostKeyScanService scanner, TargetProbeAudit probeAudit,
            HierarchyDirectory hierarchy, RequestOriginFactory origins, GatewayProperties properties,
            ConnectionDirectory connectionDirectory,
            com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeAdministration deploymentTargets,
            ObjectMapper objectMapper) {
        this(targets, registration, connections, scanner, probeAudit, hierarchy, origins,
                new ScanRateGate(properties.getTargetScan().getPermitsPerMinute(), System::nanoTime), connectionDirectory,
                deploymentTargets, objectMapper);
    }

    TargetRegistrationOrchestrator(TargetDirectory targets, TargetRegistration registration,
            TargetConnectionAdministration connections, HostKeyScanService scanner, TargetProbeAudit probeAudit,
            HierarchyDirectory hierarchy, RequestOriginFactory origins, ScanRateGate scanRate) {
        this(targets, registration, connections, scanner, probeAudit, hierarchy, origins, scanRate, null, null,
                new ObjectMapper());
    }

    private TargetRegistrationOrchestrator(TargetDirectory targets, TargetRegistration registration,
            TargetConnectionAdministration connections, HostKeyScanService scanner, TargetProbeAudit probeAudit,
            HierarchyDirectory hierarchy, RequestOriginFactory origins, ScanRateGate scanRate,
            ConnectionDirectory connectionDirectory,
            com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeAdministration deploymentTargets,
            ObjectMapper objectMapper) {
        this.targets = Objects.requireNonNull(targets, "targets");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.probeAudit = Objects.requireNonNull(probeAudit, "probeAudit");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.origins = Objects.requireNonNull(origins, "origins");
        this.scanRate = Objects.requireNonNull(scanRate, "scanRate");
        this.connectionDirectory = connectionDirectory;
        this.deploymentTargets = deploymentTargets;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** A registration's outcome: {@code 201} when this call wrote the connection record, {@code 200} for a replay. */
    public record Registered(RegistrationOutcome outcome, TargetDetail detail) {
    }

    // ---- scanHostKeys --------------------------------------------------------------------------------------

    public HostKeyScan scan(Session session, HostKeyScanRequest request) {
        UUID organization = session.organizationId();
        // the node that was authorized is the node acted on (the resolver recorded it)
        OwnershipScopeResolver.requireAuthorized(request.ownershipScope(), organization);
        OwnershipScope scope = OwnershipScope.of(request.ownershipScope(), organization);
        RequestOrigin origin = origins.create();
        int port = request.portOrDefault();

        chargeScan(session, origin, scope);
        List<HostKeyScanService.OfferedHostKey> offered;
        try {
            offered = scanner.scan(request.host(), port);
        } catch (TargetAddressRefusedException refused) {
            audit(session, origin, scope, ScanResult.ADDRESS_REFUSED);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.ADDRESS_REFUSED);
        } catch (HostKeyScanUnavailableException unavailable) {
            audit(session, origin, scope, ScanResult.UNREACHABLE);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_UNREACHABLE);
        } catch (IllegalArgumentException grammar) {
            // a host or port outside the grammar never reached a host: not a scan, so not audited as one
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    port < 1 || port > 65535 ? "port" : "host");
        }
        audit(session, origin, scope, ScanResult.COMPLETED);
        return new HostKeyScan(request.host(), port,
                offered.stream().map(key -> new OfferedHostKey(key.fingerprint(), key.algorithm())).toList());
    }

    private void audit(Session session, RequestOrigin origin, OwnershipScope scope, ScanResult result) {
        probeAudit.scanned(session.principal(), origin, new TenantId(session.organizationId()), scope.kind(),
                scope.id(), result);
    }

    // ---- registerTarget ------------------------------------------------------------------------------------

    public Registered register(Session session, RegisterTargetRequest request) {
        if (request.connectionId() != null) {
            return registerLinked(session, request);
        }
        UUID organization = session.organizationId();
        OwnershipScopeResolver.requireAuthorized(request.ownershipScope(), organization);
        ScopePath scope = scopePath(session, request.ownershipScope());
        String name = request.name().strip();
        RequestOrigin origin = origins.create();

        // A live target of this name: the same registration is a replay (and its own host keys are not "another
        // target's"); a different one is refused here, before anything is scanned or written.
        Optional<TargetView> existing = targets.listLive(organization).stream()
                .filter(view -> view.name().equals(name)).findFirst();
        if (existing.isPresent() && !sameRegistration(existing.get(), request, scope)) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.NAME_TAKEN);
        }
        UUID replaying = existing.map(TargetView::targetId).orElse(null);

        char[] key = request.privateKey().toCharArray();
        try {
            TargetConnectionSpec asked = spec(request.connection(), Set.of());
            // Review W3C-2: the pre-check opens up to seven connections to a host the caller named, exactly as
            // scanHostKeys does, so it draws on the same per-session budget and leaves the same audit row.
            OwnershipScope audited = OwnershipScope.of(request.ownershipScope(), organization);
            chargeScan(session, origin, audited);
            RegistrationPrecheck checked = precheck(session, origin, audited, organization, replaying, asked, key);
            // OR-89: every key type the host offered is recorded, so the same machine cannot be registered twice by
            // pinning a different one.
            // OR-125 (2): and the TYPE of the pinned key, as that same scan reported it (never a client-supplied value), is
            // stored beside the pin so every SSH connector negotiates it directly.
            TargetConnectionSpec spec = spec(request.connection(), new LinkedHashSet<>(checked.offeredFingerprints()),
                    checked.typeOf(request.connection().hostKeyFingerprint()).orElse(null));

            TargetView view = controlPlaneRow(session, organization, name, request, scope, origin);
            RegistrationOutcome outcome = dataPlaneRecord(organization, view.targetId(), spec, key,
                    session.principal().id(), origin);

            TargetConnectionView connection = connections.connection(organization, view.targetId())
                    .orElseThrow(() -> new IllegalStateException("the connection record just registered is not there"));
            return new Registered(outcome, detail(view, connection));
        } finally {
            Arrays.fill(key, '\0');
        }
    }

    /** Updates linked-target metadata while preserving the target id and its parent runtime/connection. */
    public TargetDetail updateLinked(Session session, UUID targetId, UpdateDeploymentTargetRequest request) {
        if (connectionDirectory == null || deploymentTargets == null) {
            throw new IllegalStateException("deployment-target administration is not configured");
        }
        TargetView current = targets.listLive(session.organizationId()).stream()
                .filter(row -> row.targetId().equals(targetId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET));
        if (current.connectionId() == null || !current.runtime().equals(request.runtime())
                || !current.connectionId().equals(request.connectionId())) {
            throw new IllegalArgumentException("parent connection and runtime cannot be changed");
        }
        ConnectionView connection = connectionDirectory.find(session.organizationId(), request.connectionId())
                .filter(ConnectionView::active)
                .filter(row -> "remote-server".equals(row.connectionType()))
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));
        String settings = runtimeSettings(request);
        TargetView view = registration.update(session.principal(), session.organizationId(), targetId,
                new TargetRegistration.UpdateRequest(request.name().strip(), request.connectionId(), request.runtime(),
                        settings), origins.create());
        deploymentTargets.upsertDeploymentTarget(session.organizationId(),
                new com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec(targetId, connection.connectionId(),
                        request.runtime(), settings), session.principal().id());
        return new TargetDetail(view.targetId(), view.name(), TargetKind.fromWire(view.kind()),
                TargetTransport.fromWire(view.transport()), toWire(view.ownershipScope()), request.configRoot(),
                request.containerPrefix(), null, view.connectionId(), view.runtime(), view.runtimeSettingsJson(), null);
    }

    private String runtimeSettings(UpdateDeploymentTargetRequest request) {
        try {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("configRoot", request.configRoot());
            if (request.containerPrefix() != null && !request.containerPrefix().isBlank()) {
                values.put("containerPrefix", request.containerPrefix());
            }
            if (request.serviceNamePattern() != null && !request.serviceNamePattern().isBlank()) {
                values.put("serviceNamePattern", request.serviceNamePattern());
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("runtime settings are invalid");
        }
    }

    /** Registers target metadata under an existing remote Connection. No SSH secret crosses this request. */
    private Registered registerLinked(Session session, RegisterTargetRequest request) {
        if (connectionDirectory == null || deploymentTargets == null) {
            throw new IllegalStateException("deployment-target administration is not configured");
        }
        ConnectionView connection = connectionDirectory.find(session.organizationId(), request.connectionId())
                .filter(ConnectionView::active)
                .filter(row -> "remote-server".equals(row.connectionType()))
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.CONNECTION));
        String runtime = request.runtime();
        if (!Set.of("docker", "systemd").contains(runtime)) {
            throw new IllegalArgumentException("runtime must be docker or systemd");
        }
        String settings;
        try {
            Map<String, String> values = new java.util.LinkedHashMap<>();
            values.put("configRoot", request.configRoot());
            if (request.containerPrefix() != null) values.put("containerPrefix", request.containerPrefix());
            if (request.serviceNamePattern() != null) values.put("serviceNamePattern", request.serviceNamePattern());
            settings = objectMapper.writeValueAsString(values);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("runtime settings are invalid");
        }
        TargetView view = registration.register(session.principal(), session.organizationId(),
                new TargetRegistration.Request(null, request.name(), runtime, "ssh", connection.ownershipScope(),
                        request.connectionId(), runtime, settings), origins.create());
        deploymentTargets.upsertDeploymentTarget(session.organizationId(),
                new com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec(view.targetId(), request.connectionId(),
                        runtime, settings), session.principal().id());
        return new Registered(RegistrationOutcome.CREATED,
                new TargetDetail(view.targetId(), view.name(), TargetKind.fromWire(view.kind()),
                        TargetTransport.fromWire(view.transport()), toWire(view.ownershipScope()), request.configRoot(),
                        request.containerPrefix(), null));
    }

    /**
     * The scan an edit of a target's host, port or pin causes (plan 013 T1.5): it draws on the SAME per-session budget as
     * {@code scanHostKeys} and {@code registerTarget} (a probe is a probe, whichever route reaches it), and a refusal is
     * recorded as the rescan it is. One place, so whichever way the owner rules W3C-2 (the shared bucket) the edit follows.
     */
    void chargeRescan(Session session, RequestOrigin origin, UUID targetId) {
        try {
            scanRate.check(session.id());
        } catch (GatewayRejectionException spent) {
            auditRescan(session, origin, targetId, ScanResult.RATE_LIMITED);
            throw spent;
        }
    }

    /**
     * The connection test's draw on the SAME per-session budget (review {@code W4C-4}, conservative): a test connects to the
     * target's host and runs a command, so it is as much a probe as a scan and a loop of them could lock {@code sm} out of
     * the host (an {@code sshd} {@code MaxStartups} or {@code fail2ban} limit). A refusal is recorded as a {@code target.use}
     * {@code DENY} and rethrown as the {@code 429}. Whether the test should have a budget of its own is the owner's, with
     * {@code W3C-2}.
     */
    void chargeTest(Session session, RequestOrigin origin, UUID targetId) {
        try {
            scanRate.check(session.id());
        } catch (GatewayRejectionException spent) {
            probeAudit.tested(session.principal(), origin, new TenantId(session.organizationId()), targetId,
                    TestResult.RATE_LIMITED);
            throw spent;
        }
    }

    void auditRescan(Session session, RequestOrigin origin, UUID targetId, ScanResult result) {
        probeAudit.rescanned(session.principal(), origin, new TenantId(session.organizationId()), targetId, result);
    }

    /** Takes this session's scan token, or records the refusal and rethrows the {@code 429}. */
    private void chargeScan(Session session, RequestOrigin origin, OwnershipScope scope) {
        try {
            scanRate.check(session.id());
        } catch (GatewayRejectionException spent) {
            audit(session, origin, scope, ScanResult.RATE_LIMITED);
            throw spent;
        }
    }

    /**
     * The pre-check, and the audit of the scan inside it. A refusal that fires before any host is contacted (the key does
     * not parse, a field is outside its grammar) is not a scan and is not recorded, as in {@code scanHostKeys}; one that
     * fires after the host answered ({@code HOST_KEY_NOT_OFFERED}, {@code HOST_KEY_HELD}) is a completed scan. The audit
     * row is the control plane's record that a probe ran, not registration state: a refused registration still leaves no
     * target, secret or event behind in either plane.
     */
    private RegistrationPrecheck precheck(Session session, RequestOrigin origin, OwnershipScope scope, UUID organization,
            UUID replaying, TargetConnectionSpec spec, char[] key) {
        try {
            RegistrationPrecheck checked = connections.precheckRegistration(organization, replaying, spec, key);
            audit(session, origin, scope, ScanResult.COMPLETED);
            return checked;
        } catch (CredentialInvalidException invalid) {       // an IllegalArgumentException: it must be caught first
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.CREDENTIAL_INVALID);
        } catch (IllegalArgumentException grammar) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    fieldOf(grammar));
        } catch (TargetAddressRefusedException refused) {
            audit(session, origin, scope, ScanResult.ADDRESS_REFUSED);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.ADDRESS_REFUSED);
        } catch (HostKeyScanUnavailableException unavailable) {
            audit(session, origin, scope, ScanResult.UNREACHABLE);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_UNREACHABLE);
        } catch (HostKeyNotOfferedException notOffered) {
            audit(session, origin, scope, ScanResult.COMPLETED);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_KEY_NOT_OFFERED);
        } catch (HostKeyHeldElsewhereException held) {
            audit(session, origin, scope, ScanResult.COMPLETED);
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_KEY_HELD);
        }
    }

    private TargetView controlPlaneRow(Session session, UUID organization, String name, RegisterTargetRequest request,
            ScopePath scope, RequestOrigin origin) {
        try {
            return registration.register(session.principal(), organization,
                    new TargetRegistration.Request(null, name, request.kind().wireValue(),
                            request.transport().wireValue(), scope), origin);
        } catch (IllegalArgumentException invalid) {
            // the name (blank after trimming) or a pair v1 does not offer; the control plane's text names neither
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    name.isEmpty() ? "name" : "kind");
        } catch (IllegalStateException differs) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.NAME_TAKEN);
        }
    }

    private RegistrationOutcome dataPlaneRecord(UUID organization, UUID targetId, TargetConnectionSpec spec, char[] key,
            UUID actor, RequestOrigin origin) {
        try {
            return connections.registerConnection(organization, targetId, spec, key, actor,
                    new TargetChangeOrigin(origin.correlationId(), origin.clientAddress()));
        } catch (TargetCredentialConflictException differentKey) {   // an IllegalStateException: caught first
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.KEY_DIFFERS);
        } catch (TargetCredentialUnreadableException unreadable) {   // review W3C-4: not "the key differs"
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.CREDENTIAL_UNREADABLE);
        } catch (TargetConnectionChangedException changed) {         // not "a different record" either: retry
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.RECORD_CHANGED);
        } catch (IllegalStateException differentRecord) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.RECORD_DIFFERS);
        } catch (HostKeyHeldElsewhereException held) {
            // the race the pre-check's probe cannot close; the control plane's row stays, listed degraded and retirable
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_KEY_HELD);
        } catch (CredentialInvalidException invalid) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.CREDENTIAL_INVALID);
        } catch (IllegalArgumentException grammar) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    fieldOf(grammar));
        }
    }

    // ---- getTargetConnection -------------------------------------------------------------------------------

    /**
     * The management read of one target's connection record (OQ-13 (a)). {@code target.edit} at the target's scope has
     * passed in the interceptor; a target the data plane holds no LIVE record for (absent, or retired) is {@code 404}.
     */
    public TargetConnection connectionOf(Session session, UUID targetId) {
        return connections.connection(session.organizationId(), targetId)
                .map(TargetRegistrationOrchestrator::connection)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET));
    }

    /**
     * Plan 015 T3.5: an agent target has no address to connect to, so a connection test is refused BEFORE the per-session probe
     * budget is drawn or any connector is built. A target the data plane has no live record for is left to the tester (404).
     */
    void requireSshTarget(Session session, UUID targetId) {
        connections.connection(session.organizationId(), targetId).ifPresent(record -> {
            if (record.isAgent()) {
                throw new GatewayRejectionException(ErrorCode.TARGET_TRANSPORT_UNSUPPORTED);
            }
        });
    }

    // ---- composition ---------------------------------------------------------------------------------------

    private static TargetConnectionSpec spec(TargetConnectionSpecRequest connection, Set<String> others) {
        return spec(connection, others, null);
    }

    private static TargetConnectionSpec spec(TargetConnectionSpecRequest connection, Set<String> others,
            HostKeyType pinnedType) {
        return new TargetConnectionSpec(connection.host(), connection.portOrDefault(), connection.sshUser(),
                connection.hostKeyFingerprint(), connection.allowedConfigRoot(), connection.allowedContainerPrefix(),
                others, pinnedType);
    }

    static TargetDetail detail(TargetView view, TargetConnectionView connection) {
        TargetConnection wire = connection(connection);
        return new TargetDetail(view.targetId(), view.name(), TargetKind.fromWire(view.kind()),
                TargetTransport.fromWire(view.transport()), toWire(view.ownershipScope()),
                connection.allowedConfigRoot(), connection.allowedContainerPrefix(), wire);
    }

    static TargetConnection connection(TargetConnectionView view) {
        if (view.isAgent()) {
            return TargetConnection.agent(view.targetId(), view.agentId(), view.allowedConfigRoot(),
                    view.allowedContainerPrefix());
        }
        return TargetConnection.ssh(view.targetId(), view.host(), view.port(),
                view.sshUser(), view.hostKeyFingerprint(), view.offeredHostKeys(), view.allowedConfigRoot(),
                view.allowedContainerPrefix(),
                new CredentialSummary(view.credential().present(), view.credential().publicKeyFingerprint(),
                        view.credential().setAt() == null ? null : view.credential().setAt().toString()));
    }

    private static boolean sameRegistration(TargetView view, RegisterTargetRequest request, ScopePath scope) {
        return view.kind().equals(request.kind().wireValue())
                && view.transport().equals(request.transport().wireValue())
                && view.ownershipScope().equals(scope);
    }

    private static com.solace.sm.gateway.http.ScopePath toWire(ScopePath path) {
        return new com.solace.sm.gateway.http.ScopePath(path.organization(), path.subOrganization(),
                path.environment(), path.solution(), path.instance());
    }

    /**
     * The full path of the node the body names, derived from the tree ({@code I5}: the organization is the session's, an
     * environment's sub-organization is looked up, and ids sent together must agree). A node the tree does not hold under
     * this organization, or that the caller cannot see, is {@code 404}: the same answer as an absent one.
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

    /**
     * The field a data-plane grammar refusal names. {@code TargetConnectionValidator} states "{@code <field> is not ...}"
     * and never the value; the first word is taken only if it is one of the fields this API accepts, so nothing else can
     * reach a response body.
     */
    private static String fieldOf(IllegalArgumentException refusal) {
        String message = refusal.getMessage();
        int space = message == null ? -1 : message.indexOf(' ');
        String first = space < 0 ? "" : message.substring(0, space);
        return switch (first) {
            case "host", "port", "sshUser", "hostKeyFingerprint", "allowedConfigRoot", "allowedContainerPrefix" ->
                    "connection." + first;
            default -> "connection";
        };
    }
}
