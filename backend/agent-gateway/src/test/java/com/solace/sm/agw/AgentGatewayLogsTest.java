package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogLineView;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** Plan 016 task 7: what is specific to the gateway side (message shape and order, the Logs RPC, retention); the port's own behaviour is in the kit. */
class AgentGatewayLogsTest {
    private static final UUID PLACEMENT = AgentGatewayServiceTest.Ports.PLACEMENT;
    private static final UUID ORG = UUID.nameUUIDFromBytes("org".getBytes());
    private static final Duration LONG = Duration.ofSeconds(10);

    private static AgentSessionRef ref(String label) { return new AgentSessionRef(AgentGatewayServiceTest.Ports.id(label), 1); }

    private static LogLineView line(String text) { return new LogLineView(Instant.parse("2026-10-20T10:00:00Z"), Optional.empty(), text); }

    private static long ignored(LogsHarness h) {
        return Math.round(h.fleet.metrics.registry().counter("agw_log_replies_ignored_total").count());
    }

    @Test
    void logRequestSentOnlyToSessionWithCapLogs() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("plain", false);
            h.connect("capable", true);
            assertEquals(Optional.of(ref("capable")), h.fleet.service.logPort().sessionFor(ORG, PLACEMENT));
            h.fleet.service.logPort().requestPage(ref("plain"), PLACEMENT, 5, null, null, LONG).get(5, TimeUnit.SECONDS);
            h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            assertEquals(0, h.requests("plain").size());
            assertEquals(1, h.requests("capable").size());
            assertTrue(h.sent("plain").stream().noneMatch(m -> m.hasLogRequest() || m.hasLogRetention()),
                    "an agent without log capability is never sent an unknown variant (its decoder rejects it)");
        }
    }

    @Test
    void pageRequestCarriesTheBindingIdAndTheQueryAndCorrelatesTheReplyByRequestId() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            Instant after = Instant.parse("2026-10-20T09:00:00Z");
            var first = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 25, after, null, LONG);
            var second = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 0, null, null, LONG);
            var requests = h.requests("capable");
            assertEquals(PLACEMENT.toString(), requests.get(0).getPlacementRef(), "the placement ref is the binding id");
            assertNotNull(requests.get(0).getEnvelope().getMessageId());
            assertEquals(25, requests.get(0).getPage().getLimit());
            assertTrue(requests.get(0).getPage().hasAfter());
            assertFalse(requests.get(0).getPage().hasBefore(), "an absent bound is unset, not epoch 0");
            assertEquals(after.getEpochSecond(), requests.get(0).getPage().getAfter().getSeconds());
            assertFalse(requests.get(1).getPage().hasAfter());
            h.sendPage("capable", requests.get(1).getRequestId(), List.of(line("second")), List.of(),
                    AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED);
            AgentLogPage page = second.get(5, TimeUnit.SECONDS);
            assertEquals("second", page.lines().get(0).message());
            assertFalse(first.isDone(), "the other request is still waiting for its own reply");
        }
    }

    @Test
    void unknownOrLateReplyIsIgnoredAndCounted() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            h.connect("other", true);
            h.sendPage("capable", "never-asked", List.of(line("x")), List.of(), AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED);
            h.sendChunk("capable", "never-asked", 1, List.of(line("x")), false);
            assertEquals(2, ignored(h));
            var future = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, Duration.ofMillis(100));
            String id = h.requests("capable").get(0).getRequestId();
            assertEquals(Optional.of("TIMEOUT"), future.get(5, TimeUnit.SECONDS).unavailableReason());
            h.sendPage("capable", id, List.of(line("late")), List.of(), AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED);
            assertEquals(3, ignored(h));
            // another agent's reply to this agent's request id is not an answer
            var mine = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            String mineId = h.requests("capable").get(1).getRequestId();
            h.sendPage("other", mineId, List.of(line("spoof")), List.of(), AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED);
            assertFalse(mine.isDone());
            assertEquals(4, ignored(h));
        }
    }

    @Test
    void noReplyByDeadlineCompletesWithTimeoutAndSendsLogCancel() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            var future = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, Duration.ofMillis(100));
            String id = h.requests("capable").get(0).getRequestId();
            assertEquals(Optional.of("TIMEOUT"), future.get(5, TimeUnit.SECONDS).unavailableReason());
            assertTrue(h.cancelled("capable", id));
        }
    }

    @Test
    void exportStreamsChunksWithCreditsAndEndsOnFinal() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            LogExportHandle export = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 7, LONG, 1_000_000);
            var request = h.requests("capable").get(0);
            assertTrue(request.hasExport());
            assertEquals(7, request.getExport().getFromSeq());
            assertEquals(0, h.creditsTo("capable", request.getRequestId()), "the agent's initial credits are its own");
            h.sendChunk("capable", request.getRequestId(), 3, List.of(line("a")), false);
            assertEquals(3, export.next(Duration.ofSeconds(5)).orElseThrow().resumeSeq());
            assertEquals(1, h.creditsTo("capable", request.getRequestId()), "one credit back per chunk taken");
            h.sendChunk("capable", request.getRequestId(), 9, List.of(line("b")), true);
            assertTrue(export.next(Duration.ofSeconds(5)).orElseThrow().last());
            assertEquals(1, h.creditsTo("capable", request.getRequestId()));
            assertEquals(LogExportHandle.State.COMPLETE, export.state());
            // finished: its slot is free and nothing more is held
            assertEquals(Optional.empty(), export.next(Duration.ofMillis(10)));
        }
    }

    @Test
    void anExportEndsAtOnceWhenItsSessionOrLogsStreamEndsWithNoFinalChunk() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            LogExportHandle one = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1_000_000);
            var page = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            h.logsIn.get("capable").onCompleted();
            assertEquals(LogExportHandle.State.SESSION_LOST, one.state());
            assertEquals(Optional.of("NO_SESSION"), page.get(5, TimeUnit.SECONDS).unavailableReason());

            h.connect("second", true);
            LogExportHandle two = h.fleet.service.logPort().openExport(ref("second"), PLACEMENT, 0, LONG, 1_000_000);
            h.fleet.inputs.get("second").onCompleted();
            assertEquals(LogExportHandle.State.SESSION_LOST, two.state());
            assertTrue(h.logs.get("second").completed, "the Logs stream is closed with its session");
        }
    }

    @Test
    void retentionPushedOnSessionOpenAndOnChange() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.fleet.ports.storedCaps = Map.of(PLACEMENT, 1000L, UUID.nameUUIDFromBytes("other".getBytes()), 5L);
            h.connect("capable", true);
            h.connect("plain", false);
            assertEquals(List.of(1000L), h.retentions("capable").stream().map(AgentGatewayOuterClass.LogRetention::getMaxBytes).toList(),
                    "only the leased placement's stored cap, at open");
            assertEquals(PLACEMENT.toString(), h.retentions("capable").get(0).getPlacementRef());
            assertTrue(h.retentions("plain").isEmpty());
            int accept = indexOf(h.sent("capable"), AgentGatewayOuterClass.GatewayToAgent::hasSessionAccept);
            int retention = indexOf(h.sent("capable"), AgentGatewayOuterClass.GatewayToAgent::hasLogRetention);
            assertTrue(accept >= 0 && retention > accept, "after the session is accepted");

            renew(h, "capable");
            assertEquals(1, h.retentions("capable").size(), "an unchanged cap is not re-sent at a lease event");
            h.fleet.ports.storedCaps = Map.of(PLACEMENT, 2000L);
            renew(h, "capable");
            assertEquals(List.of(1000L, 2000L), h.retentions("capable").stream().map(AgentGatewayOuterClass.LogRetention::getMaxBytes).toList());

            h.fleet.service.logPort().sendRetention(ref("capable"), PLACEMENT, 0);
            assertEquals(0L, h.retentions("capable").get(2).getMaxBytes(), "0 is a valid cap: collection off");
        }
    }

    private static void renew(LogsHarness h, String label) {
        h.fleet.inputs.get(label).onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setLeaseRenew(
                AgentGatewayOuterClass.LeaseRenew.newBuilder().setEnvelope(AgentGatewayServiceTest.envelope("renew-" + UUID.randomUUID()))
                        .setNonceConsumed("nonce")).build());
    }

    private static <T> int indexOf(List<T> list, java.util.function.Predicate<T> p) {
        for (int i = 0; i < list.size(); i++) if (p.test(list.get(i))) return i;
        return -1;
    }

    @Test
    void placementNotInLeaseYieldsNoSession() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            UUID other = UUID.nameUUIDFromBytes("not-leased".getBytes());
            assertEquals(Optional.empty(), h.fleet.service.logPort().sessionFor(ORG, other));
            assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"),
                    h.fleet.service.logPort().requestPage(ref("capable"), other, 5, null, null, LONG).get(5, TimeUnit.SECONDS).unavailableReason());
            // and a permission change that narrows the lease takes the placement away from log requests too
            LeaseGrant narrower = new LeaseGrant(AgentGatewayServiceTest.Ports.id("agent-1"), "capable", AgentGatewayServiceTest.Ports.TARGET,
                    List.of(), 1, 1, Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce-2", List.of(AgentCapability.LIFECYCLE),
                    Map.of(), Duration.ZERO, Instant.now());
            h.fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REISSUED, narrower, 1, 1)).toList();
            h.fleet.service.reconcileSessions();
            assertEquals(Optional.empty(), h.fleet.service.logPort().sessionFor(ORG, PLACEMENT));
            h.fleet.service.logPort().sendRetention(ref("capable"), PLACEMENT, 99);
            assertTrue(h.retentions("capable").isEmpty(), "retention is never sent for a placement outside the lease");
        }
    }

    @Test
    void aLineTheAgentTruncatedKeepsThatFlagInPagesAndExportChunks() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            var cut = new LogLineView(Instant.parse("2026-10-20T10:00:00Z"), Optional.empty(), "long", true);
            var page = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            h.sendPage("capable", h.requests("capable").get(0).getRequestId(), List.of(cut, line("whole")), List.of(),
                    AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED);
            assertEquals(List.of(true, false), page.get(5, TimeUnit.SECONDS).lines().stream().map(LogLineView::truncated).toList());

            LogExportHandle export = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1_000_000);
            h.sendChunk("capable", h.requests("capable").get(1).getRequestId(), 2, List.of(cut, line("whole")), true);
            assertEquals(List.of(true, false), export.next(Duration.ofSeconds(1)).orElseThrow().lines().stream()
                    .map(LogLineView::truncated).toList());
        }
    }

    private static void narrowLease(LogsHarness h, String label) {
        LeaseGrant narrower = new LeaseGrant(AgentGatewayServiceTest.Ports.id("agent-1"), label, AgentGatewayServiceTest.Ports.TARGET,
                List.of(), 1, 1, Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce-2", List.of(AgentCapability.LIFECYCLE),
                Map.of(), Duration.ZERO, Instant.now());
        h.fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                IdentityReconciliation.Status.REISSUED, narrower, 1, 1)).toList();
        h.fleet.service.reconcileSessions();
    }

    @Test
    void aPlacementLeavingTheLeaseEndsItsPendingRequestsAtOnceAndFreesTheirSlots() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            var page = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            LogExportHandle export = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1_000_000);
            assertEquals(LogExportHandle.State.OPEN, export.state());

            narrowLease(h, "capable");

            assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), page.get(1, TimeUnit.SECONDS).unavailableReason(),
                    "a page does not wait out its deadline for a placement the agent no longer serves");
            assertEquals(LogExportHandle.State.UNAVAILABLE, export.state(), "an export ends now, not at its 5 minute deadline");
            assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), export.unavailableReason());
            var fresh = new java.util.ArrayList<LogExportHandle>();
            for (int i = 0; i < h.fleet.properties.getLogMaxInFlightPerAgent(); i++) {
                fresh.add(h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1));
            }
            assertTrue(fresh.stream().noneMatch(e -> "BUSY".equals(e.unavailableReason().orElse(null))),
                    "the per-agent slots were released (these are refused for the lease, not for being busy)");
        }
    }

    @Test
    void aPlacementLeavingTheLeaseLeavesTheRequestsOfThePlacementsThatStayAlone() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            UUID other = UUID.nameUUIDFromBytes("other-placement".getBytes());
            LeaseGrant wider = new LeaseGrant(AgentGatewayServiceTest.Ports.id("agent-1"), "capable", AgentGatewayServiceTest.Ports.TARGET,
                    List.of(PLACEMENT, other), 1, 1, Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce-2",
                    List.of(AgentCapability.LIFECYCLE), Map.of(), Duration.ZERO, Instant.now());
            h.fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REISSUED, wider, 1, 1)).toList();
            h.fleet.service.reconcileSessions();
            var staying = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            var leaving = h.fleet.service.logPort().requestPage(ref("capable"), other, 5, null, null, LONG);
            LeaseGrant narrower = new LeaseGrant(AgentGatewayServiceTest.Ports.id("agent-1"), "capable", AgentGatewayServiceTest.Ports.TARGET,
                    List.of(PLACEMENT), 1, 1, Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce-3",
                    List.of(AgentCapability.LIFECYCLE), Map.of(), Duration.ZERO, Instant.now());
            h.fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                    IdentityReconciliation.Status.REISSUED, narrower, 1, 1)).toList();
            h.fleet.service.reconcileSessions();
            assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), leaving.get(1, TimeUnit.SECONDS).unavailableReason());
            assertFalse(staying.isDone());
        }
    }

    @Test
    void logsRpcRefusedBeforeSessionAccept() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            // no session at all
            h.fleet.thumbprint.set("nobody");
            LogsHarness.LogsObserver none = new LogsHarness.LogsObserver();
            h.fleet.service.logs(none);
            assertNotNull(none.error);
            assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(none.error).getCode());
            // a live session that did not advertise log capability
            h.connect("plain", false);
            h.fleet.thumbprint.set("plain");
            LogsHarness.LogsObserver plain = new LogsHarness.LogsObserver();
            h.fleet.service.logs(plain);
            assertEquals(Status.Code.UNAUTHENTICATED, Status.fromThrowable(plain.error).getCode());
        }
    }

    @Test
    void logsStreamDoesNotBlockHeartbeatOrLeaseRenew() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", false);
            // a Logs stream whose peer is not reading: every send to it blocks
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch blocked = new CountDownLatch(1);
            h.fleet.inputs.get("capable").onCompleted();      // reconnect with the capability
            h.fleet.connect("capable", "test", List.of(AgentGatewayOuterClass.Capability.CAPABILITY_LOGS), "198.51.100.7");
            h.fleet.thumbprint.set("capable");
            StreamObserver<AgentGatewayOuterClass.LogDown> stuck = new StreamObserver<>() {
                @Override public void onNext(AgentGatewayOuterClass.LogDown value) {
                    blocked.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                @Override public void onError(Throwable t) { }
                @Override public void onCompleted() { }
            };
            h.logsIn.put("capable", h.fleet.service.logs(stuck));
            LogExportHandle export = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1_000_000);
            String id = h.requests("capable").get(0).getRequestId();
            h.sendChunk("capable", id, 1, List.of(line("a")), false);
            Thread taker = new Thread(() -> {
                try { export.next(Duration.ofSeconds(5)); } catch (InterruptedException ignored) { }
            });
            taker.setDaemon(true);
            taker.start();
            assertTrue(blocked.await(5, TimeUnit.SECONDS), "the credit send is stuck on the Logs stream");
            h.sendChunk("capable", id, 2, List.of(line("b")), false);      // the Logs onNext still returns at once

            int before = h.sent("capable").size();
            h.fleet.inputs.get("capable").onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setHeartbeat(
                    AgentGatewayOuterClass.Heartbeat.newBuilder().setEnvelope(AgentGatewayServiceTest.envelope("hb"))).build());
            renew(h, "capable");
            List<AgentGatewayOuterClass.GatewayToAgent> after = h.sent("capable").subList(before, h.sent("capable").size());
            assertTrue(after.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasHeartbeatAck));
            assertTrue(after.stream().anyMatch(AgentGatewayOuterClass.GatewayToAgent::hasLeaseGrant));
            release.countDown();
        }
    }

    @Test
    void aSessionThatAdvertisedLogsIsGivenAtLeastOneInitialCredit() throws Exception {
        try (LogsHarness h = new LogsHarness(p -> p.setInitialTelemetryCredits(0))) {
            h.connect("capable", true);
            h.connect("plain", false);
            assertEquals(1, accept(h, "capable").getLimits().getInitialCredits());
            assertEquals(0, accept(h, "plain").getLimits().getInitialCredits(), "other sessions keep the configured value");
        }
    }

    private static AgentGatewayOuterClass.SessionAccept accept(LogsHarness h, String label) {
        return h.sent(label).stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasSessionAccept)
                .findFirst().orElseThrow().getSessionAccept();
    }

    @Test
    void replacingTheLogsStreamEndsTheRequestsWaitingOnTheOldOneAndFreesTheirSlots() throws Exception {
        try (LogsHarness h = new LogsHarness()) {
            h.connect("capable", true);
            var page = h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG);
            LogExportHandle export = h.fleet.service.logPort().openExport(ref("capable"), PLACEMENT, 0, LONG, 1_000_000);
            var held = new java.util.ArrayList<java.util.concurrent.CompletableFuture<AgentLogPage>>();
            for (int i = 0; i < 2; i++) held.add(h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG));
            // the stream flaps: the agent reopens Logs while the old stream has not yet reported its end
            var oldIn = h.logsIn.get("capable");
            h.openLogs("capable");
            assertEquals(Optional.of("NO_SESSION"), page.get(5, TimeUnit.SECONDS).unavailableReason());
            assertEquals(LogExportHandle.State.SESSION_LOST, export.state());
            for (var f : held) assertEquals(Optional.of("NO_SESSION"), f.get(5, TimeUnit.SECONDS).unavailableReason());
            // all four slots are free again, and requests on the new stream are untouched by the old stream's late end
            var fresh = new java.util.ArrayList<java.util.concurrent.CompletableFuture<AgentLogPage>>();
            for (int i = 0; i < h.fleet.properties.getLogMaxInFlightPerAgent(); i++) {
                fresh.add(h.fleet.service.logPort().requestPage(ref("capable"), PLACEMENT, 5, null, null, LONG));
            }
            assertTrue(fresh.stream().noneMatch(java.util.concurrent.CompletableFuture::isDone), "no BUSY page: the slots were freed");
            oldIn.onCompleted();      // the old stream's late end must not touch the new stream's requests
            assertTrue(fresh.stream().noneMatch(java.util.concurrent.CompletableFuture::isDone));
        }
    }
}
