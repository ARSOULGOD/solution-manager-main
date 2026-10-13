package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.agent.FakeAgentCommandPayloadSource;
import com.solace.sm.dp.agent.AgentDiscoveryService;
import com.solace.sm.dp.agent.AgentObservedState;
import com.solace.sm.dp.agent.AgentReportIntake;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.apply.persistence.JdbcLadderRepository;
import com.solace.sm.dp.apply.persistence.JdbcPlacementHealthRepository;
import com.solace.sm.dp.apply.persistence.JdbcStartObservationRepository;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.health.HealthRecorder;
import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentRegistrationState;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.EnrollmentEnvelope;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentIssuance;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import com.solace.sm.dp.apply.persistence.AgentItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The PostgreSQL 14 world of the agent-gateway port contract kits (plan 015 T2.1b): every port is the REAL repository, over the
 * RUNTIME role (so the grants are part of what the kits prove), in a database of its own shared by the agent ITs. Only what
 * lives outside the data plane is fake: the control plane's registration, the lease and enrolment issuers, the payload source,
 * and the clock. Observations read through the OWNER, so "nothing was written" is not the port's own claim.
 */
final class AgentJdbcWorld implements AgentKitWorld {

    private static final String[] AGENT_TABLES = {"agent_identity", "agent_session", "agent_lease", "agent_command",
        "agent_report_watermark", "agent_telemetry_watermark", "agent_discovery", "agent_event"};

    private static AgentItSupport.AgentDb db;

    static synchronized AgentItSupport.AgentDb db() {
        if (db == null) {
            AgentItSupport.bootstrap();
            db = AgentItSupport.freshDatabase("agent_ports");
        }
        return db;
    }

    private final NamedParameterJdbcTemplate owner = db().owner();
    private final NamedParameterJdbcTemplate runtime = db().runtime();
    private final TransactionTemplate runtimeTx;
    private final MutableClock clock = new MutableClock();

    // ---- the fakes outside the data plane ----------------------------------------------------------------------------
    private final Map<UUID, AgentRegistrationState> registrations = new ConcurrentHashMap<>();
    private volatile boolean controlPlaneDown;
    private final AtomicInteger controlPlaneReads = new AtomicInteger();
    private volatile AgentIdentity lastIssuedFor;
    private volatile UUID extraPlacement;
    private volatile java.util.function.UnaryOperator<LeaseGrant> corruption;
    private final FakeAgentCommandPayloadSource payloads = new FakeAgentCommandPayloadSource();
    private final Map<String, Journal> journal = new HashMap<>();
    private final Map<UUID, UUID> instanceOfPlacement = new HashMap<>();
    /** One apply request per placement: a second REQUESTED one on the same scope is refused, and a request holds many intents. */
    private final Map<UUID, UUID> applyOfPlacement = new HashMap<>();
    private final AtomicInteger replicas = new AtomicInteger();
    private final Map<UUID, Programmed> enrollments = new ConcurrentHashMap<>();

    private record Journal(UUID apply, UUID intent) {
    }

    private record Programmed(Enrollment enrollment, EnrollmentIssuance issuance, boolean[] refuses) {
    }

    // ---- the ports under test -----------------------------------------------------------------------------------------
    private final AgentAuthority authority;
    private final JdbcAgentIdentityRepository identities;
    private final JdbcAgentSessionRepository sessions;
    private final JdbcAgentLeaseRepository leases;
    private final JdbcAgentCommandRepository commands;
    private final JdbcAgentReportRepository reportRepository;
    private final AgentReportIntake intake;
    private final AgentObservedState observedState;
    private final com.solace.sm.dp.agent.AgentHealthStaleness staleness;
    /** Every event the data plane would publish (health transitions, config-state): after-commit is not modelled here. */
    private final List<com.solace.sm.events.EventEnvelope> published = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final JdbcAgentAuditRepository audits;
    private final JdbcAgentTelemetryWatermarkRepository telemetryRepository;
    private final JdbcAgentEnrollmentRepository enrollmentRepository;
    private final AgentDiscoveryService discovery;
    private final JdbcAgentDiscoveryRepository discoveryRepository;
    private final com.solace.sm.dp.inventory.InventoryService inventory;
    /** What the gateway would answer about the enrolled package; the kit's default is a package that declares a probe and no metrics. */
    private volatile com.solace.sm.dp.api.inventory.PackageDeclarationSource packages = (tenant, solution, version) ->
            java.util.Optional.of(new com.solace.sm.dp.api.inventory.PackageDeclarationSource.PackageDeclaration(
                    java.util.Optional.empty(), com.solace.sm.dp.api.metrics.declare.MetricsDeclaration.none()));
    private volatile com.solace.sm.dp.api.agw.AgentRegistrationCompletion completion;
    private final ApplyEventEmitter emitter;
    private final LadderRecorder ladderRecorder;
    private final com.solace.sm.dp.agent.AgentTelemetryDeclarationsService declarations;

    AgentJdbcWorld() {
        DataSource runtimeDs = runtime.getJdbcTemplate().getDataSource();
        runtimeTx = new TransactionTemplate(new DataSourceTransactionManager(runtimeDs));
        authority = new AgentAuthority(runtime, clock, runtimeTx);
        identities = new JdbcAgentIdentityRepository(runtime, runtimeTx, clock, authority, () -> ids -> {
            controlPlaneReads.incrementAndGet();
            if (controlPlaneDown) {
                throw new IllegalStateException("control plane unreachable");
            }
            Map<UUID, AgentRegistrationState> found = new HashMap<>();
            ids.forEach(id -> {
                AgentRegistrationState state = registrations.get(id);
                if (state != null) {
                    found.put(id, state);
                }
            });
            return found;
        });
        sessions = new JdbcAgentSessionRepository(runtime, clock, authority, identities);
        leases = new JdbcAgentLeaseRepository(runtime, runtimeTx, clock, authority, identities, () -> issuer());
        commands = new JdbcAgentCommandRepository(runtime, runtimeTx, clock, authority, payloads);
        reportRepository = new JdbcAgentReportRepository(runtime, runtimeTx, clock, authority);
        audits = new JdbcAgentAuditRepository(runtime, authority);
        emitter = new ApplyEventEmitter(published::add, clock, UUID::randomUUID);
        ladderRecorder = new LadderRecorder(new JdbcLadderRepository(runtime));
        JdbcPlacementHealthRepository healthRows = new JdbcPlacementHealthRepository(runtime);
        observedState = new AgentObservedState(reportRepository);
        // T3.3: the kit runs against the REAL intake bean over the runtime role, not a test-scope lambda over the helper
        HealthRecorder healthRecorder = new HealthRecorder(runtimeTx, healthRows, emitter, clock);
        staleness = new com.solace.sm.dp.agent.AgentHealthStaleness(reportRepository, healthRecorder, clock,
                java.time.Duration.ofMinutes(5));
        intake = new AgentReportIntake(reportRepository, audits,
                healthRecorder, healthRows,
                new JdbcStartObservationRepository(runtime), ladderRecorder, emitter,
                observedState, runtimeTx, clock);
        telemetryRepository = new JdbcAgentTelemetryWatermarkRepository(runtime, runtimeTx, clock, authority);
        enrollmentRepository = new JdbcAgentEnrollmentRepository(runtime, runtimeTx, clock, () -> request -> {
            Programmed p = enrollments.values().stream().filter(x -> x.enrollment().request().token().equals(request.token()))
                    .findFirst().orElseThrow(() -> new GatewayPortException(GatewayCode.TOKEN_CONSUMED, "unknown token"));
            if (p.refuses()[0]) {
                throw new GatewayPortException(GatewayCode.TOKEN_CONSUMED, "enrollment token was already used");
            }
            return p.issuance();      // the control plane replays the same token and CSR to the same answer
        });
        discoveryRepository = new JdbcAgentDiscoveryRepository(runtime);
        inventory = AgentItSupport.inventory(runtime, runtimeTx, clock, emitter);   // W4 integration: activation signals the sink
        discovery = discoveryWith(16);
        // W4 lane D: the REAL declarations service, with the signal wired as the launcher wires it (after-commit, off the emitter)
        declarations = new com.solace.sm.dp.agent.AgentTelemetryDeclarationsService(telemetryRepository,
                new com.solace.sm.dp.apply.persistence.JdbcIngestContextRepository(runtime),
                new com.solace.sm.dp.apply.persistence.MetricDefinitionCollectionRows(
                        new com.solace.sm.dp.apply.persistence.JdbcMetricDefinitionRepository(runtime)),
                (tenant, instance) -> OptionalLong.empty(), new com.solace.sm.dp.apply.persistence.JdbcPlacementRepository(runtime),
                new com.solace.sm.dp.meshbridge.SpringTransactionBoundary());
        emitter.declarationsSink(declarations);
    }

    /** Deterministic in its inputs, as the real issuer must be: identical inputs return an identical nonce (finding 2). */
    private LeaseIssuer issuer() {
        return (identity, session, agentClock, requestId, consumedNonce, placementIds, origin) -> {
            lastIssuedFor = identity;
            List<UUID> placements = new ArrayList<>(placementIds);
            if (extraPlacement != null) {
                placements.add(extraPlacement);
            }
            Instant now = clock.instant();
            Map<AgentOperation, Duration> ceilings = new EnumMap<>(AgentOperation.class);
            for (AgentOperation op : AgentOperation.values()) {
                ceilings.put(op, Duration.ofSeconds(60));
            }
            // The real signer (Pkcs12LeaseNonceSigner) is an HMAC over exactly these inputs: NOT the placement set or the epochs, so
            // a permission change between a lost reply and its retry still yields the nonce the stored row holds.
            String inputs = identity.agentId() + "|" + requestId + "|" + identity.certificateThumbprint() + "|" + consumedNonce;
            String nonce = "nonce-" + java.util.HexFormat.of().formatHex(sha256(inputs));
            LeaseGrant grant = new LeaseGrant(identity.agentId(), identity.certificateThumbprint(), identity.targetId(), placements,
                    identity.agentRevocationEpoch(), identity.fleetRevocationEpoch(), now, now.plus(Duration.ofMinutes(15)),
                    "dp", nonce, identity.capabilities(), ceilings, Duration.ofSeconds(30), now);
            return corruption == null ? grant : corruption.apply(grant);
        };
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // ---- ports -------------------------------------------------------------------------------------------------------

    @Override
    public AgentSessionPort sessions() {
        return sessions;
    }

    @Override
    public AgentLeasePort leases() {
        return leases;
    }

    @Override
    public AgentCommandPort commands() {
        return commands;
    }

    @Override
    public ActualStatePort reports() {
        return intake;
    }

    JdbcAgentReportRepository reportRepository() {
        return reportRepository;
    }

    com.solace.sm.dp.agent.AgentHealthStaleness staleness() {
        return staleness;
    }

    /** OR-140: the scheduled verdict over the same repository, recorder and clock; the test drives a pass with no thread. */
    com.solace.sm.dp.agent.AgentStalenessJob stalenessJob() {
        return new com.solace.sm.dp.agent.AgentStalenessJob(reportRepository, staleness, true, java.time.Duration.ofSeconds(30));
    }

    TransactionTemplate transactions() {
        return runtimeTx;
    }

    AgentObservedState observedState() {
        return observedState;
    }

    List<com.solace.sm.events.EventEnvelope> published() {
        return published;
    }

    @Override
    public AgentAuditPort audits() {
        return audits;
    }

    /** One real ingest service over the shared database (its writer thread is a daemon), as the runtime role. */
    private static com.solace.sm.dp.metrics.ingest.IngestService ingestService;

    private static synchronized com.solace.sm.dp.metrics.ingest.IngestService ingestService() {
        if (ingestService == null) {
            NamedParameterJdbcTemplate rt = db().runtime();
            ingestService = new com.solace.sm.dp.metrics.ingest.IngestService(
                    new TransactionTemplate(new DataSourceTransactionManager(rt.getJdbcTemplate().getDataSource())),
                    new com.solace.sm.dp.apply.persistence.JdbcIngestContextRepository(rt),
                    new com.solace.sm.dp.apply.persistence.JdbcSeriesRepository(rt),
                    new com.solace.sm.dp.apply.persistence.JdbcRawSampleRepository(rt),
                    new com.solace.sm.dp.apply.persistence.JdbcIngestCounterRepository(rt),
                    new com.solace.sm.dp.apply.persistence.JdbcRestartMarkerRepository(rt),
                    new com.solace.sm.dp.metrics.ingest.IngestProperties(20, 500, 5000, 30, Duration.ofMillis(250),
                            Duration.ofSeconds(20), Duration.ofMillis(40), Duration.ofMinutes(5), Duration.ofMillis(250)),
                    Clock.systemUTC());
        }
        return ingestService;
    }

    @Override
    public AgentTelemetryPort telemetry() {
        // the REAL bean over the real ingest service (T3.4): the kit certifies what production runs
        return new com.solace.sm.dp.agent.AgentTelemetryBridge(telemetryRepository, ingestService(),
                new com.solace.sm.dp.apply.persistence.JdbcIngestCounterRepository(runtime),
                new com.solace.sm.dp.agent.AgentTelemetryCounts(), clock);
    }

    @Override
    public com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort declarations() {
        return declarations;
    }

    @Override
    public void declareMetrics(Agent agent, String... names) {
        MapSqlParameterSource key = p("t", agent.tenantId(), "i", agent.instanceId());
        UUID solution = owner.queryForObject("SELECT catalog_solution_id FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key, UUID.class);
        String version = owner.queryForObject("SELECT declared_package_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key, String.class);
        com.solace.sm.dp.apply.persistence.JdbcMetricDefinitionRepository definitions =
                new com.solace.sm.dp.apply.persistence.JdbcMetricDefinitionRepository(runtime);
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> specs = new ArrayList<>();
        for (String name : names) {
            specs.add(new com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec(name, "cache", "gauge", "count", "http_json",
                    "{\"kind\":\"http_json\",\"port\":8081,\"path\":\"/api/cache/stats\",\"pointer\":\"/" + name
                            + "\",\"queryParams\":{},\"onUnavailable\":\"degrade\"}", null, false));
        }
        definitions.insertDefinitionsIfAbsent(agent.tenantId(), solution, version,
                new com.solace.sm.dp.api.metrics.declare.MetricsDeclaration("sha256:" + UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""), specs, List.of(), Map.of(), List.of()), Instant.now());
        definitions.insertActiveMetrics(agent.tenantId(), agent.instanceId(), 0, List.of(names), Instant.now());
    }

    @Override
    public void deactivateInstance(Agent agent) {
        owner.update("UPDATE sm_data.instance_state SET lifecycle = 'retiring' WHERE tenant_id = :t AND instance_id = :i",
                p("t", agent.tenantId(), "i", agent.instanceId()));
    }

    @Override
    public void changeDeclarations(Agent agent, UUID placementId) {
        UUID instance = instanceOfPlacement.get(placementId);
        // as InventoryService.retirePlacement does: the retire and the settle share ONE transaction, and the signal waits for its commit
        runtimeTx.executeWithoutResult(status -> {
            new com.solace.sm.dp.apply.persistence.JdbcPlacementRepository(runtime).retire(agent.tenantId(), instance, placementId,
                    Instant.now(), UUID.randomUUID());
            emitter.settleLadder(ladderRecorder, new com.solace.sm.dp.apply.model.TenantId(agent.tenantId()),
                    new com.solace.sm.dp.apply.model.InstanceId(instance));
        });
    }

    @Override
    public UUID addPlacementThroughTheDataPlane(Agent agent) {
        UUID placement = addPlacement(agent);
        UUID instance = instanceOfPlacement.get(placement);
        runtimeTx.executeWithoutResult(status -> emitter.settleLadder(ladderRecorder,
                new com.solace.sm.dp.apply.model.TenantId(agent.tenantId()), new com.solace.sm.dp.apply.model.InstanceId(instance)));
        return placement;
    }

    @Override
    public Agent enrolWithInstance(UUID instanceId) {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        String thumbprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        UUID target = AgentItSupport.agentTarget(owner, tenant, agent, thumbprint);
        AgentItSupport.declareInstance(owner, tenant, instanceId);
        AgentItSupport.agentPlacement(owner, tenant, instanceId, target, placement);
        instanceOfPlacement.put(placement, instanceId);
        registrations.put(agent, new AgentRegistrationState(agent, true, target,
                List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), 1, 1));
        return new Agent(tenant, agent, thumbprint, target, instanceId, placement);
    }

    @Override
    public AgentEnrollmentPort enrollments() {
        return enrollmentRepository;
    }

    @Override
    public AgentDiscoveryPort discovery() {
        return discovery;
    }

    /** The REAL intake with a cap of its own (T4.1), reading this world's package answer and completion at call time. */
    AgentDiscoveryService discoveryWith(int cap) {
        return discoveryWith(cap, Duration.ofMinutes(10), 256);
    }

    AgentDiscoveryService discoveryWith(int cap, Duration abandonAfter, int maxItemsPerReport) {
        return new AgentDiscoveryService(authority, discoveryRepository, inventory, audits, () -> packages, () -> completion,
                clock, cap, abandonAfter, maxItemsPerReport);
    }

    /** As {@link #discoveryWith(int)} but with NO package reader wired (a context with no console gateway). */
    AgentDiscoveryService discoveryWithoutPackageReader() {
        return new AgentDiscoveryService(authority, discoveryRepository, inventory, audits, () -> null, () -> completion,
                clock, 16, Duration.ofMinutes(10), 256);
    }

    void packages(com.solace.sm.dp.api.inventory.PackageDeclarationSource source) {
        this.packages = source;
    }

    void completion(com.solace.sm.dp.api.agw.AgentRegistrationCompletion sink) {
        this.completion = sink;
    }

    com.solace.sm.dp.inventory.InventoryService inventory() {
        return inventory;
    }

    JdbcAgentDiscoveryRepository discoveryRepository() {
        return discoveryRepository;
    }

    NamedParameterJdbcTemplate owner() {
        return owner;
    }

    NamedParameterJdbcTemplate runtime() {
        return runtime;
    }

    @Override
    public AgentIdentityPort identities() {
        return identities;
    }

    // ---- scenario construction -----------------------------------------------------------------------------------------

    @Override
    public Agent enrol() {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        String thumbprint = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
        UUID target = AgentItSupport.agentTarget(owner, tenant, agent, thumbprint);
        AgentItSupport.declareInstance(owner, tenant, instance);
        AgentItSupport.agentPlacement(owner, tenant, instance, target, placement);
        instanceOfPlacement.put(placement, instance);
        registrations.put(agent, new AgentRegistrationState(agent, true, target,
                List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), 1, 1));
        return new Agent(tenant, agent, thumbprint, target, instance, placement);
    }

    @Override
    public UUID addPlacement(Agent agent) {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        AgentItSupport.declareInstance(owner, agent.tenantId(), instance);
        AgentItSupport.agentPlacement(owner, agent.tenantId(), instance, agent.targetId(), placement);
        instanceOfPlacement.put(placement, instance);
        return placement;
    }

    @Override
    public void retirePlacement(Agent agent, UUID placementId) {
        owner.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                p("now", OffsetDateTime.now(), "t", agent.tenantId(), "p", placementId));
    }

    @Override
    public AgentRequestOrigin origin() {
        return new AgentRequestOrigin(UUID.randomUUID(), "10.0.0.7");
    }

    @Override
    public AgentSessionRef open(Agent agent) {
        return open(agent, opening());
    }

    @Override
    public AgentSessionRef open(Agent agent, SessionOpening opening) {
        return sessions.claim(agent.thumbprint(), opening, origin());
    }

    @Override
    public SessionOpening opening() {
        return new SessionOpening(1, "0.1.0", java.util.Set.of(), clock.instant());
    }

    @Override
    public LeaseGrant lease(Agent agent, AgentSessionRef session) {
        return leases.issue("lease-" + UUID.randomUUID(), session, clock.instant(), origin());
    }

    @Override
    public LeaseGrant renewLease(Agent agent, AgentSessionRef session, LeaseGrant current) {
        return leases.renew("renew-" + UUID.randomUUID(), session, current.nonce(), clock.instant(), origin());
    }

    @Override
    public AgentIdentity lastIssuedFor() {
        return lastIssuedFor;
    }

    @Override
    public void issuerCorrupts(java.util.function.UnaryOperator<LeaseGrant> corruption) {
        this.corruption = corruption;
    }

    @Override
    public void issuerAlsoNames(UUID placementId) {
        extraPlacement = placementId;
    }

    // ---- control plane and time ------------------------------------------------------------------------------------------

    @Override
    public void controlPlane(Agent agent, boolean active, long agentEpoch, long fleetEpoch, List<AgentCapability> capabilities) {
        registrations.put(agent.agentId(), new AgentRegistrationState(agent.agentId(), active, agent.targetId(),
                capabilities, agentEpoch, fleetEpoch));
    }

    @Override
    public void controlPlaneForgets(Agent agent) {
        registrations.remove(agent.agentId());
    }

    @Override
    public void controlPlaneDown(boolean down) {
        controlPlaneDown = down;
    }

    @Override
    public int controlPlaneReads() {
        return controlPlaneReads.get();
    }

    @Override
    public void advance(Duration by) {
        clock.advance(by);
    }

    @Override
    public java.util.Optional<Instant> placementStart(Agent agent) {
        return owner.query("SELECT started_at FROM sm_data.placement_start_observation WHERE tenant_id = :t AND placement_id = :p",
                p("t", agent.tenantId(), "p", agent.placementId()),
                (rs, n) -> rs.getTimestamp("started_at").toInstant()).stream().findFirst();
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    MutableClock clock() {
        return clock;
    }

    // ---- commands ------------------------------------------------------------------------------------------------------------

    @Override
    public AgentCommandIntent intent(Agent agent, String intentKey, AgentOperation operation) {
        return intentOn(agent, agent.placementId(), intentKey, operation);
    }

    @Override
    public AgentCommandIntent intentOn(Agent agent, UUID placementId, String intentKey, AgentOperation operation) {
        UUID instance = instanceOfPlacement.get(placementId);
        Journal j;
        synchronized (journal) {
            j = journal.get(intentKey);
            if (j == null) {
                UUID apply = applyOfPlacement.computeIfAbsent(placementId,
                        pl -> AgentItSupport.request(owner, agent.tenantId(), instance, pl));
                String step = switch (operation) {
                    case APPLY, REMATERIALIZE -> "MATERIALIZE";
                    case PREFLIGHT -> "PREFLIGHT";
                    case RESTART -> "LIFECYCLE";
                    case STOP -> "STOP";
                };
                UUID intent = UUID.randomUUID();
                owner.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, placement_id, replica_ref,"
                                + " step, idempotency_key, config_hash, created_at)"
                                + " VALUES (:id, :t, :a, :p, :r, :s, :k, :h, :now)",
                        p("id", intent, "t", agent.tenantId(), "a", apply, "p", placementId,
                                "r", step.equals("PREFLIGHT") ? "@placement" : "r" + replicas.incrementAndGet(), "s", step, "k", intentKey,
                                "h", step.equals("MATERIALIZE") ? hash() : null, "now", OffsetDateTime.now()));
                j = new Journal(apply, intent);
                journal.put(intentKey, j);
            }
        }
        boolean config = operation == AgentOperation.APPLY || operation == AgentOperation.REMATERIALIZE;
        return new AgentCommandIntent(agent.agentId(), new com.solace.sm.dp.api.agw.AgentPlacementRef(agent.tenantId(), instance,
                placementId), intentKey, operation, config ? hash() : null, clock.instant().plus(Duration.ofMinutes(10)),
                new OperationCorrelation(j.apply().toString(), j.intent().toString(), intentKey, UUID.randomUUID().toString(),
                        "step"), null);
    }

    private static String hash() {
        return "hmacsha256v1:" + "a".repeat(64);
    }

    @Override
    public void payload(CommandId id, byte[] bytes, String plainSha256) {
        payloads.register(id, bytes, plainSha256);
    }

    // ---- observation ---------------------------------------------------------------------------------------------------------------

    private static String table(String name) {
        if (!name.matches("agent_[a-z_]+")) {
            throw new IllegalArgumentException("not an agent table: " + name);
        }
        return "sm_data." + name;
    }

    @Override
    public long rows(String table, Agent agent) {
        return owner.queryForObject("SELECT count(*) FROM " + table(table) + " WHERE agent_id = :a", p("a", agent.agentId()), Long.class);
    }

    @Override
    public UUID storedTenant(String table, Agent agent) {
        List<UUID> tenants = owner.queryForList("SELECT DISTINCT tenant_id FROM " + table(table) + " WHERE agent_id = :a",
                p("a", agent.agentId()), UUID.class);
        if (tenants.size() != 1) {
            throw new AssertionError("expected exactly one organization in " + table + " but found " + tenants);
        }
        return tenants.get(0);
    }

    @Override
    public String rowText(String table, Agent agent) {
        String text = owner.queryForObject("SELECT COALESCE(string_agg(to_jsonb(t)::text, ' '), '') FROM " + table(table)
                + " t WHERE t.agent_id = :a", p("a", agent.agentId()), String.class);
        return text == null ? "" : text;
    }

    @Override
    public boolean anyAgentRowContains(Agent agent, String needle) {
        List<String> tables = owner.queryForList("SELECT table_name FROM information_schema.columns WHERE table_schema = 'sm_data'"
                + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'metrics\\_%' ORDER BY 1", Map.of(), String.class);
        for (String t : tables) {
            Boolean hit = owner.queryForObject("SELECT EXISTS (SELECT 1 FROM sm_data." + t.replaceAll("[^a-z_0-9]", "")
                            + " x WHERE x.tenant_id = :t AND (position(:needle in to_jsonb(x)::text) > 0"
                            + " OR position(encode(convert_to(:needle, 'UTF8'), 'hex') in to_jsonb(x)::text) > 0))",
                    p("t", agent.tenantId(), "needle", needle), Boolean.class);
            if (Boolean.TRUE.equals(hit)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String commandState(CommandId id) {
        return owner.queryForObject("SELECT state FROM sm_data.agent_command WHERE command_id = :c", p("c", id.value()), String.class);
    }

    @Override
    public String commandFingerprint(CommandId id) {
        return owner.queryForObject("SELECT to_jsonb(c)::text || '|' || pg_column_size(c.*) FROM sm_data.agent_command c"
                + " WHERE command_id = :c", p("c", id.value()), String.class);
    }

    @Override
    public Instant sessionLastSeen(Agent agent) {
        return owner.queryForObject("SELECT last_seen_at FROM sm_data.agent_session WHERE agent_id = :a", p("a", agent.agentId()),
                OffsetDateTime.class).toInstant();
    }

    @Override
    public Long clockOffsetMs(Agent agent) {
        return owner.queryForObject("SELECT clock_offset_ms FROM sm_data.agent_session WHERE agent_id = :a",
                p("a", agent.agentId()), Long.class);
    }

    AgentAuthority authority() {
        return authority;
    }

    TransactionTemplate runtimeTx() {
        return runtimeTx;
    }

    NamedParameterJdbcTemplate runtimeJdbc() {
        return runtime;
    }

    NamedParameterJdbcTemplate ownerJdbc() {
        return owner;
    }

    @Override
    public boolean sessionClosed(Agent agent) {
        return owner.queryForObject("SELECT closed_at IS NOT NULL FROM sm_data.agent_session WHERE agent_id = :a",
                p("a", agent.agentId()), Boolean.class);
    }

    @Override
    public boolean identityRevoked(Agent agent) {
        return owner.queryForObject("SELECT revoked_at IS NOT NULL FROM sm_data.agent_identity WHERE agent_id = :a",
                p("a", agent.agentId()), Boolean.class);
    }

    @Override
    public String activeLeaseNonceDigest(Agent agent) {
        return owner.queryForObject("SELECT renewal_nonce_digest FROM sm_data.agent_lease WHERE agent_id = :a AND state = 'ACTIVE'",
                p("a", agent.agentId()), String.class);
    }

    @Override
    public OptionalLong reportWatermark(Agent agent, UUID placementId) {
        List<Long> v = owner.queryForList("SELECT last_sequence FROM sm_data.agent_report_watermark WHERE agent_id = :a"
                + " AND placement_id = :p", p("a", agent.agentId(), "p", placementId), Long.class);
        return v.isEmpty() ? OptionalLong.empty() : OptionalLong.of(v.get(0));
    }

    @Override
    public OptionalLong telemetryWatermark(Agent agent, String streamId) {
        List<Long> v = owner.queryForList("SELECT last_batch_seq FROM sm_data.agent_telemetry_watermark WHERE agent_id = :a"
                + " AND stream_id = :s", p("a", agent.agentId(), "s", streamId), Long.class);
        return v.isEmpty() ? OptionalLong.empty() : OptionalLong.of(v.get(0));
    }

    // ---- enrolment ---------------------------------------------------------------------------------------------------------------

    @Override
    public Enrollment newEnrollment() {
        UUID tenant = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String requestId = "enroll-" + UUID.randomUUID();
        EnrollmentRequest request = new EnrollmentRequest(requestId, "token-" + UUID.randomUUID(), new byte[] {1, 2, 3, 4},
                "0.1.0", List.of(1), "linux", "amd64", "docker", origin());
        byte[] leaf = (UUID.randomUUID() + "-leaf").getBytes(StandardCharsets.UTF_8);
        EnrollmentIssuance issuance = new EnrollmentIssuance(tenant, UUID.randomUUID(), "cache", "2.4",
                UUID.nameUUIDFromBytes(("env-" + tenant).getBytes(StandardCharsets.UTF_8)), target, agent, List.of(leaf),
                List.of("trust".getBytes(StandardCharsets.UTF_8)), clock.instant(), clock.instant().plus(Duration.ofDays(30)),
                "gateway.example:443", new EnrollmentEnvelope("/srv/sm/" + agent, "sm-"), UUID.randomUUID(),
                List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), 1, 1);
        Enrollment enrollment = new Enrollment(request, agent, tenant, target);
        enrollments.put(agent, new Programmed(enrollment, issuance, new boolean[] {false}));
        return enrollment;
    }

    @Override
    public EnrollmentRequest replayOf(Enrollment e) {
        EnrollmentRequest r = e.request();
        return new EnrollmentRequest("enroll-" + UUID.randomUUID(), r.token(), r.csrDer(), r.agentVersion(), r.protocolVersions(),
                r.hostOs(), r.hostArch(), r.runtimeKind(), origin());
    }

    private final Map<UUID, String> occupied = new ConcurrentHashMap<>();

    @Override
    public void occupyThumbprint(Enrollment e) {
        Agent squatter = enrol();
        owner.update("UPDATE sm_data.agent_identity SET certificate_thumbprint = :th WHERE agent_id = :a",
                p("th", leafThumbprint(e), "a", squatter.agentId()));
        occupied.put(e.agentId(), squatter.thumbprint() + "|" + squatter.agentId());
    }

    @Override
    public void releaseThumbprint(Enrollment e) {
        String[] held = occupied.remove(e.agentId()).split("\\|");
        owner.update("UPDATE sm_data.agent_identity SET certificate_thumbprint = :th WHERE agent_id = :a",
                p("th", held[0], "a", UUID.fromString(held[1])));
    }

    @Override
    public void controlPlaneKnows(Enrollment enrollment) {
        registrations.put(enrollment.agentId(), new AgentRegistrationState(enrollment.agentId(), true, enrollment.targetId(),
                List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), 1, 1));
    }

    @Override
    public void issuerRefuses(Enrollment enrollment) {
        enrollments.get(enrollment.agentId()).refuses()[0] = true;
    }

    @Override
    public String leafThumbprint(Enrollment enrollment) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(enrollments.get(enrollment.agentId()).issuance().certificateChainDer().get(0)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public long targetRows(Enrollment e) {
        return owner.queryForObject("SELECT count(*) FROM sm_data.target_connection WHERE target_id = :x AND transport = 'agent'",
                p("x", e.targetId()), Long.class);
    }

    @Override
    public long enrolledEvents(Enrollment e) {
        return owner.queryForObject("SELECT count(*) FROM sm_data.target_connection_event WHERE target_id = :x"
                + " AND kind = 'enrolled' AND actor_kind = 'agent' AND actor = :a", p("x", e.targetId(), "a", e.agentId()), Long.class);
    }

    @Override
    public long identityRows(Enrollment e) {
        return owner.queryForObject("SELECT count(*) FROM sm_data.agent_identity WHERE agent_id = :a", p("a", e.agentId()), Long.class);
    }

    @Override
    public UUID enrolledTenant(Enrollment e) {
        return owner.queryForObject("SELECT tenant_id FROM sm_data.agent_identity WHERE agent_id = :a", p("a", e.agentId()), UUID.class);
    }

    @Override
    public List<String> storedEnvelope(Enrollment e) {
        return owner.queryForObject("SELECT ARRAY[allowed_config_root, allowed_container_prefix] FROM sm_data.target_connection"
                + " WHERE target_id = :x", p("x", e.targetId()), (rs, n) -> List.of((String[]) rs.getArray(1).getArray()));
    }

    private static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource m = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            m.addValue((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
