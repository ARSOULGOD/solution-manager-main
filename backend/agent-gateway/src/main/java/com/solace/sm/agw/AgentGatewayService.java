package com.solace.sm.agw;

import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentHealthObservation;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.TelemetryDeclaration;
import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentFeature;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.OperationCorrelation;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import solace.sm.agentgateway.v1draft.AgentGatewayGrpc;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.AckDetail;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.AgentToGateway;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;

/** gRPC translation layer. It owns no durable state and never sees tenant scope. */
public final class AgentGatewayService extends AgentGatewayGrpc.AgentGatewayImplBase {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentGatewayService.class);
    private static final Metadata.Key<String> TOKEN = Metadata.Key.of("x-sm-enrollment-token", Metadata.ASCII_STRING_MARSHALLER);
    /** T1.2 review note 7: the longest agent free-text detail that is kept (bounded, sanitised, never logged or echoed). */
    static final int MAX_DETAIL_CHARS = 200;
    private final AgentGatewayPortSet ports;
    private final AgentGatewayProperties properties;
    private final ScheduledExecutorService scheduler;
    private final CertificateThumbprintResolver thumbprints;
    private final AgentGatewayMetrics metrics;
    private final Map<UUID, ConnectSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Runnable>> telemetryAborts = new ConcurrentHashMap<>();
    /** Plan 015 W4 lane D: told which agent's declared set may have changed, after the change committed. */
    private final java.util.function.Consumer<UUID> declarationsListener = this::declarationsChanged;
    private volatile boolean draining;
    private volatile Duration drainRetryAfter = Duration.ofSeconds(2);
    private volatile ScheduledFuture<?> sweep;
    private volatile ScheduledFuture<?> pollSweep;
    /** Runs the full polls of the sessions that have something to be told about (a fixed, small pool; never one thread per session). */
    private final ExecutorService pollers;
    private final AdmissionLimiter admission;
    /** Plan 016 task 7: log requests, their replies and the Logs stream (in memory only). */
    private final AgentLogBroker logs;
    /** Plan 017 task 4: config view requests and their replies on the Connect stream (in memory only, one per agent). */
    private final AgentConfigViewBroker configViews;

    public AgentGatewayService(AgentGatewayPortSet ports, AgentGatewayProperties properties,
            ScheduledExecutorService scheduler) {
        this(ports, properties, scheduler, CertificateThumbprintResolver.tlsPeer(),
                new AgentGatewayMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    public AgentGatewayService(AgentGatewayPortSet ports, AgentGatewayProperties properties,
            ScheduledExecutorService scheduler, AgentGatewayMetrics metrics) {
        this(ports, properties, scheduler, CertificateThumbprintResolver.tlsPeer(), metrics);
    }

    AgentGatewayService(AgentGatewayPortSet ports, AgentGatewayProperties properties,
            ScheduledExecutorService scheduler, CertificateThumbprintResolver thumbprints) {
        this(ports, properties, scheduler, thumbprints,
                new AgentGatewayMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    AgentGatewayService(AgentGatewayPortSet ports, AgentGatewayProperties properties,
            ScheduledExecutorService scheduler, CertificateThumbprintResolver thumbprints,
            AgentGatewayMetrics metrics) {
        this.ports = ports;
        this.properties = properties;
        this.scheduler = scheduler;
        this.thumbprints = thumbprints;
        this.metrics = metrics;
        this.admission = new AdmissionLimiter(properties);
        this.logs = new AgentLogBroker(agent -> activeSessions.get(agent), () -> new ArrayList<>(activeSessions.keySet()),
                scheduler, properties, metrics);
        this.configViews = new AgentConfigViewBroker(agent -> activeSessions.get(agent), properties::getConfigViewDeadline, metrics);
        this.pollers = Executors.newFixedThreadPool(Math.max(1, properties.getPollThreads()), task -> {
            Thread thread = new Thread(task, "agent-gateway-poll");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void enroll(AgentGatewayOuterClass.EnrollRequest request, StreamObserver<AgentGatewayOuterClass.EnrollResponse> response) {
        try {
            String token = MetadataContext.TOKEN.get();
            if (token == null || token.isBlank()) throw refused(GatewayCode.UNAUTHENTICATED_IDENTITY, "enrollment token is required");
            // T1.2 review finding 7: the request id was an unkeyed MD5 of the token (plus the CSR), persisted as a replay key.
            // It is now minted here, per RPC, with the origin (OR-25's correlation id); the control plane's replay key is the
            // stored token and CSR digests (OR-128 (9)), so nothing derived from the token is a key anywhere.
            AgentRequestOrigin origin = origin();
            EnrollmentRequest input = new EnrollmentRequest("enroll-" + origin.correlationId(),
                    token, request.getCsrDer().toByteArray(), request.getAgentVersion(),
                    request.getProtocolVersionsSupportedList(), request.getHostFacts().getOs(),
                    request.getHostFacts().getArch(), request.getHostFacts().getRuntimeKind(), origin);
            var result = ports.enrollment().enroll(input);
            var builder = AgentGatewayOuterClass.EnrollResponse.newBuilder().setAgentId(result.agentId().toString())
                    .setConnectEndpoint(result.connectEndpoint()).setTrustBundle(AgentGatewayOuterClass.TrustBundle.newBuilder()
                            .addAllCertsPem(result.trustBundlePem().stream().map(ByteString::copyFrom).toList())
                            .setValidFrom(timestamp(result.validFrom())).setValidTo(timestamp(result.validTo())));
            result.certificateChainDer().forEach(x -> builder.addCertificateChain(ByteString.copyFrom(x)));
            response.onNext(builder.build());
            response.onCompleted();
        } catch (GatewayPortException e) { fail(response, e); }
        catch (RuntimeException e) { response.onError(Status.INTERNAL.withDescription("enrollment failed").withCause(e).asRuntimeException()); }
    }

    @Override
    public StreamObserver<AgentToGateway> connect(StreamObserver<GatewayToAgent> response) {
        ConnectSession session = new ConnectSession(response);
        session.openTimer = scheduler.schedule(session::openTimeout, properties.getSessionOpenTimeout().toMillis(), TimeUnit.MILLISECONDS);
        return session;
    }

    @Override
    public StreamObserver<AgentGatewayOuterClass.TelemetryUp> telemetry(StreamObserver<AgentGatewayOuterClass.TelemetryDown> response) {
        String thumbprint = thumbprints.resolve();
        AgentIdentity identity = thumbprint == null ? null : ports.identity().resolve(thumbprint).orElse(null);
        ConnectSession live = identity == null ? null : activeSessions.get(identity.agentId());
        AgentSessionRef session = live == null ? null : live.session;
        if (session == null) {
            metrics.telemetryRejected();
            response.onError(Status.UNAUTHENTICATED.withDescription("accepted agent session is required").asRuntimeException());
            return ignoredTelemetry();
        }
        UUID telemetryAgent = session.agentId();
        AgentRequestOrigin telemetryOrigin = origin();
        return new StreamObserver<>() {
            private int availableCredits = properties.getInitialTelemetryCredits();
            private boolean closed;
            private boolean recoveryScheduled;

            /**
             * A stream at zero credits sends nothing, so nothing would ever return its credits: the agent waits for a
             * grant and the gateway waits for a batch. After a pause the gateway therefore re-grants the window on a
             * timer. If the DP is still refusing, the next batch is refused again and the timer is re-armed, which is
             * the backpressure: one probe batch per interval, not a stream of them.
             */
            private void scheduleCreditRecovery(String streamId) {
                if (recoveryScheduled || closed) return;
                recoveryScheduled = true;
                scheduler.schedule(() -> {
                    synchronized (this) {
                        recoveryScheduled = false;
                        if (closed) return;
                        int before = availableCredits;
                        availableCredits = properties.getInitialTelemetryCredits();
                        metrics.creditsChanged(availableCredits - before);
                        metrics.creditsRegranted();
                        try {
                            response.onNext(AgentGatewayOuterClass.TelemetryDown.newBuilder().setTelemetryCredit(
                                    AgentGatewayOuterClass.TelemetryCredit.newBuilder().setEnvelope(envelope())
                                            .setStreamId(streamId).setCreditsGranted(availableCredits)).build());
                        } catch (RuntimeException alreadyClosed) { /* the peer went first */ }
                    }
                }, properties.getTelemetryCreditRecoveryInterval().toMillis(), TimeUnit.MILLISECONDS);
            }

            private final Runnable abort = () -> {
                synchronized (this) {
                    if (closed) return;
                    closed = true;
                    metrics.creditsChanged(-availableCredits);
                    availableCredits = 0;
                }
                try { response.onError(Status.PERMISSION_DENIED.withDescription("identity revoked").asRuntimeException()); }
                catch (RuntimeException alreadyClosed) { /* the peer went first */ }
            };

            {
                metrics.creditsChanged(availableCredits);
                telemetryAborts.computeIfAbsent(telemetryAgent, key -> new CopyOnWriteArraySet<>()).add(abort);
            }

            @Override public synchronized void onNext(AgentGatewayOuterClass.TelemetryUp value) {
                if (!value.hasTelemetryBatch() || closed) return;
                if (availableCredits == 0) {
                    scheduleCreditRecovery(value.getTelemetryBatch().getStreamId());
                    metrics.telemetryRejected();
                    response.onNext(AgentGatewayOuterClass.TelemetryDown.newBuilder().setTelemetryReject(
                            AgentGatewayOuterClass.TelemetryReject.newBuilder().setEnvelope(envelope())
                                    .setStreamId(value.getTelemetryBatch().getStreamId())
                                    .setCode(error(GatewayCode.OUT_OF_CREDITS))).build());
                    return;
                }
                // Reserve before entering the durable port. A batch is not acknowledged until the
                // port commits; a failed call returns the reservation to the sender.
                availableCredits--;
                metrics.creditsChanged(-1);
                try {
                    AgentTelemetryBatch batch = telemetry(value.getTelemetryBatch());
                    var result = ports.telemetry().ingest(session, batch, telemetryOrigin);
                    switch (result.disposition()) {
                        case DO_NOT_ACK -> {
                            // The batch is not durable: no acknowledgement. The reservation goes back and the agent is told when to try
                            // again (its runner honours retry_after, send/runner.go).
                            availableCredits = Math.min(properties.getInitialTelemetryCredits(), availableCredits + 1);
                            metrics.creditsChanged(1);
                            metrics.telemetryRejected();
                            var reject = AgentGatewayOuterClass.TelemetryReject.newBuilder().setEnvelope(envelope())
                                    .setStreamId(batch.streamId()).setCode(error(GatewayCode.INTERNAL_UNAVAILABLE));
                            if (result.retryAfter() != null) reject.setRetryAfter(duration(result.retryAfter()));
                            response.onNext(AgentGatewayOuterClass.TelemetryDown.newBuilder().setTelemetryReject(reject).build());
                        }
                        case REJECTED_COUNTED, ACK -> {
                            // REJECTED_COUNTED: the data plane dropped the batch and counted it (AGW-D27). It is ACKNOWLEDGED with the
                            // watermark it reports, so the agent trims instead of resending a batch that can never be accepted; the
                            // reason is audited, not sent as a retryable reject.
                            if (result.disposition() == com.solace.sm.dp.api.agw.AgentTelemetryResult.Disposition.REJECTED_COUNTED) {
                                metrics.telemetryRejected();
                                try { ports.audit().record(batch.streamId() + "-" + batch.batchSeq(), session, telemetryOrigin,
                                        "gateway.refusal", result.rejection() == null ? GatewayCode.UNKNOWN : result.rejection(),
                                        "telemetry-dropped", Instant.now()); }
                                catch (RuntimeException ignored) { }
                            } else {
                                metrics.telemetryBatch();
                            }
                            int granted = Math.max(0, result.creditsGranted());
                            int before = availableCredits;
                            availableCredits = Math.min(properties.getInitialTelemetryCredits(), availableCredits + granted);
                            metrics.creditsChanged(availableCredits - before);
                            response.onNext(AgentGatewayOuterClass.TelemetryDown.newBuilder().setTelemetryAck(
                                    AgentGatewayOuterClass.TelemetryAck.newBuilder().setEnvelope(envelope())
                                            .setStreamId(batch.streamId()).setHighWatermarkSeq(result.highWatermark())
                                            .setCreditsGranted(result.creditsGranted())).build());
                        }
                    }
                } catch (GatewayPortException e) {
                    LOG.warn("agent telemetry batch rejected: {}", e.code());
                    int creditDelta;
                    if (e.code() == GatewayCode.OUT_OF_CREDITS) {
                        creditDelta = -availableCredits;
                        availableCredits = 0;
                        scheduleCreditRecovery(value.getTelemetryBatch().getStreamId());
                    } else {
                        availableCredits = Math.min(properties.getInitialTelemetryCredits(), availableCredits + 1);
                        creditDelta = 1;
                    }
                    metrics.telemetryRejected();
                    metrics.creditsChanged(creditDelta);
                    try { ports.audit().record(value.getTelemetryBatch().getEnvelope().getMessageId(), session, telemetryOrigin,
                            "gateway.refusal", e.code(), "telemetry", Instant.now()); }
                    catch (RuntimeException ignored) { }
                    response.onNext(AgentGatewayOuterClass.TelemetryDown.newBuilder().setTelemetryReject(
                            AgentGatewayOuterClass.TelemetryReject.newBuilder().setEnvelope(envelope())
                                    .setStreamId(value.getTelemetryBatch().getStreamId()).setCode(error(e.code()))).build());
                } catch (RuntimeException e) {
                    LOG.error("agent telemetry ingest failed: {} {}", e.getClass().getName(), rootCause(e));
                    availableCredits = Math.min(properties.getInitialTelemetryCredits(), availableCredits + 1);
                    metrics.telemetryRejected();
                    metrics.creditsChanged(1);
                    response.onError(Status.INTERNAL.withDescription("telemetry ingest failed")
                            .withCause(e).asRuntimeException());
                }
            }
            private void deregister() {
                Set<Runnable> aborts = telemetryAborts.get(telemetryAgent);
                if (aborts != null) aborts.remove(abort);
            }
            @Override public synchronized void onError(Throwable t) {
                closed = true;
                deregister();
                metrics.creditsChanged(-availableCredits);
                availableCredits = 0;
            }
            @Override public synchronized void onCompleted() {
                closed = true;
                deregister();
                metrics.creditsChanged(-availableCredits);
                availableCredits = 0;
                response.onCompleted();
            }
        };
    }

    /** The log port the data plane's reads call (plan 016 task 7); the gateway implements it over its live sessions. */
    public com.solace.sm.dp.api.agw.AgentLogPort logPort() { return logs; }

    /** What the data plane's config view calls (plan 017 task 4): the live sessions are this process's. */
    public com.solace.sm.dp.api.agw.AgentConfigViewPort configViewPort() { return configViews; }

    /**
     * The {@code Logs} stream, opened by an agent after its {@code SessionAccept} when it advertised log capability. Refused unless the
     * caller has a live accepted session that advertised it. Its {@code onNext} only hands the message to the broker (a lookup and a
     * queue offer), so it never holds up the {@code Connect} stream's heartbeats or lease renewals, which run on their own monitor.
     */
    @Override
    public StreamObserver<AgentGatewayOuterClass.LogUp> logs(StreamObserver<AgentGatewayOuterClass.LogDown> response) {
        String thumbprint = thumbprints.resolve();
        AgentIdentity identity = thumbprint == null ? null : ports.identity().resolve(thumbprint).orElse(null);
        ConnectSession live = identity == null ? null : activeSessions.get(identity.agentId());
        AgentSessionRef session = live == null ? null : live.session;
        if (session == null || !live.logsSupported()) {
            response.onError(Status.UNAUTHENTICATED.withDescription("accepted agent session with log capability is required")
                    .asRuntimeException());
            return new StreamObserver<>() { public void onNext(AgentGatewayOuterClass.LogUp x) {} public void onError(Throwable x) {} public void onCompleted() {} };
        }
        AgentLogBroker.LogStream stream = logs.attach(session, response);
        return new StreamObserver<>() {
            @Override public void onNext(AgentGatewayOuterClass.LogUp value) {
                try { logs.onUp(stream, value); }
                catch (RuntimeException failure) { LOG.warn("agent-gateway log message dropped: {}", failure.getClass().getName()); }
            }
            @Override public void onError(Throwable t) { logs.detach(stream); }
            @Override public void onCompleted() { logs.detach(stream); }
        };
    }

    /** The outcome of a planned drain, for the operator's log and the tests. */
    public record DrainSummary(int sessions, int answered, int forced) { }

    /** Starts the periodic check of live sessions against the control plane (revocation, permission changes). */
    public synchronized void startMaintenance() {
        if (sweep != null) return;
        // The data plane's declarations-change signal (plan 015 W4 lane D): the same consumer instance every start, so a restart of
        // maintenance never registers twice.
        ports.declarations().onDeclarationsChanged(declarationsListener);
        long millis = properties.getRevocationCheckInterval().toMillis();
        sweep = scheduler.scheduleWithFixedDelay(this::reconcileSessions, millis, millis, TimeUnit.MILLISECONDS);
        long poll = properties.getPollInterval().toMillis();
        pollSweep = scheduler.scheduleWithFixedDelay(this::pollTick, poll, poll, TimeUnit.MILLISECONDS);
    }

    /**
     * The data plane signalled that an agent's declarations may have changed (a placement added or retired on its target, an instance
     * activated or retired, a metric activation). It runs on the committing thread, so it does no read itself: the live session's own
     * work runs on the poll pool, COALESCED to at most one queued task per session (a signal that arrives while one runs marks the
     * session dirty and is served by one more run, never lost and never piled up). An agent with no live session here is told at its
     * next session open.
     */
    void declarationsChanged(UUID agentId) {
        ConnectSession live = activeSessions.get(agentId);
        if (live == null) return;
        live.scheduleDeclarationsPush();
    }

    /**
     * One reconcile of this agent alone: when its lease no longer names the live placement set (or anything else the reconciliation
     * judges), the existing {@code RENEWAL_REQUIRED} path asks the agent to renew, and the renewal is what declares a placement the
     * lease did not hold. Epoch and revocation outcomes are applied exactly as the sweep applies them.
     */
    private void reconcileOne(ConnectSession target) {
        AgentSessionRef ref = target.session;
        if (ref == null) return;
        List<IdentityReconciliation> results;
        try {
            results = ports.identity().reconcile(List.of(ref));
        } catch (RuntimeException e) {
            metrics.reconcileFailed();
            LOG.warn("agent-gateway reconciliation of one agent failed (the sweep retries): {}", e.getClass().getName());
            return;
        }
        for (IdentityReconciliation result : results) {
            if (!result.session().equals(target.session)) continue;
            switch (result.status()) {
                case REVOKED -> target.revokeAndClose(result.agentRevocationEpoch(), result.fleetRevocationEpoch(), "identity-revoked");
                case STALE -> target.supersede();
                case REISSUED -> target.pushLease(result.lease());
                case RENEWAL_REQUIRED -> target.promptRenewalNow();
                case UNCHANGED -> { }
            }
        }
    }

    public synchronized void stopMaintenance() {
        if (sweep != null) sweep.cancel(false);
        sweep = null;
        if (pollSweep != null) pollSweep.cancel(false);
        pollSweep = null;
        pollers.shutdown();
    }

    /**
     * One shared poll for the whole replica. Each session used to run its own scheduled poll (about six statements a second per session even
     * when nothing was queued); now ONE set-based read says which agents have anything to be told about and only those sessions are polled in
     * full, on a small fixed pool. An idle fleet therefore costs one statement per interval, whatever its size.
     */
    void pollTick() {
        List<ConnectSession> live = new ArrayList<>(activeSessions.values());
        live.removeIf(x -> x.session == null);
        if (live.isEmpty()) return;
        java.util.Set<UUID> work;
        try {
            work = ports.commands().agentsWithDeliverableWork(live.stream().map(x -> x.session).toList(), properties.getNoticeRetryInterval());
        } catch (RuntimeException e) {
            LOG.warn("agent-gateway poll read failed (will retry): {}", e.getClass().getName());
            return;
        }
        for (ConnectSession target : live) {
            if (work.contains(target.session.agentId())) target.schedulePoll();
        }
    }

    /**
     * One reconciliation pass. A revoked agent gets {@code Revoke} and its streams closed; a permission change
     * pushes the re-issued lease; a superseded session is told so. A control-plane read failure keeps the sessions
     * it has and is retried on the next pass, so a CP blip never mass-disconnects the fleet.
     */
    void reconcileSessions() {
        List<ConnectSession> live = new ArrayList<>(activeSessions.values());
        if (live.isEmpty()) return;
        List<IdentityReconciliation> results;
        try {
            results = ports.identity().reconcile(live.stream().map(x -> x.session).filter(java.util.Objects::nonNull).toList());
        } catch (RuntimeException e) {
            metrics.reconcileFailed();
            LOG.warn("agent-gateway reconciliation failed (will retry): {}", e.getClass().getName());
            return;
        }
        for (IdentityReconciliation result : results) {
            ConnectSession target = activeSessions.get(result.session().agentId());
            if (target == null || !result.session().equals(target.session)) continue;
            switch (result.status()) {
                case REVOKED -> target.revokeAndClose(result.agentRevocationEpoch(), result.fleetRevocationEpoch(),
                        "identity-revoked");
                case STALE -> target.supersede();
                case REISSUED -> target.pushLease(result.lease());
                case RENEWAL_REQUIRED -> target.promptRenewalNow();
                case UNCHANGED -> { }
            }
        }
    }

    /**
     * Planned drain (AGW-D18). New sessions are refused {@code DRAINING}; every live session is sent
     * {@code Drain} with a staggered {@code reconnect_after} and a deadline; each agent finishes what it has in
     * flight, answers {@code DrainComplete} and is closed. Whatever has not answered when the window ends is
     * closed anyway: its unacknowledged commands stay {@code OFFERED}/{@code DELIVERED} at the DP and are
     * re-notified on its next session, so nothing is lost, only delayed.
     */
    public DrainSummary drain() {
        Duration window = properties.getDrainWindow();
        draining = true;
        drainRetryAfter = properties.getDrainReconnectMin();
        List<ConnectSession> sessions = new ArrayList<>(activeSessions.values());
        sessions.sort(java.util.Comparator.comparing(x -> x.session == null ? "" : x.session.agentId().toString()));
        int n = sessions.size();
        metrics.drainStarted(n);
        List<CompletableFuture<Boolean>> pending = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long spreadMillis = n > 1 ? properties.getDrainReconnectSpread().toMillis() * i / (n - 1) : 0;
            Duration reconnectAfter = properties.getDrainReconnectMin().plusMillis(spreadMillis);
            pending.add(sessions.get(i).beginDrain(reconnectAfter, window));
        }
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(window.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expected) {
            // fall through: the stragglers are cut below
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException ignored) {
            // a session's own failure only completes its future exceptionally; the count below is what matters
        }
        int answered = 0, forced = 0;
        for (int i = 0; i < n; i++) {
            CompletableFuture<Boolean> done = pending.get(i);
            if (done.isDone() && !done.isCompletedExceptionally() && done.getNow(false)) answered++;
            else { forced++; sessions.get(i).forceClose("drain window elapsed"); }
        }
        metrics.drainForced(forced);
        return new DrainSummary(n, answered, forced);
    }

    /**
     * Operator-requested drain of ONE agent (plan 015 T4.3, {@code drainAgent}; AGW-D18; the {@code AgentDrainPort} contract). Tells that
     * agent's accepted session to finish what it holds and reconnect after {@code drain-reconnect-min}; it does not stop the gateway or
     * refuse other sessions. A session that has not answered {@code DrainComplete} when the window ends is closed (as {@link #drain()}'s
     * stragglers are); its unacknowledged commands stay with the data plane. Only THIS process's sessions are reachable (AGW-D32).
     *
     * @return the {@code reconnect_after} told, or empty when NOTHING was sent: no accepted session here, or it is already draining
     */
    public java.util.Optional<Duration> drainAgent(UUID agentId, String reason) {
        if (reason == null || !DRAIN_REASON.matcher(reason).matches()) throw new IllegalArgumentException("drain reason is a short token");
        ConnectSession target = activeSessions.get(Objects.requireNonNull(agentId, "agentId"));
        if (target == null) return java.util.Optional.empty();
        Duration reconnectAfter = properties.getDrainReconnectMin();
        Duration window = properties.getDrainWindow();
        CompletableFuture<Boolean> done = target.beginDrain(reconnectAfter, window, reason);
        if (done == null) return java.util.Optional.empty();
        try {
            scheduler.schedule(() -> {
                if (!done.isDone()) target.forceClose("drain window elapsed");
            }, window.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            target.forceClose("drain window elapsed");        // the deadline cannot be armed: close now rather than leave it open
        }
        return java.util.Optional.of(reconnectAfter);
    }

    private static final java.util.regex.Pattern DRAIN_REASON = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");

    public boolean isDraining() { return draining; }

    /** Number of live agent sessions on this gateway. */
    public int liveSessions() { return activeSessions.size(); }

    private final class ConnectSession implements StreamObserver<AgentToGateway>, AgentLogBroker.Link, AgentConfigViewBroker.Link {
        private final StreamObserver<GatewayToAgent> response;
        private volatile AgentSessionRef session;
        private AgentIdentity identity;
        private ScheduledFuture<?> poller;
        private boolean accepted;
        /** The claim succeeded but the session is not accepted yet: it is waiting for the agent's LeaseRenew. */
        private boolean claimed;
        private boolean awaitingRenewal;
        private boolean streamClosed;
        private boolean drainingSession;
        private volatile ScheduledFuture<?> openTimer;
        private long lastRenewPromptNanos;
        /** The optional wire features this agent announced at SessionOpen (DISCOVERY, DECLARATIONS...). */
        private java.util.Set<AgentFeature> features = java.util.Set.of();
        /** The declarations last sent for each placement, without envelope: what makes "on change" exact (config metadata, never a payload). */
        private final Map<UUID, AgentGatewayOuterClass.TelemetryDeclarations> declarationsSent = new java.util.HashMap<>();
        private final CompletableFuture<Boolean> drained = new CompletableFuture<>();
        /** Plan 016: the agent advertised CAPABILITY_LOGS at SessionOpen (accepting the session accepts it; there is no per-capability field). */
        private volatile boolean logsSupported;
        /** Plan 017: the agent advertised CAPABILITY_CONFIG_VIEW at SessionOpen; only such a session is sent a config view request. */
        private volatile boolean configViewSupported;
        /** The placements (binding ids) of the lease last sent: the only ones a log request or retention may name. */
        private volatile Set<UUID> leasePlacements = Set.of();
        /** The retention cap last sent for each placement, so a lease event re-sends only what changed. */
        private final Map<UUID, Long> retentionSent = new java.util.HashMap<>();

        private final AgentRequestOrigin origin = origin();

        private ConnectSession(StreamObserver<GatewayToAgent> response) { this.response = response; }

        @Override public AgentSessionRef ref() { return session; }
        @Override public boolean logsSupported() { return logsSupported; }
        @Override public boolean configViewSupported() { return configViewSupported; }
        @Override public boolean inLease(UUID placementId) { return leasePlacements.contains(placementId); }

        @Override public synchronized boolean sendMessage(GatewayToAgent message) {
            if (streamClosed || !accepted) return false;
            send(message);
            return !streamClosed;
        }

        @Override public synchronized void pushRetention(UUID placementId, long maxBytes) {
            if (!accepted || !logsSupported || !leasePlacements.contains(placementId)) return;
            sendRetention(placementId, maxBytes);
        }

        private void sendRetention(UUID placementId, long maxBytes) {
            send(GatewayToAgent.newBuilder().setLogRetention(AgentGatewayOuterClass.LogRetention.newBuilder()
                    .setEnvelope(envelope()).setPlacementRef(placementId.toString()).setMaxBytes(maxBytes)).build());
            retentionSent.put(placementId, maxBytes);
        }

        /**
         * Sends each stored retention cap that differs from what this session was last sent, for the placements of its lease only (the
         * agent does not check the lease, M-3). Runs with the declarations push: at open, on each renewal or re-issue, and on the data
         * plane's change signal. A read failure never fails the session; the next lease event tries again.
         */
        private void sendLogRetentions() {
            if (!logsSupported) return;
            try {
                Map<UUID, Long> stored = ports.logRetention().capsFor(session);
                for (UUID placement : leasePlacements) {
                    Long cap = stored.get(placement);
                    if (cap == null || cap.equals(retentionSent.get(placement))) continue;
                    sendRetention(placement, cap);
                }
            } catch (RuntimeException failure) {
                LOG.warn("agent-gateway could not read log retention caps (will retry at the next lease event): {}",
                        failure.getClass().getName());
            }
        }

        @Override public synchronized void onNext(AgentToGateway message) {
            try {
                if (!accepted) {
                    if (awaitingRenewal) {
                        // Design §4 case (3): a lease exists but has expired. Nothing but the renewal is meaningful yet.
                        if (message.hasLeaseRenew()) renewBeforeAccept(message.getLeaseRenew());
                        else if (message.hasTeardown()) onCompleted();
                        return;
                    }
                    if (!message.hasSessionOpen()) throw refused(GatewayCode.UNAUTHENTICATED_IDENTITY, "SessionOpen must be first");
                    open(message.getSessionOpen());
                    return;
                }
                if (message.hasLeaseRenew()) renew(message.getLeaseRenew());
                else if (message.hasCommandPull()) offer(message.getCommandPull());
                else if (message.hasCommandReceived()) ports.commands().received(session, message.getCommandReceived().getEnvelope().getMessageId(),
                        CommandId.parse(message.getCommandReceived().getCommandId()), message.getCommandReceived().getGeneration());
                else if (message.hasCommandAck()) ack(message.getCommandAck());
                else if (message.hasActualStateReportBatch()) report(message.getActualStateReportBatch());
                else if (message.hasDiscoveryReport()) discover(message.getDiscoveryReport());
                else if (message.hasHeartbeat()) heartbeat(message.getHeartbeat());
                else if (message.hasDrainComplete()) drainComplete(message.getDrainComplete());
                else if (message.hasConfigViewReply()) configViews.onReply(session, message.getConfigViewReply());
                else if (message.hasTeardown()) onCompleted();
            } catch (AdmissionRefused limited) {
                metrics.admissionRejected(limited.reason.name().toLowerCase(java.util.Locale.ROOT));
                sendError(GatewayCode.ADMISSION_LIMITED, "", limited.retryAfter);
                closeIfNotOpen();
            } catch (GatewayPortException e) {
                LOG.warn("agent-gateway refused a message: {}", e.code());
                metrics.claimRefused();
                String correlation = message.hasSessionOpen() ? message.getSessionOpen().getEnvelope().getMessageId() : envelopeId(message);
                auditRefusal(correlation, e);
                if (accepted && e.code() == GatewayCode.IDENTITY_REVOKED) {
                    // Any durable write can be the first to notice a revocation (the sweep may not have run yet).
                    revokeAndClose(0, 0, "identity-revoked");
                } else if (accepted && e.code() == GatewayCode.STALE_EPOCH) {
                    supersedeOrRevoke();
                } else {
                    sendError(e.code(), correlation);
                    closeIfNotOpen();
                }
            }
            catch (RuntimeException e) {
                // Class only: the message of a persistence/protocol failure can echo payload or SQL detail.
                LOG.error("agent-gateway stream handler failed: {}", e.getClass().getName());
                sendError(GatewayCode.INTERNAL_UNAVAILABLE, "");
                closeIfNotOpen();
            }
        }

        /**
         * A refusal of the open (admission, draining, unknown identity, protocol version, a CP read failure...) ends the stream. The real
         * agent closes its side on any of these; a client that does not would otherwise hold a server stream open forever. Established
         * sessions are not closed by a refused message — only a stream that never became a session.
         */
        private void closeIfNotOpen() {
            if (!accepted && !awaitingRenewal) onCompleted();
        }

        /** A stream that has not sent a usable {@code SessionOpen} within {@code session-open-timeout} is closed. */
        private synchronized void openTimeout() {
            if (!accepted && !streamClosed) {
                // Also a stream awaiting its LeaseRenew: every reconnect now waits for one, and an agent that never sends it must not hold a stream.
                LOG.debug("agent-gateway closed a stream that sent no usable SessionOpen or LeaseRenew in time");
                if (awaitingRenewal) audit("gateway.refusal", GatewayCode.LEASE_RENEW_REQUIRED, "renewal-timeout-" + UUID.randomUUID(),
                        "agent-renewal-timeout");
                onCompleted();
            }
        }

        private String envelopeId(AgentToGateway message) {
            if (message.hasLeaseRenew()) return message.getLeaseRenew().getEnvelope().getMessageId();
            if (message.hasCommandPull()) return message.getCommandPull().getEnvelope().getMessageId();
            if (message.hasCommandReceived()) return message.getCommandReceived().getEnvelope().getMessageId();
            if (message.hasCommandAck()) return message.getCommandAck().getEnvelope().getMessageId();
            if (message.hasActualStateReportBatch()) return message.getActualStateReportBatch().getEnvelope().getMessageId();
            if (message.hasDiscoveryReport()) return message.getDiscoveryReport().getEnvelope().getMessageId();
            if (message.hasHeartbeat()) return message.getHeartbeat().getEnvelope().getMessageId();
            if (message.hasTeardown()) return message.getTeardown().getEnvelope().getMessageId();
            if (message.hasDrainComplete()) return message.getDrainComplete().getEnvelope().getMessageId();
            return "";
        }

        private void auditRefusal(String requestId, GatewayPortException error) {
            if (session == null || requestId == null || requestId.isBlank()) return;
            try { ports.audit().record(requestId, session, origin, "gateway.refusal", error.code(), "agent-session", Instant.now()); }
            catch (RuntimeException ignored) { }
        }

        private void open(AgentGatewayOuterClass.SessionOpen open) {
            if (draining) {
                // A gateway that is going away does not take new sessions; the agent retries after the stagger floor.
                throw new GatewayPortException(GatewayCode.DRAINING, "gateway is draining");
            }
            if (open.getProtocolVersion() != 1) {
                throw refused(GatewayCode.PROTOCOL_VERSION_UNSUPPORTED, "protocol version is not supported");
            }
            // Admission precedes identity resolution, so a storm costs no port call (design §5.6).
            var globalRefusal = admission.admit(activeSessions.size());
            if (globalRefusal.isPresent()) throw admissionLimited(globalRefusal.get());
            String thumbprint = thumbprints.resolve();
            identity = thumbprint == null ? null : ports.identity().resolve(thumbprint).orElse(null);
            if (identity == null) throw refused(GatewayCode.UNAUTHENTICATED_IDENTITY, "client certificate is not enrolled");
            if (identity.revoked()) throw refused(GatewayCode.IDENTITY_REVOKED, "identity is revoked");
            var agentRefusal = admission.admitAgent(identity.agentId().toString());
            if (agentRefusal.isPresent()) throw admissionLimited(agentRefusal.get());
            AgentSessionRef claimed = ports.sessions().claim(thumbprint, sessionOpening(open), origin);
            session = claimed;
            this.claimed = true;
            this.features = sessionOpening(open).features();
            this.logsSupported = open.getCapabilitiesSupportedList().contains(AgentGatewayOuterClass.Capability.CAPABILITY_LOGS);
            this.configViewSupported = open.getCapabilitiesSupportedList().contains(AgentGatewayOuterClass.Capability.CAPABILITY_CONFIG_VIEW);
            LeaseGrant lease;
            try {
                // current() never returns a grant from the data plane's store (only the nonce digest is kept): any lease row means
                // LEASE_RENEW_REQUIRED, none means a first-ever session (design 4.6, OR-136 (3)). A reconnect therefore costs a
                // renewal round trip and one control-plane call; a control-plane outage blocks reconnect (accepted for v1).
                lease = ports.leases().current(claimed).orElse(null);
                if (lease == null) {
                    lease = ports.leases().issue("lease-" + open.getEnvelope().getMessageId(), claimed, instant(open.getAgentClock()), origin);
                }
            } catch (GatewayPortException needsRenewal) {
                // ISSUE_NOT_ALLOWED here is a lease row appearing between current() and issue(), OR a first-issue refusal the control
                // plane folds into that code (not enrolled, certificate mismatch or expired). It is never forwarded raw (the agent halts
                // on it); the agent is asked to renew with its last grant's nonce. An agent that holds NO grant (a lost first
                // SessionAccept) cannot renew and is stranded until the open timeout closes the stream; that case is audited (see
                // openTimeout) and routed to the owner: a replay through issue() cannot recover it, because the nonce is bound to the
                // first attempt's request id and a reconnect's SessionOpen has a new one.
                if (needsRenewal.code() != GatewayCode.LEASE_RENEW_REQUIRED && needsRenewal.code() != GatewayCode.ISSUE_NOT_ALLOWED) {
                    throw needsRenewal;
                }
                awaitingRenewal = true;
                sendError(GatewayCode.LEASE_RENEW_REQUIRED, open.getEnvelope().getMessageId());
                return;
            }
            finishOpen(lease);
        }

        /** The renewal an expired-lease session waited for: success completes the open; a refusal ends it as design §4 says. */
        private void renewBeforeAccept(AgentGatewayOuterClass.LeaseRenew renew) {
            LeaseGrant grant;
            try {
                grant = ports.leases().renew("renew-" + renew.getEnvelope().getMessageId(), session, renew.getNonceConsumed(),
                        instant(renew.getAgentClock()), origin);
            } catch (GatewayPortException refusedRenewal) {
                // Every outcome of a refused renewal ends this stream, so the wait is over (otherwise closeIfNotOpen and openTimeout
                // would both leave an orphaned stream that is invisible to the sweep, drain() and pollTick()).
                awaitingRenewal = false;
                if (!reEnrollRefusal(refusedRenewal.code())) {
                    // Anything else (a control-plane outage, a behind DP, a revocation, a superseded session...) goes to the caller's
                    // catch as its own coded Error and closes the stream. The agent keeps its lease and retries (ClassRetry/ClassAct) or
                    // halts on a revocation; only the refusals below delete the lease.
                    throw refusedRenewal;
                }
                rejectForReEnrolment(renew.getEnvelope().getMessageId());
                return;
            }
            awaitingRenewal = false;
            finishOpen(grant);
        }

        /**
         * The refusals that mean "this agent's grant can no longer be renewed", the only ones that tell it to enrol again:
         * the nonce is not honoured (LEASE_RENEW_REFUSED), or the control plane's certificate-class refusal that the lease issuer folds into
         * ISSUE_NOT_ALLOWED on a renewal (not enrolled, certificate mismatch or expired, a fleet bump the DP is merely behind on).
         */
        private boolean reEnrollRefusal(GatewayCode code) {
            return code == GatewayCode.LEASE_RENEW_REFUSED || code == GatewayCode.ISSUE_NOT_ALLOWED
                    || code == GatewayCode.RE_ENROLL_REQUIRED;
        }

        private void rejectForReEnrolment(String messageId) {
            audit("gateway.refusal", GatewayCode.RE_ENROLL_REQUIRED, "reenroll-" + messageId);
            send(GatewayToAgent.newBuilder().setSessionReject(AgentGatewayOuterClass.SessionReject.newBuilder()
                    .setEnvelope(envelope()).setCode(error(GatewayCode.RE_ENROLL_REQUIRED))).build());
            onCompleted();
        }

        private void finishOpen(LeaseGrant lease) {
            if (openTimer != null) openTimer.cancel(false);
            if (draining) {
                // This stream was waiting for its renewal when drain() took its snapshot, so it was never told to drain.
                sendError(GatewayCode.DRAINING, "");
                onCompleted();
                return;
            }
            ConnectSession previous = activeSessions.put(identity.agentId(), this);
            if (previous != null && previous != this) previous.supersede();
            accepted = true;
            leasePlacements = Set.copyOf(lease.placementIds());
            metrics.sessionClaimed();
            response.onNext(GatewayToAgent.newBuilder().setSessionAccept(AgentGatewayOuterClass.SessionAccept.newBuilder()
                    .setEnvelope(envelope()).setSessionId("session-" + session.fencingEpoch())
                    .setAssignedTargetId(identity.targetId().toString()).addAllRequiredCapabilities(identity.capabilities().stream().map(AgentGatewayService::capability).toList())
                    .setLease(lease(lease)).setLimits(limits(properties, logsSupported)).setServerClock(timestamp(Instant.now())).build()).build());
            sendDeclarations();
            // No poll of its own at open: the next shared tick (within poll-interval) tells this session about anything already queued,
            // and a reconnect storm must not buy several database statements per session before the session is even running.
        }

        private final AtomicBoolean polling = new AtomicBoolean();

        /** Queues one full poll of this session unless one is already queued or running. */
        void schedulePoll() {
            if (!polling.compareAndSet(false, true)) return;
            try {
                pollers.execute(() -> {
                    try { poll(); }
                    finally { polling.set(false); }
                });
            } catch (RejectedExecutionException shuttingDown) {
                polling.set(false);
            }
        }

        synchronized void promptRenewalNow() {
            if (accepted) promptRenewal();
        }

        private synchronized void poll() {
            if (!accepted || drainingSession) return;
            try { for (CommandNotice notice : ports.commands().pendingFor(session, properties.getPollBatchSize(), properties.getNoticeRetryInterval())) {
                metrics.noticeSent();
                var noticeBuilder = AgentGatewayOuterClass.GenerationAvailable.newBuilder()
                        .setEnvelope(envelope()).setCommandId(notice.commandId().toString()).setBindingId(notice.placementId().toString())
                        .setGeneration(notice.generation()).setOperation(operation(notice.operation()))
                        .setDeadline(timestamp(notice.deadline()));
                // After an SM restart a notice carries no plain digest (T2.1b fix-round (b)); the agent compares at pull.
                if (notice.configSha256() != null) noticeBuilder.setConfigSha256(wireHash(notice.configSha256()));
                if (notice.correlation() != null) noticeBuilder.setCorrelation(correlation(notice.correlation()));
                response.onNext(GatewayToAgent.newBuilder().setGenerationAvailable(noticeBuilder.build()).build());
            }} catch (GatewayPortException refused) {
                // A superseded or revoked session must be told, not left holding a stream that does nothing.
                if (refused.code() == GatewayCode.STALE_EPOCH) supersedeOrRevoke();
                else if (refused.code() == GatewayCode.IDENTITY_REVOKED) revokeAndClose(0, 0, "identity-revoked");
                else if (refused.code() == GatewayCode.LEASE_RENEW_REQUIRED) promptRenewal();
                // anything else: the next tick re-evaluates
            } catch (RuntimeException failure) {
                // An uncaught exception would silently cancel every later tick of the scheduled poll. Class only:
                // the message can carry command or payload detail.
                LOG.error("agent-gateway poll failed (will retry): {}", failure.getClass().getName());
            }
        }

        /**
         * The lease is no longer usable (expired, or renewals were refused for a while and it lapsed). The agent has no
         * timer that retries a renewal from LEASE_EXPIRED, so unless it is told it would stay fail-closed until it next
         * reconnected, with the DP perfectly healthy. Prompt it, at most once per notice-retry interval; its LeaseRenew
         * carries the nonce of its last grant, so the single-use renewal semantics are untouched.
         */
        private void promptRenewal() {
            long now = System.nanoTime();
            if (lastRenewPromptNanos != 0 && now - lastRenewPromptNanos < properties.getNoticeRetryInterval().toNanos()) return;
            lastRenewPromptNanos = now;
            sendError(GatewayCode.LEASE_RENEW_REQUIRED, "");
        }

        private void offer(AgentGatewayOuterClass.CommandPull pull) {
            metrics.pull();
            var command = ports.commands().pull(session, CommandId.parse(pull.getCommandId()));
            var builder = AgentGatewayOuterClass.CommandOffer.newBuilder().setEnvelope(envelope()).setCommandId(command.commandId().toString())
                    .setGeneration(command.generation()).setIdempotencyKey(command.idempotencyKey()).setBindingId(command.placementId().toString())
                    .setOperation(operation(command.operation())).setPayloadBytes(ByteString.copyFrom(command.payload().bytes()))
                    .setDeadline(timestamp(command.deadline()));
            if (command.payload().configSha256() != null) builder.setConfigSha256(wireHash(command.payload().configSha256()));
            // REMATERIALIZE's write precondition (T2.3): present on that operation, "" meaning "must still be absent". An absent field there
            // is a protocol error the agent refuses (KEY_HASH_MISMATCH).
            if (command.payload().expectedCurrentSha256() != null) {
                builder.setExpectedCurrentSha256(wireHash(command.payload().expectedCurrentSha256()));
            }
            if (command.correlation() != null) builder.setCorrelation(correlation(command.correlation()));
            if (command.supersedesGeneration() != null) builder.setSupersedesGeneration(command.supersedesGeneration());
            response.onNext(GatewayToAgent.newBuilder().setCommandOffer(builder).build());
        }

        private void ack(AgentGatewayOuterClass.CommandAck ack) {
            AgentOutcome outcome = AgentOutcome.valueOf(ack.getOutcome().name().replace("OUTCOME_", ""));
            // The data plane stores this as detail_code, bounded to [A-Za-z][A-Za-z0-9._]{0,63}; anything outside that grammar is stored as
            // "invalid.detail" and the refusal's reason is lost. An error detail therefore travels as the bare error code ("TARGET_MOVED",
            // "KEY_HASH_MISMATCH"), which is the vocabulary the adapter classifies on; any other detail keeps its own name.
            // ACK_DETAIL_CODE_UNSPECIFIED (the real agent's codec always sets a detail, even for a message alone) is an absent code.
            String detailCode = !ack.hasDetail() ? ""
                    : ack.getDetail().getCode() == AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_ERROR
                            ? ack.getDetail().getErrorCode().name().replace("ERROR_CODE_", "")
                            : ack.getDetail().getCode() == AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_UNSPECIFIED ? ""
                            : ack.getDetail().getCode().name();
            // T1.2 review note 7: agent free text is bounded and sanitised here, and is never logged or echoed.
            String detail = ack.hasDetail() ? boundedDetail(ack.getDetail().getMessage()) : "";
            ports.commands().ack(session, CommandId.parse(ack.getCommandId()), new AgentAck(ack.getEnvelope().getMessageId(),
                    ack.getGeneration(), outcome, detailCode, detail,
                    ack.hasContainerStartedAt() ? instant(ack.getContainerStartedAt()) : null));
            metrics.commandAcked();
            if (outcome == AgentOutcome.REFUSED) {
                // A refusal by the agent (hash mismatch, capability, stale generation...) is a security-relevant fact; the
                // request id is the command and generation so a replayed terminal ack records it once.
                GatewayCode code = GatewayCode.UNKNOWN;
                if (ack.hasDetail() && ack.getDetail().getCode() == AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_ERROR) {
                    try { code = GatewayCode.valueOf(ack.getDetail().getErrorCode().name().replace("ERROR_CODE_", "")); }
                    catch (IllegalArgumentException unmapped) { code = GatewayCode.UNKNOWN; }
                }
                audit("gateway.command.refused", code, "refused-" + ack.getCommandId() + "-" + ack.getGeneration());
            }
        }

        private void report(AgentGatewayOuterClass.ActualStateReportBatch batch) {
            List<ReportItem> items = batch.getItemsList().stream().map(x -> new ReportItem(x.getItemId(), x.getSequence(),
                    x.getBindingId(), x.getReplicaRef(), x.getIncarnation(), x.getPersistedGeneration(),
                    x.hasRestartObservedAt() ? instant(x.getRestartObservedAt()) : null, x.hasEffectiveHash() ? dpHash(x.getEffectiveHash()) : null,
                    x.getVersionObservation().getMechanism(), x.getVersionObservation().getValue(), x.getVersionObservation().getConfidence(),
                    x.hasCorrelation() ? correlation(x.getCorrelation()) : null, x.hasHealth() ? health(x.getHealth()) : null,
                    x.hasContainerStartedAt() ? instant(x.getContainerStartedAt()) : null)).toList();
            List<ReportResult> results = ports.reports().report(session, items, origin);
            response.onNext(GatewayToAgent.newBuilder().setReportAck(AgentGatewayOuterClass.ReportAck.newBuilder().setEnvelope(envelope())
                    .setInReplyToMessageId(batch.getEnvelope().getMessageId()).addAllResults(results.stream().map(AgentGatewayService::reportResult).toList())).build());
        }

        private void heartbeat(AgentGatewayOuterClass.Heartbeat heartbeat) {
            ports.sessions().heartbeat(session, instant(heartbeat.getAgentClock()));
            response.onNext(GatewayToAgent.newBuilder().setHeartbeatAck(AgentGatewayOuterClass.HeartbeatAck.newBuilder()
                    .setEnvelope(envelope()).setServerClock(timestamp(Instant.now())).setClockState(AgentGatewayOuterClass.ClockState.CLOCK_STATE_OK)).build());
        }

        private void renew(AgentGatewayOuterClass.LeaseRenew renew) {
            LeaseGrant grant;
            try {
                grant = ports.leases().renew("renew-" + renew.getEnvelope().getMessageId(), session,
                        renew.getNonceConsumed(), instant(renew.getAgentClock()), origin);
            } catch (GatewayPortException refusedRenewal) {
                if (!reEnrollRefusal(refusedRenewal.code())) throw refusedRenewal;
                // Never forwarded raw (ISSUE_NOT_ALLOWED is a Halt to the agent): the same answer as before the accept.
                rejectForReEnrolment(renew.getEnvelope().getMessageId());
                return;
            }
            leasePlacements = Set.copyOf(grant.placementIds());
            logs.placementsLeft(session, leasePlacements);
            configViews.placementsLeft(session, leasePlacements);
            response.onNext(GatewayToAgent.newBuilder().setLeaseGrant(lease(grant)).build());
            sendDeclarations();
        }

        private void sendError(GatewayCode code, String correlation) {
            sendError(code, correlation, null);
        }

        private void sendError(GatewayCode code, String correlation, Duration retryAfter) {
            var error = AgentGatewayOuterClass.Error.newBuilder()
                    .setEnvelope(envelope()).setCode(error(code)).setCorrelationId(correlation).setMessage(code.name());
            if (retryAfter != null) error.setRetryAfter(duration(retryAfter));
            else if (code == GatewayCode.DRAINING) error.setRetryAfter(duration(drainRetryAfter));
            send(GatewayToAgent.newBuilder().setError(error).build());
        }

        /** A send that cannot fail the caller: the stream may already be closed by a concurrent teardown. */
        private void send(GatewayToAgent message) {
            if (streamClosed) return;
            try { response.onNext(message); }
            catch (RuntimeException closed) { LOG.debug("agent-gateway send after close: {}", closed.getClass().getName()); }
        }

        /**
         * Sends the planned-drain notice; the returned future completes true when the agent answered.
         *
         * <p>The durable audit write is deliberately done <em>outside</em> this session's monitor (review finding,
         * 2026-10-07): {@code onNext} is itself {@code synchronized} on this same instance, so every inbound message
         * from this agent (heartbeat, ack, {@code DrainComplete} itself) would otherwise queue behind a JDBC insert
         * for no correctness reason — nothing depends on the audit landing before the {@code Drain} notice is on the
         * wire, or before the next inbound message is processed.
         */
        CompletableFuture<Boolean> beginDrain(Duration reconnectAfter, Duration deadline) {
            CompletableFuture<Boolean> sent = beginDrain(reconnectAfter, deadline, "planned-restart");
            return sent == null ? drained : sent;
        }

        /** As above with the reason carried in the notice and the audit row; NULL when nothing was sent (not accepted, or already draining). */
        CompletableFuture<Boolean> beginDrain(Duration reconnectAfter, Duration deadline, String reason) {
            if (!sendDrainNotice(reconnectAfter, deadline, reason)) return null;
            // The assigned stagger is recorded so an operator (and the fleet drill) can see how a drain spread the fleet.
            audit("gateway.drain", GatewayCode.DRAINING, "gateway.drain-" + UUID.randomUUID(),
                    "reconnect-after-ms=" + reconnectAfter.toMillis() + ";deadline-ms=" + deadline.toMillis()
                            + ("planned-restart".equals(reason) ? "" : ";reason=" + reason));
            return drained;
        }

        /** The state transition and the send, under the session's monitor; returns true the first time this session is told to drain. */
        private synchronized boolean sendDrainNotice(Duration reconnectAfter, Duration deadline, String reason) {
            if (!accepted || drainingSession) return false;
            drainingSession = true;
            if (poller != null) poller.cancel(false);
            send(GatewayToAgent.newBuilder().setDrain(AgentGatewayOuterClass.Drain.newBuilder().setEnvelope(envelope())
                    .setReason(reason).setReconnectAfter(duration(reconnectAfter))
                    .setDeadline(duration(deadline))).build());
            return true;
        }

        private void drainComplete(AgentGatewayOuterClass.DrainComplete complete) {
            metrics.drainAbandoned(complete.getAbandonedCommands());
            audit("gateway.drain.complete", GatewayCode.DRAINING);
            drained.complete(true);
            onCompleted();
        }

        synchronized void forceClose(String reason) {
            if (!accepted) { drained.complete(false); return; }
            send(GatewayToAgent.newBuilder().setTeardown(AgentGatewayOuterClass.Teardown.newBuilder()
                    .setEnvelope(envelope()).setReason(reason)).build());
            drained.complete(false);
            onCompleted();
        }

        /**
         * A STALE_EPOCH on a write is ambiguous: a newer session took over, or a revocation closed this session (it revokes the
         * identity and closes the session row in one transaction, so a write in flight meets the closed session and the port
         * contract refuses it STALE_EPOCH). Ask which (plan 015 Final, W5 m-2): a revoked identity gets Revoke and the
         * gateway.revoked audit, a superseded one the supersede. A failed read falls back to the supersede.
         *
         * <p>The REVOKED answer rests on an invariant of the real repositories, not on this class: the data plane's revocation closes
         * the session and marks the identity revoked in ONE transaction, so a session that meets the closed row still reconciles as
         * REVOKED (a session closed without a revocation reconciles STALE). It is pinned over the real
         * {@code JdbcAgentIdentityRepository} and {@code JdbcAgentSessionRepository} by
         * {@code JdbcAgentRevocationReconcileIT} (dp-core): a later change that marks the identity after (or apart from) closing the
         * session fails that test, not silently this method's Revoke.
         */
        void supersedeOrRevoke() {
            try {
                for (IdentityReconciliation result : ports.identity().reconcile(List.of(session))) {
                    if (result.session().equals(session) && result.status() == IdentityReconciliation.Status.REVOKED) {
                        revokeAndClose(result.agentRevocationEpoch(), result.fleetRevocationEpoch(), "identity-revoked");
                        return;
                    }
                }
            } catch (RuntimeException unreadable) {
                // fall through: the supersede is what this always did
            }
            supersede();
        }

        /** Tells the agent a newer session owns it, and ends this one. */
        synchronized void supersede() {
            if (!accepted) return;
            sendError(GatewayCode.STALE_EPOCH, "");
            onCompleted();
        }

        /** Revocation: the agent is told to stop and disconnect, its lease is already gone, and every stream ends. */
        synchronized void revokeAndClose(long agentEpoch, long fleetEpoch, String reason) {
            if (!accepted) return;
            var revoke = AgentGatewayOuterClass.Revoke.newBuilder().setEnvelope(envelope()).setReason(reason);
            if (agentEpoch > 0) revoke.setAgentRevocationEpoch(agentEpoch);
            if (fleetEpoch > 0) revoke.setFleetRevocationEpoch(fleetEpoch);
            send(GatewayToAgent.newBuilder().setRevoke(revoke).build());
            metrics.sessionRevoked();
            audit("gateway.revoked", GatewayCode.IDENTITY_REVOKED);
            Set<Runnable> aborts = telemetryAborts.remove(session.agentId());
            if (aborts != null) aborts.forEach(Runnable::run);
            onCompleted();
        }

        private final AtomicBoolean pushQueued = new AtomicBoolean();
        private final AtomicBoolean pushDirty = new AtomicBoolean();

        /** At most one queued push per session; a signal during a run sets {@code pushDirty} and the run loops once more. */
        void scheduleDeclarationsPush() {
            pushDirty.set(true);
            if (!pushQueued.compareAndSet(false, true)) return;
            try {
                pollers.execute(() -> {
                    try {
                        while (pushDirty.getAndSet(false)) {
                            try {
                                reconcileOne(this);
                                pushDeclarations();
                            } catch (RuntimeException failure) {
                                LOG.warn("agent-gateway could not push declarations: {}", failure.getClass().getName());
                            }
                        }
                    } finally {
                        pushQueued.set(false);
                        if (pushDirty.get()) scheduleDeclarationsPush();      // a signal landed between the last check and the reset
                    }
                });
            } catch (RejectedExecutionException shuttingDown) {
                pushQueued.set(false);
            }
        }

        /** The change signal for this session: re-reads the declared sets and sends only what differs (nothing, if unchanged). */
        synchronized void pushDeclarations() {
            if (!accepted || drainingSession) return;
            sendDeclarations();
        }

        /** Pushes a re-issued lease (a permission change took effect); the agent adopts it as it would a renewal. */
        synchronized void pushLease(LeaseGrant grant) {
            if (!accepted || grant == null) return;
            metrics.leaseReissued();
            leasePlacements = Set.copyOf(grant.placementIds());
            logs.placementsLeft(session, leasePlacements);
            configViews.placementsLeft(session, leasePlacements);
            send(GatewayToAgent.newBuilder().setLeaseGrant(lease(grant)).build());
            sendDeclarations();
        }

        /**
         * Tells the agent what to collect, to a session that announced DECLARATIONS: each placement's COMPLETE set, in full, whenever it
         * differs from what was last sent; a placement that is gone is cleared with an empty set (the set replaces atomically). The check
         * runs when the session opens, whenever its lease is renewed or re-issued, and (plan 015 W4 lane D) when the data plane signals
         * a change ({@link #pushDeclarations}); the renewal stays the backstop for a lost signal. A read failure never fails the session:
         * the next lease event or signal tries again.
         */
        private void sendDeclarations() {
            sendTelemetryDeclarations();
            sendLogRetentions();
        }

        private void sendTelemetryDeclarations() {
            if (!features.contains(AgentFeature.DECLARATIONS)) return;
            try {
                Map<UUID, AgentGatewayOuterClass.TelemetryDeclarations> current = new java.util.LinkedHashMap<>();
                for (TelemetryDeclaration declaration : ports.declarations().forAgent(session)) {
                    current.put(declaration.placementId(), declarations(declaration));
                }
                for (UUID gone : new ArrayList<>(declarationsSent.keySet())) {
                    if (!current.containsKey(gone)) {
                        current.put(gone, AgentGatewayOuterClass.TelemetryDeclarations.newBuilder().setBindingId(gone.toString())
                                .setInterval(duration(Duration.ofSeconds(5))).build());
                    }
                }
                for (var entry : current.entrySet()) {
                    AgentGatewayOuterClass.TelemetryDeclarations wanted = entry.getValue();
                    boolean empty = wanted.getMetricsCount() == 0 && !wanted.hasHealthProbe();
                    if (empty ? !declarationsSent.containsKey(entry.getKey()) : wanted.equals(declarationsSent.get(entry.getKey()))) continue;
                    send(GatewayToAgent.newBuilder().setTelemetryDeclarations(wanted.toBuilder().setEnvelope(envelope())).build());
                    if (empty) declarationsSent.remove(entry.getKey());
                    else declarationsSent.put(entry.getKey(), wanted);
                }
            } catch (RuntimeException failure) {
                LOG.warn("agent-gateway could not read telemetry declarations (will retry at the next lease event): {}",
                        failure.getClass().getName());
            }
        }

        private void discover(AgentGatewayOuterClass.DiscoveryReport report) {
            List<DiscoveredContainer> items = report.getItemsList().stream().map(AgentGatewayService::discovered).toList();
            List<DiscoveryResult> results = ports.discovery().report(session, report.getEnvelope().getMessageId(), items, origin);
            response.onNext(GatewayToAgent.newBuilder().setDiscoveryAck(AgentGatewayOuterClass.DiscoveryAck.newBuilder()
                    .setEnvelope(envelope()).setInReplyToMessageId(report.getEnvelope().getMessageId())
                    .addAllResults(results.stream().map(AgentGatewayService::discoveryResult).toList())).build());
        }

        private void audit(String eventType, GatewayCode code) {
            audit(eventType, code, eventType + "-" + UUID.randomUUID());
        }

        private void audit(String eventType, GatewayCode code, String requestId) {
            audit(eventType, code, requestId, "agent-session");
        }

        private void audit(String eventType, GatewayCode code, String requestId, String subject) {
            if (session == null) return;
            try { ports.audit().record(requestId, session, origin, eventType, code, subject, Instant.now()); }
            catch (RuntimeException ignored) { }
        }

        @Override public synchronized void onError(Throwable t) { close(); drained.complete(false); }
        @Override public synchronized void onCompleted() {
            boolean wasOpen = !streamClosed;
            close();
            if (wasOpen) {
                streamClosed = true;
                try { response.onCompleted(); } catch (RuntimeException alreadyClosed) { /* peer went first */ }
            }
        }
        private void close() {
            if (poller != null) poller.cancel(false);
            if (session != null && claimed) {
                try { ports.sessions().close(session, CloseReason.STREAM_CLOSED); }
                catch (RuntimeException ignored) { /* the durable row is fenced by epoch; release is best effort */ }
            }
            if (session != null && accepted) {
                logs.sessionClosed(session);
                configViews.sessionClosed(session);
                activeSessions.remove(session.agentId(), this);
                metrics.sessionReleased();
            }
            claimed = false;
            awaitingRenewal = false;
            accepted = false;
            streamClosed = true;
            drained.complete(false);
        }
    }

    private static AgentGatewayOuterClass.Envelope envelope() { return AgentGatewayOuterClass.Envelope.newBuilder()
            .setMessageId(UUID.randomUUID().toString()).setSentAt(timestamp(Instant.now())).build(); }
    private static com.google.protobuf.Duration duration(Duration value) {
        return com.google.protobuf.Duration.newBuilder().setSeconds(value.getSeconds()).setNanos(value.getNano()).build();
    }
    private static Timestamp timestamp(Instant instant) { return Timestamps.fromMillis(instant.toEpochMilli()); }
    private static AgentGatewayOuterClass.OperationCorrelation correlation(OperationCorrelation source) {
        return AgentGatewayOuterClass.OperationCorrelation.newBuilder().setApplyId(source.applyId())
                .setIntentId(source.intentId()).setAttemptId(source.attemptId()).setStep(source.step()).build();
    }
    private static OperationCorrelation correlation(AgentGatewayOuterClass.OperationCorrelation source) {
        return new OperationCorrelation(source.getApplyId(), source.getIntentId(), null, source.getAttemptId(), source.getStep());
    }
    private static Instant instant(Timestamp timestamp) { return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()); }
    /**
     * A session that advertised log capability is given at least one initial credit: the agent starts every log export with
     * {@code initial_credits} itself, and with 0 its first credit could race the request (spec 4.1). Telemetry, which has its own
     * credit pool in the gateway, is unaffected.
     */
    private static AgentGatewayOuterClass.Limits limits(AgentGatewayProperties properties, boolean logs) { return AgentGatewayOuterClass.Limits.newBuilder().setMaxMessageBytes(properties.getMaxMessageBytes())
            .setMaxBatchBytes(Math.min(properties.getMaxMessageBytes(), 4 * 1024 * 1024)).setInitialCredits(logs ? Math.max(1, properties.getInitialTelemetryCredits()) : properties.getInitialTelemetryCredits()).setHeartbeatInterval(com.google.protobuf.Duration.newBuilder().setSeconds(30))
            .setKeepaliveInterval(com.google.protobuf.Duration.newBuilder().setSeconds(30)).setSkewTolerance(com.google.protobuf.Duration.newBuilder().setSeconds(30))
            .setReportInterval(com.google.protobuf.Duration.newBuilder().setSeconds(30)).build(); }
    private static AgentGatewayOuterClass.LeaseGrant lease(LeaseGrant source) {
        var builder = AgentGatewayOuterClass.LeaseGrant.newBuilder();
        // The agent keys ceilings by lowercase operation name and derives every step deadline from them;
        // an absent ceiling is zero, i.e. "deadline exceeded" the instant a command is accepted.
        source.verbCeilings().forEach((operation, ceiling) -> builder.putVerbCeilings(
                operation.name().toLowerCase(java.util.Locale.ROOT), com.google.protobuf.Duration.newBuilder()
                        .setSeconds(ceiling.toSeconds()).setNanos(ceiling.toNanosPart()).build()));
        return builder.setEnvelope(envelope()).setAgentId(source.agentId().toString())
                .setCnfThumbprint(source.certificateThumbprint()).setTargetId(source.targetId().toString()).addAllBindingIds(source.placementIds().stream().map(UUID::toString).toList())
                .setAgentRevocationEpoch(source.agentRevocationEpoch()).setFleetRevocationEpoch(source.fleetRevocationEpoch())
                .setIssuedAt(timestamp(source.issuedAt())).setExpiresAt(timestamp(source.expiresAt())).setAudience(source.audience())
                .setNonce(source.nonce()).addAllCapabilities(source.capabilities().stream().map(AgentGatewayService::capability).toList())
                .setAdmissionMargin(com.google.protobuf.Duration.newBuilder().setNanos((int) (source.admissionMargin().toNanos() % 1_000_000_000)).setSeconds(source.admissionMargin().toSeconds()))
                .setServerClock(timestamp(source.serverClock())).build();
    }
    private static AgentGatewayOuterClass.ReportResult reportResult(ReportResult source) {
        var b = AgentGatewayOuterClass.ReportResult.newBuilder().setInReplyToItemId(source.itemId()).setSequence(source.sequence())
                .setTrust(source.trust().equals("TRUSTED") ? AgentGatewayOuterClass.Trust.TRUST_TRUSTED : AgentGatewayOuterClass.Trust.TRUST_UNTRUSTED)
                .setDisposition(source.disposition().equals("APPLIED") ? AgentGatewayOuterClass.Disposition.DISPOSITION_APPLIED
                        : source.disposition().equals("STALE") ? AgentGatewayOuterClass.Disposition.DISPOSITION_STALE : AgentGatewayOuterClass.Disposition.DISPOSITION_REFUSED);
        if (source.replicaId() != null) b.setReplicaId(source.replicaId());
        if (source.incarnationId() != null) b.setIncarnationId(source.incarnationId());
        if (source.refusedReason() != null) b.setRefusedReason(error(source.refusedReason()));
        return b.build();
    }
    private static AgentGatewayOuterClass.Capability capability(AgentCapability value) { return AgentGatewayOuterClass.Capability.valueOf("CAPABILITY_" + value.name()); }
    private static AgentGatewayOuterClass.Operation operation(AgentOperation value) { return AgentGatewayOuterClass.Operation.valueOf("OPERATION_" + value.name()); }
    static AgentGatewayOuterClass.ErrorCode error(GatewayCode value) {
        if (value == GatewayCode.STALE_EPOCH) return AgentGatewayOuterClass.ErrorCode.ERROR_CODE_SESSION_SUPERSEDED;
        // Thrown at pull by the payload source; a per-command refusal for the agent, not the retry a fallback code would be.
        if (value == GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED) return AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RENDER_CHANGED;
        try { return AgentGatewayOuterClass.ErrorCode.valueOf("ERROR_CODE_" + value.name()); }
        catch (IllegalArgumentException e) { return AgentGatewayOuterClass.ErrorCode.ERROR_CODE_INTERNAL_UNAVAILABLE; }
    }
    /** An admission refusal: coded {@code ADMISSION_LIMITED}, carrying the wait the agent must honour. */
    static final class AdmissionRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final AdmissionLimiter.Reason reason;
        final Duration retryAfter;
        AdmissionRefused(AdmissionLimiter.Refusal refusal) {
            super("admission limited", null, false, false);
            this.reason = refusal.reason();
            this.retryAfter = refusal.retryAfter();
        }
    }

    private static AdmissionRefused admissionLimited(AdmissionLimiter.Refusal refusal) { return new AdmissionRefused(refusal); }

    private static GatewayPortException refused(GatewayCode code, String message) { return new GatewayPortException(code, message); }
    private static void fail(StreamObserver<?> response, GatewayPortException error) { response.onError(Status.FAILED_PRECONDITION.withDescription(error.code().name()).asRuntimeException()); }
    private static StreamObserver<AgentGatewayOuterClass.TelemetryUp> ignoredTelemetry() { return new StreamObserver<>() { public void onNext(AgentGatewayOuterClass.TelemetryUp x) {} public void onError(Throwable x) {} public void onCompleted() {} }; }

    private static AgentTelemetryBatch telemetry(AgentGatewayOuterClass.TelemetryBatch source) {
        return new AgentTelemetryBatch(source.getStreamId(), source.getBatchSeq(), source.getSamplesList().stream().map(x -> new AgentTelemetryBatch.Sample(
                x.getSeriesKey(), x.getType().name().replace("METRIC_TYPE_", "").toLowerCase(), instant(x.getTs()), x.getValue(), x.getLabelsMap())).toList(),
                source.getGapMarkersList().stream().map(x -> new AgentTelemetryBatch.Gap(x.getFromSeq(), x.getToSeq(), x.getReason())).toList(), instant(source.getProducedAt()));
    }

    /** The facts of a SessionOpen the DP persists at claim; unknown capability values (older proto) are not features. */
    private static SessionOpening sessionOpening(AgentGatewayOuterClass.SessionOpen open) {
        java.util.EnumSet<AgentFeature> features = java.util.EnumSet.noneOf(AgentFeature.class);
        for (AgentGatewayOuterClass.Capability capability : open.getCapabilitiesSupportedList()) {
            try { features.add(AgentFeature.valueOf(capability.name().replace("CAPABILITY_", ""))); }
            catch (IllegalArgumentException notAFeature) { /* APPLY_CONFIG etc. are capabilities, not features */ }
        }
        return new SessionOpening(open.getProtocolVersion(), open.getAgentVersion(), features, instant(open.getAgentClock()));
    }

    private static final java.util.regex.Pattern BARE_ADDRESS = java.util.regex.Pattern.compile("^[A-Za-z0-9:._%-]{1,64}$");

    /**
     * Minted per RPC from the gRPC peer — never from a value the agent sent (BE-D70, OR-25). The interceptor passes the bare IP
     * literal; anything else (it should not happen) degrades to {@code unknown} rather than failing the call.
     */
    private static AgentRequestOrigin origin() {
        String address = MetadataContext.CLIENT_ADDRESS.get();
        return new AgentRequestOrigin(UUID.randomUUID(), address != null && BARE_ADDRESS.matcher(address).matches() ? address : "unknown");
    }

    static final class MetadataContext {
        static final io.grpc.Context.Key<String> CLIENT_ADDRESS = io.grpc.Context.key("sm-client-address");
        static final io.grpc.Context.Key<String> TOKEN = io.grpc.Context.key("sm-enrollment-token");
        static final io.grpc.Context.Key<String> THUMBPRINT = io.grpc.Context.key("sm-agent-thumbprint");
    }

    /**
     * The wire carries bare lowercase hex (as the agent computes it); the DP's canonical form (HF18: {@code AgentObservation},
     * {@code CommandPayload}) is {@code sha256:<hex>}. Outbound this strips the prefix. The data plane keeps the reported value in
     * memory only, so the prefix never put a plain digest into storage (OR-32); the keyed bridge converts it there.
     */
    static String wireHash(String dpHash) {
        return dpHash != null && dpHash.startsWith("sha256:") ? dpHash.substring("sha256:".length()) : dpHash;
    }


    private static final java.util.regex.Pattern PLAIN_SHA256 = java.util.regex.Pattern.compile("^[0-9a-f]{64}$");

    /** Inbound: exactly 64 lowercase hex characters become {@code sha256:<hex>}; anything else is {@code null} (agent text is never passed through). */
    static String dpHash(String wireHash) {
        return wireHash != null && PLAIN_SHA256.matcher(wireHash).matches() ? "sha256:" + wireHash : null;
    }

    /** Printable ASCII only, at most {@link #MAX_DETAIL_CHARS}: agent free text reaches no table, log or answer unbounded or raw. */
    static String boundedDetail(String raw) {
        return boundedText(raw, MAX_DETAIL_CHARS);
    }

    private static String boundedText(String raw, int max) {
        if (raw == null || raw.isEmpty()) return "";
        int length = Math.min(raw.length(), max);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            out.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return out.toString();
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String prefix, String name, E fallback) {
        try { return Enum.valueOf(type, name.startsWith(prefix) ? name.substring(prefix.length()) : name); }
        catch (IllegalArgumentException unmapped) { return fallback; }
    }

    private static AgentHealthObservation health(AgentGatewayOuterClass.HealthObservation source) {
        return new AgentHealthObservation(
                enumOr(AgentHealthObservation.State.class, "HEALTH_STATE_", source.getState().name(), AgentHealthObservation.State.UNSPECIFIED),
                source.hasObservedAt() ? instant(source.getObservedAt()) : null,
                enumOr(AgentHealthObservation.Strength.class, "PROBE_STRENGTH_", source.getProbeStrength().name(),
                        AgentHealthObservation.Strength.UNSPECIFIED),
                source.getHttpStatus(), boundedText(source.getActuatorStatus(), 64),
                enumOr(AgentHealthObservation.Failure.class, "PROBE_FAILURE_", source.getFailure().name(), AgentHealthObservation.Failure.NONE));
    }

    /** Facts, not conclusions (AGW-D30): only {@code com.solace.sm.} labels are kept, and nothing here resolves tenant or instance. */
    private static DiscoveredContainer discovered(AgentGatewayOuterClass.DiscoveredContainer source) {
        Map<String, String> labels = new java.util.LinkedHashMap<>();
        source.getLabelsMap().forEach((key, value) -> { if (key.startsWith("com.solace.sm.")) labels.put(key, value); });
        return new DiscoveredContainer(source.getContainerId(), source.getContainerName(), source.getImageRef(), labels,
                source.getMountsList().stream().map(m -> new DiscoveredContainer.Mount(m.getSource(), m.getDestination())).toList(),
                source.getPublishedPortsList().stream().map(p -> new DiscoveredContainer.PublishedPort(p.getContainerPort(), p.getHostPort())).toList());
    }

    private static AgentGatewayOuterClass.DiscoveryResult discoveryResult(DiscoveryResult source) {
        var result = AgentGatewayOuterClass.DiscoveryResult.newBuilder().setContainerId(source.containerId())
                .setState(AgentGatewayOuterClass.DiscoveryState.valueOf("DISCOVERY_STATE_" + source.state().name()));
        if (source.code() != null) result.setCode(source.code().wire());
        if (source.placementId() != null) result.setBindingId(source.placementId().toString());
        return result.build();
    }

    private static AgentGatewayOuterClass.MetricType metricType(String name) {
        try { return AgentGatewayOuterClass.MetricType.valueOf("METRIC_TYPE_" + name.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException | NullPointerException unmapped) { return AgentGatewayOuterClass.MetricType.METRIC_TYPE_UNSPECIFIED; }
    }

    private static AgentGatewayOuterClass.TelemetryDeclarations declarations(TelemetryDeclaration source) {
        var builder = AgentGatewayOuterClass.TelemetryDeclarations.newBuilder().setBindingId(source.placementId().toString())
                .setInterval(duration(source.interval()));
        for (TelemetryDeclaration.Metric metric : source.metrics()) {
            builder.addMetrics(AgentGatewayOuterClass.TelemetryMetric.newBuilder().setName(metric.name())
                    .setMetricType(metricType(metric.metricType()))
                    .setUnit(metric.unit() == null || metric.unit().isBlank() ? "1" : metric.unit())
                    .setHttpJson(AgentGatewayOuterClass.HttpJsonSource.newBuilder().setPort(metric.containerPort()).setPath(metric.path())
                            .setPointer(metric.pointer() == null ? "" : metric.pointer())
                            .setOnUnavailable(metric.degradeWhenUnavailable() ? AgentGatewayOuterClass.OnUnavailable.ON_UNAVAILABLE_DEGRADE
                                    : AgentGatewayOuterClass.OnUnavailable.ON_UNAVAILABLE_ERROR)));
        }
        if (source.healthProbe() != null) {
            var probe = source.healthProbe();
            builder.setHealthProbe(AgentGatewayOuterClass.HealthProbe.newBuilder()
                    .setKind("actuator-health".equals(probe.kind()) ? AgentGatewayOuterClass.ProbeKind.PROBE_KIND_ACTUATOR_HEALTH
                            : "http-status".equals(probe.kind()) ? AgentGatewayOuterClass.ProbeKind.PROBE_KIND_HTTP_STATUS
                            : AgentGatewayOuterClass.ProbeKind.PROBE_KIND_UNSPECIFIED)
                    .setPath(probe.path()).setHostPort(probe.hostPort())
                    .setStrength(AgentGatewayOuterClass.ProbeStrength.valueOf("PROBE_STRENGTH_" + probe.strength().name())));
        }
        return builder.build();
    }

    /** Class of the root cause only (a message can carry payload or SQL detail); no JDBC types in the gateway. */
    private static String rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return "root=" + root.getClass().getName();
    }
}
