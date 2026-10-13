package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryBatch;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.AgentTelemetryResult;
import com.solace.sm.dp.api.agw.AgentFeature;
import com.solace.sm.dp.api.agw.SessionOpening;
import com.solace.sm.dp.api.agw.CloseReason;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.CommandCompletion;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.TelemetryDeclaration;
import java.util.UUID;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandAckResult;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.ReportItem;
import com.solace.sm.dp.api.agw.ReportResult;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

class AgentGatewayServiceTest {
    @Test
    void sessionOpenAndPullUseOnlyPortResults() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        try {
            Ports ports = new Ports();
            AgentGatewayProperties properties = new AgentGatewayProperties();
            properties.setPollInterval(Duration.ofHours(1));
            AgentGatewayService service = new AgentGatewayService(ports.set(), properties, scheduler, () -> "thumb");
            CapturingObserver output = new CapturingObserver();
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> input = service.connect(output);
            input.onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setSessionOpen(
                    AgentGatewayOuterClass.SessionOpen.newBuilder().setEnvelope(envelope("open"))
                            .setProtocolVersion(1).setAgentVersion("test")).build());
            assertEquals("SESSION_ACCEPT", output.messages.get(0).getMsgCase().name());
            input.onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setCommandPull(
                    AgentGatewayOuterClass.CommandPull.newBuilder().setEnvelope(envelope("pull")).setCommandId(Ports.COMMAND.toString())).build());
            assertTrue(output.messages.stream().anyMatch(x -> x.hasCommandOffer()
                    && x.getCommandOffer().getPayloadBytes().equals(ByteString.copyFromUtf8("payload"))));
            input.onCompleted();
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void theEnrolmentRequestIdIsMintedPerRpcAndDerivedFromNeitherTheTokenNorTheCsr() throws Exception {
        // T1.2 review finding 7: the id was UUID.nameUUIDFromBytes(token:csrHex), an unkeyed MD5 of the token used as a replay key.
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        try {
            Ports ports = new Ports();
            AgentGatewayService service = new AgentGatewayService(ports.set(), new AgentGatewayProperties(), scheduler,
                    () -> "thumb");
            AgentGatewayOuterClass.EnrollRequest request = AgentGatewayOuterClass.EnrollRequest.newBuilder()
                    .setCsrDer(ByteString.copyFrom(new byte[] {1, 2, 3})).build();
            for (int i = 0; i < 2; i++) {
                io.grpc.Context.current().withValue(AgentGatewayService.MetadataContext.TOKEN, "the-same-token")
                        .withValue(AgentGatewayService.MetadataContext.CLIENT_ADDRESS, "203.0.113.5")
                        .run(() -> service.enroll(request, new StreamObserver<AgentGatewayOuterClass.EnrollResponse>() {
                            @Override public void onNext(AgentGatewayOuterClass.EnrollResponse value) { }
                            @Override public void onError(Throwable t) { }
                            @Override public void onCompleted() { }
                        }));
            }
            assertEquals(2, ports.enrollments.size());
            EnrollmentRequest first = ports.enrollments.get(0);
            EnrollmentRequest second = ports.enrollments.get(1);
            assertEquals("203.0.113.5", first.origin().clientAddress());
            assertTrue(first.requestId().startsWith("enroll-"));
            assertEquals("enroll-" + first.origin().correlationId(), first.requestId());
            assertEquals("enroll-" + second.origin().correlationId(), second.requestId());
            assertFalse(first.requestId().equals(second.requestId()),
                    "the same token and CSR yield a fresh id per RPC: nothing derived from them is a key");
            String md5Id = "enroll-" + UUID.nameUUIDFromBytes(("the-same-token:010203")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(first.requestId().equals(md5Id) || second.requestId().equals(md5Id));
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void telemetryReservesCreditsAndRefusesAfterTheDurablePoolIsEmpty() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        try {
            Ports ports = new Ports();
            ports.creditsGranted = 0;
            AgentGatewayProperties properties = new AgentGatewayProperties();
            properties.setPollInterval(Duration.ofHours(1));
            properties.setInitialTelemetryCredits(1);
            AgentGatewayService service = new AgentGatewayService(ports.set(), properties, scheduler, () -> "thumb");

            CapturingObserver sessionOutput = new CapturingObserver();
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> input = service.connect(sessionOutput);
            input.onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setSessionOpen(
                    AgentGatewayOuterClass.SessionOpen.newBuilder().setEnvelope(envelope("open-telemetry"))
                            .setProtocolVersion(1).setAgentVersion("test")).build());

            TelemetryObserver telemetryOutput = new TelemetryObserver();
            StreamObserver<AgentGatewayOuterClass.TelemetryUp> telemetry = service.telemetry(telemetryOutput);
            telemetry.onNext(telemetry(1));
            telemetry.onNext(telemetry(2));

            assertEquals(1, ports.ingested);
            assertTrue(telemetryOutput.messages.get(0).hasTelemetryAck());
            assertTrue(telemetryOutput.messages.get(1).hasTelemetryReject());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_OUT_OF_CREDITS,
                    telemetryOutput.messages.get(1).getTelemetryReject().getCode());
            input.onCompleted();
            telemetry.onCompleted();
        } finally {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    // ---- AGW-D18 planned drain ----

    static final class Fleet implements AutoCloseable {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        final Ports ports = new Ports();
        final AgentGatewayProperties properties = new AgentGatewayProperties();
        final AtomicReference<String> thumbprint = new AtomicReference<>("thumb");
        final AgentGatewayService service;
        final java.util.Map<String, CapturingObserver> outputs = new java.util.LinkedHashMap<>();
        /** Every observer an agent has had, oldest first (a reconnect replaces its entry in {@link #outputs}). */
        final java.util.Map<String, List<CapturingObserver>> history = new java.util.concurrent.ConcurrentHashMap<>();

        List<CapturingObserver> allOutputs(String agent) { return history.getOrDefault(agent, List.of()); }
        final java.util.Map<String, StreamObserver<AgentGatewayOuterClass.AgentToGateway>> inputs = new java.util.LinkedHashMap<>();

        Fleet() {
            this(p -> { });
        }

        Fleet(java.util.function.Consumer<AgentGatewayProperties> configure) {
            properties.setPollInterval(Duration.ofHours(1));
            configure.accept(properties);
            service = new AgentGatewayService(ports.set(), properties, scheduler, () -> thumbprint.get());
        }

        void connect(String agent) {
            connect(agent, "test", List.of(), "198.51.100.7");
        }

        /** Connects as the gRPC peer {@code peer} (the interceptor's context value), the agent announcing {@code features}. */
        void connect(String agent, String agentVersion, List<AgentGatewayOuterClass.Capability> features, String peer) {
            thumbprint.set(agent);
            CapturingObserver out = new CapturingObserver();
            java.util.concurrent.atomic.AtomicReference<StreamObserver<AgentGatewayOuterClass.AgentToGateway>> opened =
                    new java.util.concurrent.atomic.AtomicReference<>();
            io.grpc.Context.current().withValue(AgentGatewayService.MetadataContext.CLIENT_ADDRESS, peer)
                    .run(() -> opened.set(service.connect(out)));
            StreamObserver<AgentGatewayOuterClass.AgentToGateway> in = opened.get();
            in.onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setSessionOpen(
                    AgentGatewayOuterClass.SessionOpen.newBuilder().setEnvelope(envelope("open-" + agent))
                            .setProtocolVersion(1).setAgentVersion(agentVersion).addAllCapabilitiesSupported(features)).build());
            outputs.put(agent, out);
            history.computeIfAbsent(agent, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(out);
            inputs.put(agent, in);
        }

        @Override public void close() throws Exception {
            scheduler.shutdownNow();
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static AgentGatewayOuterClass.Drain drainOf(CapturingObserver out) {
        return out.messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasDrain)
                .map(AgentGatewayOuterClass.GatewayToAgent::getDrain).findFirst().orElse(null);
    }

    @Test
    void drainStaggersReconnectEvenlyAcrossTheSpreadAndClosesEachSessionWhenItAnswers() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofSeconds(10));
            fleet.properties.setDrainReconnectMin(Duration.ofSeconds(2));
            fleet.properties.setDrainReconnectSpread(Duration.ofSeconds(30));
            for (String agent : List.of("a1", "a2", "a3", "a4")) fleet.connect(agent);
            assertEquals(4, fleet.service.liveSessions());

            CompletableFuture<AgentGatewayService.DrainSummary> done = CompletableFuture.supplyAsync(fleet.service::drain);
            // every session is told at once, each with its own reconnect_after, all with the same deadline
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fleet.outputs.values().stream().anyMatch(o -> drainOf(o) == null) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            List<Long> reconnect = new java.util.ArrayList<>();
            for (var entry : fleet.outputs.entrySet()) {
                AgentGatewayOuterClass.Drain drain = drainOf(entry.getValue());
                assertNotNull(drain, entry.getKey() + " was not told to drain");
                assertEquals(10, drain.getDeadline().getSeconds());
                assertEquals("planned-restart", drain.getReason());
                reconnect.add(drain.getReconnectAfter().getSeconds());
            }
            assertEquals(List.of(2L, 12L, 22L, 32L), reconnect, "evenly staggered across [min, min + spread]");
            assertFalse(done.isDone(), "the gateway waits for the agents");

            fleet.inputs.values().forEach(in -> in.onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setDrainComplete(
                    AgentGatewayOuterClass.DrainComplete.newBuilder().setEnvelope(envelope("dc"))).build()));
            AgentGatewayService.DrainSummary summary = done.get(5, TimeUnit.SECONDS);
            assertEquals(new AgentGatewayService.DrainSummary(4, 4, 0), summary);
            assertEquals(0, fleet.service.liveSessions());
            assertEquals(4, fleet.ports.released.size(), "each session's ownership was released");
            assertTrue(fleet.ports.audit.contains("gateway.drain") && fleet.ports.audit.contains("gateway.drain.complete"));
        }
    }

    @Test
    void drainClosesSessionsThatDoNotAnswerWhenTheWindowEndsAndNeverHangs() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofMillis(300));
            fleet.connect("a1");
            fleet.connect("a2");
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setDrainComplete(
                    AgentGatewayOuterClass.DrainComplete.newBuilder().setEnvelope(envelope("dc"))).build());
            // a1 answers before the drain is even started (an agent that was idle); a2 never answers
            AgentGatewayService.DrainSummary summary = fleet.service.drain();
            assertEquals(1, summary.sessions(), "a1 already left");
            assertEquals(0, summary.answered());
            assertEquals(1, summary.forced());
            assertEquals(0, fleet.service.liveSessions());
            assertTrue(fleet.outputs.get("a2").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasTeardown),
                    "a straggler is told why it is being closed");
        }
    }

    // ---- plan 015 T4.3: the operator drains ONE agent ----

    @Test
    void drainAgentTellsOnlyThatAgentToDrainWithTheConfiguredReconnectAfterAndLeavesTheGatewayAcceptingSessions() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofSeconds(5));
            fleet.properties.setDrainReconnectMin(Duration.ofSeconds(7));
            fleet.connect("a1");
            fleet.connect("a2");

            assertEquals(java.util.Optional.of(Duration.ofSeconds(7)), fleet.service.drainAgent(Ports.id("a1"), "operator-drain"));

            AgentGatewayOuterClass.Drain drain = drainOf(fleet.outputs.get("a1"));
            assertNotNull(drain, "the named agent is told to drain");
            assertEquals(7, drain.getReconnectAfter().getSeconds());
            assertEquals(5, drain.getDeadline().getSeconds());
            assertNull(drainOf(fleet.outputs.get("a2")), "another agent's session is untouched");
            assertFalse(fleet.service.isDraining(), "the gateway itself keeps accepting sessions");
            assertTrue(fleet.ports.audit.contains("gateway.drain"));

            // it answers, and its session closes
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setDrainComplete(
                    AgentGatewayOuterClass.DrainComplete.newBuilder().setEnvelope(envelope("dc"))).build());
            assertEquals(1, fleet.service.liveSessions(), "a1 closed on DrainComplete; a2 is still connected");
        }
    }

    @Test
    void drainAgentOfAnAgentWithNoLiveSessionIsEmptyAndSendsNothing() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            assertEquals(java.util.Optional.empty(), fleet.service.drainAgent(Ports.id("never-connected"), "operator-drain"));
            assertNull(drainOf(fleet.outputs.get("a1")));
        }
    }

    @Test
    void drainAgentClosesASessionThatDoesNotAnswerWhenTheWindowEnds() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofMillis(300));
            fleet.connect("a1");
            assertTrue(fleet.service.drainAgent(Ports.id("a1"), "operator-drain").isPresent());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fleet.service.liveSessions() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, fleet.service.liveSessions(), "the straggler is cut, never left hanging");
            assertTrue(fleet.outputs.get("a1").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasTeardown));
        }
    }

    @Test
    void drainAgentWithAClosedSchedulerClosesTheSessionAtOnceInsteadOfThrowing() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            fleet.scheduler.shutdownNow();
            assertTrue(fleet.service.drainAgent(Ports.id("a1"), "operator-drain").isPresent(), "the Drain went out");
            assertEquals(0, fleet.service.liveSessions(), "and the deadline could not be armed, so the session is closed now");
        }
    }

    @Test
    void aDrainingGatewayRefusesNewSessionsWithDrainingAndARetryAfter() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofMillis(50));
            fleet.properties.setDrainReconnectMin(Duration.ofSeconds(3));
            fleet.service.drain();
            fleet.connect("late");
            var error = fleet.outputs.get("late").messages.get(0);
            assertTrue(error.hasError());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_DRAINING, error.getError().getCode());
            assertEquals(3, error.getError().getRetryAfter().getSeconds());
            assertEquals(0, fleet.service.liveSessions(), "no session was claimed");
        }
    }

    @Test
    void aDrainedSessionStopsPollingForNewWork() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.properties.setDrainWindow(Duration.ofSeconds(2));
            fleet.connect("a1");
            fleet.service.pollTick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (fleet.ports.pollCount.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(fleet.ports.pollCount.get() > 0, "a live session is polled when the shared read says it has work");
            CompletableFuture.runAsync(fleet.service::drain);
            while (drainOf(fleet.outputs.get("a1")) == null && System.nanoTime() < deadline) Thread.sleep(5);
            int before = fleet.ports.pollCount.get();
            for (int i = 0; i < 3; i++) { fleet.service.pollTick(); Thread.sleep(50); }
            assertEquals(before, fleet.ports.pollCount.get(), "no polling after Drain: the DP keeps the work for the next session");
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setDrainComplete(
                    AgentGatewayOuterClass.DrainComplete.newBuilder().setEnvelope(envelope("dc"))).build());
        }
    }

    @Test
    void aReplicaAtItsSessionCapRefusesTheNextSessionWithAdmissionLimitedAndARetryAfter() throws Exception {
        try (Fleet fleet = new Fleet(p -> p.setMaxSessions(1))) {
            fleet.connect("a1");
            fleet.connect("a2");
            var refused = fleet.outputs.get("a2").messages.get(0);
            assertTrue(refused.hasError());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_ADMISSION_LIMITED, refused.getError().getCode());
            assertTrue(refused.getError().getRetryAfter().getSeconds() >= 5, "told to wait: " + refused.getError().getRetryAfter());
            assertEquals(1, fleet.service.liveSessions(), "the refused agent never became a session");
            assertEquals(0, fleet.ports.claims.get() - 1, "and cost no claim");
        }
    }

    @Test
    void anAgentThatReclaimsTooFastIsRefusedWithoutAClaim() throws Exception {
        try (Fleet fleet = new Fleet(p -> p.setClaimMinInterval(Duration.ofSeconds(30)))) {
            fleet.connect("a1");
            fleet.inputs.get("a1").onCompleted();
            int claimsAfterFirst = fleet.ports.claims.get();
            fleet.connect("a1");
            var refused = fleet.outputs.get("a1").messages.get(0);
            assertTrue(refused.hasError());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_ADMISSION_LIMITED, refused.getError().getCode());
            assertEquals(claimsAfterFirst, fleet.ports.claims.get(), "no claim for the refused attempt");
        }
    }

    @Test
    void aStreamThatNeverBecameASessionIsClosedAfterItsRefusalAndAfterTheOpenTimeout() throws Exception {
        try (Fleet fleet = new Fleet(p -> { p.setMaxSessions(1); p.setSessionOpenTimeout(Duration.ofMillis(150)); })) {
            fleet.connect("a1");
            fleet.connect("a2"); // refused: ADMISSION_LIMITED
            assertTrue(fleet.outputs.get("a2").completed, "a refused open ends the stream; a client that does not close must not hold it");
            assertFalse(fleet.outputs.get("a1").completed, "an established session is untouched");

            // a stream that opens and then says nothing at all
            CapturingObserver silent = new CapturingObserver();
            fleet.service.connect(silent);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!silent.completed && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(silent.completed, "a stream with no SessionOpen is closed at the open timeout");
        }
    }

    // ---- an agent whose lease expired while it was away (design §4, SessionOpen lease resolution, case 3) ----

    @Test
    void anExpiredLeaseIsRenewedWithItsNonceBeforeTheSessionIsAcceptedNotRefusedIssueNotAllowed() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.leaseExpired = true;
            fleet.connect("a1");
            var first = fleet.outputs.get("a1").messages;
            assertEquals(1, first.size(), "no SessionAccept yet: " + first);
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_LEASE_RENEW_REQUIRED, first.get(0).getError().getCode());
            assertEquals(0, fleet.service.liveSessions(), "not a live session until it has a lease");

            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setLeaseRenew(
                    AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(envelope("renew")).setNonceConsumed("last-nonce")
                            .setAgentClock(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))).build());

            var accept = fleet.outputs.get("a1").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasSessionAccept).findFirst();
            assertTrue(accept.isPresent(), "SessionAccept follows the renewed grant");
            assertEquals("nonce", accept.get().getSessionAccept().getLease().getNonce());
            assertEquals(1, fleet.service.liveSessions());
        }
    }

    @Test
    void anExpiredLeaseWhoseNonceTheControlPlaneRefusesEndsInReEnrollRequired() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.leaseExpired = true;
            fleet.ports.renewRefusal = GatewayCode.LEASE_RENEW_REFUSED;
            fleet.connect("a1");
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setLeaseRenew(
                    AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(envelope("renew")).setNonceConsumed("consumed")
                            .setAgentClock(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))).build());
            var reject = fleet.outputs.get("a1").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasSessionReject).findFirst();
            assertTrue(reject.isPresent());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RE_ENROLL_REQUIRED, reject.get().getSessionReject().getCode());
            assertEquals(0, fleet.service.liveSessions());
            assertTrue(fleet.ports.released.contains("a1"), "the claimed epoch was released, not leaked");
        }
    }

    @Test
    void aRevocationDiscoveredDuringExpiredLeaseRenewalClosesTheStreamInsteadOfLeakingIt() throws Exception {
        // The agent was revoked while its lease was expired and it was awaiting renewal (never added to activeSessions,
        // since that only happens in finishOpen). If awaitingRenewal is not cleared before the IDENTITY_REVOKED refusal
        // propagates, closeIfNotOpen's "leave it alone, it's awaiting renewal" guard leaves the stream open forever —
        // invisible to the revocation sweep, drain() and pollTick() alike, because it was never registered as a session.
        try (Fleet fleet = new Fleet()) {
            fleet.ports.leaseExpired = true;
            fleet.connect("a1");
            assertEquals(0, fleet.service.liveSessions(), "not yet a live session: still awaiting renewal");

            fleet.ports.renewRefusal = GatewayCode.IDENTITY_REVOKED;
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setLeaseRenew(
                    AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(envelope("renew")).setNonceConsumed("last-nonce")
                            .setAgentClock(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))).build());

            assertTrue(fleet.outputs.get("a1").completed, "a stream revoked while awaiting renewal must still be torn down");
            assertEquals(0, fleet.service.liveSessions());
        }
    }

    @Test
    void aSharedPollReadDecidesWhichSessionsArePolledInFull() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.workFor = java.util.Set.of("a2");
            fleet.connect("a1");
            fleet.connect("a2");
            fleet.connect("a3");
            fleet.service.pollTick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (fleet.ports.polledAgents.size() < 1 && System.nanoTime() < deadline) Thread.sleep(5);
            Thread.sleep(100);
            assertEquals(java.util.List.of("a2"), fleet.ports.polledAgents, "only the agent the shared read named was polled");
            assertEquals(1, fleet.ports.sharedReads.get(), "one set-based read for the whole fleet");
        }
    }

    @Test
    void aLapsedLeaseFoundBySweepPromptsTheAgentToRenewEvenWithNothingQueued() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.RENEWAL_REQUIRED, null, 1, 1)).toList();
            fleet.service.reconcileSessions();
            var prompt = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasError).findFirst();
            assertTrue(prompt.isPresent());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_LEASE_RENEW_REQUIRED, prompt.get().getError().getCode());
            assertEquals(1, fleet.service.liveSessions(), "prompting is not ending the session");
        }
    }

    // ---- revocation and permission changes on live sessions ----

    @Test
    void revocationFoundBySweepSendsRevokeEndsTheSessionAndAbortsItsTelemetryStream() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            TelemetryObserverWithError telemetryOut = new TelemetryObserverWithError();
            fleet.service.telemetry(telemetryOut);
            AgentSessionRef ref = new AgentSessionRef(Ports.id("thumb"), 1);
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REVOKED, null, 7, 3)).toList();

            fleet.service.reconcileSessions();

            var revoke = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasRevoke).findFirst();
            assertTrue(revoke.isPresent(), "the agent is told to stop");
            assertEquals(7, revoke.get().getRevoke().getAgentRevocationEpoch());
            assertEquals(3, revoke.get().getRevoke().getFleetRevocationEpoch());
            assertEquals(0, fleet.service.liveSessions());
            assertTrue(fleet.ports.released.contains(Ports.name(ref.agentId())));
            assertTrue(fleet.ports.audit.contains("gateway.revoked"));
            assertNotNull(telemetryOut.error, "its telemetry stream is aborted too");
        }
    }

    @Test
    void aWriteThatNoticesRevocationFirstEndsTheSessionWithoutWaitingForTheSweep() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            fleet.ports.heartbeatRefusal = GatewayCode.IDENTITY_REVOKED;
            fleet.inputs.get("thumb").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setHeartbeat(
                    AgentGatewayOuterClass.Heartbeat.newBuilder().setEnvelope(envelope("hb"))).build());
            assertTrue(fleet.outputs.get("thumb").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasRevoke));
            assertEquals(0, fleet.service.liveSessions());
        }
    }

    /**
     * Plan 015 Final, W5 m-2 / finding 9: a revocation closes the session row, so a write in flight is refused STALE_EPOCH (the port
     * contract). The gateway asks which it is: a revoked identity gets Revoke and the gateway.revoked audit; a genuinely superseded
     * session still gets the supersede.
     */
    @Test
    void aWriteRefusedStaleBecauseARevocationClosedTheSessionIsTreatedAsARevocation() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            fleet.ports.heartbeatRefusal = GatewayCode.STALE_EPOCH;
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REVOKED, null, 7, 3)).toList();
            fleet.inputs.get("thumb").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setHeartbeat(
                    AgentGatewayOuterClass.Heartbeat.newBuilder().setEnvelope(envelope("hb"))).build());
            var revoke = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasRevoke).findFirst();
            assertTrue(revoke.isPresent(), "the agent is told it was revoked, not that it was superseded");
            assertEquals(7, revoke.get().getRevoke().getAgentRevocationEpoch());
            assertTrue(fleet.ports.audit.contains("gateway.revoked"));
            assertTrue(fleet.outputs.get("thumb").messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasError));
            assertEquals(0, fleet.service.liveSessions());
        }
    }

    @Test
    void aWriteRefusedStaleBecauseANewerSessionTookOverIsStillSuperseded() throws Exception {
        for (boolean readFails : new boolean[] {false, true}) {
            try (Fleet fleet = new Fleet()) {
                fleet.connect("thumb");
                fleet.ports.heartbeatRefusal = GatewayCode.STALE_EPOCH;
                fleet.ports.reconciler = sessions -> {
                    if (readFails) throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "cp down");
                    return sessions.stream().map(x -> new IdentityReconciliation(x,
                            IdentityReconciliation.Status.STALE, null, 0, 0)).toList();
                };
                fleet.inputs.get("thumb").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setHeartbeat(
                        AgentGatewayOuterClass.Heartbeat.newBuilder().setEnvelope(envelope("hb"))).build());
                var error = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasError).findFirst();
                assertTrue(error.isPresent(), "readFails=" + readFails);
                assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_SESSION_SUPERSEDED, error.get().getError().getCode());
                assertTrue(fleet.outputs.get("thumb").messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasRevoke));
                assertTrue(!fleet.ports.audit.contains("gateway.revoked"));
            }
        }
    }

    @Test
    void aPermissionChangePushesTheReissuedLeaseWithoutEndingTheSession() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            LeaseGrant narrower = new LeaseGrant(Ports.id("agent-1"), "thumb", Ports.TARGET, List.of(), 1, 1, Instant.now(),
                    Instant.now().plusSeconds(300), "dp", "nonce-2", List.of(AgentCapability.LIFECYCLE), Map.of(),
                    Duration.ZERO, Instant.now());
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REISSUED, narrower, 1, 1)).toList();

            fleet.service.reconcileSessions();

            var grants = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasLeaseGrant).toList();
            assertEquals(1, grants.size());
            assertEquals("nonce-2", grants.get(0).getLeaseGrant().getNonce());
            assertEquals(0, grants.get(0).getLeaseGrant().getBindingIdsCount());
            assertEquals(1, fleet.service.liveSessions(), "the session stays up");
        }
    }

    @Test
    void aSupersededSessionIsToldSoAndClosedByTheSweepAndByItsOwnPoll() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.STALE, null, 0, 0)).toList();
            fleet.service.reconcileSessions();
            var error = fleet.outputs.get("thumb").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasError).findFirst();
            assertTrue(error.isPresent());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_SESSION_SUPERSEDED, error.get().getError().getCode());
            assertEquals(0, fleet.service.liveSessions());
        }
        try (Fleet fleet = new Fleet()) {
            fleet.ports.pollRefusal = GatewayCode.STALE_EPOCH;
            fleet.connect("thumb");
            fleet.service.pollTick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fleet.service.liveSessions() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, fleet.service.liveSessions(), "a poll that finds the session superseded closes it");
            assertTrue(fleet.outputs.get("thumb").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasError));
        }
    }

    @Test
    void aControlPlaneReadFailureKeepsEverySessionAndIsCounted() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            fleet.ports.reconciler = sessions -> { throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "cp down"); };
            fleet.service.reconcileSessions();
            assertEquals(1, fleet.service.liveSessions(), "a CP blip must never mass-disconnect the fleet");
        }
    }

    // ---- plan 015 T3.2 ----

    private static AgentGatewayOuterClass.AgentToGateway renewMessage(String id, String nonce) {
        return AgentGatewayOuterClass.AgentToGateway.newBuilder().setLeaseRenew(
                AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(envelope(id)).setNonceConsumed(nonce)
                        .setAgentClock(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))).build();
    }

    @Test
    void aReconnectWithAnyLeaseRowIsToldToRenewAndNeverGetsIssueNotAllowed() throws Exception {
        // T2.1b fix-round (a), OR-136 (3): current() answers LEASE_RENEW_REQUIRED whenever a lease row exists; issue is then never called.
        try (Fleet fleet = new Fleet()) {
            fleet.ports.leaseRowExists = true;
            fleet.connect("a1");
            var first = fleet.outputs.get("a1").messages;
            assertEquals(1, first.size(), first.toString());
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_LEASE_RENEW_REQUIRED, first.get(0).getError().getCode());
            assertEquals(0, fleet.ports.issueCalls.get(), "issue is never attempted when a lease row exists");
            assertFalse(fleet.outputs.get("a1").completed, "the stream stays open for the LeaseRenew");
            assertEquals(0, fleet.service.liveSessions());

            fleet.inputs.get("a1").onNext(renewMessage("renew", "last-nonce"));
            assertTrue(fleet.outputs.get("a1").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasSessionAccept));
            assertEquals(1, fleet.service.liveSessions());
            assertEquals(0, fleet.ports.issueCalls.get());
        }
    }

    @Test
    void aFirstEverSessionIssuesItsLease() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            assertEquals(1, fleet.ports.issueCalls.get());
            assertTrue(fleet.outputs.get("a1").messages.get(0).hasSessionAccept());
        }
    }

    @Test
    void aStreamAwaitingItsRenewalIsClosedWhenTheOpenTimeoutElapses() throws Exception {
        try (Fleet fleet = new Fleet(p -> p.setSessionOpenTimeout(Duration.ofMillis(700)))) {
            fleet.ports.leaseRowExists = true;
            fleet.connect("a1");
            assertFalse(fleet.outputs.get("a1").completed, "still open right after LEASE_RENEW_REQUIRED");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fleet.outputs.get("a1").completed && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(fleet.outputs.get("a1").completed, "an agent that never renews does not hold a stream forever");
            assertTrue(fleet.ports.audit.contains("gateway.refusal"), "the timeout is audited so an operator can see a stranded agent");
        }
    }

    @Test
    void everyConnectStreamsOriginIsThePeerAddressAndNeverAnAgentSuppliedValue() throws Exception {
        try (Fleet fleet = new Fleet()) {
            // The agent's own text (its version) looks like an address; the origin must still be the transport peer's.
            fleet.connect("a1", "10.9.9.9", List.of(), "203.0.113.44");
            assertEquals(1, fleet.ports.claimOrigins.size());
            assertEquals("203.0.113.44", fleet.ports.claimOrigins.get(0).clientAddress());
        }
    }

    @Test
    void aPeerAddressThatIsNotABareHostAddressDegradesToUnknownInsteadOfFailingTheCall() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1", "test", List.of(), "10.0.0.1:55000 /with space");
            assertEquals("unknown", fleet.ports.claimOrigins.get(0).clientAddress());
        }
    }

    @Test
    void aDiscoveryReportIsForwardedToTheDiscoveryPortAndItsPerItemResultsAreReturned() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DISCOVERY), "198.51.100.7");
            UUID placement = UUID.nameUUIDFromBytes("discovered".getBytes());
            fleet.ports.discoveryResults = List.of(
                    new DiscoveryResult("c1", DiscoveryResult.State.REGISTERED, null, placement),
                    new DiscoveryResult("c2", DiscoveryResult.State.REFUSED, GatewayCode.AGENT_DISCOVERY_PORT_TAKEN, null));
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setDiscoveryReport(
                    AgentGatewayOuterClass.DiscoveryReport.newBuilder().setEnvelope(envelope("disc-1"))
                            .addItems(AgentGatewayOuterClass.DiscoveredContainer.newBuilder().setContainerId("c1").setContainerName("cache-1")
                                    .setImageRef("img:1").putLabels("com.solace.sm.solution", "cache").putLabels("other.vendor.label", "x")
                                    .addMounts(AgentGatewayOuterClass.DiscoveredMount.newBuilder().setSource("/etc/c").setDestination("/c"))
                                    .addPublishedPorts(AgentGatewayOuterClass.DiscoveredPort.newBuilder().setContainerPort(8080).setHostPort(18080)))
                            .addItems(AgentGatewayOuterClass.DiscoveredContainer.newBuilder().setContainerId("c2"))).build());
            assertEquals(2, fleet.ports.discovered.size());
            DiscoveredContainer first = fleet.ports.discovered.get(0);
            assertEquals("cache-1", first.containerName());
            assertEquals(Map.of("com.solace.sm.solution", "cache"), first.labels(), "labels outside com.solace.sm. never reach the port");
            assertEquals(8080, first.publishedPorts().get(0).containerPort());
            var ack = fleet.outputs.get("a1").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasDiscoveryAck).findFirst();
            assertTrue(ack.isPresent());
            assertEquals("disc-1", ack.get().getDiscoveryAck().getInReplyToMessageId());
            assertEquals(2, ack.get().getDiscoveryAck().getResultsCount());
            assertEquals(AgentGatewayOuterClass.DiscoveryState.DISCOVERY_STATE_REGISTERED, ack.get().getDiscoveryAck().getResults(0).getState());
            assertEquals(placement.toString(), ack.get().getDiscoveryAck().getResults(0).getBindingId());
            assertEquals(AgentGatewayOuterClass.DiscoveryState.DISCOVERY_STATE_REFUSED, ack.get().getDiscoveryAck().getResults(1).getState());
            assertEquals("agent.discovery.port.taken", ack.get().getDiscoveryAck().getResults(1).getCode());
        }
    }

    private static TelemetryDeclaration declaration(UUID placement, String metricName) {
        return new TelemetryDeclaration(placement, Duration.ofSeconds(5),
                List.of(new TelemetryDeclaration.Metric(metricName, "gauge", "1", 8080, "/actuator/metrics", "/value", true)),
                new com.solace.sm.dp.api.inventory.HealthProbeSpec("actuator-health", "/actuator/health", 18080,
                        com.solace.sm.dp.api.inventory.ProbeStrength.READINESS));
    }

    private static List<AgentGatewayOuterClass.TelemetryDeclarations> declarationsSent(CapturingObserver out) {
        return out.messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasTelemetryDeclarations)
                .map(AgentGatewayOuterClass.GatewayToAgent::getTelemetryDeclarations).toList();
    }

    @Test
    void declarationsAreSentAfterSessionAcceptOnlyToAnAgentThatUnderstandsThemAndOnChange() throws Exception {
        try (Fleet fleet = new Fleet()) {
            UUID placement = UUID.nameUUIDFromBytes("p-decl".getBytes());
            fleet.ports.declarations = List.of(declaration(placement, "heap"));
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            fleet.connect("a2");   // an older agent: no DECLARATIONS capability
            var messages = fleet.outputs.get("a1").messages;
            assertTrue(messages.get(0).hasSessionAccept(), "SessionAccept comes first");
            assertTrue(messages.get(1).hasTelemetryDeclarations());
            var sent = declarationsSent(fleet.outputs.get("a1"));
            assertEquals(1, sent.size());
            assertEquals(placement.toString(), sent.get(0).getBindingId());
            assertEquals(5, sent.get(0).getInterval().getSeconds());
            assertEquals("heap", sent.get(0).getMetrics(0).getName());
            assertEquals(AgentGatewayOuterClass.MetricType.METRIC_TYPE_GAUGE, sent.get(0).getMetrics(0).getMetricType());
            assertEquals(8080, sent.get(0).getMetrics(0).getHttpJson().getPort());
            assertEquals(AgentGatewayOuterClass.OnUnavailable.ON_UNAVAILABLE_DEGRADE, sent.get(0).getMetrics(0).getHttpJson().getOnUnavailable());
            assertEquals(AgentGatewayOuterClass.ProbeKind.PROBE_KIND_ACTUATOR_HEALTH, sent.get(0).getHealthProbe().getKind());
            assertEquals(18080, sent.get(0).getHealthProbe().getHostPort());
            assertEquals(AgentGatewayOuterClass.ProbeStrength.PROBE_STRENGTH_READINESS, sent.get(0).getHealthProbe().getStrength());
            assertEquals(0, declarationsSent(fleet.outputs.get("a2")).size(), "never sent to a session that did not announce DECLARATIONS");

            // A renewal with nothing changed re-sends nothing; a changed set is sent in full; a removed binding is cleared.
            fleet.inputs.get("a1").onNext(renewMessage("renew-1", "nonce"));
            assertEquals(1, declarationsSent(fleet.outputs.get("a1")).size(), "unchanged: nothing re-sent");
            fleet.ports.declarations = List.of(declaration(placement, "heap-used"));
            fleet.inputs.get("a1").onNext(renewMessage("renew-2", "nonce"));
            sent = declarationsSent(fleet.outputs.get("a1"));
            assertEquals(2, sent.size());
            assertEquals("heap-used", sent.get(1).getMetrics(0).getName());
            fleet.ports.declarations = List.of();
            fleet.inputs.get("a1").onNext(renewMessage("renew-3", "nonce"));
            sent = declarationsSent(fleet.outputs.get("a1"));
            assertEquals(3, sent.size());
            assertEquals(placement.toString(), sent.get(2).getBindingId());
            assertEquals(0, sent.get(2).getMetricsCount(), "the empty COMPLETE set replaces the previous one");
            assertFalse(sent.get(2).hasHealthProbe());
        }
    }

    @Test
    void aDeclarationsChangeSignalPushesTheNewSetOnTheLiveStreamAndOnlyToTheAgentNamed() throws Exception {
        try (Fleet fleet = new Fleet()) {
            UUID placement = UUID.nameUUIDFromBytes("p-signal".getBytes());
            fleet.ports.declarations = List.of(declaration(placement, "heap"));
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            fleet.connect("a2", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.8");
            fleet.service.startMaintenance();
            fleet.service.startMaintenance();                       // a second start registers nothing more
            assertEquals(1, fleet.ports.declarationSubscribers.size(), "one registration, however often maintenance starts");
            assertEquals(1, declarationsSent(fleet.outputs.get("a1")).size());
            assertEquals(1, declarationsSent(fleet.outputs.get("a2")).size());

            // the data plane's change: a1 is told, off the committing thread; a re-read finds the new set and sends it in full
            fleet.ports.declarations = List.of(declaration(placement, "heap-used"));
            fleet.ports.declarationSubscribers.get(0).accept(fleet.ports.resolve("a1").orElseThrow().agentId());
            awaitSent(fleet.outputs.get("a1"), 2);
            var sent = declarationsSent(fleet.outputs.get("a1"));
            assertEquals("heap-used", sent.get(1).getMetrics(0).getName());
            assertEquals(1, declarationsSent(fleet.outputs.get("a2")).size(), "a2 was not named: nothing was sent to it");

            // a signal with nothing different sends nothing; one for an agent with no live session is ignored
            fleet.ports.declarationSubscribers.get(0).accept(fleet.ports.resolve("a1").orElseThrow().agentId());
            fleet.ports.declarationSubscribers.get(0).accept(UUID.randomUUID());
            Thread.sleep(300);
            assertEquals(2, declarationsSent(fleet.outputs.get("a1")).size(), "unchanged: nothing re-sent");

            // the empty set clears
            fleet.ports.declarations = List.of();
            fleet.ports.declarationSubscribers.get(0).accept(fleet.ports.resolve("a1").orElseThrow().agentId());
            awaitSent(fleet.outputs.get("a1"), 3);
            assertEquals(0, declarationsSent(fleet.outputs.get("a1")).get(2).getMetricsCount());
            fleet.service.stopMaintenance();
        }
    }

    @Test
    void aDeclarationsSignalAsksTheNamedAgentToRenewWhenItsLeaseNoLongerNamesThePlacementSet() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            fleet.connect("a2", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.8");
            fleet.service.startMaintenance();
            java.util.List<UUID> reconciled = new java.util.concurrent.CopyOnWriteArrayList<>();
            fleet.ports.reconciler = sessions -> sessions.stream().map(x -> {
                reconciled.add(x.agentId());
                return new IdentityReconciliation(x, IdentityReconciliation.Status.RENEWAL_REQUIRED, null, 1, 1);
            }).toList();

            fleet.ports.declarationSubscribers.get(0).accept(fleet.ports.resolve("a1").orElseThrow().agentId());

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (renewPrompts(fleet.outputs.get("a1")) == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(1, renewPrompts(fleet.outputs.get("a1")), "the existing renewal prompt, sent to the agent named");
            assertEquals(0, renewPrompts(fleet.outputs.get("a2")), "and to no other");
            assertEquals(List.of(fleet.ports.resolve("a1").orElseThrow().agentId()), reconciled, "one agent reconciled, not the fleet");
            fleet.service.stopMaintenance();
        }
    }

    @Test
    void aSignalWithNothingChangedAsksForNoRenewal() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            fleet.service.startMaintenance();
            java.util.concurrent.atomic.AtomicInteger reconciles = new java.util.concurrent.atomic.AtomicInteger();
            fleet.ports.reconciler = sessions -> {
                reconciles.incrementAndGet();
                return sessions.stream().map(IdentityReconciliation::unchanged).toList();
            };
            fleet.ports.declarationSubscribers.get(0).accept(fleet.ports.resolve("a1").orElseThrow().agentId());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (reconciles.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            Thread.sleep(200);
            assertEquals(0, renewPrompts(fleet.outputs.get("a1")));
            fleet.service.stopMaintenance();
        }
    }

    @Test
    void signalsForOneAgentAreCoalescedToAtMostOneQueuedPushAndNoneIsLost() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            fleet.service.startMaintenance();
            java.util.concurrent.CountDownLatch inFirst = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger reconciles = new java.util.concurrent.atomic.AtomicInteger();
            fleet.ports.reconciler = sessions -> {
                if (reconciles.incrementAndGet() == 1) {
                    inFirst.countDown();
                    try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return sessions.stream().map(IdentityReconciliation::unchanged).toList();
            };
            UUID agent = fleet.ports.resolve("a1").orElseThrow().agentId();
            fleet.ports.declarationSubscribers.get(0).accept(agent);
            assertTrue(inFirst.await(10, TimeUnit.SECONDS));
            for (int i = 0; i < 50; i++) fleet.ports.declarationSubscribers.get(0).accept(agent);     // while the first is running
            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (reconciles.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20);
            Thread.sleep(300);
            assertEquals(2, reconciles.get(), "one run, plus ONE more for the 50 that arrived during it: coalesced, none lost");
            fleet.service.stopMaintenance();
        }
    }

    private static long renewPrompts(CapturingObserver out) {
        return out.messages.stream().filter(m -> m.hasError()
                && m.getError().getCode() == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_LEASE_RENEW_REQUIRED).count();
    }

    private static void awaitSent(CapturingObserver out, int count) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (declarationsSent(out).size() < count && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(declarationsSent(out).size() >= count, "the pushed declarations never arrived");
    }

    @Test
    void aDeclarationsReadFailureNeverFailsTheSession() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.declarations = null;   // the fake then throws a NullPointerException from forAgent's caller side
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            assertTrue(fleet.outputs.get("a1").messages.get(0).hasSessionAccept());
            assertEquals(1, fleet.service.liveSessions());
        }
    }

    @Test
    void aRevocationFoundWhileRenewingAnAcceptedSessionClosesEveryStream() throws Exception {
        // The branch's BLOCKER regression [An 1.10]: the connect stream, the lease and the telemetry stream all end.
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            TelemetryObserverWithError telemetryOut = new TelemetryObserverWithError();
            fleet.service.telemetry(telemetryOut);
            fleet.ports.renewRefusal = GatewayCode.IDENTITY_REVOKED;
            fleet.inputs.get("thumb").onNext(renewMessage("renew", "nonce"));
            assertTrue(fleet.outputs.get("thumb").messages.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasRevoke));
            assertTrue(fleet.outputs.get("thumb").completed, "the connect stream is closed");
            assertEquals(0, fleet.service.liveSessions());
            assertNotNull(telemetryOut.error, "the telemetry stream is aborted too");
        }
    }

    @Test
    void anAckDetailIsBoundedAndSanitisedBeforeItReachesThePortAndIsNeverEchoed() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            String hostile = "line1\nline2\u0000\u001b[31m" + "x".repeat(5000) + "\u00e9";
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setCommandAck(
                    AgentGatewayOuterClass.CommandAck.newBuilder().setEnvelope(envelope("ack-1")).setCommandId(Ports.COMMAND.toString())
                            .setGeneration(1).setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_FAILED_NO_EFFECT)
                            .setDetail(AgentGatewayOuterClass.AckDetail.newBuilder()
                                    .setCode(AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_ERROR)
                                    .setErrorCode(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_INTERNAL_UNAVAILABLE).setMessage(hostile))
                            .setContainerStartedAt(com.google.protobuf.util.Timestamps.fromMillis(1_700_000_000_000L))).build());
            assertEquals(1, fleet.ports.acks.size());
            AgentAck ack = fleet.ports.acks.get(0);
            // The data plane stores this as the command's detail_code and bounds it to [A-Za-z][A-Za-z0-9._]{0,63}: a value outside the
            // grammar is stored as "invalid.detail" and the agent's refusal reason is lost (found by the real-agent harness, plan 015 T4.4:
            // the adapter's TARGET_MOVED classification never fired). So the error code travels bare, as the data plane's own vocabulary.
            assertEquals("INTERNAL_UNAVAILABLE", ack.detailCode());
            assertTrue(ack.detailCode().matches("^[A-Za-z][A-Za-z0-9._]{0,63}$"), "inside the data plane's detail-code grammar: " + ack.detailCode());
            assertTrue(ack.detailMessage().length() <= AgentGatewayService.MAX_DETAIL_CHARS, "bounded: " + ack.detailMessage().length());
            assertTrue(ack.detailMessage().chars().allMatch(c -> c >= 0x20 && c < 0x7f), "printable ASCII only: " + ack.detailMessage());
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), ack.containerStartedAt(), "taken from the ack, host clock");
            assertTrue(fleet.outputs.get("a1").messages.stream().noneMatch(m -> m.toString().contains("line1")),
                    "never echoed back to the agent");
        }
    }

    /** HF18 addition (plan 015 Final, W5 review m-4 (b)): a refused ack's code is audited as itself, not as UNKNOWN. */
    @Test
    void aRefusedAckWithTargetMovedOrRenderChangedIsAuditedWithThatCode() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            int generation = 0;
            for (AgentGatewayOuterClass.ErrorCode wire : List.of(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_TARGET_MOVED,
                    AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RENDER_CHANGED)) {
                generation++;
                fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setCommandAck(
                        AgentGatewayOuterClass.CommandAck.newBuilder().setEnvelope(envelope("ack-" + generation))
                                .setCommandId(Ports.COMMAND.toString()).setGeneration(generation)
                                .setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_REFUSED)
                                .setDetail(AgentGatewayOuterClass.AckDetail.newBuilder()
                                        .setCode(AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_ERROR).setErrorCode(wire))).build());
            }
            assertTrue(fleet.ports.auditCoded.contains("gateway.command.refused/TARGET_MOVED"), fleet.ports.auditCoded.toString());
            assertTrue(fleet.ports.auditCoded.contains("gateway.command.refused/RENDER_CHANGED"), fleet.ports.auditCoded.toString());
            assertTrue(fleet.ports.auditCoded.stream().noneMatch(c -> c.endsWith("/UNKNOWN")), fleet.ports.auditCoded.toString());
            assertEquals(GatewayCode.TARGET_MOVED, GatewayCode.valueOf("TARGET_MOVED"));
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_TARGET_MOVED, AgentGatewayService.error(GatewayCode.TARGET_MOVED));
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RENDER_CHANGED, AgentGatewayService.error(GatewayCode.RENDER_CHANGED));
        }
    }

    @Test
    void anUnspecifiedAckDetailCodeIsAnAbsentDetailCodeNotTheEnumName() throws Exception {
        // Review m-4(a): the real agent's codec always sets a detail, so an ack with a message and no code arrived as
        // "ACK_DETAIL_CODE_UNSPECIFIED" and was stored as the command's detail_code ("agent.command.refused.ack.detail.code.unspecified").
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setCommandAck(
                    AgentGatewayOuterClass.CommandAck.newBuilder().setEnvelope(envelope("ack-u")).setCommandId(Ports.COMMAND.toString())
                            .setGeneration(1).setOutcome(AgentGatewayOuterClass.Outcome.OUTCOME_REFUSED)
                            .setDetail(AgentGatewayOuterClass.AckDetail.newBuilder()
                                    .setCode(AgentGatewayOuterClass.AckDetailCode.ACK_DETAIL_CODE_UNSPECIFIED).setMessage("unknown operation"))
                            .build()).build());
            assertEquals(1, fleet.ports.acks.size());
            assertEquals("", fleet.ports.acks.get(0).detailCode(), "no code on the wire is no code at the port");
            assertEquals("unknown operation", fleet.ports.acks.get(0).detailMessage(), "the message still travels");
        }
    }

    @Test
    void aReportItemCarriesTheAgentsHealthStartedAtAndTheCanonicalHash() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            String plain = "ab".repeat(32);
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setActualStateReportBatch(
                    AgentGatewayOuterClass.ActualStateReportBatch.newBuilder().setEnvelope(envelope("rep-1"))
                            .addItems(AgentGatewayOuterClass.ActualStateReport.newBuilder().setItemId("i1").setSequence(3)
                                    .setBindingId(Ports.PLACEMENT.toString()).setReplicaRef("cache-1").setEffectiveHash(plain)
                                    .setHealth(AgentGatewayOuterClass.HealthObservation.newBuilder()
                                            .setState(AgentGatewayOuterClass.HealthState.HEALTH_STATE_HEALTHY)
                                            .setObservedAt(com.google.protobuf.util.Timestamps.fromMillis(1_700_000_001_000L))
                                            .setProbeStrength(AgentGatewayOuterClass.ProbeStrength.PROBE_STRENGTH_LIVENESS)
                                            .setHttpStatus(200).setActuatorStatus("UP"))
                                    .setContainerStartedAt(com.google.protobuf.util.Timestamps.fromMillis(1_700_000_002_000L))))
                    .build());
            assertEquals(1, fleet.ports.reportItems.size());
            ReportItem item = fleet.ports.reportItems.get(0);
            assertEquals("sha256:" + plain, item.effectiveHash(), "the DP canonical form HF18 documents; the data plane keeps it in memory only");
            assertEquals(com.solace.sm.dp.api.agw.AgentHealthObservation.State.HEALTHY, item.health().state());
            assertEquals(200, item.health().httpStatus());
            assertEquals("UP", item.health().actuatorStatus());
            assertEquals(Instant.ofEpochMilli(1_700_000_002_000L), item.containerStartedAt());
        }
    }

    @Test
    void aNoticeAfterARestartHasNoDigestAndIsStillSent() throws Exception {
        // T2.1b fix-round (b): after an SM restart a CommandNotice carries no plain digest; the agent compares at pull.
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            fleet.ports.pending = List.of(new CommandNotice(new CommandId(Ports.COMMAND), Ports.PLACEMENT, 1, AgentOperation.APPLY,
                    null, Instant.now().plusSeconds(60), null));
            fleet.service.pollTick();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fleet.outputs.get("a1").messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasGenerationAvailable)
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var notice = fleet.outputs.get("a1").messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasGenerationAvailable).findFirst();
            assertTrue(notice.isPresent());
            assertFalse(notice.get().getGenerationAvailable().hasConfigSha256());
        }
    }

    // ---- fix round: renewal refusals, REMATERIALIZE, telemetry dispositions, drain, mapping ----

    private static AgentGatewayOuterClass.GatewayToAgent only(CapturingObserver out, java.util.function.Predicate<AgentGatewayOuterClass.GatewayToAgent> p) {
        return out.messages.stream().filter(p).findFirst().orElse(null);
    }

    @Test
    void aControlPlaneOutageDuringTheRenewalBeforeAcceptIsARetryableErrorAndNeverReEnrolment() throws Exception {
        for (GatewayCode transientCode : List.of(GatewayCode.INTERNAL_UNAVAILABLE, GatewayCode.STALE_EPOCH, GatewayCode.IN_PROGRESS,
                GatewayCode.CLOCK_SUSPECT)) {
            try (Fleet fleet = new Fleet()) {
                fleet.ports.leaseRowExists = true;
                fleet.connect("a1");
                fleet.ports.renewRefusal = transientCode;
                fleet.inputs.get("a1").onNext(renewMessage("renew", "last-nonce"));
                var out = fleet.outputs.get("a1");
                assertTrue(out.messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasSessionReject),
                        transientCode + " must not tell the agent to enrol again: " + out.messages);
                var error = out.messages.get(out.messages.size() - 1);
                assertTrue(error.hasError(), transientCode + ": " + out.messages);
                assertEquals(transientCode == GatewayCode.STALE_EPOCH ? AgentGatewayOuterClass.ErrorCode.ERROR_CODE_SESSION_SUPERSEDED
                        : AgentGatewayService.error(transientCode), error.getError().getCode());
                assertTrue(out.completed, "the stream ends and the agent reconnects with its lease intact");
                assertEquals(0, fleet.service.liveSessions());
            }
        }
    }

    @Test
    void theRefusalsThatMeanTheGrantIsGoneAnswerReEnrollRequiredBeforeAndAfterTheAccept() throws Exception {
        for (GatewayCode code : List.of(GatewayCode.LEASE_RENEW_REFUSED, GatewayCode.ISSUE_NOT_ALLOWED)) {
            try (Fleet fleet = new Fleet()) {   // before the accept
                fleet.ports.leaseRowExists = true;
                fleet.connect("a1");
                fleet.ports.renewRefusal = code;
                fleet.inputs.get("a1").onNext(renewMessage("renew", "n"));
                var reject = only(fleet.outputs.get("a1"), AgentGatewayOuterClass.GatewayToAgent::hasSessionReject);
                assertNotNull(reject, code.toString());
                assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RE_ENROLL_REQUIRED, reject.getSessionReject().getCode());
                assertTrue(fleet.outputs.get("a1").completed);
            }
            try (Fleet fleet = new Fleet()) {   // in session: ISSUE_NOT_ALLOWED is never forwarded raw
                fleet.connect("a1");
                fleet.ports.renewRefusal = code;
                fleet.inputs.get("a1").onNext(renewMessage("renew", "n"));
                var out = fleet.outputs.get("a1");
                assertTrue(out.messages.stream().noneMatch(m -> m.hasError()
                        && m.getError().getCode() == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_ISSUE_NOT_ALLOWED), out.messages.toString());
                assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RE_ENROLL_REQUIRED,
                        only(out, AgentGatewayOuterClass.GatewayToAgent::hasSessionReject).getSessionReject().getCode());
            }
        }
    }

    @Test
    void aStreamThatWasWaitingForItsRenewalWhenTheDrainBeganIsToldDrainingInsteadOfBeingAccepted() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.leaseRowExists = true;
            fleet.connect("a1");
            fleet.service.drain();
            fleet.inputs.get("a1").onNext(renewMessage("renew", "n"));
            var out = fleet.outputs.get("a1");
            assertTrue(out.messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasSessionAccept));
            assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_DRAINING, out.messages.get(out.messages.size() - 1).getError().getCode());
            assertTrue(out.completed);
            assertEquals(0, fleet.service.liveSessions());
        }
    }

    private AgentGatewayOuterClass.CommandOffer offerFor(AgentOperation operation, CommandPayload payload) throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("a1");
            fleet.ports.pullOperation = operation;
            fleet.ports.pullPayload = payload;
            fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setCommandPull(
                    AgentGatewayOuterClass.CommandPull.newBuilder().setEnvelope(envelope("pull")).setCommandId(Ports.COMMAND.toString())).build());
            return only(fleet.outputs.get("a1"), AgentGatewayOuterClass.GatewayToAgent::hasCommandOffer).getCommandOffer();
        }
    }

    @Test
    void aRematerializeOfferCarriesItsWritePreconditionAndOtherOffersDoNot() throws Exception {
        String hex = "cd".repeat(32);
        var withHash = offerFor(AgentOperation.REMATERIALIZE, new CommandPayload("p".getBytes(), "sha256:" + "ab".repeat(32), "sha256:" + hex));
        assertTrue(withHash.hasExpectedCurrentSha256());
        assertEquals(hex, withHash.getExpectedCurrentSha256(), "the prefix is stripped");
        assertEquals("ab".repeat(32), withHash.getConfigSha256());
        var mustBeAbsent = offerFor(AgentOperation.REMATERIALIZE, new CommandPayload("p".getBytes(), "sha256:" + "ab".repeat(32), ""));
        assertTrue(mustBeAbsent.hasExpectedCurrentSha256(), "present and empty means: the file must still be absent");
        assertEquals("", mustBeAbsent.getExpectedCurrentSha256());
        assertFalse(offerFor(AgentOperation.APPLY, new CommandPayload("p".getBytes(), "sha256:" + "ab".repeat(32), null)).hasExpectedCurrentSha256());
    }

    @Test
    void aMalformedEffectiveHashIsNullNeverPassedThrough() throws Exception {
        for (String bad : List.of("sha256:" + "ab".repeat(32), "AB".repeat(32), "ab".repeat(31), "../../etc/passwd", "ab".repeat(32) + "\n")) {
            try (Fleet fleet = new Fleet()) {
                fleet.connect("a1");
                fleet.inputs.get("a1").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setActualStateReportBatch(
                        AgentGatewayOuterClass.ActualStateReportBatch.newBuilder().setEnvelope(envelope("rep"))
                                .addItems(AgentGatewayOuterClass.ActualStateReport.newBuilder().setItemId("i").setEffectiveHash(bad))).build());
                assertEquals(null, fleet.ports.reportItems.get(0).effectiveHash(), bad);
            }
        }
    }

    private List<AgentGatewayOuterClass.TelemetryDown> telemetryWith(com.solace.sm.dp.api.agw.AgentTelemetryResult result) throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.ports.telemetryResult = result;
            fleet.connect("thumb");
            TelemetryObserver out = new TelemetryObserver();
            StreamObserver<AgentGatewayOuterClass.TelemetryUp> up = fleet.service.telemetry(out);
            up.onNext(telemetry(1));
            return out.messages;
        }
    }

    @Test
    void aDoNotAckTelemetryResultIsARejectWithItsRetryAfterAndNeverAnAck() throws Exception {
        var sent = telemetryWith(new com.solace.sm.dp.api.agw.AgentTelemetryResult(
                com.solace.sm.dp.api.agw.AgentTelemetryResult.Disposition.DO_NOT_ACK, 0, 0, Duration.ofSeconds(7), null));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).hasTelemetryReject(), sent.toString());
        assertEquals(7, sent.get(0).getTelemetryReject().getRetryAfter().getSeconds());
    }

    @Test
    void aRejectedCountedTelemetryResultIsAcknowledgedWithTheWatermarkSoTheAgentTrims() throws Exception {
        var sent = telemetryWith(new com.solace.sm.dp.api.agw.AgentTelemetryResult(
                com.solace.sm.dp.api.agw.AgentTelemetryResult.Disposition.REJECTED_COUNTED, 9, 1, null, GatewayCode.PAYLOAD_TOO_LARGE));
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).hasTelemetryAck(), sent.toString());
        assertEquals(9, sent.get(0).getTelemetryAck().getHighWatermarkSeq());
    }

    @Test
    void aCommittedTelemetryResultIsAcknowledged() throws Exception {
        var sent = telemetryWith(AgentTelemetryResult.acked(4, 1));
        assertTrue(sent.get(0).hasTelemetryAck());
        assertEquals(4, sent.get(0).getTelemetryAck().getHighWatermarkSeq());
    }

    @Test
    void theTelemetryOriginIsThePeerAddress() throws Exception {
        try (Fleet fleet = new Fleet()) {
            fleet.connect("thumb");
            io.grpc.Context.current().withValue(AgentGatewayService.MetadataContext.CLIENT_ADDRESS, "192.0.2.77")
                    .run(() -> fleet.service.telemetry(new TelemetryObserver()).onNext(telemetry(1)));
            assertEquals("192.0.2.77", fleet.ports.telemetryOrigins.get(0).clientAddress());
        }
    }

    @Test
    void everyGatewayCodeAPortCanThrowMapsToItsOwnWireCodeExceptTheOnesWithNone() {
        java.util.Set<GatewayCode> noWireCode = java.util.EnumSet.of(GatewayCode.UNKNOWN, GatewayCode.AGENT_SESSION_ABSENT,
                GatewayCode.AGENT_SESSION_LOST, GatewayCode.AGENT_OPERATION_UNSUPPORTED, GatewayCode.AGENT_DISCOVERY_UNAVAILABLE,
                GatewayCode.AGENT_DISCOVERY_INCOMPLETE, GatewayCode.AGENT_DISCOVERY_OUTSIDE_ENVELOPE, GatewayCode.AGENT_DISCOVERY_PORT_TAKEN,
                GatewayCode.AGENT_DISCOVERY_CONTAINER_TAKEN, GatewayCode.AGENT_DISCOVERY_CONFIG_TAKEN,
                GatewayCode.AGENT_PLACEMENT_CAP, GatewayCode.INSTANCE_NAME_TAKEN, GatewayCode.ENTITLEMENT_QUOTA);
        for (GatewayCode code : GatewayCode.values()) {
            boolean fallback = AgentGatewayService.error(code) == AgentGatewayOuterClass.ErrorCode.ERROR_CODE_INTERNAL_UNAVAILABLE
                    && code != GatewayCode.INTERNAL_UNAVAILABLE;
            assertEquals(noWireCode.contains(code), fallback, code + " (the dotted discovery/session codes travel as strings or have no wire code)");
        }
        assertEquals(AgentGatewayOuterClass.ErrorCode.ERROR_CODE_RENDER_CHANGED, AgentGatewayService.error(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED));
    }

    @Test
    void aMetricWithNoUnitIsSentWithADefaultTheAgentAccepts() throws Exception {
        try (Fleet fleet = new Fleet()) {
            UUID placement = UUID.nameUUIDFromBytes("p-unit".getBytes());
            fleet.ports.declarations = List.of(new TelemetryDeclaration(placement, Duration.ofSeconds(5),
                    List.of(new TelemetryDeclaration.Metric("heap", "gauge", null, 8080, "/m", "", true)), null));
            fleet.connect("a1", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_DECLARATIONS), "198.51.100.7");
            assertEquals("1", declarationsSent(fleet.outputs.get("a1")).get(0).getMetrics(0).getUnit());
        }
    }

    private static final class TelemetryObserverWithError implements StreamObserver<AgentGatewayOuterClass.TelemetryDown> {
        volatile Throwable error;
        @Override public void onNext(AgentGatewayOuterClass.TelemetryDown value) { }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onCompleted() { }
    }

    static AgentGatewayOuterClass.Envelope envelope(String id) {
        return AgentGatewayOuterClass.Envelope.newBuilder().setMessageId(id)
                .setSentAt(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli())).build();
    }

    static final class CapturingObserver implements StreamObserver<AgentGatewayOuterClass.GatewayToAgent> {
        final java.util.List<AgentGatewayOuterClass.GatewayToAgent> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean completed;
        @Override public void onNext(AgentGatewayOuterClass.GatewayToAgent value) { messages.add(value); }
        @Override public void onError(Throwable t) { throw new AssertionError(t); }
        @Override public void onCompleted() { completed = true; }
    }

    private static final class TelemetryObserver implements StreamObserver<AgentGatewayOuterClass.TelemetryDown> {
        final java.util.List<AgentGatewayOuterClass.TelemetryDown> messages = new java.util.ArrayList<>();
        @Override public void onNext(AgentGatewayOuterClass.TelemetryDown value) { messages.add(value); }
        @Override public void onError(Throwable t) { throw new AssertionError(t); }
        @Override public void onCompleted() { }
    }

    private static AgentGatewayOuterClass.TelemetryUp telemetry(long sequence) {
        return AgentGatewayOuterClass.TelemetryUp.newBuilder().setTelemetryBatch(
                AgentGatewayOuterClass.TelemetryBatch.newBuilder().setEnvelope(envelope("telemetry-" + sequence))
                        .setStreamId("stream-1").setBatchSeq(sequence)
                        .setProducedAt(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))
                        .addSamples(AgentGatewayOuterClass.Sample.newBuilder().setSeriesKey("cpu")
                                .setType(AgentGatewayOuterClass.MetricType.METRIC_TYPE_GAUGE)
                                .setTs(com.google.protobuf.util.Timestamps.fromMillis(Instant.now().toEpochMilli()))
                                .setValue(1.0))).build();
    }

    static final class Ports implements AgentEnrollmentPort, AgentIdentityPort, AgentSessionPort, AgentLeasePort,
            AgentCommandPort, ActualStatePort, AgentTelemetryPort, AgentAuditPort, AgentDiscoveryPort,
            AgentTelemetryDeclarationsPort {
        /** The ports carry UUID ids; the tests name agents by string, so the fake maps both ways. */
        private static final java.util.Map<UUID, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();
        static final UUID TENANT = UUID.nameUUIDFromBytes("tenant-1".getBytes());
        static final UUID ENVIRONMENT = UUID.nameUUIDFromBytes("env-1".getBytes());
        static final UUID TARGET = UUID.nameUUIDFromBytes("target-1".getBytes());
        static final UUID PLACEMENT = UUID.nameUUIDFromBytes("placement-1".getBytes());
        static final UUID COMMAND = UUID.nameUUIDFromBytes("cmd-1".getBytes());

        /** Order-preserving (the drain sorts sessions by agent id, and the tests name agents a1..a4): the name's bytes, left-aligned. */
        static UUID id(String name) {
            byte[] bytes = java.util.Arrays.copyOf(name.getBytes(), 16);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            UUID id = new UUID(buffer.getLong(), buffer.getLong());
            NAMES.put(id, name);
            return id;
        }

        static String name(UUID id) { return NAMES.getOrDefault(id, id.toString()); }

        AgentGatewayPortSet set() {
            return new AgentGatewayPortSet(this, this, this, this, this, this, this, this, this, this);
        }

        final AgentIdentity identity = new AgentIdentity(id("agent-1"), "thumb", TENANT, ENVIRONMENT, TARGET,
                1, 1, List.of(AgentCapability.APPLY_CONFIG, AgentCapability.LIFECYCLE), false);
        final LeaseGrant lease = new LeaseGrant(id("agent-1"), "thumb", TARGET, List.of(PLACEMENT), 1, 1,
                Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce", identity.capabilities(), Map.of(),
                Duration.ZERO, Instant.now());
        int creditsGranted = 1;
        int ingested;
        /** Decides what each reconciliation pass reports; default: nothing changed. */
        Function<List<AgentSessionRef>, List<IdentityReconciliation>> reconciler =
                sessions -> sessions.stream().map(IdentityReconciliation::unchanged).toList();
        final List<String> audit = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Each audit row's type and code, as {@code type/CODE} (plan 015 Final: the refused-ack codes). */
        final List<String> auditCoded = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> released = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** When set, pendingFor() refuses with this code (a session that was superseded or revoked underneath). */
        volatile GatewayCode pollRefusal;
        volatile GatewayCode heartbeatRefusal;
        /** When set, the shared read names exactly these agents (by test name); null means "everyone" (the port's default). */
        volatile java.util.Set<String> workFor;
        final java.util.concurrent.atomic.AtomicInteger sharedReads = new java.util.concurrent.atomic.AtomicInteger();
        final List<String> polledAgents = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public java.util.Set<UUID> agentsWithDeliverableWork(List<AgentSessionRef> sessions, Duration renotifyAfter) {
            sharedReads.incrementAndGet();
            java.util.Set<String> only = workFor;
            if (only != null) return only.stream().map(Ports::id).collect(java.util.stream.Collectors.toSet());
            return sessions.stream().map(AgentSessionRef::agentId).collect(java.util.stream.Collectors.toSet());
        }
        final java.util.concurrent.atomic.AtomicInteger pollCount = new java.util.concurrent.atomic.AtomicInteger();
        @Override public List<IdentityReconciliation> reconcile(List<AgentSessionRef> sessions) { return reconciler.apply(sessions); }
        final List<EnrollmentRequest> enrollments = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public EnrollmentResult enroll(EnrollmentRequest request) {
            enrollments.add(request);
            throw new GatewayPortException(GatewayCode.TOKEN_CONSUMED, "test");
        }
        @Override public Optional<AgentIdentity> resolve(String thumbprint) {
            return Optional.of(new AgentIdentity(id(thumbprint), thumbprint, TENANT, ENVIRONMENT, TARGET, 1, 1,
                    identity.capabilities(), false));
        }
        final java.util.concurrent.atomic.AtomicInteger claims = new java.util.concurrent.atomic.AtomicInteger();
        final List<AgentRequestOrigin> claimOrigins = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<AgentAck> acks = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ReportItem> reportItems = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<DiscoveredContainer> discovered = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile List<DiscoveryResult> discoveryResults = List.of();
        volatile List<TelemetryDeclaration> declarations = List.of();
        final java.util.concurrent.atomic.AtomicInteger declarationReads = new java.util.concurrent.atomic.AtomicInteger();
        @Override public AgentSessionRef claim(String thumbprint, SessionOpening opening, AgentRequestOrigin origin) {
            claims.incrementAndGet();
            claimOrigins.add(origin);
            return new AgentSessionRef(id(thumbprint), 1);
        }
        @Override public void close(AgentSessionRef session, CloseReason reason) { released.add(name(session.agentId())); }
        @Override public boolean liveFor(UUID agentId, AgentPlacementRef placement) { return true; }
        @Override public java.util.Set<AgentFeature> featuresOf(UUID agentId, AgentPlacementRef placement) { return java.util.Set.of(); }
        @Override public void heartbeat(AgentSessionRef session, Instant agentClock) {
            if (heartbeatRefusal != null) throw new GatewayPortException(heartbeatRefusal, "test");
        }
        /** The lease an agent holds is expired: current() is empty and a no-nonce issue is refused. */
        volatile boolean leaseExpired;
        volatile GatewayCode renewRefusal;
        /** The real port never returns a grant from current(): any lease row means LEASE_RENEW_REQUIRED, none means empty. */
        volatile boolean leaseRowExists;
        final java.util.concurrent.atomic.AtomicInteger issueCalls = new java.util.concurrent.atomic.AtomicInteger();
        @Override public Optional<LeaseGrant> current(AgentSessionRef session) {
            if (leaseExpired || leaseRowExists) throw new GatewayPortException(GatewayCode.LEASE_RENEW_REQUIRED, "a lease exists; renew it");
            return Optional.empty();
        }
        @Override public LeaseGrant issue(String id, AgentSessionRef session, Instant clock, AgentRequestOrigin origin) {
            issueCalls.incrementAndGet();
            if (leaseExpired || leaseRowExists) throw new GatewayPortException(GatewayCode.ISSUE_NOT_ALLOWED, "identity already has a lease");
            return lease;
        }
        @Override public LeaseGrant renew(String id, AgentSessionRef session, String nonce, Instant clock, AgentRequestOrigin origin) {
            if (renewRefusal != null) throw new GatewayPortException(renewRefusal, "test");
            return lease;
        }
        @Override public AgentCommandRef enqueue(AgentCommandIntent intent) { return new AgentCommandRef(new CommandId(COMMAND), 1, true); }
        @Override public void supersedeOutstanding(AgentPlacementRef placement, String keepIntentKey) { }
        @Override public List<CommandNotice> pendingFor(AgentSessionRef session, int limit, Duration renotifyAfter) {
            pollCount.incrementAndGet();
            polledAgents.add(name(session.agentId()));
            GatewayCode refusal = pollRefusal;
            if (refusal != null) throw new GatewayPortException(refusal, "test");
            return pending;
        }
        volatile List<CommandNotice> pending = List.of();
        volatile AgentOperation pullOperation = AgentOperation.APPLY;
        volatile CommandPayload pullPayload = new CommandPayload("payload".getBytes(), "hash", null);
        @Override public CommandOffer pull(AgentSessionRef session, CommandId id) {
            return new CommandOffer(id, 1, "key", PLACEMENT, pullOperation, null, Instant.now().plusSeconds(60), null, pullPayload);
        }
        @Override public void received(AgentSessionRef session, String requestId, CommandId id, long wireGeneration) { }
        @Override public CommandAckResult ack(AgentSessionRef session, CommandId id, AgentAck ack) { acks.add(ack); return CommandAckResult.COMMITTED; }
        @Override public Optional<CommandCompletion> completion(AgentPlacementRef placement, CommandId id) { return Optional.empty(); }
        @Override public List<ReportResult> report(AgentSessionRef session, List<ReportItem> items, AgentRequestOrigin origin) {
            reportItems.addAll(items);
            return List.of();
        }
        volatile AgentTelemetryResult telemetryResult;
        final List<AgentRequestOrigin> telemetryOrigins = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public AgentTelemetryResult ingest(AgentSessionRef session, AgentTelemetryBatch batch, AgentRequestOrigin origin) {
            ingested++;
            telemetryOrigins.add(origin);
            if (telemetryResult != null) return telemetryResult;
            return AgentTelemetryResult.acked(batch.batchSeq(), creditsGranted);
        }
        @Override public List<DiscoveryResult> report(AgentSessionRef ref, String requestId, List<DiscoveredContainer> items, AgentRequestOrigin origin) {
            discovered.addAll(items);
            return discoveryResults;
        }
        @Override public List<TelemetryDeclaration> forAgent(AgentSessionRef ref) {
            declarationReads.incrementAndGet();
            return declarations;
        }
        final List<java.util.function.Consumer<UUID>> declarationSubscribers = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void onDeclarationsChanged(java.util.function.Consumer<UUID> affectedAgents) {
            if (!declarationSubscribers.contains(affectedAgents)) declarationSubscribers.add(affectedAgents);
        }
        @Override public void record(String id, AgentSessionRef session, AgentRequestOrigin origin, String type, GatewayCode code, String subject, Instant at) {
            audit.add(type);
            auditCoded.add(type + "/" + code);
        }
    }
}
