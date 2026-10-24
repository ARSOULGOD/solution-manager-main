package com.solace.sm.dp.api.agw.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigView.Entry;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import com.solace.sm.dp.api.agw.AgentConfigView.Status;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What every {@link AgentConfigViewPort} must do (plan 017 task 4; AGW-D2's "every in-process port has contract tests that a future
 * remote adapter must pass"). The port reaches only the sessions the implementation holds; a remote adapter must route to the replica
 * that holds the session and pass the same cases.
 *
 * <p>An implementation supplies a fake agent per labelled session that can see what the port sends and answer as a real agent
 * would. No gRPC type appears here. {@link AgentConfigViewPort#read} blocks, so the kit calls it on its own threads.
 */
public abstract class AbstractAgentConfigViewPortContractTest {

    protected abstract AgentConfigViewPort port();

    /** Opens a live accepted session for a new agent; its lease names {@link #leasedPlacement()}. */
    protected abstract void connect(String label);

    /** Ends the agent's session (its stream closes). */
    protected abstract void disconnect(String label);

    /** The agent id (a UUID) the port knows the agent {@code label} by. */
    protected abstract String agentId(String label);

    /** A placement every connected agent holds in its lease. */
    protected abstract String leasedPlacement();

    /** A placement no agent holds. */
    protected abstract String unleasedPlacement();

    /** The request ids of every config view request the agent has been sent, in order. */
    protected abstract List<String> requestsReceivedBy(String label);

    /** The agent {@code label} answers {@code requestId} with an OK reply over its own session. */
    protected abstract void agentRepliesOk(String label, String requestId, List<Entry> entries, int hiddenCount, boolean truncated);

    /** The agent {@code label} answers {@code requestId} with a refusal over its own session. */
    protected abstract void agentRefuses(String label, String requestId, Reason reason);

    /** A permission change takes {@link #leasedPlacement()} out of the agent's lease. */
    protected abstract void placementLeavesLease(String label);

    /** How long the implementation waits for a reply. Short in a test (the timeout case waits it out). */
    protected abstract Duration deadline();

    protected static final String TENANT = "8a0f3e7c-0000-4000-8000-000000000001";
    private static final List<String> ALLOW = List.of("server.port", "cache.*");
    private final ExecutorService callers = Executors.newCachedThreadPool();

    @AfterEach
    void stopCallers() {
        callers.shutdownNow();
    }

    private CompletableFuture<AgentConfigView> readAsync(String label, String placement) {
        String agent = agentId(label);
        return CompletableFuture.supplyAsync(() -> port().read(TENANT, agent, placement, ALLOW, 65536), callers);
    }

    private AgentConfigView readNow(String label, String placement) {
        return port().read(TENANT, agentId(label), placement, ALLOW, 65536);
    }

    private String awaitRequest(String label, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (requestsReceivedBy(label).size() < count && System.nanoTime() < end) Thread.sleep(5);
        List<String> ids = requestsReceivedBy(label);
        assertTrue(ids.size() >= count, "the agent should have been sent request " + count);
        return ids.get(count - 1);
    }

    private static AgentConfigView await(CompletableFuture<AgentConfigView> view) throws Exception {
        return view.get(5, TimeUnit.SECONDS);
    }

    private long waitBound() { return deadline().toMillis() + 5_000; }

    /** Proves the agent's slot is free: a new request is sent (not refused BUSY), then answered so it holds nothing either. */
    private void assertSlotFree(String label) throws Exception {
        int before = requestsReceivedBy(label).size();
        CompletableFuture<AgentConfigView> probe = readAsync(label, leasedPlacement());
        String id = awaitRequest(label, before + 1);
        agentRepliesOk(label, id, List.of(), 0, false);
        assertEquals(Status.OK, await(probe).status(), "the agent's slot was released");
    }

    @Test
    void anOkReplyIsRoutedByRequestIdAndCarriesItsEntries() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        String id = awaitRequest("a", 1);
        agentRepliesOk("a", id, List.of(new Entry("server.port", "8080"), new Entry("cache.size", "64")), 3, true);
        AgentConfigView got = await(view);
        assertEquals(Status.OK, got.status());
        assertEquals(List.of(new Entry("server.port", "8080"), new Entry("cache.size", "64")), got.entries());
        assertEquals(3, got.hiddenCount());
        assertTrue(got.truncated());
        assertEquals(null, got.reason());
    }

    @Test
    void repliesOfTwoAgentsAreMatchedByRequestIdWhateverTheirOrder() throws Exception {
        connect("a");
        connect("b");
        CompletableFuture<AgentConfigView> first = readAsync("a", leasedPlacement());
        CompletableFuture<AgentConfigView> second = readAsync("b", leasedPlacement());
        String firstId = awaitRequest("a", 1);
        String secondId = awaitRequest("b", 1);
        assertNotEquals(firstId, secondId, "request ids are unique");
        agentRepliesOk("b", secondId, List.of(new Entry("k", "b")), 0, false);
        agentRepliesOk("a", firstId, List.of(new Entry("k", "a")), 0, false);
        assertEquals("a", await(first).entries().get(0).value());
        assertEquals("b", await(second).entries().get(0).value());
    }

    @Test
    void aSecondRequestForTheSameAgentIsBusyAndNotSent() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> held = readAsync("a", leasedPlacement());
        String id = awaitRequest("a", 1);
        assertEquals(AgentConfigView.busy(), readNow("a", leasedPlacement()));
        assertEquals(1, requestsReceivedBy("a").size(), "the refused request is not sent");
        agentRepliesOk("a", id, List.of(), 0, false);
        assertEquals(Status.OK, await(held).status());
        assertSlotFree("a");
    }

    @Test
    void anAgentRefusalIsAViewWithItsReasonAndFreesTheSlot() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        agentRefuses("a", awaitRequest("a", 1), Reason.NOT_PERMITTED);
        assertEquals(AgentConfigView.refused(Reason.NOT_PERMITTED), await(view));
        assertSlotFree("a");
    }

    @Test
    void noReplyByTheDeadlineIsUnavailableFreesTheSlotAndALateReplyIsDropped() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        String id = awaitRequest("a", 1);
        AgentConfigView got = view.get(waitBound(), TimeUnit.MILLISECONDS);
        assertEquals(AgentConfigView.unavailable(), got, "an agent that never answers (an old one) ends at the deadline");
        agentRepliesOk("a", id, List.of(new Entry("late", "x")), 0, false);      // must not throw or complete anything
        assertSlotFree("a");
    }

    @Test
    void aPlacementOutsideTheLeaseIsRefusedAndNothingIsSent() {
        connect("a");
        assertEquals(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE), readNow("a", unleasedPlacement()));
        assertTrue(requestsReceivedBy("a").isEmpty());
    }

    @Test
    void anAgentWithoutASessionIsUnavailableWithoutBeingAsked() {
        connect("a");
        String stranger = "00000000-0000-4000-8000-0000000000ff";
        assertEquals(AgentConfigView.unavailable(), port().read(TENANT, stranger, leasedPlacement(), ALLOW, 1024));
        assertTrue(requestsReceivedBy("a").isEmpty());
    }

    @Test
    void aPlacementLeavingTheLeaseEndsItsPendingViewAtOnceAndFreesTheSlot() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        awaitRequest("a", 1);
        placementLeavesLease("a");
        AgentConfigView got = view.get(Math.max(1, deadline().toMillis() / 2), TimeUnit.MILLISECONDS);
        assertEquals(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE), got, "it does not wait out its deadline");
        assertEquals(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE), readNow("a", leasedPlacement()),
                "refused for the lease, not BUSY: the slot was released");
    }

    @Test
    void theSessionEndingEndsItsPendingViewAtOnceAndFreesTheSlot() throws Exception {
        connect("a");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        awaitRequest("a", 1);
        disconnect("a");
        AgentConfigView got = view.get(Math.max(1, deadline().toMillis() / 2), TimeUnit.MILLISECONDS);
        assertEquals(AgentConfigView.unavailable(), got);
        connect("a");
        assertSlotFree("a");
    }

    @Test
    void aReplyForAnUnknownOrFinishedRequestIsDropped() throws Exception {
        connect("a");
        agentRepliesOk("a", "no-such-request", List.of(new Entry("stray", "x")), 0, false);
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        String id = awaitRequest("a", 1);
        agentRepliesOk("a", id, List.of(new Entry("real", "1")), 0, false);
        assertEquals("real", await(view).entries().get(0).path());
        agentRepliesOk("a", id, List.of(new Entry("duplicate", "2")), 0, false);      // must not throw or resurrect it
        assertSlotFree("a");
    }

    @Test
    void aReplyFromAnotherAgentsSessionNeverAnswersTheRequest() throws Exception {
        connect("a");
        connect("b");
        CompletableFuture<AgentConfigView> view = readAsync("a", leasedPlacement());
        String id = awaitRequest("a", 1);
        agentRepliesOk("b", id, List.of(new Entry("forged", "x")), 0, false);
        Thread.sleep(50);
        assertFalse(view.isDone(), "only the session the request was sent to can answer it");
        agentRepliesOk("a", id, List.of(new Entry("genuine", "y")), 0, false);
        assertEquals("genuine", await(view).entries().get(0).path());
    }
}
