package com.solace.sm.dp.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.AddPlacementCommand;
import com.solace.sm.dp.api.inventory.ConnectionRetirement;
import com.solace.sm.dp.api.inventory.ConnectionRuntimePolicy;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.EditPlan;
import com.solace.sm.dp.api.inventory.DeclareInstanceCommand;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceNotActiveException;
import com.solace.sm.dp.api.inventory.InstanceRequestInFlightException;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.LastLivePlacementException;
import com.solace.sm.dp.api.inventory.PlacementInFlightException;
import com.solace.sm.dp.api.inventory.PlacementSchedulePendingException;
import com.solace.sm.dp.api.inventory.PlacementNotLiveException;
import com.solace.sm.dp.api.inventory.AgentStillEnrolledException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.inventory.TargetIsAgentManagedException;
import com.solace.sm.dp.api.inventory.TargetTransportUnsupportedException;
import com.solace.sm.dp.api.inventory.PushMetricsPlacementRefusedException;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.RegistrationPrecheck;
import com.solace.sm.dp.api.inventory.ScanAccounting;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionAdministration;
import com.solace.sm.dp.api.inventory.TargetConnectionChangedException;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionManagement;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetCredentialConflictException;
import com.solace.sm.dp.api.inventory.TargetCredentialUnreadableException;
import com.solace.sm.dp.api.inventory.TargetEnvelope;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.api.inventory.TargetWorkInFlightException;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.metrics.declare.MetricDeclarationRows;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The DP inventory (plan 010 T2.5): instances, their immutable placements, and target connection records, behind
 * the {@code dp-api} ports.
 *
 * <p><b>Transactions.</b> Every operation that writes more than one row runs in one transaction on the
 * {@code sm_data} manager: reserve (instance + placement), abandon (lifecycle + retiring the placements, M7(c)),
 * register and rotate (a secret + the connection row, so a failed insert strands no key).
 *
 * <p><b>No no-op writes.</b> Every {@code instance_state} UPDATE stamps {@code state_version}, even when nothing
 * changed, so a repeat {@code activate} or {@code abandon} reads and returns without writing: the stamp must mean
 * "something observable changed" (OR-35).
 *
 * <p><b>The B2 envelope</b> is checked here first, before any row is written, which is what makes the refusal a
 * clean 422 naming only the field; V007's trigger is the second enforcer and the adapter's canonical-path
 * preflight (T6.1) the third.
 *
 * <p><b>Mechanism and retention are not browser input.</b> {@link PlacementSpec} has no field for either, so a
 * placement is stored with {@link #DEFAULT_MECHANISM} ({@code spring.config.additional-location}, the only mechanism
 * the v1 fixture uses) and {@link #DEFAULT_RETAINED_GENERATIONS}. A package-declared mechanism would be a contract
 * change, routed to the owner rather than invented here.
 *
 * <p>{@code I5}: the tenant is always the {@code organizationId} argument, which the gateway takes from the
 * session. {@code I6}: the private key is passed through to the secret store and never held, logged or put in an
 * exception; the caller zeroises its buffer.
 */
public final class InventoryService implements InstanceInventoryService, TargetConnectionService,
        TargetConnectionAdministration, TargetConnectionManagement,
        com.solace.sm.dp.api.inventory.ConnectionRuntimeAdministration,
        com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeAdministration {

    /** The mechanism a declare stores: a {@code ConfigLocation.Mechanism} constant name, as V007's CHECK requires. */
    public static final String DEFAULT_MECHANISM = ConfigLocation.Mechanism.ADDITIONAL_LOCATION.name();
    private static final ObjectMapper TARGET_SETTINGS = new ObjectMapper();

    /** Generations kept per placement (V007 CHECKs {@code >= 1}). */
    public static final int DEFAULT_RETAINED_GENERATIONS = 10;

    /** Stale unregistered rows abandoned per settle; the rest wait for the next request's sweep (idempotent). */
    static final int SWEEP_BATCH = 500;

    private final TransactionTemplate tx;
    private final InstanceRows instances;
    private final PlacementRows placements;
    private final TargetConnectionRows connections;
    /** The agent-target retire gate's question; see {@link #agentRevocation(AgentRevocation)}. */
    private volatile AgentRevocation agentRevocation;
    private final SecretStore secrets;
    private final SecretResolver resolver;
    private final PrivateKeyInspector keys = new PrivateKeyInspector();
    private final Clock clock;
    private final MetricDeclarationRows metrics;
    private final LadderRecorder ladder;
    private final ApplyEventEmitter events;
    private final HostKeyScanService scanner;

    /**
     * Plan 010's arity: no metrics repository, so a declare that carries a metrics declaration is refused and one that
     * carries none ({@link MetricsDeclaration#none()}) is unchanged. ⛔ No {@code src/main} class may call it
     * ({@code MetricsDeclarationConstructorTest}): the composition roots pass the real repository.
     */
    public InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, Clock clock) {
        this(tx, instances, placements, connections, secrets, secrets instanceof SecretResolver r ? r : null, clock,
                MetricDeclarationRows.DISCARDING, LadderRecorder.none(), ApplyEventEmitter.silent(), null, true);
    }

    /**
     * Plan 011's arity: a store that is also a resolver (the production {@code EnvelopeSecretStore} is both) is used to
     * open a stored key for the replay compare; any other store cannot be, and a compare against a stored key then
     * answers "cannot show they are the same" (a conflict on register, a replacement on rotate).
     */
    public InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, Clock clock, MetricDeclarationRows metrics) {
        this(tx, instances, placements, connections, secrets, secrets instanceof SecretResolver r ? r : null, clock,
                metrics, LadderRecorder.none(), ApplyEventEmitter.silent(), null, true);
    }

    /**
     * Plan 013's arity: the secret store that SEALS a key and the resolver that OPENS a stored one, for the compare of a
     * replayed or rotated key against the stored one. The resolver is REQUIRED (W1 review C-6): without one every replay
     * would read as a conflict and every rotation would write a new, never-deleted {@code sm_secret} row. Builds a service
     * that settles no ladder and announces nothing ({@link LadderRecorder#none()}, {@link ApplyEventEmitter#silent()}):
     * its {@code addPlacement} and {@code retirePlacement} then leave {@code reached_rung} alone. Production uses the
     * canonical form below.
     */
    public InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, SecretResolver resolver, Clock clock,
            MetricDeclarationRows metrics) {
        this(tx, instances, placements, connections, secrets, Objects.requireNonNull(resolver, "resolver"), clock,
                metrics, LadderRecorder.none(), ApplyEventEmitter.silent(), null, false);
    }

    /**
     * The canonical constructor (plan 008 S3): the above plus the ladder and the emitter, so a placement change settles
     * the instance's configuration rung through the same {@link ApplyEventEmitter#settleLadder} the apply landing uses
     * (OQ-8.10 (a): one rule, two callers).
     */
    public InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, SecretResolver resolver, Clock clock,
            MetricDeclarationRows metrics, LadderRecorder ladder, ApplyEventEmitter events) {
        this(tx, instances, placements, connections, secrets, Objects.requireNonNull(resolver, "resolver"), clock,
                metrics, ladder, events, null, false);
    }

    /**
     * Plan 013 T1.4's arity: the canonical one plus the host-key scan the registration's pre-check asks. Without one
     * ({@code null}, the ten-argument form) {@link #precheckRegistration} refuses to run rather than pass a host it never
     * looked at.
     */
    public InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, SecretResolver resolver, Clock clock,
            MetricDeclarationRows metrics, LadderRecorder ladder, ApplyEventEmitter events,
            HostKeyScanService scanner) {
        this(tx, instances, placements, connections, secrets, Objects.requireNonNull(resolver, "resolver"), clock,
                metrics, ladder, events, scanner, false);
    }

    private InventoryService(TransactionTemplate tx, InstanceRows instances, PlacementRows placements,
            TargetConnectionRows connections, SecretStore secrets, SecretResolver resolver, Clock clock,
            MetricDeclarationRows metrics, LadderRecorder ladder, ApplyEventEmitter events,
            HostKeyScanService scanner, boolean resolverMayBeAbsent) {
        this.tx = Objects.requireNonNull(tx);
        this.instances = Objects.requireNonNull(instances);
        this.placements = Objects.requireNonNull(placements);
        this.connections = Objects.requireNonNull(connections);
        this.agentRevocation = connections::agentRevoked;
        this.secrets = Objects.requireNonNull(secrets);
        this.resolver = resolver;
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.ladder = Objects.requireNonNull(ladder, "ladder");
        this.events = Objects.requireNonNull(events, "events");
        this.scanner = scanner;
    }

    /**
     * Whether an agent is revoked, asked by the agent-target retire gate (plan 015 T3.5). The default reads only the data plane's own
     * {@code sm_data.agent_identity.revoked_at}.
     */
    @FunctionalInterface
    public interface AgentRevocation {
        boolean revoked(UUID tenantId, UUID agentId);
    }

    /**
     * Replaces the retire gate's revocation check (plan 015 W4 integration, review F1). {@code revokeAgent} records the revocation in the
     * control plane, and the data plane's identity learns it only when the agent's live session is reconciled, so an OFFLINE agent's
     * target could never be retired. The composition passes a check that applies the control plane's revocation to the data plane's
     * identity first. One call, at composition, the way {@link ApplyEventEmitter#declarationsSink} is set.
     */
    public void agentRevocation(AgentRevocation check) {
        this.agentRevocation = Objects.requireNonNull(check, "check");
    }

    // ---- instances -----------------------------------------------------------------------------------------

    @Override
    public InstanceView reserve(UUID organizationId, DeclareInstanceCommand command) {
        if (command.connectionId() != null) {
            return reserveOnConnection(organizationId, command);
        }
        Optional<TargetConnectionRecord> target = connections.find(organizationId, command.targetId())
                .filter(c -> c.retiredAt() == null);
        if (target.isPresent()) {
            if (target.get().isAgent()) {
                throw new TargetIsAgentManagedException();
            }
            requireInsideEnvelope(target.get(), command.placement());
        } else {
            requireDeploymentTargetEnvelope(organizationId, command.targetId(), command.placement());
        }

        Instant now = clock.instant();
        InstanceRecord instance = new InstanceRecord(organizationId, Uuid7.next(), command.environmentId(),
                command.catalogSolutionId(), command.solution(), command.declaredPackageVersion(), command.name(),
                InstanceLifecycle.PENDING, command.acquisition(), command.requestedBy(), now, 0L,
                command.deploymentMode(), command.role());
        PlacementRecord placement = placementOf(organizationId, instance.instanceId(), command, now);
        return tx.execute(status -> {
            holdTargetLive(organizationId, command.targetId());
            instances.insert(instance);
            placements.insert(placement);
            recordMetricsDeclaration(organizationId, instance, placement, command.metrics(), now);
            return view(require(organizationId, instance.instanceId()));
        });
    }

    /**
     * Plan 014: a {@code dp-remote} declare bound to a remote-server Connection. The connection id is the control plane's,
     * validated by the gateway; it is resolved here ONLY through the data plane's own {@code connection_runtime}
     * projection ({@code I7}: nothing here reads {@code sm_control}). No live projection is a refusal, never a fallback
     * to a Target (fail closed). The projection is held {@code FOR UPDATE} first, then the rows are written, all in one
     * transaction. Runtime-specific placement/envelope validation is deferred for instance creation.
     */
    private InstanceView reserveOnConnection(UUID organizationId, DeclareInstanceCommand command) {
        Instant now = clock.instant();
        InstanceRecord instance = new InstanceRecord(organizationId, Uuid7.next(), command.environmentId(),
                command.catalogSolutionId(), command.solution(), command.declaredPackageVersion(), command.name(),
                InstanceLifecycle.PENDING, command.acquisition(), command.requestedBy(), now, 0L,
                command.deploymentMode(), command.role());
        PlacementRecord placement = connectionPlacementOf(organizationId, instance.instanceId(), command.connectionId(),
                command.placement(), now);
        return tx.execute(status -> {
            if (ConnectionRuntimePolicy.REQUIRE_LIVE_PROJECTION) {
                connections.holdConnectionRuntimeLive(organizationId, command.connectionId())
                        .orElseThrow(() -> new NoSuchElementException("the connection has no live runtime projection"));
            }
            instances.insert(instance);
            placements.insert(placement);
            recordMetricsDeclaration(organizationId, instance, placement, command.metrics(), now);
            return view(require(organizationId, instance.instanceId()));
        });
    }

    @Override
    public boolean connectionRuntimeLive(UUID organizationId, UUID connectionId) {
        return connections.connectionRuntimeLive(organizationId, connectionId);
    }

    @Override
    public void upsert(UUID organizationId, com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec spec, UUID actor) {
        tx.executeWithoutResult(status -> connections.upsertConnectionRuntime(organizationId, spec));
    }

    @Override
    public void retire(UUID organizationId, UUID connectionId, UUID actor) {
        tx.executeWithoutResult(status -> connections.retireConnectionRuntime(organizationId, connectionId));
    }

    @Override
    public void upsertDeploymentTarget(UUID organizationId,
            com.solace.sm.dp.api.inventory.DeploymentTargetRuntimeSpec spec, UUID actor) {
        tx.executeWithoutResult(status -> connections.upsertDeploymentTargetRuntime(organizationId, spec));
    }

    @Override
    public void retireDeploymentTarget(UUID organizationId, UUID targetId, UUID actor) {
        tx.executeWithoutResult(status -> connections.retireDeploymentTargetRuntime(organizationId, targetId));
    }

    /**
     * Plan 015 T4.1 ({@code OR-27}): the declare phase for an instance an AGENT discovered, as a separate entry. {@link #reserve}
     * and {@link #addPlacement} refuse every agent target for an operator ({@code TargetIsAgentManagedException}) and still do;
     * this method is the other door, public on the class but on no {@code dp-api} interface, so the only caller is the discovery
     * intake, which holds a data-plane type. It is the declare of {@link #reserve} with these differences, each by design: the
     * target MUST be an agent target, the acquisition mode is {@code agent}, the instance is created by the enrolment's issuing
     * principal and the placement by the agent, and the caller supplies the ids (so it can write the tombstone that names them).
     * The envelope (B2) is re-checked here as for every placement; V007's trigger is the second enforcer.
     *
     * <p>Runs in the caller's transaction when there is one (the intake's, so the {@code agent_discovery} row and the instance
     * commit together), else in its own. The tombstone of the instance's END (abandon, undeclare, placement retirement) is not
     * written here or by the services that end it: V031's trigger on {@code placement.retired_at} writes it in the same statement's
     * transaction, whoever retires the placement.
     */
    public void reserveDiscovered(UUID organizationId, DiscoveredReservation reservation) {
        TargetConnectionRecord target = connections.find(organizationId, reservation.targetId())
                .filter(c -> c.retiredAt() == null)
                .orElseThrow(() -> new NoSuchElementException("the target has no live connection record"));
        if (!target.isAgent()) {
            throw new IllegalStateException("a discovered instance is reserved only on an agent target");
        }
        requireInsideEnvelope(target, reservation.placement());

        Instant now = clock.instant();
        InstanceRecord instance = new InstanceRecord(organizationId, reservation.instanceId(), reservation.environmentId(),
                reservation.catalogSolutionId(), reservation.solution(), reservation.declaredPackageVersion(), reservation.name(),
                InstanceLifecycle.PENDING, AcquisitionMode.AGENT, reservation.requestedBy(), now, 0L);
        PlacementSpec s = reservation.placement();
        HealthProbeSpec probe = s.probe();
        PlacementRecord placement = new PlacementRecord(organizationId, reservation.placementId(), reservation.instanceId(),
                reservation.targetId(), s.containerName(), s.configDirectory(), s.containerConfigPath(), s.fileName(),
                DEFAULT_MECHANISM, DEFAULT_RETAINED_GENERATIONS, Optional.ofNullable(probe).map(HealthProbeSpec::kind),
                Optional.ofNullable(probe).map(HealthProbeSpec::path), Optional.ofNullable(probe).map(HealthProbeSpec::hostPort),
                Optional.ofNullable(probe).map(HealthProbeSpec::strength), now, reservation.agentId());
        tx.executeWithoutResult(status -> {
            holdTargetLive(organizationId, reservation.targetId());
            instances.insert(instance);
            placements.insert(placement);
            recordMetricsDeclaration(organizationId, instance, placement, reservation.metrics(), now);
        });
    }

    /**
     * Plan 011 M-Q5 (a'), M-Q14, M-Q15: the version's definitions (if absent), this placement's metrics endpoints and
     * the generation-0 active list, in THE SAME transaction as the instance and the placement, so any refusal (a port
     * collision, a digest mismatch) rolls the placement back with them. The order is the lock order V016 measured:
     * instance, placement, the target row, the endpoints.
     */
    private void recordMetricsDeclaration(UUID organizationId, InstanceRecord instance, PlacementRecord placement,
            MetricsDeclaration declaration, Instant now) {
        if (declaration.isNone()) {
            return;
        }
        metrics.insertDefinitionsIfAbsent(organizationId, instance.catalogSolutionId(), instance.declaredPackageVersion(),
                declaration, now);
        // ⚠️ Plan 014: a connection-backed placement records no host-side metrics endpoints yet. V016's endpoint table
        // proves its target by a foreign key through the placement, and a connection-backed placement has no target.
        // Collecting through the connection is plan 014 step 3; until then such a placement is simply not collected
        // (the collection plan finds no endpoint), never collected against a wrong host.
        if (!placement.connectionBacked()) {
            metrics.insertEndpoints(organizationId, placement.placementId(), placement.targetId(),
                    declaration.metricsPorts(), now);
        }
        metrics.insertActiveMetrics(organizationId, instance.instanceId(), 0L, declaration.activeMetricNames(), now);
    }

    @Override
    public InstanceView activate(UUID organizationId, UUID instanceId) {
        return tx.execute(status -> {
            InstanceRecord current = require(organizationId, instanceId);
            if (current.lifecycle() == InstanceLifecycle.ACTIVE) {
                return view(current);
            }
            if (current.lifecycle() == InstanceLifecycle.ABANDONED) {
                throw new IllegalStateException("an abandoned instance cannot be activated");
            }
            instances.activate(organizationId, instanceId);
            InstanceRecord after = require(organizationId, instanceId);
            if (after.lifecycle() != InstanceLifecycle.ACTIVE) {
                throw new IllegalStateException("the instance left 'pending' while it was being activated");
            }
            events.declarationsChanged(new TenantId(organizationId), new InstanceId(instanceId));   // W4 lane D: a pending instance declared nothing
            return view(after);
        });
    }

    @Override
    public void abandon(UUID organizationId, UUID instanceId) {
        tx.executeWithoutResult(status -> {
            InstanceRecord current = require(organizationId, instanceId);
            if (current.lifecycle() == InstanceLifecycle.ABANDONED) {
                return;
            }
            if (current.lifecycle() == InstanceLifecycle.ACTIVE) {
                throw new IllegalStateException("an active instance cannot be abandoned");
            }
            // ORDER MATTERS (sm_data V031): the instance is marked 'abandoned' BEFORE its placements are retired; the trigger on
            // placement.retired_at reads the lifecycle to write the agent discovery tombstone (ABANDONED, not RETIRED).
            instances.abandon(organizationId, instanceId);
            if (require(organizationId, instanceId).lifecycle() != InstanceLifecycle.ABANDONED) {
                throw new IllegalStateException("the instance left 'pending' while it was being abandoned");
            }
            placements.retireAll(organizationId, instanceId, clock.instant());
        });
    }

    @Override
    public Optional<InstanceView> findActive(UUID organizationId, UUID instanceId) {
        return tx.execute(status -> instances.find(organizationId, instanceId)
                .filter(r -> r.lifecycle() == InstanceLifecycle.ACTIVE).map(this::view));
    }

    @Override
    public List<InstanceView> listActive(UUID organizationId, UUID catalogSolutionId) {
        return tx.execute(status -> instances.list(organizationId, InstanceLifecycle.ACTIVE,
                Optional.of(catalogSolutionId)).stream().map(this::view).toList());
    }

    @Override
    public List<UUID> pendingOlderThan(UUID organizationId, Duration age) {
        return instances.pendingCreatedBefore(organizationId, clock.instant().minus(age));
    }

    @Override
    public List<UUID> pending(UUID organizationId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return instances.pendingIds(organizationId, limit);
    }

    @Override
    public void settlePending(UUID organizationId, Set<UUID> registered, Duration abandonAfter) {
        Objects.requireNonNull(registered);
        if (abandonAfter.isZero() || abandonAfter.isNegative()) {
            throw new IllegalArgumentException("abandonAfter must be positive");
        }
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            // Rule 1: a registered pending row is activated at any age. `activate` is conditional on 'pending', so an
            // id that is already active, abandoned, unknown or another tenant's changes nothing and stamps nothing.
            for (UUID id : registered) {
                instances.activate(organizationId, id);
                events.declarationsChanged(new TenantId(organizationId), new InstanceId(id));   // W4 lane D (a no-op for one not pending)
            }
            // Rule 2: every OTHER pending row older than the window is abandoned, its placements retired with it.
            for (UUID id : instances.pendingCreatedBefore(organizationId, now.minus(abandonAfter), SWEEP_BATCH)) {
                // ORDER MATTERS (sm_data V031): abandon first, then retire the placements (the trigger tombstones by lifecycle)
                if (!registered.contains(id) && instances.abandon(organizationId, id) == 1) {
                    placements.retireAll(organizationId, id, now);
                }
            }
        });
    }

    // ---- placements of an existing instance (plan 008 S3) -----------------------------------------------------

    /** The {@code solace_event} source kind: a push reading, attributed to a placement by the instance key in its payload. */
    static final String PUSH_SOURCE_KIND = "solace_event";

    @Override
    public InstanceView addPlacement(UUID organizationId, UUID instanceId, AddPlacementCommand command) {
        // Every refusal that needs no row is decided first, so a refused add leaves nothing (BE-D47, OQ-8.9 (a)): the push
        // package, then the target and its envelope (B2, the first of three enforcers).
        if (command.metrics().definitions().stream().anyMatch(d -> PUSH_SOURCE_KIND.equals(d.sourceKind()))) {
            throw new PushMetricsPlacementRefusedException();
        }
        Optional<TargetConnectionRecord> target = connections.find(organizationId, command.targetId())
                .filter(c -> c.retiredAt() == null);
        if (target.isPresent()) {
            if (target.get().isAgent()) {
                throw new TargetIsAgentManagedException();
            }
            requireInsideEnvelope(target.get(), command.placement());
        } else {
            requireDeploymentTargetEnvelope(organizationId, command.targetId(), command.placement());
        }

        Instant now = clock.instant();
        PlacementRecord placement = placementOf(organizationId, instanceId, command.targetId(), command.placement(), now,
                command.requestedBy());
        return tx.execute(status -> {
            // apply_scope FIRST, then the instance row: the established order (admission's, and retire's), so the add
            // cannot deadlock against either, and with the scope held no request can be admitted while the check below is
            // made (W2 review W2A-2). A stop reads the live set once, when it starts running; an add that slipped in under
            // one in flight would leave its container running under a stop that reports the instance stopped.
            if (!placements.lockApplyScope(organizationId, instanceId)) {
                throw new InstanceNotActiveException();      // no such instance of this tenant: nothing to lock
            }
            // THE guard (BE-D47): no database rule ties a placement insert to an active instance, so the row lock and the
            // re-check under it are what keep a placement off an instance that has just left 'active'. reserve's lock
            // order from here: instance, placement, target row (V016's trigger), endpoints.
            InstanceRecord instance = lockedActive(organizationId, instanceId);
            if (placements.instanceWideInFlight(organizationId, instanceId)) {
                throw new InstanceRequestInFlightException();
            }
            // The target is re-read under a lock that a retirement cannot pass (review W4C-2): the read above was made
            // outside this transaction, and a retirement that commits after it would otherwise leave a live placement on a
            // retired target. The lock order is apply_scope, instance, then the target row (retirement takes only the row).
            holdTargetLive(organizationId, command.targetId());
            placements.insert(placement);
            recordPlacementMetrics(organizationId, instance, placement, command.metrics(), now);
            return placementSetChanged(organizationId, instanceId);
        });
    }

    @Override
    public InstanceView retirePlacement(UUID organizationId, UUID instanceId, UUID placementId, UUID requestedBy,
            UUID correlationId) {
        // Required AND recorded twice, in this one transaction: placement.retired_by with retired_at (OR-125 (1), sm_data V026)
        // and, since plan 012 T5, the placement-scoped instance_retirement record (actor, correlation id, last health).
        // A refused retirement writes neither.
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(correlationId, "correlationId");
        Instant now = clock.instant();
        return tx.execute(status -> {
            // apply_scope FIRST, then the instance row: the admission path's order, so the two cannot deadlock, and with
            // the scope held no request can be admitted between the in-flight check and the retirement (BE-D53).
            if (!placements.lockApplyScope(organizationId, instanceId)) {
                throw new InstanceNotActiveException();      // no such instance of this tenant: nothing to lock
            }
            lockedActive(organizationId, instanceId);
            if (placements.findLive(organizationId, instanceId, placementId).isEmpty()) {
                throw new PlacementNotLiveException();
            }
            if (placements.live(organizationId, instanceId).size() <= 1) {
                throw new LastLivePlacementException();
            }
            if (placements.inFlightOn(organizationId, instanceId, placementId)) {
                throw new PlacementInFlightException();
            }
            // Fable F-3 (OR-131 Q2 by analogy): a PENDING schedule names the placement, under the same locks as the check above.
            if (placements.scheduleReservesPlacement(organizationId, instanceId, placementId)) {
                throw new PlacementSchedulePendingException();
            }
            if (placements.recordRetirement(organizationId, Uuid7.next(), instanceId, placementId, requestedBy, now,
                    correlationId) != 1) {
                throw new IllegalStateException("the placement's retirement record could not be written");
            }
            if (placements.retire(organizationId, instanceId, placementId, now, requestedBy) != 1) {
                // Held under the instance lock, so nobody else can have retired it since findLive: a defect, not a race.
                throw new IllegalStateException("the placement changed while it was being retired");
            }
            return placementSetChanged(organizationId, instanceId);
        });
    }

    /**
     * Holds the target's connection record live ({@code FOR UPDATE}) until the caller's transaction ends; else the
     * target is, for this call, not there. Pairs with {@code retireConnection}'s {@code FOR UPDATE} (review W4C-2).
     */
    private void holdTargetLive(UUID organizationId, UUID targetId) {
        if (!connections.holdLive(organizationId, targetId)) {
            // A linked deployment target is retired through its CP governance row. Its access projection is immutable
            // target metadata and is intentionally not copied into target_connection (which would duplicate secrets).
            if (connections.findDeploymentTargetRuntime(organizationId, targetId).isEmpty()) {
                throw new NoSuchElementException("the target has no live connection record");
            }
        }
    }

    /** The row, locked {@code FOR NO KEY UPDATE}, and {@code active}: else the instance is, for this call, not there. */
    private InstanceRecord lockedActive(UUID organizationId, UUID instanceId) {
        InstanceRecord instance = instances.lockForPlacementChange(organizationId, instanceId)
                .orElseThrow(InstanceNotActiveException::new);
        if (instance.lifecycle() != InstanceLifecycle.ACTIVE) {
            throw new InstanceNotActiveException();
        }
        return instance;
    }

    /**
     * The instance's placement set changed, inside the caller's transaction: stamp the instance (its observable view just
     * changed even though no column of the row did, OR-35's "a stamp means something observable changed"), settle the
     * ladder (OQ-8.10 (a): the lowest rung across LIVE placements moved when the set did, so the stored rung and
     * {@code instance.config-state} must follow the read), and return the view the caller reads.
     */
    private InstanceView placementSetChanged(UUID organizationId, UUID instanceId) {
        if (instances.touch(organizationId, instanceId) != 1) {
            throw new IllegalStateException("the instance left 'active' while its placements were being changed");
        }
        events.settleLadder(ladder, new TenantId(organizationId), new InstanceId(instanceId));
        return view(require(organizationId, instanceId));
    }

    /**
     * The new placement's metrics endpoints (plan 011 M-Q14) and, if absent, the version's definitions: the same rows
     * {@code reserve} writes, in the same transaction, so a port collision rolls the placement back with them. NOT the
     * generation-0 active list: that is the instance's, written once at its declare.
     */
    private void recordPlacementMetrics(UUID organizationId, InstanceRecord instance, PlacementRecord placement,
            MetricsDeclaration declaration, Instant now) {
        if (declaration.isNone()) {
            return;
        }
        metrics.insertDefinitionsIfAbsent(organizationId, instance.catalogSolutionId(), instance.declaredPackageVersion(),
                declaration, now);
        metrics.insertEndpoints(organizationId, placement.placementId(), placement.targetId(), declaration.metricsPorts(),
                now);
    }

    // ---- target connections --------------------------------------------------------------------------------

    /**
     * The operator command's registration (plan 010 T7.1; unchanged in what it refuses and what it answers, so a
     * constraint violation still reaches the command as a named {@code PgConstraintViolation}). Since plan 013 T1.4 it
     * also records the key's PUBLIC fingerprint on the row and writes the {@code registered} event, with the command's
     * own origin ({@link TargetChangeOrigin#operatorCommand}). The HTTP path is {@link #registerConnection}.
     */
    @Override
    public void register(UUID organizationId, UUID targetId, TargetConnectionSpec spec, char[] privateKey,
            UUID actor) {
        // Every refusal that needs no row is decided first, so a refused registration leaves nothing (D-REG section
        // 8): the grammar, then the key (it must open as an unencrypted private key; its PUBLIC fingerprint is what a
        // replay compares).
        TargetConnectionValidator.validate(spec);
        String candidate = keys.publicFingerprint(privateKey);
        TargetChangeOrigin origin = TargetChangeOrigin.operatorCommand();
        tx.executeWithoutResult(status -> {
            Optional<TargetConnectionRecord> existing = connections.find(organizationId, targetId);
            if (existing.isPresent()) {
                requireReplayOf(organizationId, existing.get(), spec, candidate);
                // Reusing a registration may still learn of a key type the first run did not know (OR-89): record
                // it, or refuse if another live target of the organization already holds it.
                connections.addHostKeys(organizationId, targetId, spec.allHostKeyFingerprints());
                backFillHostKeyType(organizationId, existing.get(), spec);
                return;
            }
            UUID credential = secrets.put(organizationId, SecretPurpose.SSH_PRIVATE_KEY, privateKey, actor);
            connections.insert(newRecord(organizationId, targetId, spec, credential, actor, candidate));
            // The pinned key was mirrored by the database; the host's other key types are recorded here. A key any
            // other live target of this organization holds refuses the whole registration (one transaction).
            connections.addHostKeys(organizationId, targetId, spec.allHostKeyFingerprints());
            connections.recordEvent(registeredEvent(organizationId, targetId, actor, origin, candidate));
        });
    }

    @Override
    public RegistrationPrecheck precheckRegistration(UUID organizationId, UUID replayingTargetId,
            TargetConnectionSpec spec, char[] privateKey) {
        if (scanner == null) {
            throw new IllegalStateException("no host-key scanner is configured");
        }
        // The order is D-REG section 8's, and each step refuses before the next one costs anything: the grammar (free),
        // the key (a parse, no I/O), then the address policy and the scan (the scan applies the policy first and connects
        // to the address it checked), then the pin, then the probe. Nothing is written, so every refusal leaves nothing.
        TargetConnectionValidator.validate(spec);
        String publicFingerprint = keys.publicFingerprint(privateKey);
        List<OfferedHostKey> offered = scanner.scan(spec.host(), spec.port());
        Set<String> fingerprints = new LinkedHashSet<>();
        for (OfferedHostKey key : offered) {
            fingerprints.add(key.fingerprint());
        }
        if (!fingerprints.contains(spec.hostKeyFingerprint())) {
            throw new HostKeyNotOfferedException();
        }
        if (connections.liveHolderOfAny(organizationId, fingerprints, replayingTargetId).isPresent()) {
            throw new HostKeyHeldElsewhereException();
        }
        return new RegistrationPrecheck(offered, publicFingerprint);
    }

    @Override
    public RegistrationOutcome registerConnection(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
            char[] privateKey, UUID actor, TargetChangeOrigin origin) {
        Objects.requireNonNull(origin, "origin");
        TargetConnectionValidator.validate(spec);
        String candidate = keys.publicFingerprint(privateKey);
        // Two identical requests at once both pass the pre-check and both find no row; one wins the insert. The loser's
        // transaction is rolled back whole (its secret with it: they are one transaction) and run again, and the second
        // run finds the winner's row and takes the replay path. The loser learns of the loss in one of two ways:
        // the primary key (insertOnce answers false), or, when its insert reached the host-key indexes before the
        // winner's row was visible to the primary-key check, a unique violation on a host key. Both requests pin the same
        // key, so the second looks like "another target holds this key" and is not: the winner IS this target. Only the
        // second run can tell, because it looks the row up again. A second loss is therefore final: a host-key refusal
        // then is a genuine other holder, and a lost race cannot repeat (the winner's row is committed and visible).
        for (int attempt = 0; ; attempt++) {
            try {
                return tx.execute(status -> registerOnce(organizationId, targetId, spec, privateKey, actor, origin,
                        candidate));
            } catch (RegistrationRaceLost lost) {
                if (attempt > 0) {
                    throw new TargetConnectionChangedException();
                }
            } catch (HostKeyHeldElsewhereException held) {
                if (attempt > 0) {
                    throw held;
                }
            } catch (ConcurrencyFailureException deadlocked) {
                // Review W3C-7: two registrations of one machine under two names with different pins each mirror their own
                // pin into target_host_key (the V012 trigger) and then add the other's, and each waits on the other's
                // uncommitted unique entry. PostgreSQL aborts one (40P01, Spring: DeadlockLoserDataAccessException, a
                // ConcurrencyFailureException) and rolls its transaction back whole, so it is retried like a lost race. The
                // winner has committed or rolled back by then, so the second run either replays, or meets the winner's
                // committed host key and is refused as a genuine other holder.
                if (attempt > 0) {
                    throw new TargetConnectionChangedException();
                }
            }
        }
    }

    private RegistrationOutcome registerOnce(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
            char[] privateKey, UUID actor, TargetChangeOrigin origin, String candidate) {
        Optional<TargetConnectionRecord> existing = connections.find(organizationId, targetId);
        if (existing.isPresent()) {
            requireReplayOf(organizationId, existing.get(), spec, candidate);
            connections.addHostKeysOnce(organizationId, targetId, spec.allHostKeyFingerprints());
            backFillHostKeyType(organizationId, existing.get(), spec);
            return RegistrationOutcome.REPLAYED;
        }
        UUID credential = secrets.put(organizationId, SecretPurpose.SSH_PRIVATE_KEY, privateKey, actor);
        if (!connections.insertOnce(newRecord(organizationId, targetId, spec, credential, actor, candidate))) {
            throw new RegistrationRaceLost();
        }
        connections.addHostKeysOnce(organizationId, targetId, spec.allHostKeyFingerprints());
        connections.recordEvent(registeredEvent(organizationId, targetId, actor, origin, candidate));
        return RegistrationOutcome.CREATED;
    }

    /** Thrown inside the transaction, only to roll it back; never leaves {@link #registerConnection}. */
    private static final class RegistrationRaceLost extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RegistrationRaceLost() {
            super("the connection record was registered by another request first", null, false, false);
        }
    }

    /**
     * A replay carries the data plane's own fresh scan, so it may learn the type of a pin the stored row was registered
     * without (a row that pre-dates V027, OR-125 (2)). Written only while the row has no type, and only for this very pin,
     * so a replay never replaces a recorded type with a claim; a replay that knows no type writes nothing.
     */
    private void backFillHostKeyType(UUID organizationId, TargetConnectionRecord existing, TargetConnectionSpec spec) {
        if (existing.hostKeyType() == null && spec.hostKeyType() != null) {
            connections.recordHostKeyType(organizationId, existing.hostKeyFingerprint(), spec.hostKeyType());
        }
    }

    /**
     * The record already exists: it is a replay only if it is live, has the same fields, and holds the same KEY. Silently
     * keeping the stored key would drop the one the caller just sent (D-REG section 8). Nothing is written, except the
     * back-fill of a legacy row's fingerprint, which {@link #credentialMatches} does in the caller's transaction.
     */
    private void requireReplayOf(UUID organizationId, TargetConnectionRecord existing, TargetConnectionSpec spec,
            String candidate) {
        if (existing.retiredAt() != null) {
            throw new IllegalStateException("the target's connection record is retired; register a new target");
        }
        if (existing.isAgent() || !sameRecord(existing, spec)) {
            throw new IllegalStateException("the target is already registered with a different connection record");
        }
        if (!credentialMatches(organizationId, existing, candidate)) {
            throw new TargetCredentialConflictException();
        }
    }

    private TargetConnectionRecord newRecord(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
            UUID credential, UUID actor, String publicFingerprint) {
        return new TargetConnectionRecord(organizationId, targetId, spec.host(), spec.port(), spec.sshUser(),
                spec.hostKeyFingerprint(), credential, spec.allowedConfigRoot(), spec.allowedContainerPrefix(), actor,
                clock.instant(), null, publicFingerprint, spec.hostKeyType());
    }

    private TargetConnectionEvent registeredEvent(UUID organizationId, UUID targetId, UUID actor,
            TargetChangeOrigin origin, String publicFingerprint) {
        return new TargetConnectionEvent(organizationId, targetId, Uuid7.next(), TargetConnectionEvent.Kind.REGISTERED,
                actor, origin.correlationId(), origin.clientAddress(), List.of(), publicFingerprint, clock.instant());
    }

    @Override
    public Optional<TargetConnectionView> connection(UUID organizationId, UUID targetId) {
        return tx.execute(status -> connections.find(organizationId, targetId).filter(c -> c.retiredAt() == null)
                .map(c -> c.isAgent()
                        ? TargetConnectionView.ofAgent(c.targetId(), c.agentId(), c.allowedConfigRoot(), c.allowedContainerPrefix())
                        : new TargetConnectionView(c.targetId(), c.host(), c.port(), c.sshUser(),
                        c.hostKeyFingerprint(), List.copyOf(connections.liveHostKeys(organizationId, targetId)),
                        c.allowedConfigRoot(), c.allowedContainerPrefix(),
                        new TargetConnectionView.CredentialSummary(true, c.credentialPublicFingerprint(),
                                connections.credentialSetAt(organizationId, targetId).orElse(null)))));
    }

    /**
     * The operator command's rotation. Since plan 013 T1.5 it also appends the {@code credential_rotated} event, with the
     * command's own origin ({@link TargetChangeOrigin#operatorCommand}), exactly as {@link #rotateConnectionCredential}
     * does for the HTTP path: every change of a connection record leaves one (BE-D56).
     */
    @Override
    public void rotateCredential(UUID organizationId, UUID targetId, char[] privateKey, UUID actor) {
        rotate(organizationId, targetId, privateKey, actor, TargetChangeOrigin.operatorCommand());
    }

    @Override
    public TargetConnectionView.CredentialSummary rotateConnectionCredential(UUID organizationId, UUID targetId,
            char[] privateKey, UUID actor, TargetChangeOrigin origin) {
        String fingerprint = rotate(organizationId, targetId, privateKey, actor, Objects.requireNonNull(origin, "origin"));
        Instant setAt = tx.execute(status -> connections.credentialSetAt(organizationId, targetId).orElse(null));
        return new TargetConnectionView.CredentialSummary(true, fingerprint, setAt);
    }

    /** @return the PUBLIC fingerprint of the key now stored (the candidate's, whether or not it was new) */
    private String rotate(UUID organizationId, UUID targetId, char[] privateKey, UUID actor, TargetChangeOrigin origin) {
        // An agent target has no key to rotate: refused before the key is even opened (plan 015 T3.5).
        connections.find(organizationId, targetId).filter(c -> c.retiredAt() == null).ifPresent(InventoryService::sshOnly);
        // The key is opened first: a key that does not open is refused before any row is read, locked or written.
        String candidate = keys.publicFingerprint(privateKey);
        return tx.execute(status -> {
            // The record is locked (FOR NO KEY UPDATE) for the compare AND the write: without the lock two concurrent
            // rotations to one new key each saw "different" and each stored a secret row that is never deleted (OR-85;
            // T1.1's residual). A placement insert is not blocked by it.
            TargetConnectionRecord live = sshOnly(connections.findLiveForChange(organizationId, targetId)
                    .orElseThrow(() -> new NoSuchElementException("the target has no live connection record")));
            boolean same;
            try {
                same = credentialMatches(organizationId, live, candidate);
            } catch (TargetCredentialUnreadableException e) {
                // Rotation is the repair for a stored key nobody can open: replacing it is the point.
                same = false;
            }
            if (!same) {
                UUID credential = secrets.put(organizationId, SecretPurpose.SSH_PRIVATE_KEY, privateKey, actor);
                if (connections.updateCredential(organizationId, targetId, credential, candidate) != 1) {
                    throw new TargetConnectionChangedException();
                }
                connections.recordEvent(event(organizationId, targetId, TargetConnectionEvent.Kind.CREDENTIAL_ROTATED,
                        actor, origin, List.of(), candidate));
            }
            // The same key again (a double submit, a re-export) wrote nothing: the runtime has no DELETE on sm_secret, so
            // every row stored is stored forever (OR-85).
            return candidate;
        });
    }

    // ---- edit and retire (plan 013 T1.5) ------------------------------------------------------------------------

    /** The four editable connection fields, as one value so a stored record and an edited one compare. */
    private record Connection(String host, int port, String sshUser, String pin) {

        static Connection of(TargetConnectionRecord r) {
            return new Connection(r.host(), r.port(), r.sshUser(), r.hostKeyFingerprint());
        }

        /** What the record would hold after {@code edit}: a field not named keeps the stored value. */
        Connection with(TargetConnectionEdit edit) {
            return new Connection(edit.host() != null ? edit.host() : host, edit.port() != null ? edit.port() : port,
                    edit.sshUser() != null ? edit.sshUser() : sshUser,
                    edit.hostKeyFingerprint() != null ? edit.hostKeyFingerprint() : pin);
        }

        /** The host, the port or the pin differ: the machine the record names is not the one that was verified. */
        boolean needsScanFrom(Connection stored) {
            return !sameMachineAs(stored);
        }

        /** The same host, port and pin: what a scan verifies. The SSH user is not part of it. */
        boolean sameMachineAs(Connection other) {
            return host.equals(other.host) && port == other.port && pin.equals(other.pin);
        }

        List<String> changedFrom(Connection stored) {
            List<String> names = new ArrayList<>(4);
            if (!host.equals(stored.host)) {
                names.add("host");
            }
            if (port != stored.port) {
                names.add("port");
            }
            if (!sshUser.equals(stored.sshUser)) {
                names.add("sshUser");
            }
            if (!pin.equals(stored.pin)) {
                names.add("hostKeyFingerprint");
            }
            return List.copyOf(names);
        }
    }

    private TargetConnectionRecord liveRecord(UUID organizationId, UUID targetId) {
        return sshOnly(connections.find(organizationId, targetId).filter(c -> c.retiredAt() == null)
                .orElseThrow(() -> new NoSuchElementException("the target has no live connection record")));
    }

    /** An edit, a rotation and a test address an SSH record; an agent target has none (plan 015 T3.5, V028). */
    private static TargetConnectionRecord sshOnly(TargetConnectionRecord record) {
        if (record.isAgent()) {
            throw new TargetTransportUnsupportedException();
        }
        return record;
    }

    /** Validates what the edit would leave in the record (naming the first bad field, never its value). */
    private static Connection effective(TargetConnectionRecord stored, TargetConnectionEdit edit) {
        Connection after = Connection.of(stored).with(edit);
        TargetConnectionValidator.validate(new TargetConnectionSpec(after.host(), after.port(), after.sshUser(),
                after.pin(), stored.allowedConfigRoot(), stored.allowedContainerPrefix()));
        return after;
    }

    @Override
    public EditPlan planEdit(UUID organizationId, UUID targetId, TargetConnectionEdit edit) {
        Objects.requireNonNull(edit, "edit");
        TargetConnectionRecord stored = liveRecord(organizationId, targetId);
        Connection after = effective(stored, edit);
        Connection before = Connection.of(stored);
        return new EditPlan(!after.changedFrom(before).isEmpty(), after.needsScanFrom(before));
    }

    @Override
    public TargetConnectionView editConnection(UUID organizationId, UUID targetId, TargetConnectionEdit edit,
            UUID actor, TargetChangeOrigin origin, ScanAccounting accounting) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(accounting, "accounting");
        TargetConnectionRecord read = liveRecord(organizationId, targetId);
        Connection wanted = effective(read, edit);
        if (wanted.changedFrom(Connection.of(read)).isEmpty()) {
            return currentConnection(organizationId, targetId);
        }

        // The scan is made BEFORE any lock: a connection to a host the caller named can take seconds, and no lock on
        // apply_scope (which blocks every admission on the instance) may be held across it. What it verified is the
        // (host, port, pin) it was asked about; under the lock the edit is applied only to that very triple.
        Connection scanned = null;
        Set<String> offeredFingerprints = Set.of();
        HostKeyType scannedType = null;
        if (wanted.needsScanFrom(Connection.of(read))) {
            if (scanner == null) {
                throw new IllegalStateException("no host-key scanner is configured");
            }
            Set<String> fingerprints = new LinkedHashSet<>();
            accounting.beforeScan();             // W4C-3: the caller charges the scan THIS decision causes, or refuses it
            for (OfferedHostKey key : scanner.scan(wanted.host(), wanted.port())) {
                fingerprints.add(key.fingerprint());
                if (key.fingerprint().equals(wanted.pin())) {
                    // The pin's type comes from this very scan (OR-125 (2)); one the data plane does not pin by is simply unknown.
                    scannedType = HostKeyType.ofWire(key.algorithm()).orElse(null);
                }
            }
            accounting.afterScan();
            if (!fingerprints.contains(wanted.pin())) {
                throw new HostKeyNotOfferedException();
            }
            if (connections.liveHolderOfAny(organizationId, fingerprints, targetId).isPresent()) {
                throw new HostKeyHeldElsewhereException();
            }
            scanned = wanted;
            offeredFingerprints = fingerprints;
        }
        Connection verified = scanned;
        Set<String> offered = offeredFingerprints;
        HostKeyType verifiedType = scannedType;
        Instant now = clock.instant();
        try {
            tx.executeWithoutResult(status -> applyEdit(organizationId, targetId, edit, actor, origin, verified, offered,
                    verifiedType, now));
        } catch (ConcurrencyFailureException deadlocked) {
            // A lock-order inversion is not designed for (apply_scope, then the record); if the database found one it
            // rolled this transaction back whole. The caller retries.
            throw new TargetConnectionChangedException();
        }
        return currentConnection(organizationId, targetId);
    }

    private void applyEdit(UUID organizationId, UUID targetId, TargetConnectionEdit edit, UUID actor,
            TargetChangeOrigin origin, Connection verified, Set<String> offered, HostKeyType scannedType, Instant now) {
        boolean rescanned = verified != null;
        if (rescanned) {
            // apply_scope of every instance with a live placement on the target FIRST, in instance_id order: the
            // established order (admission's, addPlacement's, retirePlacement's), then the record. With them held no
            // request can be admitted on those instances while the in-flight check below is made (OQ-12 (a), R4).
            placements.lockApplyScopesOfTarget(organizationId, targetId);
        }
        TargetConnectionRecord locked = sshOnly(connections.findLiveForChange(organizationId, targetId)
                .orElseThrow(() -> new NoSuchElementException("the target has no live connection record")));
        Connection before = Connection.of(locked);
        Connection after = before.with(edit);
        List<String> changed = after.changedFrom(before);
        if (changed.isEmpty()) {
            return;                 // another edit made this one a no-op since it was read: nothing to write
        }
        if (after.needsScanFrom(before)) {
            // The record moved since it was read, and this edit now points somewhere nobody scanned.
            if (!rescanned || !verified.sameMachineAs(after)) {
                throw new TargetConnectionChangedException();
            }
            if (placements.inFlightOnTarget(organizationId, targetId)) {
                throw new TargetWorkInFlightException();
            }
        }
        // The pin and its type are written in ONE statement (OR-125 (2)). A scanned edit takes the type that very scan
        // reported for the pin (null when it is of a type the data plane does not pin by, never the OLD pin's type); an edit
        // that scanned nothing leaves the pin as it was, so it keeps the recorded type.
        HostKeyType type = after.needsScanFrom(before) ? scannedType : locked.hostKeyType();
        if (connections.updateConnection(organizationId, targetId, after.host(), after.port(), after.sshUser(),
                after.pin(), type) != 1) {
            throw new TargetConnectionChangedException();
        }
        if (after.needsScanFrom(before)) {
            // R9: the host keys follow the scan. Retire what the machine no longer offers, revive what it offers again
            // (the pinned-key trigger's DO NOTHING would keep a once-retired key retired), record the rest. A key another
            // live target holds refuses the whole edit (one transaction).
            connections.retireHostKeysNotIn(organizationId, targetId, offered, now);
            connections.reviveHostKeys(organizationId, targetId, offered);
            connections.addHostKeysOnce(organizationId, targetId, offered);
        }
        TargetConnectionEvent.Kind kind = !after.pin().equals(before.pin()) ? TargetConnectionEvent.Kind.REPINNED
                : TargetConnectionEvent.Kind.EDITED;
        connections.recordEvent(event(organizationId, targetId, kind, actor, origin, changed, null));
    }

    private TargetConnectionView currentConnection(UUID organizationId, UUID targetId) {
        return connection(organizationId, targetId)
                .orElseThrow(() -> new NoSuchElementException("the target has no live connection record"));
    }

    @Override
    public ConnectionRetirement retireConnection(UUID organizationId, UUID targetId, UUID actor,
            TargetChangeOrigin origin) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(origin, "origin");
        Instant now = clock.instant();
        return tx.execute(status -> {
            // FOR UPDATE (review W4C-2), as defence in depth: the race itself is closed on the placement side, where
            // addPlacement and reserve hold the record live FOR UPDATE and re-read retired_at under it (holdLive). A plain
            // UPDATE of retired_at would take FOR NO KEY UPDATE, which that lock already excludes; this takes the strongest
            // lock before the placement look-up, whatever a future writer of rows that reference the target holds.
            Optional<TargetConnectionRecord> record = connections.findForRetirement(organizationId, targetId);
            if (record.isEmpty()) {
                return ConnectionRetirement.NO_CONNECTION;
            }
            if (record.get().retiredAt() != null) {
                return ConnectionRetirement.ALREADY_RETIRED;
            }
            // plan 015 T3.5: an agent target is retired only after its agent is revoked (revocation is sticky)
            if (record.get().isAgent() && !agentRevocation.revoked(organizationId, record.get().agentId())) {
                throw new AgentStillEnrolledException();
            }
            // This pre-check exists only for the error text (R10): V007's target_connection_retire_guard is the enforcer,
            // and, with the lock above, a placement that is in flight at this instant is already visible to it.
            if (placements.anyLiveOnTarget(organizationId, targetId)) {
                throw new TargetHasLivePlacementsException();
            }
            if (connections.retire(organizationId, targetId, now) != 1) {
                // Retired by a concurrent request between the read and the write: that request wrote the event.
                if (connections.find(organizationId, targetId).map(c -> c.retiredAt() != null).orElse(false)) {
                    return ConnectionRetirement.ALREADY_RETIRED;
                }
                throw new TargetConnectionChangedException();
            }
            connections.recordEvent(event(organizationId, targetId, TargetConnectionEvent.Kind.RETIRED, actor, origin,
                    List.of(), null));
            return ConnectionRetirement.RETIRED;
        });
    }

    private TargetConnectionEvent event(UUID organizationId, UUID targetId, TargetConnectionEvent.Kind kind, UUID actor,
            TargetChangeOrigin origin, List<String> changedFields, String publicFingerprint) {
        return new TargetConnectionEvent(organizationId, targetId, Uuid7.next(), kind, actor, origin.correlationId(),
                origin.clientAddress(), changedFields, publicFingerprint, clock.instant());
    }

    /**
     * Whether the record's stored key has the PUBLIC fingerprint {@code candidate}. The recorded fingerprint
     * ({@code credential_public_fingerprint}, V022) is compared when there is one, and no secret is opened. A legacy row
     * has none: its stored secret is opened here, inside {@code dp-core}, its fingerprint derived with the same
     * inspector and the plaintext closed in a {@code finally}, and the column is back-filled in the caller's transaction,
     * so the next compare opens nothing. Only same-or-differs leaves this method (OR-104's rule, without
     * {@code SecretCompare}, which resolves {@code CONFIG_FIELD} only and compares text, so it would read a re-export as
     * a different key).
     *
     * <p>A stored key that does not parse as a key (a row registered before the parse existed) is not the candidate: the
     * answer is {@code false}, and nothing is back-filled (there is no fingerprint to record). A stored secret that cannot
     * be OPENED at all (W1 review C-6: a missing row, a key the ring no longer holds) is a different failure, not "a
     * different key": it throws {@link TargetCredentialUnreadableException}, which a rotation treats as the repair it is
     * and which a replay carries to the caller as its own refusal (review W3C-4: it is not "the key differs", and not
     * "the record differs"), since nothing could be compared.
     */
    private boolean credentialMatches(UUID organizationId, TargetConnectionRecord record, String candidate) {
        String stored = record.credentialPublicFingerprint();
        if (stored == null) {
            stored = derivedFingerprint(organizationId, record);
            if (stored != null) {
                connections.backfillCredentialFingerprint(organizationId, record.targetId(), stored);
            }
        }
        return candidate.equals(stored);
    }

    /** The PUBLIC fingerprint of the stored key, derived by opening it; {@code null} when it is not a key at all. */
    private String derivedFingerprint(UUID organizationId, TargetConnectionRecord record) {
        if (resolver == null) {
            return null;
        }
        SecretMaterial material;
        try {
            material = resolver.resolve(organizationId, record.credentialRef(), SecretPurpose.SSH_PRIVATE_KEY);
        } catch (SecretException e) {
            // Fixed text, no cause: a secret-store message is not ours to forward (I6).
            throw new TargetCredentialUnreadableException();
        }
        try (material) {
            char[] stored = material.chars();
            try {
                return keys.publicFingerprint(stored);
            } finally {
                Arrays.fill(stored, '\0');
            }
        } catch (CredentialInvalidException e) {
            return null;
        }
    }

    @Override
    public Map<UUID, TargetEnvelope> liveEnvelopes(UUID organizationId, Collection<UUID> targetIds) {
        Map<UUID, TargetEnvelope> out = new LinkedHashMap<>();
        for (UUID targetId : targetIds) {
            connections.find(organizationId, targetId).filter(c -> c.retiredAt() == null).ifPresent(
                    c -> out.put(targetId, new TargetEnvelope(c.allowedConfigRoot(), c.allowedContainerPrefix())));
        }
        return out;
    }

    /**
     * The record a session reads FRESH when it opens (OR-28): rotation takes effect for the next session, never
     * for one already open, which holds the key it resolved. Internal to the data plane — {@code credentialRef}
     * is deliberately not in any {@code dp-api} view.
     */
    public Optional<TargetConnectionRecord> connectionOf(UUID organizationId, UUID targetId) {
        return connections.find(organizationId, targetId);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private InstanceRecord require(UUID organizationId, UUID instanceId) {
        return instances.find(organizationId, instanceId)
                .orElseThrow(() -> new NoSuchElementException("no such instance"));
    }

    private InstanceView view(InstanceRecord r) {
        List<PlacementRecord> rows = placements.live(r.tenantId(), r.instanceId());
        // one batched read of the targets' transports (plan 015 T3.3): an agent placement is refused the operations that reach a host
        Map<UUID, String> transports = rows.isEmpty() ? Map.of()
                : connections.transportsOf(r.tenantId(), rows.stream().map(PlacementRecord::targetId)
                        .filter(java.util.Objects::nonNull).distinct().toList());
        // a connection-backed placement (plan 014) has no target: it is SSH-reached, so ssh-docker (OR-147 reconciliation)
        List<PlacementView> live = rows.stream()
                .map(p -> new PlacementView(p.placementId(), p.targetId(), p.containerName(), p.healthStrength(),
                        p.targetId() == null ? PlacementView.SSH_DOCKER
                                : transports.getOrDefault(p.targetId(), PlacementView.SSH_DOCKER),
                        p.connectionId()))
                .toList();
        return new InstanceView(r.instanceId(), r.name(), r.environmentId(), r.catalogSolutionId(),
                r.declaredPackageVersion(), r.lifecycle(), r.acquisition(), live, r.stateVersion(), r.role());
    }

    private static PlacementRecord placementOf(UUID tenant, UUID instanceId, DeclareInstanceCommand c, Instant now) {
        return placementOf(tenant, instanceId, c.targetId(), c.placement(), now, c.requestedBy());
    }

    private static PlacementRecord placementOf(UUID tenant, UUID instanceId, UUID targetId, PlacementSpec s,
            Instant now, UUID createdBy) {
        HealthProbeSpec probe = s.probe();
        return new PlacementRecord(tenant, Uuid7.next(), instanceId, targetId, s.containerName(),
                s.configDirectory(), s.containerConfigPath(), s.fileName(), DEFAULT_MECHANISM,
                DEFAULT_RETAINED_GENERATIONS, Optional.ofNullable(probe).map(HealthProbeSpec::kind),
                Optional.ofNullable(probe).map(HealthProbeSpec::path),
                Optional.ofNullable(probe).map(HealthProbeSpec::hostPort),
                Optional.ofNullable(probe).map(HealthProbeSpec::strength), now, createdBy, null);
    }

    /** A placement bound to a remote-server Connection (plan 014): no target, the connection id instead. */
    private static PlacementRecord connectionPlacementOf(UUID tenant, UUID instanceId, UUID connectionId,
            PlacementSpec s, Instant now) {
        PlacementRecord shape = placementOf(tenant, instanceId, null, s, now, null);
        return new PlacementRecord(shape.tenantId(), shape.placementId(), shape.instanceId(), null,
                shape.containerName(), shape.remoteConfigDirectory(), shape.containerConfigPath(), shape.fileName(),
                shape.mechanism(), shape.retainedGenerations(), shape.healthKind(), shape.healthPath(),
                shape.healthPort(), shape.healthStrength(), shape.createdAt(), null, connectionId);
    }

    /** B2: the container must carry the prefix, the directory must be the root or lie beneath it ({@code root + "/"}). */
    private static void requireInsideEnvelope(TargetConnectionRecord target, PlacementSpec spec) {
        requireInsideEnvelope(target.allowedConfigRoot(), target.allowedContainerPrefix(), spec);
    }

    private static void requireInsideEnvelope(String root, String prefix, PlacementSpec spec) {
        if (!spec.containerName().startsWith(prefix)) {
            throw new PlacementOutsideEnvelopeException("containerName");
        }
        String dir = spec.configDirectory();
        boolean inside = dir.equals(root) || dir.startsWith(root + "/");
        if (!inside || dir.contains("..")) {
            throw new PlacementOutsideEnvelopeException("configDirectory");
        }
    }

    /** Target-owned envelope validation for linked targets; SSH credentials remain on the parent Connection. */
    private void requireDeploymentTargetEnvelope(UUID organizationId, UUID targetId, PlacementSpec spec) {
        // The caller has already selected the tenant-scoped target row. The metadata lookup is deliberately repeated
        // here, immediately before the write, so a retired target cannot be admitted through a stale list response.
        var target = connections.findDeploymentTargetRuntime(organizationId, targetId)
                .orElseThrow(() -> new NoSuchElementException("the target has no live runtime metadata"));
        try {
            JsonNode settings = TARGET_SETTINGS.readTree(target.settingsJson());
            String root = textSetting(settings, "configRoot");
            String directory = spec.configDirectory();
            if (!(directory.equals(root) || directory.startsWith(root + "/")) || directory.contains("..")) {
                throw new PlacementOutsideEnvelopeException("configDirectory");
            }
            if ("docker".equals(target.runtime())) {
                String prefix = textSetting(settings, "containerPrefix");
                if (!spec.containerName().startsWith(prefix)) {
                    throw new PlacementOutsideEnvelopeException("containerName");
                }
            } else if ("systemd".equals(target.runtime())) {
                String pattern = textSetting(settings, "serviceNamePattern");
                boolean matches = pattern.endsWith("-")
                        ? spec.containerName().startsWith(pattern)
                        : Pattern.matches(pattern, spec.containerName());
                if (!matches) {
                    throw new PlacementOutsideEnvelopeException("containerName");
                }
            } else {
                throw new NoSuchElementException("the target runtime is unsupported");
            }
        } catch (PlacementOutsideEnvelopeException e) {
            throw e;
        } catch (Exception e) {
            throw new NoSuchElementException("the target runtime metadata is invalid");
        }
    }

    private static String textSetting(JsonNode settings, String key) {
        JsonNode value = settings == null ? null : settings.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("missing target setting");
        }
        return value.asText();
    }

    private static boolean sameRecord(TargetConnectionRecord r, TargetConnectionSpec s) {
        return r.host().equals(s.host()) && r.port() == s.port() && r.sshUser().equals(s.sshUser())
                && r.hostKeyFingerprint().equals(s.hostKeyFingerprint())
                && r.allowedConfigRoot().equals(s.allowedConfigRoot())
                && r.allowedContainerPrefix().equals(s.allowedContainerPrefix());
    }
}
