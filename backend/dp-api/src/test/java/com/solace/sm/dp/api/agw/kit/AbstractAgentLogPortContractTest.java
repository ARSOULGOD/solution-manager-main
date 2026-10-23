package com.solace.sm.dp.api.agw.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentLogChunk;
import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogLineView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What every {@link AgentLogPort} must do (plan 016 task 7, AGW-D2's "every in-process port has contract tests that a future remote
 * adapter must pass"). The port reaches only the sessions the implementation holds (single replica); a remote adapter must route to
 * the replica that holds the session and pass the same cases.
 *
 * <p>An implementation supplies the hooks: a fake agent for each labelled session that can receive what the port sends and answer
 * as a real agent would. No gRPC type appears here.
 */
public abstract class AbstractAgentLogPortContractTest {

    protected abstract AgentLogPort port();

    /** Opens a live accepted session for a new agent; {@code withLogs} says whether it advertised log capability. */
    protected abstract void connect(String label, boolean withLogs);

    /** The session ref the port knows the agent {@code label} by. */
    protected abstract AgentSessionRef session(String label);

    /** A placement every connected agent holds in its lease. */
    protected abstract UUID leasedPlacement();

    /** A placement no agent holds. */
    protected abstract UUID unleasedPlacement();

    /** The request ids of every log request the agent has been sent, in order. */
    protected abstract List<String> requestsReceivedBy(String label);

    protected abstract void agentSendsPage(String label, String requestId, List<LogLineView> lines, List<AgentLogGap> gaps,
            Optional<String> unavailable);

    protected abstract void agentSendsChunk(String label, String requestId, long resumeSeq, List<LogLineView> lines, boolean last);

    /** The credits the agent has been granted for one request, in total. */
    protected abstract int creditsGrantedTo(String label, String requestId);

    protected abstract boolean cancelReceivedBy(String label, String requestId);

    /** The caps the agent has been sent for one placement, in order. */
    protected abstract List<Long> retentionsReceivedBy(String label, UUID placement);

    /** The most log requests one agent may have in flight before the next is answered BUSY. */
    protected abstract int perAgentBound();

    protected static final Duration LONG = Duration.ofSeconds(10);
    private static final Duration SHORT = Duration.ofMillis(150);
    private static final UUID ORG = UUID.nameUUIDFromBytes("org".getBytes());

    private static LogLineView line(String text) {
        return new LogLineView(Instant.parse("2026-10-20T10:00:00Z"), Optional.empty(), text);
    }

    private String awaitRequest(String label, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (requestsReceivedBy(label).size() < count && System.nanoTime() < end) Thread.sleep(5);
        List<String> ids = requestsReceivedBy(label);
        assertTrue(ids.size() >= count, "the agent should have been sent request " + count);
        return ids.get(count - 1);
    }

    @Test
    void onlyALogCapableSessionWithThePlacementInItsLeaseIsFound() {
        connect("plain", false);
        assertEquals(Optional.empty(), port().sessionFor(ORG, leasedPlacement()));
        connect("capable", true);
        assertEquals(Optional.of(session("capable")), port().sessionFor(ORG, leasedPlacement()));
        assertEquals(Optional.empty(), port().sessionFor(ORG, unleasedPlacement()));
    }

    @Test
    void aRequestGoesOnlyToASessionThatAdvertisedLogs() throws Exception {
        connect("plain", false);
        AgentLogPage page = port().requestPage(session("plain"), leasedPlacement(), 10, null, null, LONG).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of("NO_LOGS_CAPABILITY"), page.unavailableReason());
        assertTrue(requestsReceivedBy("plain").isEmpty(), "nothing is sent to an agent without the capability");
    }

    @Test
    void aPlacementOutsideTheLeaseIsRefusedAndNothingIsSent() throws Exception {
        connect("capable", true);
        AgentLogPage page = port().requestPage(session("capable"), unleasedPlacement(), 10, null, null, LONG).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), page.unavailableReason());
        LogExportHandle export = port().openExport(session("capable"), unleasedPlacement(), 0, LONG, 1_000_000);
        assertEquals(LogExportHandle.State.UNAVAILABLE, export.state());
        assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), export.unavailableReason());
        assertTrue(requestsReceivedBy("capable").isEmpty());
    }

    @Test
    void anUnknownSessionIsRefusedWithoutBeingAsked() throws Exception {
        connect("capable", true);
        AgentSessionRef stale = new AgentSessionRef(session("capable").agentId(), session("capable").fencingEpoch() + 1);
        AgentLogPage page = port().requestPage(stale, leasedPlacement(), 10, null, null, LONG).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of("NO_SESSION"), page.unavailableReason());
        assertTrue(requestsReceivedBy("capable").isEmpty());
    }

    @Test
    void repliesAreMatchedByRequestIdWhateverTheirOrder() throws Exception {
        connect("capable", true);
        CompletableFuture<AgentLogPage> first = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG);
        CompletableFuture<AgentLogPage> second = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG);
        String firstId = awaitRequest("capable", 1);
        String secondId = awaitRequest("capable", 2);
        assertFalse(firstId.equals(secondId), "request ids are unique");
        AgentLogGap gap = new AgentLogGap(Instant.parse("2026-10-20T09:00:00Z"), Instant.parse("2026-10-20T09:30:00Z"), "DROPPED_OLDEST");
        agentSendsPage("capable", secondId, List.of(line("two")), List.of(gap), Optional.empty());
        agentSendsPage("capable", firstId, List.of(line("one")), List.of(), Optional.empty());
        assertEquals("one", first.get(5, TimeUnit.SECONDS).lines().get(0).message());
        AgentLogPage secondPage = second.get(5, TimeUnit.SECONDS);
        assertEquals("two", secondPage.lines().get(0).message());
        assertEquals(List.of(gap), secondPage.gaps());
    }

    @Test
    void anAgentRefusalIsAPageWithItsReason() throws Exception {
        connect("capable", true);
        CompletableFuture<AgentLogPage> page = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG);
        agentSendsPage("capable", awaitRequest("capable", 1), List.of(), List.of(), Optional.of("NOT_COLLECTING"));
        assertEquals(Optional.of("NOT_COLLECTING"), page.get(5, TimeUnit.SECONDS).unavailableReason());
    }

    @Test
    void anUnknownOrDuplicateReplyChangesNothing() throws Exception {
        connect("capable", true);
        agentSendsPage("capable", "no-such-request", List.of(line("stray")), List.of(), Optional.empty());
        CompletableFuture<AgentLogPage> page = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG);
        String id = awaitRequest("capable", 1);
        agentSendsPage("capable", id, List.of(line("real")), List.of(), Optional.empty());
        assertEquals("real", page.get(5, TimeUnit.SECONDS).lines().get(0).message());
        agentSendsPage("capable", id, List.of(line("duplicate")), List.of(), Optional.empty());      // must not throw or resurrect it
        assertEquals("real", page.get().lines().get(0).message());
    }

    @Test
    void noReplyByTheDeadlineIsATimeoutPageAndAnAgentCancelThenALateReplyIsIgnored() throws Exception {
        connect("capable", true);
        CompletableFuture<AgentLogPage> page = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, SHORT);
        String id = awaitRequest("capable", 1);
        assertEquals(Optional.of("TIMEOUT"), page.get(5, TimeUnit.SECONDS).unavailableReason());
        assertTrue(cancelReceivedBy("capable", id), "the agent is told to stop");
        agentSendsPage("capable", id, List.of(line("late")), List.of(), Optional.empty());
        assertEquals(Optional.of("TIMEOUT"), page.get().unavailableReason());
    }

    @Test
    void moreRequestsThanTheBoundAreBusyNotQueued() throws Exception {
        connect("capable", true);
        List<CompletableFuture<AgentLogPage>> held = new ArrayList<>();
        for (int i = 0; i < perAgentBound(); i++) {
            held.add(port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG));
        }
        AgentLogPage over = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, LONG).get(5, TimeUnit.SECONDS);
        assertEquals(Optional.of("BUSY"), over.unavailableReason());
        assertEquals(perAgentBound(), requestsReceivedBy("capable").size(), "the refused request is not sent");
        agentSendsPage("capable", awaitRequest("capable", 1), List.of(line("x")), List.of(), Optional.empty());
        held.get(0).get(5, TimeUnit.SECONDS);
        AgentLogPage again = port().requestPage(session("capable"), leasedPlacement(), 10, null, null, SHORT).get(5, TimeUnit.SECONDS);
        assertFalse(again.unavailableReason().equals(Optional.of("BUSY")), "a finished request frees its slot");
    }

    @Test
    void anExportDeliversChunksInOrderGrantsCreditAsTheyAreTakenAndEndsOnTheFinalChunk() throws Exception {
        connect("capable", true);
        LogExportHandle export = port().openExport(session("capable"), leasedPlacement(), 0, LONG, 1_000_000);
        String id = awaitRequest("capable", 1);
        assertEquals(0, creditsGrantedTo("capable", id), "the agent starts with its own initial credits; none is sent up front");
        agentSendsChunk("capable", id, 2, List.of(line("a"), line("b")), false);
        agentSendsChunk("capable", id, 3, List.of(line("c")), false);
        AgentLogChunk first = export.next(Duration.ofSeconds(5)).orElseThrow();
        assertEquals(2, first.resumeSeq());
        assertEquals(1, creditsGrantedTo("capable", id), "one credit back per chunk taken");
        AgentLogChunk second = export.next(Duration.ofSeconds(5)).orElseThrow();
        assertEquals(3, second.resumeSeq());
        assertEquals(2, creditsGrantedTo("capable", id));
        assertEquals(LogExportHandle.State.OPEN, export.state());
        agentSendsChunk("capable", id, 4, List.of(), true);
        AgentLogChunk last = export.next(Duration.ofSeconds(5)).orElseThrow();
        assertTrue(last.last());
        assertEquals(2, creditsGrantedTo("capable", id), "no credit after the final chunk");
        awaitState(export, LogExportHandle.State.COMPLETE);
        assertEquals(Optional.empty(), export.next(Duration.ofMillis(10)));
    }

    @Test
    void anExportRefusalArrivesAsAPageAndEndsTheExportWithItsReason() throws Exception {
        connect("capable", true);
        LogExportHandle export = port().openExport(session("capable"), leasedPlacement(), 0, LONG, 1_000_000);
        agentSendsPage("capable", awaitRequest("capable", 1), List.of(), List.of(), Optional.of("PLACEMENT_NOT_IN_LEASE"));
        awaitState(export, LogExportHandle.State.UNAVAILABLE);
        assertEquals(Optional.of("PLACEMENT_NOT_IN_LEASE"), export.unavailableReason());
    }

    @Test
    void cancellingAnExportTellsTheAgentAndSendsNoFinalChunkExpectation() throws Exception {
        connect("capable", true);
        LogExportHandle export = port().openExport(session("capable"), leasedPlacement(), 5, LONG, 1_000_000);
        String id = awaitRequest("capable", 1);
        export.cancel();
        assertEquals(LogExportHandle.State.CANCELLED, export.state());
        assertTrue(cancelReceivedBy("capable", id));
        export.cancel();      // idempotent
    }

    @Test
    void anExportThatMissesItsDeadlineTimesOutAndTheAgentIsCancelled() throws Exception {
        connect("capable", true);
        LogExportHandle export = port().openExport(session("capable"), leasedPlacement(), 0, SHORT, 1_000_000);
        String id = awaitRequest("capable", 1);
        awaitState(export, LogExportHandle.State.TIMED_OUT);
        assertTrue(cancelReceivedBy("capable", id));
    }

    @Test
    void anExportStopsAtItsTotalSizeBound() throws Exception {
        connect("capable", true);
        LogExportHandle export = port().openExport(session("capable"), leasedPlacement(), 0, LONG, 4);
        String id = awaitRequest("capable", 1);
        agentSendsChunk("capable", id, 1, List.of(line("12345")), false);
        awaitState(export, LogExportHandle.State.SIZE_LIMIT);
        assertTrue(cancelReceivedBy("capable", id));
        assertTrue(export.next(Duration.ofSeconds(1)).isPresent(), "what arrived is still readable");
    }

    @Test
    void retentionIsSentOnlyToACapableSessionForAPlacementInItsLease() {
        connect("plain", false);
        connect("capable", true);
        port().sendRetention(session("plain"), leasedPlacement(), 1000);
        port().sendRetention(session("capable"), unleasedPlacement(), 1000);
        port().sendRetention(session("capable"), leasedPlacement(), 2048);
        assertTrue(retentionsReceivedBy("plain", leasedPlacement()).isEmpty());
        assertTrue(retentionsReceivedBy("capable", unleasedPlacement()).isEmpty());
        assertEquals(List.of(2048L), retentionsReceivedBy("capable", leasedPlacement()));
    }

    private static void awaitState(LogExportHandle export, LogExportHandle.State expected) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (export.state() != expected && System.nanoTime() < end) Thread.sleep(5);
        assertEquals(expected, export.state());
    }
}
