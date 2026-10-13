package com.solace.sm.launcher;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import solace.sm.agentgateway.v1draft.AgentGatewayGrpc;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.AgentToGateway;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;

/**
 * A protocol-faithful agent without a host (plan 015 T4.4, ported onto the amended wire): it enrols over the bootstrap TLS surface exactly
 * as the Go agent does (EC key, CSR, the one-time token the console issued), connects with mTLS, opens a session, REPORTS ITS ONE
 * CONTAINER (OR-27 discovery: facts only) and so is given a placement, renews its lease to learn it, heartbeats, answers
 * {@code GenerationAvailable} with a pull, a {@code CommandReceived} and, after a configurable "operation time", a terminal
 * {@code CommandAck} that mirrors what a host would have done (an apply moves the file hash, a restart moves the start time), reports actual
 * state on a cadence, and streams telemetry under the credit protocol with the three closed labels the DP minted for it. It exists to put
 * many of those on one gateway for the capacity measurement; it does NOT replace the real agent in any correctness drill.
 *
 * <p>Everything it learns is recorded in {@link Stats}: counts by outcome and latency reservoirs for the protocol's round trips.
 */
final class SyntheticAgent {

    /** Shared, lock-free measurements for a whole fleet. */
    static final class Stats {
        final AtomicLong enrolled = new AtomicLong(), sessionsOpened = new AtomicLong(), sessionsClosed = new AtomicLong();
        final AtomicLong refusedAdmission = new AtomicLong(), refusedOther = new AtomicLong(), errors = new AtomicLong();
        final AtomicLong commandsOffered = new AtomicLong(), commandsAcked = new AtomicLong();
        final AtomicLong heartbeats = new AtomicLong(), reports = new AtomicLong();
        final AtomicLong telemetryBatches = new AtomicLong(), telemetryAcked = new AtomicLong(), telemetryRejected = new AtomicLong();
        final AtomicLong drains = new AtomicLong();
        final Map<String, AtomicLong> errorCodes = new ConcurrentHashMap<>();
        final Latency sessionOpen = new Latency(), heartbeat = new Latency(), report = new Latency(), telemetry = new Latency(),
                enrol = new Latency(), noticeToOffer = new Latency();

        /** Expected protocol events (a renewal prompt, an accepted report, a close during a restart): counted apart from the errors. */
        final Map<String, AtomicLong> notes = new ConcurrentHashMap<>();

        void note(String what) {
            notes.computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
        }

        void error(String code) {
            errors.incrementAndGet();
            errorCodes.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /** A fixed-capacity reservoir of nanosecond samples; the first N samples win, which is enough for a bounded run. */
    static final class Latency {
        private final AtomicLongArray samples = new AtomicLongArray(2_000_000);
        private final AtomicLong next = new AtomicLong();

        void record(long nanos) {
            long i = next.getAndIncrement();
            if (i < samples.length()) {
                samples.set((int) i, nanos);
            }
        }

        int count() {
            return (int) Math.min(next.get(), samples.length());
        }

        /** {@code p50, p95, p99, max} in milliseconds over the samples recorded since {@code fromIndex}. */
        double[] percentiles(int fromIndex) {
            int n = count();
            if (n <= fromIndex) {
                return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
            }
            long[] copy = new long[n - fromIndex];
            for (int i = fromIndex; i < n; i++) {
                copy[i - fromIndex] = samples.get(i);
            }
            java.util.Arrays.sort(copy);
            return new double[] {at(copy, 0.50), at(copy, 0.95), at(copy, 0.99), copy[copy.length - 1] / 1e6};
        }

        private static double at(long[] sorted, double q) {
            return sorted[Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1)] / 1e6;
        }
    }

    /** What a fleet shares: where to connect, who to trust, and how the synthetic hosts behave. */
    record Config(String host, int port, File trustBundle, java.time.Duration heartbeatInterval,
            java.time.Duration reportInterval, java.time.Duration telemetryInterval, int samplesPerBatch,
            java.time.Duration operationTime, ScheduledExecutorService clock, Stats stats, String configRoot) {
    }

    private final Config config;
    final int index;
    final String agentId;
    final String token;
    final String label;
    final String containerName;
    final String containerId = (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()).replace("-", "").substring(0, 64);
    /** The placement id the data plane made for the discovered container: this agent's binding and telemetry stream. Null until REGISTERED. */
    private volatile String binding;
    private volatile String leaseNonce = "";
    /** One renewal in flight at a time: a second one presents the nonce the first just consumed, which the gateway answers RE_ENROLL_REQUIRED. */
    private final java.util.concurrent.atomic.AtomicBoolean renewing = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String replicaId;
    private volatile String incarnationId;
    private volatile String fileSha = "";
    private volatile Timestamp startedAt = Timestamps.fromMillis(System.currentTimeMillis());
    private volatile AgentGatewayOuterClass.OperationCorrelation lastCorrelation;
    private volatile long lastGeneration;
    private PrivateKey privateKey;
    private X509Certificate[] chain;
    private ManagedChannel channel;
    private volatile StreamObserver<AgentToGateway> up;
    private volatile StreamObserver<AgentGatewayOuterClass.TelemetryUp> telemetryUp;
    private volatile boolean active;
    private volatile boolean stopped;
    private long openedAtNanos;
    private long reportSequence = 1_000_000;
    private long telemetrySequence;
    private volatile int credits;
    private final Map<Long, Long> telemetrySentAt = new ConcurrentHashMap<>();
    private final Map<String, Long> noticeAt = new ConcurrentHashMap<>();
    private java.util.concurrent.ScheduledFuture<?> heartbeatTask, reportTask, telemetryTask;
    private volatile long heartbeatSentAt;
    private volatile long reportSentAt;
    private final AtomicLong reconnectDelayMillis = new AtomicLong();

    SyntheticAgent(Config config, int index, String agentId, String token) {
        this.config = config;
        this.index = index;
        this.agentId = agentId;
        this.token = token;
        this.label = "inst-syn-" + index;
        this.containerName = "sm-syn-" + index;
    }

    /** The placement the data plane made for this agent's container; null before discovery registered it. */
    String placement() {
        return binding;
    }

    boolean isActive() {
        return active && binding != null;
    }

    // ---- enrolment ----

    void enrol() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        privateKey = pair.getPrivate();
        byte[] csr = new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=synthetic-" + index), pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())).getEncoded();

        ManagedChannel bootstrap = NettyChannelBuilder.forAddress(config.host(), config.port())
                .sslContext(GrpcSslContexts.forClient().trustManager(config.trustBundle()).build()).build();
        try {
            Metadata headers = new Metadata();
            headers.put(Metadata.Key.of("x-sm-enrollment-token", Metadata.ASCII_STRING_MARSHALLER), token);
            long started = System.nanoTime();
            var response = AgentGatewayGrpc.newBlockingStub(bootstrap).withInterceptors(new ClientInterceptor() {
                @Override
                public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                        CallOptions options, Channel next) {
                    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                        @Override
                        public void start(Listener<RespT> listener, Metadata metadata) {
                            metadata.merge(headers);
                            super.start(listener, metadata);
                        }
                    };
                }
            }).enroll(AgentGatewayOuterClass.EnrollRequest.newBuilder().setCsrDer(com.google.protobuf.ByteString.copyFrom(csr))
                    .setAgentVersion("synthetic").addProtocolVersionsSupported(1)
                    .setHostFacts(AgentGatewayOuterClass.HostFacts.newBuilder().setOs("linux").setArch("amd64").setRuntimeKind("synthetic"))
                    .build());
            config.stats().enrol.record(System.nanoTime() - started);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certs = new ArrayList<>();
            for (var der : response.getCertificateChainList()) {
                certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der.toByteArray())));
            }
            chain = certs.toArray(X509Certificate[]::new);
            config.stats().enrolled.incrementAndGet();
        } finally {
            bootstrap.shutdownNow();
        }
    }

    // ---- session ----

    void connect() throws Exception {
        if (stopped) {
            return;
        }
        if (channel == null) {
            channel = NettyChannelBuilder.forAddress(config.host(), config.port())
                    .sslContext(GrpcSslContexts.forClient().trustManager(config.trustBundle()).keyManager(privateKey, chain).build())
                    .keepAliveTime(60, TimeUnit.SECONDS).build();
        }
        openedAtNanos = System.nanoTime();
        up = AgentGatewayGrpc.newStub(channel).connect(new StreamObserver<GatewayToAgent>() {
            @Override
            public void onNext(GatewayToAgent message) {
                handle(message);
            }

            @Override
            public void onError(Throwable t) {
                config.stats().note("closed-with:" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage()).split("\\R")[0].replaceAll("[0-9]+", "N"));
                closed();
            }

            @Override
            public void onCompleted() {
                config.stats().note("closed-by-gateway");
                closed();
            }
        });
        send(AgentToGateway.newBuilder().setSessionOpen(AgentGatewayOuterClass.SessionOpen.newBuilder().setEnvelope(envelope())
                .setProtocolVersion(1).setAgentVersion("synthetic")
                .addCapabilitiesSupported(AgentGatewayOuterClass.Capability.CAPABILITY_APPLY_CONFIG)
                .addCapabilitiesSupported(AgentGatewayOuterClass.Capability.CAPABILITY_LIFECYCLE)
                .addCapabilitiesSupported(AgentGatewayOuterClass.Capability.CAPABILITY_DISCOVERY).setAgentClock(now())).build());
    }

    void stop() {
        stopped = true;
        active = false;
        cancelTasks();
        if (channel != null) {
            channel.shutdownNow();
        }
    }

    private void closed() {
        renewing.set(false);
        if (active) {
            config.stats().sessionsClosed.incrementAndGet();
        }
        active = false;
        cancelTasks();
        StreamObserver<AgentGatewayOuterClass.TelemetryUp> telemetry = telemetryUp;
        telemetryUp = null;
        if (telemetry != null) {
            try {
                telemetry.onCompleted();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
        if (!stopped) {
            long delay = reconnectDelayMillis.getAndSet(0);
            config.clock().schedule(() -> {
                try {
                    connect();
                } catch (Exception e) {
                    config.stats().error("connect:" + e.getClass().getSimpleName());
                }
            }, delay > 0 ? delay : 1000 + (index % 1000), TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTasks() {
        for (var task : new java.util.concurrent.ScheduledFuture<?>[] {heartbeatTask, reportTask, telemetryTask}) {
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private void handle(GatewayToAgent m) {
        var stats = config.stats();
        switch (m.getMsgCase()) {
            case SESSION_ACCEPT -> {
                renewing.set(false);
                stats.sessionOpen.record(System.nanoTime() - openedAtNanos);
                stats.sessionsOpened.incrementAndGet();
                active = true;
                credits = m.getSessionAccept().getLimits().getInitialCredits();
                leaseNonce = m.getSessionAccept().getLease().getNonce();
                long hb = config.heartbeatInterval().toMillis();
                heartbeatTask = config.clock().scheduleWithFixedDelay(this::heartbeat, hb / 2 + index % Math.max(1, hb / 2), hb, TimeUnit.MILLISECONDS);
                if (m.getSessionAccept().getLease().getBindingIdsList().contains(binding == null ? "" : binding)) {
                    startReporting();
                }
                discover();
            }
            case LEASE_GRANT -> {
                leaseNonce = m.getLeaseGrant().getNonce();
                renewing.set(false);
                if (binding != null && m.getLeaseGrant().getBindingIdsList().contains(binding)) {
                    startReporting();
                }
            }
            case DISCOVERY_ACK -> {
                for (var result : m.getDiscoveryAck().getResultsList()) {
                    if (result.getState() == AgentGatewayOuterClass.DiscoveryState.DISCOVERY_STATE_REGISTERED && !result.getBindingId().isEmpty()
                            && result.getContainerId().equals(containerId)) {
                        binding = result.getBindingId();
                        renewLease();       // a new placement reaches the agent's lease only at a renewal
                    } else if (result.getState() == AgentGatewayOuterClass.DiscoveryState.DISCOVERY_STATE_PENDING) {
                        config.clock().schedule(this::discover, 1, TimeUnit.SECONDS);
                    } else if (result.getState() == AgentGatewayOuterClass.DiscoveryState.DISCOVERY_STATE_REFUSED) {
                        stats.error("discovery:" + result.getCode());
                    }
                }
            }
            case GENERATION_AVAILABLE -> {
                var notice = m.getGenerationAvailable();
                noticeAt.put(notice.getCommandId(), System.nanoTime());
                send(AgentToGateway.newBuilder().setCommandPull(AgentGatewayOuterClass.CommandPull.newBuilder()
                        .setEnvelope(envelope()).setCommandId(notice.getCommandId())).build());
            }
            case COMMAND_OFFER -> {
                var offer = m.getCommandOffer();
                Long noticed = noticeAt.remove(offer.getCommandId());
                if (noticed != null) {
                    stats.noticeToOffer.record(System.nanoTime() - noticed);
                }
                stats.commandsOffered.incrementAndGet();
                send(AgentToGateway.newBuilder().setCommandReceived(AgentGatewayOuterClass.CommandReceived.newBuilder()
                        .setEnvelope(envelope()).setCommandId(offer.getCommandId()).setGeneration(offer.getGeneration())).build());
                config.clock().schedule(() -> finish(offer), config.operationTime().toMillis(), TimeUnit.MILLISECONDS);
            }
            case HEARTBEAT_ACK -> stats.heartbeat.record(System.nanoTime() - heartbeatSentAt);
            case REPORT_ACK -> {
                stats.report.record(System.nanoTime() - reportSentAt);
                for (var result : m.getReportAck().getResultsList()) {
                    String outcome = "report-result:" + result.getDisposition().name().replace("DISPOSITION_", "") + ":"
                            + result.getTrust().name().replace("TRUST_", "") + ":" + result.getRefusedReason().name().replace("ERROR_CODE_", "");
                    if (result.getDisposition() == AgentGatewayOuterClass.Disposition.DISPOSITION_APPLIED
                            && result.getTrust() == AgentGatewayOuterClass.Trust.TRUST_TRUSTED) {
                        stats.note(outcome);
                    } else {
                        stats.error(outcome);
                    }
                    if (result.hasReplicaId() && result.hasIncarnationId()) {
                        replicaId = result.getReplicaId();
                        incarnationId = result.getIncarnationId();
                        openTelemetry();           // a no-op while the stream is open; reopened after a reconnect
                    }
                }
            }
            case ERROR -> {
                var error = m.getError();
                String code = error.getCode().name().replace("ERROR_CODE_", "");
                if (error.getCode() == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_ADMISSION_LIMITED) {
                    stats.refusedAdmission.incrementAndGet();
                    reconnectDelayMillis.set(error.hasRetryAfter() ? Math.max(500, error.getRetryAfter().getSeconds() * 1000L) : 3000);
                    closeStream(); // what the real agent does on any refusal of its open: close, wait retry_after, dial again
                } else if (error.getCode() == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_LEASE_RENEW_REQUIRED) {
                    stats.note(code);
                    renewLease();
                } else if (error.getCode() == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_DRAINING) {
                    reconnectDelayMillis.set(error.hasRetryAfter() ? Math.max(500, error.getRetryAfter().getSeconds() * 1000L) : 3000);
                    stats.error(code);
                    closeStream();
                } else {
                    stats.error(code);
                }
            }
            case DRAIN -> {
                stats.drains.incrementAndGet();
                reconnectDelayMillis.set(Math.max(500, m.getDrain().getReconnectAfter().getSeconds() * 1000L));
                send(AgentToGateway.newBuilder().setDrainComplete(AgentGatewayOuterClass.DrainComplete.newBuilder()
                        .setEnvelope(envelope()).setInReplyToMessageId(m.getDrain().getEnvelope().getMessageId())).build());
            }
            case REVOKE, SESSION_REJECT -> {
                stats.error(m.getMsgCase().name() + (m.hasSessionReject() ? ":" + m.getSessionReject().getCode().name() : ""));
                stopped = true;
            }
            default -> { }
        }
    }

    private void closeStream() {
        StreamObserver<AgentToGateway> stream = up;
        if (stream != null) {
            try {
                synchronized (stream) {
                    stream.onCompleted();
                }
            } catch (RuntimeException alreadyClosed) {
                // the peer closed first
            }
        }
    }

    private void heartbeat() {
        heartbeatSentAt = System.nanoTime();
        config.stats().heartbeats.incrementAndGet();
        send(AgentToGateway.newBuilder().setHeartbeat(AgentGatewayOuterClass.Heartbeat.newBuilder().setEnvelope(envelope())
                .setAgentClock(now())).build());
    }

    /** One container, as the Docker adapter reports it: facts only (OR-27). Sent at session open and again while PENDING. */
    private void discover() {
        if (!active || binding != null) {
            return;
        }
        String source = config.configRoot() + "/" + containerName;
        send(AgentToGateway.newBuilder().setDiscoveryReport(AgentGatewayOuterClass.DiscoveryReport.newBuilder().setEnvelope(envelope())
                .addItems(AgentGatewayOuterClass.DiscoveredContainer.newBuilder().setContainerId(containerId).setContainerName(containerName)
                        .setImageRef("synthetic:capacity")
                        .putLabels("com.solace.sm.instance", label)
                        .putLabels("com.solace.sm.config-file", "/app/external/spring/config/application.yml")
                        .addMounts(AgentGatewayOuterClass.DiscoveredMount.newBuilder().setSource(source)
                                .setDestination("/app/external/spring/config"))
                        .addPublishedPorts(AgentGatewayOuterClass.DiscoveredPort.newBuilder().setContainerPort(8081)
                                .setHostPort(20_000 + index)))).build());
    }

    private void renewLease() {
        if (!renewing.compareAndSet(false, true)) {
            return;
        }
        send(AgentToGateway.newBuilder().setLeaseRenew(AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(envelope())
                .setNonceConsumed(leaseNonce).setAgentClock(now())).build());
    }

    private void startReporting() {
        if (reportTask != null && !reportTask.isDone()) {
            return;
        }
        long ri = config.reportInterval().toMillis();
        reportTask = config.clock().scheduleWithFixedDelay(this::report, 100 + index % 5000, ri, TimeUnit.MILLISECONDS);
    }

    /** What a host would have done, and then what it would report: an apply moves the file hash, a restart moves the start time. */
    private void finish(AgentGatewayOuterClass.CommandOffer offer) {
        var ack = AgentGatewayOuterClass.CommandAck.newBuilder().setEnvelope(envelope()).setCommandId(offer.getCommandId())
                .setGeneration(offer.getGeneration()).setAgentClock(now());
        lastCorrelation = offer.getCorrelation();
        lastGeneration = offer.getGeneration();
        switch (offer.getOperation()) {
            case OPERATION_PREFLIGHT -> ack.setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_PREFLIGHT_OK);
            case OPERATION_APPLY, OPERATION_REMATERIALIZE -> {
                fileSha = offer.getConfigSha256();
                ack.setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_APPLIED);
            }
            case OPERATION_RESTART -> {
                startedAt = now();
                ack.setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_APPLIED).setContainerStartedAt(startedAt);
            }
            default -> ack.setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_APPLIED);
        }
        send(AgentToGateway.newBuilder().setCommandAck(ack).build());
        config.stats().commandsAcked.incrementAndGet();
        report();          // the report that proves what the ack said: the engine's read-back waits for it
    }

    private void report() {
        String placement = binding;
        if (placement == null) {
            return;
        }
        reportSentAt = System.nanoTime();
        config.stats().reports.incrementAndGet();
        var item = AgentGatewayOuterClass.ActualStateReport.newBuilder().setItemId(UUID.randomUUID().toString())
                .setSequence(++reportSequence).setBindingId(placement).setReplicaRef(containerName).setIncarnation(containerId.substring(0, 12))
                .setPersistedGeneration(Math.max(1, lastGeneration)).setContainerStartedAt(startedAt)
                .setVersionObservation(AgentGatewayOuterClass.VersionObservation.newBuilder().setMechanism("none").setConfidence("none"))
                .setHealth(AgentGatewayOuterClass.HealthObservation.newBuilder()
                        .setState(AgentGatewayOuterClass.HealthState.HEALTH_STATE_HEALTHY).setObservedAt(now())
                        .setHttpStatus(200).setActuatorStatus("UP"));
        if (!fileSha.isEmpty()) {
            item.setEffectiveHash(fileSha);
        }
        if (lastCorrelation != null) {
            item.setCorrelation(lastCorrelation);
        }
        send(AgentToGateway.newBuilder().setActualStateReportBatch(AgentGatewayOuterClass.ActualStateReportBatch.newBuilder()
                .setEnvelope(envelope()).addItems(item)).build());
    }

    private void openTelemetry() {
        if (config.telemetryInterval().isZero() || telemetryUp != null) {
            return;
        }
        var stats = config.stats();
        telemetryUp = AgentGatewayGrpc.newStub(channel).telemetry(new StreamObserver<AgentGatewayOuterClass.TelemetryDown>() {
            @Override
            public void onNext(AgentGatewayOuterClass.TelemetryDown down) {
                switch (down.getMsgCase()) {
                    case TELEMETRY_ACK -> {
                        credits += down.getTelemetryAck().getCreditsGranted();
                        stats.telemetryAcked.incrementAndGet();
                        Long sent = telemetrySentAt.remove(down.getTelemetryAck().getHighWatermarkSeq());
                        if (sent != null) {
                            stats.telemetry.record(System.nanoTime() - sent);
                        }
                    }
                    case TELEMETRY_CREDIT -> credits += down.getTelemetryCredit().getCreditsGranted();
                    case TELEMETRY_REJECT -> {
                        stats.telemetryRejected.incrementAndGet();
                        credits++;
                    }
                    default -> { }
                }
            }

            @Override
            public void onError(Throwable t) {
                telemetryUp = null;
            }

            @Override
            public void onCompleted() {
                telemetryUp = null;
            }
        });
        long interval = config.telemetryInterval().toMillis();
        telemetryTask = config.clock().scheduleWithFixedDelay(this::telemetry, interval / 2 + index % Math.max(1, interval), interval, TimeUnit.MILLISECONDS);
    }

    private void telemetry() {
        var stream = telemetryUp;
        if (stream == null || credits <= 0) {
            return;
        }
        credits--;
        long seq = ++telemetrySequence;
        var batch = AgentGatewayOuterClass.TelemetryBatch.newBuilder().setEnvelope(envelope()).setStreamId(binding)
                .setBatchSeq(seq).setProducedAt(now());
        long base = System.currentTimeMillis();
        for (int i = 0; i < config.samplesPerBatch(); i++) {
            batch.addSamples(AgentGatewayOuterClass.Sample.newBuilder().setSeriesKey("cache.hit_rate")
                    .setType(AgentGatewayOuterClass.MetricType.METRIC_TYPE_GAUGE).setTs(Timestamps.fromMillis(base + i))
                    .setValue(0.5).putLabels("binding_id", binding).putLabels("replica_id", replicaId)
                    .putLabels("incarnation_id", incarnationId));
        }
        telemetrySentAt.put(seq, System.nanoTime());
        config.stats().telemetryBatches.incrementAndGet();
        try {
            synchronized (stream) {
                stream.onNext(AgentGatewayOuterClass.TelemetryUp.newBuilder().setTelemetryBatch(batch).build());
            }
        } catch (RuntimeException e) {
            telemetryUp = null;
        }
    }

    private void send(AgentToGateway message) {
        StreamObserver<AgentToGateway> stream = up;
        if (stream == null) {
            return;
        }
        try {
            synchronized (stream) {
                stream.onNext(message);
            }
        } catch (RuntimeException e) {
            config.stats().error("send:" + e.getClass().getSimpleName());
        }
    }

    private static AgentGatewayOuterClass.Envelope envelope() {
        return AgentGatewayOuterClass.Envelope.newBuilder().setMessageId(UUID.randomUUID().toString()).setSentAt(now()).build();
    }

    private static Timestamp now() {
        return Timestamps.fromMillis(System.currentTimeMillis());
    }

    @SuppressWarnings("unused")
    private static Duration seconds(long s) {
        return Duration.newBuilder().setSeconds(s).build();
    }
}
