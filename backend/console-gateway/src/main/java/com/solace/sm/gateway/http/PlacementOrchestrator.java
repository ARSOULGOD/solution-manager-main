package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementNotLiveException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code addPlacement} and {@code retirePlacement} (plan 008 S3, {@code BE-D47}, {@code BE-D53}). A composition of the two
 * planes' ports, like {@link DeclareOrchestrator}: the control plane authorizes and holds the package, the data plane owns
 * the placement rows, and neither reaches the other ({@code I7}).
 *
 * <p><b>The order.</b> {@code instance.bindTarget} at the instance has passed in {@link AuthenticationInterceptor} (the
 * path names the instance). Then: the instance is read through {@link DeclareOrchestrator#get}, so a {@code pending},
 * {@code abandoned}, unknown or other tenant's instance is the same {@code 404}; then {@code target.use} at the NAMED
 * target ({@code D10}'s two-sided rule; a denial names the verb, an unknown or out-of-scope target stays a {@code 404});
 * then the body's grammar ({@code 422} naming the field); then the package; then the data plane, which takes the instance
 * row, re-checks {@code active} and writes. Every authorization is settled before either plane is written.
 *
 * <p><b>What comes from the package, never the body.</b> The probe's {@code kind}, {@code path} and {@code strength}, and
 * the metrics definitions and source ports, are resolved from the instance's DECLARED package version
 * ({@link DeclareOrchestrator#probeOf}, {@link MetricsDeclarationBuilder}); the body supplies only the host-side ports
 * ({@code healthPort}, {@code metricsPorts}), as at declare. A package that declares a {@code solace_event} metric source
 * is refused {@code 409} by the data plane ({@code OQ-8.9 (a)}).
 *
 * <p>{@code I5}: the organization is {@code Session.organizationId()}. No quota check: a placement is not an instance
 * ({@code OQ-8.8 (a)}). <b>Who added and who retired a placement</b> is recorded ({@code OR-125} (1), sm_data V026:
 * {@code placement.created_by}, {@code placement.retired_by}); a retirement also writes, since plan 012 T5, an
 * {@code instance_retirement} row (placement-scoped, with the session principal and the request's correlation id), both in
 * the retirement's own transaction.
 */
@Component
public class PlacementOrchestrator {

    private final DeclareOrchestrator instances;
    private final InstanceInventoryService inventory;
    private final TargetConnectionService connections;
    private final SolutionCatalog catalog;
    private final AuthorizationService authorization;
    private final DesignerCodec codec;

    public PlacementOrchestrator(DeclareOrchestrator instances, InstanceInventoryService inventory,
            TargetConnectionService connections, SolutionCatalog catalog, AuthorizationService authorization,
            DesignerCodec codec) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    // ---- addPlacement --------------------------------------------------------------------------------------

    public InstanceView add(Session session, UUID instanceId, AddPlacementRequest request) {
        UUID organization = session.organizationId();
        PrincipalRef principal = session.principal();
        InstanceView instance = requireActive(session, instanceId);

        // D10's two-sided rule: the second verb, at the TARGET. A denial names the verb; a target that is not this
        // organization's stays an indistinguishable 404 (ResourceNotFoundException propagates).
        requireAlso(principal, Permission.TARGET_USE,
                ResourceRef.of(ResourceKind.DEPLOYMENT_TARGET, request.targetId(), new TenantId(organization)));

        DeclareInstanceRequest.Placement placement = request.placement();
        DeclareOrchestrator.requirePlacementGrammar(placement);

        // The instance's own declared version, as the data plane recorded it: never a version the body names.
        String version = instance.declaredPackageVersion();
        LoadedPackage declared = catalog.find(organization, instance.catalogSolutionId(), version)
                .orElseThrow(PlacementOrchestrator::packageMissing);
        String document = catalog.documentOf(organization, instance.catalogSolutionId(), version)
                .orElseThrow(PlacementOrchestrator::packageMissing);
        HealthProbeSpec probe = DeclareOrchestrator.probeOf(document, placement.healthPort());
        MetricsDeclaration metrics = metricsOf(declared, document, version, placement);

        if (connections.liveEnvelopes(organization, List.of(request.targetId())).isEmpty()) {
            throw new DeclareValidationException("targetId");
        }

        try {
            return inventory.addPlacement(organization, instanceId, new AddPlacementCommand(request.targetId(),
                    new PlacementSpec(placement.containerName(), placement.configDirectory(),
                            placement.containerConfigPath(), placement.fileName(), probe),
                    principal.id(), metrics));
        } catch (InstanceNotActiveException leftActive) {
            // The race: the instance left 'active' between the read above and the data plane's lock.
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        } catch (NoSuchElementException noLiveTarget) {
            // The target was retired between liveEnvelopes and the lock; the same 422 as a target that was never live.
            throw new DeclareValidationException("targetId");
        }
    }

    /**
     * The definitions and this placement's endpoint map, from the declared version. The generation-0 active list is the
     * INSTANCE's, written once at its declare, so none is evaluated here.
     */
    private MetricsDeclaration metricsOf(LoadedPackage declared, String document, String version,
            DeclareInstanceRequest.Placement placement) {
        PackageModel.SolutionPackage pkg;
        try {
            pkg = codec.readPackage(document);
        } catch (IllegalArgumentException e) {
            throw packageMissing();   // the message would quote the document (I6)
        }
        return MetricsDeclarationBuilder.build(declared.digest(), pkg, InstanceController.packageVersionOnTheWire(version),
                placement.metricsPorts(), List.of());
    }

    /** An instance whose declared package version the catalog cannot produce: a defect in the install, not the caller's. */
    private static IllegalStateException packageMissing() {
        return new IllegalStateException("the instance's declared package version is not in the catalog");
    }

    // ---- retirePlacement -----------------------------------------------------------------------------------

    public PlacementRetirement retire(Session session, RequestOrigin origin, UUID instanceId, UUID placementId) {
        InstanceView instance = requireActive(session, instanceId);
        if (instance.placements().stream().noneMatch(p -> p.placementId().equals(placementId))) {
            // Not a live placement of THIS instance of THIS organization: unknown, another instance's, already retired.
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        InstanceView after;
        try {
            after = inventory.retirePlacement(session.organizationId(), instanceId, placementId,
                    session.principal().id(), origin.correlationId());
        } catch (InstanceNotActiveException | PlacementNotLiveException raced) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
        return new PlacementRetirement(placementId, PlacementRetirement.LEFT_RUNNING, InstanceController.toWire(after));
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** Authorization at the instance has passed; only an ACTIVE row is actionable, else the same 404 as out of scope. */
    private InstanceView requireActive(Session session, UUID instanceId) {
        return instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    /** A secondary verb: a denial names WHICH verb; a missing or out-of-scope resource stays a 404. */
    private void requireAlso(PrincipalRef principal, Permission permission, ResourceRef resource) {
        try {
            authorization.require(principal, permission, resource);
        } catch (AccessDeniedException e) {
            throw new SecondaryVerbDeniedException(permission);
        }
    }
}
