package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.connection.ConnectionDirectory;
import com.solace.sm.cp.api.target.TargetDirectory;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.ConnectionRuntimePolicy;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.DeploymentMode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two-phase declare across the two planes, composed here because the gateway is the one module permitted both
 * {@code -api}s ({@code I7}; BE-D32). {@code dp-core} cannot ask the control plane which of its pending ids were
 * registered, and the control plane cannot reach the data plane, so neither learns of the other.
 *
 * <p><b>The order is the protocol.</b> {@code reserve} (DP) commits BEFORE {@code register} (CP), because a CP node
 * with no DP row is unrecoverable by the sweep, while a {@code pending} DP row with no CP node is: the sweep abandons
 * it after the window, and activates it the moment the CP holds its node. {@code activate} (DP) is last.
 *
 * <p><b>The sweep is lazy, per tenant, with no loop</b> ({@code B4}): {@link #settle} runs inside the requests a
 * stale {@code pending} row can affect, for the session's own organization only - before {@code reserve} in every
 * declare (a stale row holds names and, once registered, quota), and before the read in {@code listInstances} and
 * {@code getInstance} (a registered but pending instance must become visible).
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()} in every call on both planes; it is never a
 * request field.
 *
 * <p>{@link #settle} also runs the undeclare sweep ({@link UndeclareSweep}), the reverse sweep for a half-finished
 * undeclare, in the same three places and for the same organization.
 *
 * <p>What this deliberately does NOT do on a failed {@code register}: only a DEFINITE refusal (quota, entitlement, a
 * resource that is not this organization's) abandons the reserved row synchronously. Any other failure - a duplicate
 * instance id, a lost connection - leaves the outcome unknown (the CP transaction may even have committed), so the
 * row is left {@code pending}: the sweep abandons it after the window if the CP never registered it and activates it
 * if it did, which is right for both outcomes.
 */
@Component
public class DeclareOrchestrator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern HEALTH_PATH = Pattern.compile("/[A-Za-z0-9/_.-]{0,255}");
    // Kept for addPlacement's current grammar gate. New instance creation intentionally defers this validation.
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+");

    private final InstanceInventoryService inventory;
    private final TargetConnectionService connections;
    private final InstanceGovernance governance;
    private final SolutionCatalog catalog;
    private final AuthorizationService authorization;
    private final ReachAuthorization reach;
    private final DesignerCodec codec;
    private final MetricApplicability applicability;
    private final Duration abandonAfter;
    private final ConnectionDirectory connectionDirectory;
    private final TargetDirectory targetDirectory;
    private final UndeclareSweep undeclareSweep;

    /** The plan 013 connection type a {@code dp-remote} declare binds to (plan 014). */
    static final String REMOTE_SERVER = "remote-server";

    /**
     * The pre-plan-014 arity, kept so the gateway's slice tests stay unchanged: a connection directory that knows no
     * connection, so every connection-backed declare through it is refused ({@code 422 connectionId}), never admitted.
     */
    public DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability, Duration abandonAfter) {
        this(inventory, connections, governance, catalog, authorization, reach, codec, applicability, abandonAfter,
                organizationId -> List.of(), null, (UndeclareSweep) null);
    }

    /**
     * @param abandonAfter        {@code sm.dp.declare.abandon-after}, the same key the data plane documents
     *                            ({@code DeclareSweepProperties}, which the gateway may not import). ⚠️ The ten-minute
     *                            default is therefore stated in two modules.
     * @param connectionDirectory the control plane's Connection registry (plan 013), read to validate a
     *                            {@code connectionId} (plan 014)
     */
    public DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability,
            @Value("${sm.dp.declare.abandon-after:10m}") Duration abandonAfter,
            ConnectionDirectory connectionDirectory) {
        this(inventory, connections, governance, catalog, authorization, reach, codec, applicability, abandonAfter,
                connectionDirectory, (TargetDirectory) null, (UndeclareSweep) null);
    }

    /**
     * The composition root's constructor (plan 012 T5): the above plus the undeclare sweep, which {@link #settle} runs beside
     * the declare sweep. The nine-argument form passes none - for the declare path's own tests, which have no undeclare port;
     * Spring uses this one.
     */
    public DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability,
            Duration abandonAfter, UndeclareSweep undeclareSweep) {
        this(inventory, connections, governance, catalog, authorization, reach, codec, applicability, abandonAfter,
                organizationId -> List.of(), (TargetDirectory) null, undeclareSweep);
    }

    /** Composition root: keep both the connection registry and undeclare sweep available to the gateway. */
    public DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability,
            Duration abandonAfter, ConnectionDirectory connectionDirectory, UndeclareSweep undeclareSweep) {
        this(inventory, connections, governance, catalog, authorization, reach, codec, applicability, abandonAfter,
                connectionDirectory, (TargetDirectory) null, undeclareSweep);
    }

    /** Composition root with target metadata available for linked deployment targets. */
    @Autowired
    public DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability,
            @Value("${sm.dp.declare.abandon-after:10m}") Duration abandonAfter,
            ConnectionDirectory connectionDirectory, UndeclareSweep undeclareSweep, TargetDirectory targetDirectory) {
        this(inventory, connections, governance, catalog, authorization, reach, codec, applicability, abandonAfter,
                connectionDirectory, targetDirectory, undeclareSweep);
    }

    private DeclareOrchestrator(InstanceInventoryService inventory, TargetConnectionService connections,
            InstanceGovernance governance, SolutionCatalog catalog, AuthorizationService authorization,
            ReachAuthorization reach, DesignerCodec codec, MetricApplicability applicability, Duration abandonAfter,
            ConnectionDirectory connectionDirectory, TargetDirectory targetDirectory, UndeclareSweep undeclareSweep) {
        this.connectionDirectory = Objects.requireNonNull(connectionDirectory, "connectionDirectory");
        this.targetDirectory = targetDirectory;
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.reach = Objects.requireNonNull(reach, "reach");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.applicability = Objects.requireNonNull(applicability, "applicability");
        this.abandonAfter = Objects.requireNonNull(abandonAfter, "abandonAfter");
        this.undeclareSweep = undeclareSweep;
        if (abandonAfter.isZero() || abandonAfter.isNegative()) {
            throw new IllegalArgumentException("sm.dp.declare.abandon-after must be positive");
        }
    }

    // ---- declare -------------------------------------------------------------------------------------------

    /**
     * Steps 1-5 of BE-D32. {@code instance.create} at the environment has already passed (the interceptor).
     */
    public InstanceView declare(Session session, RequestOrigin origin, UUID environmentId,
            DeclareInstanceRequest request) {
        UUID organization = session.organizationId();
        PrincipalRef principal = session.principal();
        TenantId tenant = new TenantId(organization);

        // Step 2: every authorization is settled before either plane is written (rule 2). D10's two-sided rule.
        requireAlso(principal, Permission.INSTANCE_BIND_TARGET,
                ResourceRef.of(ResourceKind.ENVIRONMENT, environmentId, tenant));
        DeploymentMode deployment = deploymentModeOf(request);
        boolean onConnection = request.connectionId() != null;
        // Plan 014: the same two-sided rule, mapped to the selected Connection resource ("permissions and scope checks
        // remain equivalent to the current instance.bindTarget/target.use checks"). The catalog has no connection.use
        // verb, and a closed catalog may not grow here, so target.use is asked AT THE CONNECTION'S scope.
        requireAlso(principal, Permission.TARGET_USE, onConnection
                ? ResourceRef.of(ResourceKind.CONNECTION, request.connectionId(), tenant)
                : ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, request.targetId(), tenant));

        AcquisitionMode mode = acquisitionOf(request.acquisition());
        DeclareInstanceRequest.Placement placement = request.placement();
        // Placement container/path/file validation is intentionally deferred for instance creation. The connection
        // identity remains validated below; runtime-specific placement validation will be added in its later phase.

        String version = request.declaredPackageVersion().toString();   // canonical JSON text: 1, "v2"
        LoadedPackage declared = catalog.find(organization, request.solutionId(), version)
                .orElseThrow(() -> new DeclareValidationException("declaredPackageVersion"));
        String document = catalog.documentOf(organization, request.solutionId(), version)
                .orElseThrow(() -> new DeclareValidationException("declaredPackageVersion"));
        HealthProbeSpec probe = probeOf(document, placement.healthPort());
        PackageModel.SolutionPackage pkg = packageOf(document);
        String role = PackageRoleDiscovery.resolve(pkg, InstanceController.packageVersionOnTheWire(version), request.role());
        MetricsDeclaration metrics = metricsOf(declared, pkg, version, placement.metricsPorts(), role);

        if (onConnection) {
            requireRemoteServerConnection(organization, request.connectionId());
        } else {
            Optional<TargetView> target = targetDirectory == null ? Optional.empty()
                    : targetDirectory.listLive(organization).stream()
                            .filter(view -> view.targetId().equals(request.targetId())).findFirst();
            if (target.isPresent() && target.get().connectionId() != null) {
                // Linked target: the parent Connection projection is the DP's only source of SSH coordinates and
                // credentials. Fail at declaration with a field-level validation error instead of letting the
                // placement trigger surface a generic 500 when a pre-existing target was created before the
                // connection projection was available.
                if (!connections.connectionRuntimeLive(organization, target.get().connectionId())) {
                    throw new DeclareValidationException("targetId");
                }
            } else if (connections.liveEnvelopes(organization, List.of(request.targetId())).isEmpty()) {
                throw new DeclareValidationException("targetId");
            }
        }

        settle(organization);
        // Pre-check only: it spares a doomed reserve. The check that counts is inside register, under the lock.
        if (!governance.withinQuota(organization, declared.solution())) {
            throw new EntitlementRefusedException();
        }

        // Step 3: DP reserve. The envelope (B2) and every placement collision refuse here, before any row on either plane.
        InstanceView reserved = inventory.reserve(organization, new DeclareInstanceCommand(environmentId,
                request.solutionId(), declared.solution(), version, request.name(), request.targetId(), mode,
                new PlacementSpec(placement.containerName(), placement.configDirectory(),
                        placement.containerConfigPath(), placement.fileName(), probe),
                principal.id(), metrics, request.connectionId(), deployment, role));

        // Step 4: CP register (the node, and the quota under the entitlement row lock).
        try {
            governance.register(principal, origin, organization, environmentId, request.solutionId(),
                    reserved.instanceId());
        } catch (QuotaExceededException | EntitlementRefusedException | ResourceNotFoundException refused) {
            abandonQuietly(organization, reserved.instanceId(), refused);
            throw refused;
        }

        // Step 5: DP activate.
        return inventory.activate(organization, reserved.instanceId());
    }

    /**
     * Plan 011 M-Q5, M-Q14, M-Q15: what the data plane records for this version and this placement, resolved HERE because the
     * data plane reads no package. The digest is the control plane's stored one ({@code LoadedPackage.digest()}),
     * passed through: {@code documentOf} says why this never hashes the document (the {@code jsonb} store re-spells it, so
     * a hash of it could differ from the control plane's and refuse a good declare as a mismatch). The generation-0 list
     * is the control plane's own evaluator over the package defaults, for the components a declare necessarily has.
     */
    private MetricsDeclaration metricsOf(LoadedPackage declared, PackageModel.SolutionPackage pkg, String version,
            Map<String, Integer> bodyMetricsPorts, String role) {
        Object onTheWire = InstanceController.packageVersionOnTheWire(version);
        List<String> active = applicability.activeMetricNames(pkg, onTheWire,
                MetricsDeclarationBuilder.declareTimeSelection(pkg, onTheWire, role), Map.of());
        return MetricsDeclarationBuilder.build(declared.digest(), pkg, onTheWire, bodyMetricsPorts, active);
    }

    private PackageModel.SolutionPackage packageOf(String document) {
        try {
            return codec.readPackage(document);
        } catch (IllegalArgumentException e) {
            throw new DeclareValidationException("declaredPackageVersion");
        }
    }

    // ---- reads ---------------------------------------------------------------------------------------------

    /** {@code listInstances} (reach): the organization's active instances of one catalog entry, filtered by reach. */
    public List<InstanceView> list(Session session, UUID catalogSolutionId) {
        UUID organization = session.organizationId();
        settle(organization);
        List<InstanceView> active = inventory.listActive(organization, catalogSolutionId);
        if (active.isEmpty()) {
            return active;
        }
        // The interceptor's pre-check authorized NO item: filter by the operation's own verb at each instance's scope.
        Set<UUID> allowed = reach.authorizedSubset(session.principal(), Permission.INSTANCE_VIEW,
                ResourceKind.INSTANCE, active.stream().map(InstanceView::instanceId).toList());
        return active.stream().filter(view -> allowed.contains(view.instanceId())).toList();
    }

    /** {@code getInstance}: authorization at the instance has passed; only an ACTIVE row is readable. */
    public Optional<InstanceView> get(Session session, UUID instanceId) {
        UUID organization = session.organizationId();
        settle(organization);
        return inventory.findActive(organization, instanceId);
    }

    // ---- the sweep -----------------------------------------------------------------------------------------

    /**
     * BE-D32's sweep for ONE tenant: ask the data plane which instances are {@code pending}, ask the control plane
     * which of those it registered, and let the data plane activate the registered ones and abandon the unregistered
     * ones older than the window. One bounded read when nothing is pending.
     */
    void settle(UUID organization) {
        List<UUID> pending = inventory.pending(organization, InstanceGovernance.MAX_BATCH);
        if (!pending.isEmpty()) {
            Set<UUID> registered = governance.registered(organization, pending);
            inventory.settlePending(organization, registered, abandonAfter);
        }
        // Plan 012 T5: the same requests also protect against a stale 'retiring' row (undeclare's half-finished state): it
        // is out of every read, yet it still holds its name and its quota slot until it is finished or rolled back.
        if (undeclareSweep != null) {
            undeclareSweep.sweep(organization);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * A secondary verb: a denial names WHICH verb, a missing or out-of-scope resource stays an indistinguishable 404
     * ({@link ResourceNotFoundException} propagates).
     */
    private void requireAlso(PrincipalRef principal, Permission permission, ResourceRef resource) {
        try {
            authorization.require(principal, permission, resource);
        } catch (AccessDeniedException e) {
            throw new SecondaryVerbDeniedException(permission);
        }
    }

    private void abandonQuietly(UUID organization, UUID instanceId, RuntimeException original) {
        try {
            inventory.abandon(organization, instanceId);
        } catch (RuntimeException e) {
            // The refusal the caller sees is the real one; the sweep abandons the row after the window regardless.
            original.addSuppressed(e);
        }
    }

    /** The existing add-placement grammar gate; declaration intentionally does not call this yet. */
    static void requirePlacementGrammar(DeclareInstanceRequest.Placement placement) {
        requireMatches(NAME, placement.containerName(), "placement.containerName");
        requireAbsolutePath(placement.configDirectory(), "placement.configDirectory");
        requireAbsolutePath(placement.containerConfigPath(), "placement.containerConfigPath");
        requireMatches(NAME, placement.fileName(), "placement.fileName");
    }

    private static void requireMatches(Pattern pattern, String value, String field) {
        if (!pattern.matcher(value).matches()) {
            throw new DeclareValidationException(field);
        }
    }

    private static void requireAbsolutePath(String value, String field) {
        if (!ABSOLUTE_PATH.matcher(value).matches() || value.contains("..")) {
            throw new DeclareValidationException(field);
        }
    }

    /**
     * Plan 014: the deployment mode (absent means {@code dp-remote}) and the host binding. Only {@code dp-remote} is
     * accepted; the two reserved modes are refused until their agent contracts land. Exactly one of {@code targetId}
     * and {@code connectionId}: a body naming both names {@code connectionId} (the new binding is the one in doubt),
     * a body naming neither names {@code connectionId} (the binding new declarations use).
     */
    private static DeploymentMode deploymentModeOf(DeclareInstanceRequest request) {
        DeploymentMode deployment = request.deploymentMode() == null ? DeploymentMode.DP_REMOTE
                : DeploymentMode.fromWire(request.deploymentMode());   // the record already refused a non-member (400)
        if (deployment != DeploymentMode.DP_REMOTE) {
            throw new DeclareValidationException("deploymentMode");
        }
        if ((request.targetId() == null) == (request.connectionId() == null)) {
            throw new DeclareValidationException("connectionId");
        }
        return deployment;
    }

    /**
     * Plan 014: the Connection is the control plane's ({@code sm_control.connection}), read through {@code cp-api}; the
     * runtime projection is the data plane's ({@code sm_data.connection_runtime}), asked through {@code dp-api}. The
     * active control-plane Connection is required now; the live projection gate is temporarily controlled by
     * {@link ConnectionRuntimePolicy#REQUIRE_LIVE_PROJECTION} while generic server connections are being introduced.
     */
    private void requireRemoteServerConnection(UUID organization, UUID connectionId) {
        boolean usable = connectionDirectory.find(organization, connectionId)
                .filter(c -> REMOTE_SERVER.equals(c.connectionType()) && c.active())
                .isPresent();
        if (!usable || (ConnectionRuntimePolicy.REQUIRE_LIVE_PROJECTION
                && !connections.connectionRuntimeLive(organization, connectionId))) {
            throw new DeclareValidationException("connectionId");
        }
    }

    /** {@code acquisition} is exactly {@code {mode}}, a member of the closed vocabulary (OR-71); a host is refused (OR-82). */
    private static AcquisitionMode acquisitionOf(JsonNode acquisition) {
        if (!acquisition.isObject() || acquisition.size() != 1 || !acquisition.has("mode")
                || !acquisition.get("mode").isTextual()) {
            throw new DeclareValidationException("acquisition");
        }
        try {
            AcquisitionMode mode = AcquisitionMode.fromWire(acquisition.get("mode").textValue());
            if (mode == AcquisitionMode.AGENT) {
                throw new DeclareValidationException("acquisition");      // never an operator's choice (plan 015 T3.5)
            }
            return mode;
        } catch (IllegalArgumentException e) {
            throw new DeclareValidationException("acquisition");
        }
    }

    /**
     * M8: the probe's {@code kind}, {@code path} and {@code strength} come ONLY from the declared package (the data plane
     * never reads the package, T6.2); the host-side port comes from the body. ⛔ No branch on the solution: whatever
     * the package declares is copied, for every MI alike. The first component declaring an HTTP probe supplies it; a
     * package declaring none (a {@code broker-heartbeat} only) passes {@code null}; the data plane records that
     * placement {@code NOT_APPLICABLE} (OR-101), never a fault.
     */
    static HealthProbeSpec probeOf(String document, int hostPort) {
        JsonNode components;
        try {
            components = MAPPER.readTree(document).path("components");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a stored package document is not JSON", e);
        }
        for (JsonNode component : components) {
            JsonNode probe = component.path("health").path("probe");
            String kind = probe.path("kind").asText("");
            if (!kind.equals("actuator-health") && !kind.equals("http-status")) {
                continue;
            }
            JsonNode path = probe.path("path");
            if (!path.isTextual() || !HEALTH_PATH.matcher(path.textValue()).matches()) {
                throw new DeclareValidationException("declaredPackageVersion");
            }
            ProbeStrength strength;
            try {
                strength = ProbeStrength.fromWire(probe.path("strength").asText(""));
            } catch (IllegalArgumentException e) {
                throw new DeclareValidationException("declaredPackageVersion");
            }
            return new HealthProbeSpec(kind, path.textValue(), hostPort, strength);
        }
        return null;
    }
}
