package com.solace.sm.dp.agent;

import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentRegistrationCompletion;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.api.inventory.PlacementSpec;
import com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException;
import com.solace.sm.dp.apply.model.Uuid7;
import com.solace.sm.dp.apply.persistence.PgConstraintViolation;
import com.solace.sm.dp.apply.persistence.agent.AgentAuthority;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentDiscoveryRepository;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentDiscoveryRepository.Enrolment;
import com.solace.sm.dp.apply.persistence.agent.JdbcAgentDiscoveryRepository.Row;
import com.solace.sm.dp.inventory.DiscoveredReservation;
import com.solace.sm.dp.inventory.InventoryService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data plane's side of {@code OR-27} (plan 015 T4.1): an enrolled agent reports the containers it found, and each labelled
 * one becomes a PENDING instance and placement in the environment and solution the agent was ENROLLED into, on the agent's own
 * target. The agent reports <b>facts, not conclusions</b> ({@code AGW-D30}); every column of the placement is derived here from
 * those facts and from the data plane's enrolment record, by the table in the plan. Nothing in a report names a tenant,
 * environment, target or instance, and nothing is resolved from {@code ReportItem.bindingId} or a label (I5).
 *
 * <p><b>One transaction per item</b>, opened by {@link AgentAuthority#underSession} (the session's and identity's rows held
 * {@code FOR SHARE} to its end, so a revocation or a newer session cannot land between the fence and the write). In it, in order:
 * the tombstone row of the item's key (terminal states answer and mint nothing), the refusals that need no write (envelope,
 * incomplete facts, cap, name and index collisions - each a coded refusal and an {@code agent_event}, never a raw {@code 23505}),
 * then the declare phase ({@link InventoryService#reserveDiscovered}, the separate entry beside the operator's refused one) and the
 * PENDING tombstone. A refused key is written as a {@code REFUSED} tombstone so that the next {@code Connect} does not retry it,
 * and is released only by a re-created container ({@code V031}); an UNDECLARED or RETIRED key only by a changed label.
 *
 * <p><b>After the commit</b> the completion sink ({@link AgentRegistrationCompletion}, T4.2) is called for every item that is
 * PENDING, with the tenant resolved from the identity and this RPC's origin; the PENDING row, not that call, is the contract.
 * A report is idempotent: the same item again finds its row.
 *
 * <p>The host port of the probe and of each metrics source is the port Docker PUBLISHED for the container port the package
 * declares (the agent's facts), else, for the probe, the label {@code com.solace.sm.health-port}. The agent runs the probe itself
 * ({@code OR-130} D-4); the DP's poller skips agent placements.
 */
public final class AgentDiscoveryService implements AgentDiscoveryPort {

    private static final Logger LOG = LoggerFactory.getLogger(AgentDiscoveryService.class);

    static final String LABEL_INSTANCE = "com.solace.sm.instance";
    static final String LABEL_CONFIG_FILE = "com.solace.sm.config-file";
    static final String LABEL_HEALTH_PORT = "com.solace.sm.health-port";

    /** The placement and tombstone grammars (V007, V028), restated so a value the database would refuse is a coded refusal. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("/([A-Za-z0-9._-]+/)*[A-Za-z0-9._-]+");
    private static final Pattern CONTAINER_ID = Pattern.compile("[0-9a-f]{12,64}");

    static final String EVENT_REGISTERED = "agent.discovery.registered";
    static final String EVENT_REFUSED = "agent.discovery.refused";

    private final AgentAuthority authority;
    private final JdbcAgentDiscoveryRepository tombstones;
    private final InventoryService inventory;
    private final AgentAuditPort audits;
    private final Supplier<PackageDeclarationSource> packages;
    private final Supplier<AgentRegistrationCompletion> completion;
    private final Clock clock;
    private final int maxPlacementsPerTarget;
    private final java.time.Duration abandonAfter;
    private final int maxItemsPerReport;

    /**
     * @param packages   the gateway's package reader, or a supplier of {@code null} (a context with no console gateway): an item that
     *                   needs the package is then refused {@code agent.discovery.unavailable}, audited, with NO row written, so the
     *                   agent's next report retries it (a missing reader is a wiring fault, not a verdict on the container)
     * @param completion the T4.2 sink, or a supplier of {@code null}: items then stay PENDING until a completer exists
     * @param maxPlacementsPerTarget {@code sm.dp.agent.max-placements-per-target} ({@code OR-128} (11)), default 16
     * @param abandonAfter {@code sm.dp.declare.abandon-after}: a PENDING row older than this is never handed to the sink (the sweep
     *                   abandons and tombstones it; completing it now could orphan a control-plane node that holds a quota slot)
     * @param maxItemsPerReport {@code sm.dp.agent.max-discovery-items-per-report}, default 256: items past it are answered
     *                   {@code agent.placement.cap} without being looked at
     */
    public AgentDiscoveryService(AgentAuthority authority, JdbcAgentDiscoveryRepository tombstones, InventoryService inventory,
            AgentAuditPort audits, Supplier<PackageDeclarationSource> packages, Supplier<AgentRegistrationCompletion> completion,
            Clock clock, int maxPlacementsPerTarget, java.time.Duration abandonAfter, int maxItemsPerReport) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.tombstones = Objects.requireNonNull(tombstones, "tombstones");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.audits = Objects.requireNonNull(audits, "audits");
        this.packages = Objects.requireNonNull(packages, "packages");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPlacementsPerTarget < 1) {
            throw new IllegalArgumentException("sm.dp.agent.max-placements-per-target must be at least 1");
        }
        this.maxPlacementsPerTarget = maxPlacementsPerTarget;
        if (abandonAfter.isZero() || abandonAfter.isNegative() || maxItemsPerReport < 1) {
            throw new IllegalArgumentException("abandonAfter and maxItemsPerReport must be positive");
        }
        this.abandonAfter = abandonAfter;
        this.maxItemsPerReport = maxItemsPerReport;
    }

    @Override
    public List<DiscoveryResult> report(AgentSessionRef ref, String requestId, List<DiscoveredContainer> items,
            AgentRequestOrigin origin) {
        authority.requireSession(ref);                      // fenced first, as the stub was: a stale session writes nothing
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(origin, "origin");
        List<DiscoveryResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            if (i >= maxItemsPerReport) {
                // a bound on what one report makes the DP do: the rest are not looked at, written or audited
                results.add(new DiscoveryResult(items.get(i).containerId(), DiscoveryResult.State.REFUSED,
                        GatewayCode.AGENT_PLACEMENT_CAP, null));
                continue;
            }
            results.add(one(ref, requestId + "/" + i, items.get(i), origin));
        }
        return List.copyOf(results);
    }

    // ---- one item -------------------------------------------------------------------------------------------------

    /** What the item's transaction decided; {@code pending} is set when the completion sink is to be called after the commit. */
    private record Decision(DiscoveryResult result, AgentRegistrationCompletion.Pending pending, String label) {
    }

    private DiscoveryResult one(AgentSessionRef ref, String eventId, DiscoveredContainer item, AgentRequestOrigin origin) {
        Decision decision;
        try {
            decision = authority.underSession(ref, standing -> decide(ref, standing, eventId, item, origin));
        } catch (PgConstraintViolation | MetricsPortCollisionException | PackageVersionMismatchException conflict) {
            // The statement that failed aborted its transaction: the refusal is written in a fresh one. A concurrent report of the
            // same key may have won; then its row is the answer.
            decision = authority.underSession(ref, standing -> afterConflict(ref, standing, eventId, item, origin, conflict));
        }
        if (decision.pending() == null) {
            return decision.result();
        }
        AgentRegistrationCompletion sink = completion.get();
        if (sink == null) {
            return decision.result();
        }
        try {
            sink.complete(decision.pending(), new Settlement(ref, eventId, origin, decision.pending()));
        } catch (RuntimeException failure) {
            // The PENDING row is the contract: the next report (or the sweep) finishes it. Only the class is logged (I6).
            LOG.warn("agent registration completion did not finish ({}); the instance stays pending", failure.getClass().getName());
        }
        UUID instanceId = decision.pending().instanceId();
        return authority.underSession(ref, standing -> tombstones.byInstance(standing.tenantId(), standing.agentId(), instanceId))
                .map(row -> answer(item.containerId(), row)).orElse(decision.result());
    }

    private Decision decide(AgentSessionRef ref, AgentAuthority.Standing standing, String eventId, DiscoveredContainer item,
            AgentRequestOrigin origin) {
        UUID tenant = standing.tenantId();
        UUID agent = standing.agentId();
        String label = item.labels().get(LABEL_INSTANCE);
        if (label == null || !NAME.matcher(label).matches() || !NAME.matcher(item.containerName()).matches()
                || !CONTAINER_ID.matcher(item.containerId()).matches()) {
            // No usable key: nothing to tombstone (the key's own columns are CHECKed), but the refusal is still audited.
            return refusedNoRow(ref, standing, item, origin, GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        Optional<Row> existing = tombstones.find(tenant, agent, label, item.containerName());
        if (existing.isPresent()) {
            Row row = existing.get();
            switch (row.state()) {
                case "REGISTERED" -> {
                    tombstones.recordContainerId(tenant, agent, label, item.containerName(), item.containerId());
                    return new Decision(answer(item.containerId(), row), null, label);
                }
                case "PENDING" -> {
                    tombstones.recordContainerId(tenant, agent, label, item.containerName(), item.containerId());
                    if (row.firstSeenAt() != null && row.firstSeenAt().plus(abandonAfter).isBefore(clock.instant())) {
                        // past the BE-D32 window: the sweep's to settle, never the completer's (a node written now could be orphaned)
                        return new Decision(answer(item.containerId(), row), null, label);
                    }
                    Enrolment enrolment = enrolment(tenant, agent);
                    return new Decision(answer(item.containerId(), row), pending(tenant, agent, enrolment, row.instanceId(), origin),
                            label);
                }
                case "REFUSED", "ABANDONED" -> {
                    if (item.containerId().equals(row.containerId())) {
                        return new Decision(answer(item.containerId(), row), null, label);      // the tombstone answers
                    }
                    // the container under the key was re-created: the one release an operator can cause
                }
                default -> {
                    return new Decision(answer(item.containerId(), row), null, label);          // UNDECLARED, RETIRED: final
                }
            }
        }
        return mint(ref, standing, eventId, item, origin, label, existing.orElse(null));
    }

    private Decision mint(AgentSessionRef ref, AgentAuthority.Standing standing, String eventId, DiscoveredContainer item,
            AgentRequestOrigin origin, String label, Row released) {
        UUID tenant = standing.tenantId();
        UUID agent = standing.agentId();
        Enrolment enrolment = enrolment(tenant, agent);
        Derived derived = derive(tenant, enrolment, item, label);
        if (derived.refusal() == GatewayCode.AGENT_DISCOVERY_UNAVAILABLE) {
            return refusedNoRow(ref, standing, item, origin, derived.refusal());      // retryable: no tombstone
        }
        if (derived.refusal() != null) {
            return refusedKey(ref, tenant, agent, eventId, item, origin, label, released, derived.refusal());
        }
        UUID instanceId = Uuid7.next();
        UUID placementId = Uuid7.next();
        inventory.reserveDiscovered(tenant, new DiscoveredReservation(instanceId, placementId, enrolment.environmentId(),
                enrolment.catalogSolutionId(), enrolment.solution(), enrolment.packageVersion(), label, enrolment.targetId(),
                derived.placement(), enrolment.issuedBy(), agent, derived.metrics()));
        if (released == null) {
            tombstones.insertPending(tenant, agent, label, item.containerName(), item.containerId(), instanceId, placementId,
                    clock.instant());
        } else if (!tombstones.release(tenant, agent, label, item.containerName(), item.containerId(), instanceId, placementId,
                clock.instant())) {
            throw new IllegalStateException("the tombstone could not be released");
        }
        return new Decision(new DiscoveryResult(item.containerId(), DiscoveryResult.State.PENDING, null, null),
                pending(tenant, agent, enrolment, instanceId, origin), label);
    }

    private Enrolment enrolment(UUID tenant, UUID agent) {
        return tombstones.enrolment(tenant, agent).orElseThrow(
                () -> new NoSuchElementException("the agent has no enrolment record or its target is retired"));
    }

    private static AgentRegistrationCompletion.Pending pending(UUID tenant, UUID agent, Enrolment enrolment, UUID instanceId,
            AgentRequestOrigin origin) {
        return new AgentRegistrationCompletion.Pending(tenant, agent, enrolment.environmentId(), enrolment.catalogSolutionId(),
                instanceId, origin);
    }

    // ---- the placement's columns, from the facts and the enrolment ------------------------------------------------------

    /** Either the placement and metrics to reserve, or the coded refusal. */
    private record Derived(PlacementSpec placement, MetricsDeclaration metrics, GatewayCode refusal) {
        static Derived refused(GatewayCode code) {
            return new Derived(null, null, code);
        }
    }

    private Derived derive(UUID tenant, Enrolment e, DiscoveredContainer item, String label) {
        // container_name must start with the target's allowed_container_prefix (before V007's trigger is reached)
        if (!item.containerName().startsWith(e.allowedContainerPrefix())) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE);
        }
        // container_config_path and file_name: dirname and basename of label com.solace.sm.config-file (an absolute in-container path)
        String configFile = item.labels().get(LABEL_CONFIG_FILE);
        if (configFile == null || !ABSOLUTE_PATH.matcher(configFile).matches() || configFile.contains("..")) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        int slash = configFile.lastIndexOf('/');
        String containerConfigPath = configFile.substring(0, slash);
        String fileName = configFile.substring(slash + 1);
        if (!ABSOLUTE_PATH.matcher(containerConfigPath).matches() || !NAME.matcher(fileName).matches()) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        // remote_config_directory: the source of the bind mount whose destination is container_config_path, inside allowed_config_root
        Optional<DiscoveredContainer.Mount> mount = item.mounts().stream()
                .filter(m -> containerConfigPath.equals(m.destination())).findFirst();
        if (mount.isEmpty()) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        String source = mount.get().source();
        if (source == null || !ABSOLUTE_PATH.matcher(source).matches() || source.contains("..")) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        String root = e.allowedConfigRoot();
        if (!source.equals(root) && !source.startsWith(root + "/")) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE);
        }
        // the package: probe and metrics, from the gateway (the DP reads no package); the enrolment's version, never the agent's
        PackageDeclarationSource source1 = packages.get();
        if (source1 == null) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_UNAVAILABLE);
        }
        Optional<PackageDeclarationSource.PackageDeclaration> declared =
                source1.resolve(tenant, e.catalogSolutionId(), e.packageVersion());
        if (declared.isEmpty()) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
        }
        Map<Integer, Integer> published = new LinkedHashMap<>();
        item.publishedPorts().forEach(p -> published.putIfAbsent(p.containerPort(), p.hostPort()));
        HealthProbeSpec probe = null;
        if (declared.get().probe().isPresent()) {
            PackageDeclarationSource.Probe wanted = declared.get().probe().get();
            Integer hostPort = wanted.containerPort() == null ? null : published.get(wanted.containerPort());
            if (hostPort == null) {
                hostPort = healthPortLabel(item.labels().get(LABEL_HEALTH_PORT));
            }
            if (hostPort == null) {
                return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);
            }
            probe = new HealthProbeSpec(wanted.kind(), wanted.path(), hostPort, wanted.strength());
        }
        MetricsDeclaration metrics = declared.get().metrics();
        for (MetricDefinitionSpec definition : metrics.definitions()) {
            if (definition.unit() == null || definition.unit().isBlank()) {
                return Derived.refused(GatewayCode.AGENT_DISCOVERY_INCOMPLETE);   // T3.2 note (2): refused at the source, not at the agent
            }
        }
        metrics = remapped(metrics, published);

        // the refusals that read the registry: the cap, then each unique index a placement can trip, then the instance name
        if (tombstones.livePlacements(tenant, e.targetId()) >= maxPlacementsPerTarget) {
            return Derived.refused(GatewayCode.AGENT_PLACEMENT_CAP);
        }
        if (tombstones.containerNameLive(tenant, e.targetId(), item.containerName())) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_CONTAINER_TAKEN);
        }
        if (tombstones.configDirectoryLive(tenant, e.targetId(), source)) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_CONFIG_TAKEN);
        }
        if (probe != null && tombstones.healthPortLive(tenant, e.targetId(), probe.hostPort())) {
            return Derived.refused(GatewayCode.AGENT_DISCOVERY_PORT_TAKEN);
        }
        if (tombstones.instanceNameLive(tenant, e.environmentId(), label)) {
            return Derived.refused(GatewayCode.INSTANCE_NAME_TAKEN);
        }
        return new Derived(new PlacementSpec(item.containerName(), source, containerConfigPath, fileName, probe), metrics, null);
    }

    private static Integer healthPortLabel(String label) {
        if (label == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(label);
            return port >= 1 && port <= 65_535 ? port : null;
        } catch (NumberFormatException notAPort) {
            return null;
        }
    }

    /** Each port the package calls is reached on the host port Docker published for it, else on itself (the declare default). */
    private static MetricsDeclaration remapped(MetricsDeclaration declaration, Map<Integer, Integer> published) {
        Map<Integer, Integer> hostPorts = new TreeMap<>();
        declaration.metricsPorts().forEach((source, host) -> hostPorts.put(source, published.getOrDefault(source, host)));
        return new MetricsDeclaration(declaration.packageDigest(), declaration.definitions(), declaration.restartSignals(),
                hostPorts, declaration.activeMetricNames());
    }

    // ---- refusals -------------------------------------------------------------------------------------------------------

    /**
     * A refusal that writes no row (no usable key, or a retryable condition): audited under a request id derived from the item's
     * FACTS, not the report's message id, so the same malformed container is one event for its agent however often it is reported.
     */
    private Decision refusedNoRow(AgentSessionRef ref, AgentAuthority.Standing standing, DiscoveredContainer item,
            AgentRequestOrigin origin, GatewayCode code) {
        String facts = standing.agentId() + "|" + item.containerId() + "|" + item.containerName() + "|"
                + item.labels().get(LABEL_INSTANCE) + "|" + code.wire();
        String eventId;
        try {
            eventId = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(facts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        audits.record(eventId, ref, origin, EVENT_REFUSED, code, null, clock.instant());
        return new Decision(new DiscoveryResult(item.containerId(), DiscoveryResult.State.REFUSED, code, null), null, null);
    }

    /** A refusal of a well-formed key: a tombstone (or the same one, again) and an {@code agent_event}. */
    private Decision refusedKey(AgentSessionRef ref, UUID tenant, UUID agent, String eventId, DiscoveredContainer item,
            AgentRequestOrigin origin, String label, Row released, GatewayCode code) {
        if (released == null) {
            tombstones.insertRefused(tenant, agent, label, item.containerName(), item.containerId(), code.wire(), clock.instant());
        } else {
            tombstones.refuseAgain(tenant, agent, label, item.containerName(), item.containerId(), code.wire(), clock.instant());
        }
        audits.record(eventId, ref, origin, EVENT_REFUSED, code, null, clock.instant());
        return new Decision(new DiscoveryResult(item.containerId(), DiscoveryResult.State.REFUSED, code, null), null, label);
    }

    /** A statement of the item's mint failed: another report won the key, or a unique index the pre-checks raced. */
    private Decision afterConflict(AgentSessionRef ref, AgentAuthority.Standing standing, String eventId, DiscoveredContainer item,
            AgentRequestOrigin origin, RuntimeException conflict) {
        String label = item.labels().get(LABEL_INSTANCE);
        Optional<Row> row = tombstones.find(standing.tenantId(), standing.agentId(), label, item.containerName());
        boolean stillReleasable = row.isEmpty() || (("REFUSED".equals(row.get().state()) || "ABANDONED".equals(row.get().state()))
                && !item.containerId().equals(row.get().containerId()));
        if (!stillReleasable) {
            // a concurrent report of this key committed first: its row is the answer (and, if PENDING, the sink is called)
            Row won = row.get();
            AgentRegistrationCompletion.Pending pending = "PENDING".equals(won.state())
                    ? pending(standing.tenantId(), standing.agentId(), enrolment(standing.tenantId(), standing.agentId()),
                            won.instanceId(), origin)
                    : null;
            return new Decision(answer(item.containerId(), won), pending, label);
        }
        GatewayCode code = codeOf(conflict);
        if (code == null) {
            throw conflict;
        }
        return refusedKey(ref, standing.tenantId(), standing.agentId(), eventId, item, origin, label, row.orElse(null), code);
    }

    private static GatewayCode codeOf(RuntimeException conflict) {
        if (conflict instanceof MetricsPortCollisionException) {
            return GatewayCode.AGENT_DISCOVERY_PORT_TAKEN;
        }
        if (conflict instanceof PackageVersionMismatchException) {
            return GatewayCode.AGENT_DISCOVERY_INCOMPLETE;
        }
        if (conflict instanceof PgConstraintViolation violation && violation.constraint() != null) {
            return switch (violation.constraint()) {
                case "placement_container_once" -> GatewayCode.AGENT_DISCOVERY_CONTAINER_TAKEN;
                case "placement_config_dir_once" -> GatewayCode.AGENT_DISCOVERY_CONFIG_TAKEN;
                case "placement_health_port_once", "metrics_host_port_in_use", "metrics_host_port_is_a_health_port",
                        "health_port_is_a_metrics_port" -> GatewayCode.AGENT_DISCOVERY_PORT_TAKEN;
                case "instance_name_once" -> GatewayCode.INSTANCE_NAME_TAKEN;
                default -> null;
            };
        }
        return null;
    }

    // ---- the answer -------------------------------------------------------------------------------------------------------

    private static DiscoveryResult answer(String containerId, Row row) {
        DiscoveryResult.State state = DiscoveryResult.State.valueOf(row.state());
        GatewayCode code = state == DiscoveryResult.State.REFUSED ? codeOf(row.refusalCode()) : null;
        UUID placement = switch (state) {
            case REGISTERED, UNDECLARED, RETIRED -> row.placementId();
            default -> null;
        };
        return new DiscoveryResult(containerId, state, code, placement);
    }

    private static GatewayCode codeOf(String wire) {
        for (GatewayCode code : GatewayCode.values()) {
            if (code.wire().equals(wire)) {
                return code;
            }
        }
        return GatewayCode.AGENT_DISCOVERY_UNAVAILABLE;
    }

    // ---- the completer's way back into the data plane -----------------------------------------------------------------------

    /** Binds the completion to its one pending row; each method is its own transaction, fenced like any agent write. */
    private final class Settlement implements AgentRegistrationCompletion.Settlement {

        private final AgentSessionRef ref;
        private final String eventId;
        private final AgentRequestOrigin origin;
        private final AgentRegistrationCompletion.Pending pending;

        Settlement(AgentSessionRef ref, String eventId, AgentRequestOrigin origin, AgentRegistrationCompletion.Pending pending) {
            this.ref = ref;
            this.eventId = eventId;
            this.origin = origin;
            this.pending = pending;
        }

        /**
         * Lock order: the INSTANCE row first (the sweep's and undeclare's first lock), then the discovery row; branches on the lifecycle
         * read under that lock instead of catching a failed step, which inside this joined transaction would mark it rollback-only.
         */
        @Override
        public void registered() {
            authority.underSession(ref, standing -> {
                Optional<String> lifecycle = tombstones.lockInstance(standing.tenantId(), pending.instanceId());
                if (lifecycle.isEmpty() || "abandoned".equals(lifecycle.get()) || "retiring".equals(lifecycle.get())
                        || "retired".equals(lifecycle.get())) {
                    return null;                    // the sweep (or an undeclare) settled it first: its tombstone is the answer
                }
                if ("pending".equals(lifecycle.get())) {
                    inventory.activate(standing.tenantId(), pending.instanceId());
                }
                if (tombstones.registered(standing.tenantId(), standing.agentId(), pending.instanceId(), clock.instant())) {
                    audits.record(eventId, ref, origin, EVENT_REGISTERED, null, null, clock.instant());
                }
                return null;
            });
        }

        /** Same lock order. A PENDING instance is abandoned with the REFUSED tombstone written first (the trigger then finds no PENDING row). */
        @Override
        public void refused(GatewayCode code) {
            Objects.requireNonNull(code, "code");
            authority.underSession(ref, standing -> {
                Optional<String> lifecycle = tombstones.lockInstance(standing.tenantId(), pending.instanceId());
                if (lifecycle.isEmpty() || !("pending".equals(lifecycle.get()) || "active".equals(lifecycle.get()))) {
                    return null;                    // already abandoned, retiring or retired: that tombstone stands
                }
                if (tombstones.refused(standing.tenantId(), standing.agentId(), pending.instanceId(), code.wire(), clock.instant())) {
                    if ("pending".equals(lifecycle.get())) {
                        inventory.abandon(standing.tenantId(), pending.instanceId());     // retires its placement: frees the name and the slot
                    } else {
                        LOG.warn("a refused agent registration was already active; it stays active");
                    }
                    audits.record(eventId, ref, origin, EVENT_REFUSED, code, null, clock.instant());
                }
                return null;
            });
        }
    }
}
