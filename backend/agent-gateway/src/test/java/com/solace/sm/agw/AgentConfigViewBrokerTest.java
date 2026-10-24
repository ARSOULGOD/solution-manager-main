package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.ConfigViewReply;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;

/** Plan 017 task 4: the broker's slot and deadline discipline over a fake link (every exit releases the agent's one slot). */
class AgentConfigViewBrokerTest {
    private static final UUID AGENT = UUID.nameUUIDFromBytes("agent".getBytes());
    private static final UUID PLACEMENT = UUID.nameUUIDFromBytes("placement".getBytes());
    private static final AgentSessionRef REF = new AgentSessionRef(AGENT, 1);
    private static final String TENANT = "tenant";

    /** A link whose send runs {@code onSend} and reports its answer. */
    private static final class FakeLink implements AgentConfigViewBroker.Link {
        final List<GatewayToAgent> sent = new CopyOnWriteArrayList<>();
        volatile Set<UUID> lease = Set.of(PLACEMENT);
        volatile Predicate<GatewayToAgent> onSend = m -> true;
        volatile boolean configView = true;
        /** Runs on each lease check, before it is answered (a test narrows the lease from here). */
        volatile Runnable onLeaseCheck = () -> { };
        @Override public AgentSessionRef ref() { return REF; }
        @Override public boolean inLease(UUID placementId) {
            onLeaseCheck.run();
            return lease.contains(placementId);
        }
        @Override public boolean configViewSupported() { return configView; }
        @Override public boolean sendMessage(GatewayToAgent message) {
            sent.add(message);
            return onSend.test(message);
        }
    }

    private final AgentGatewayMetrics metrics = new AgentGatewayMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private AgentConfigViewBroker broker(FakeLink link, Duration deadline) {
        return new AgentConfigViewBroker(agent -> AGENT.equals(agent) ? link : null, () -> deadline, metrics);
    }

    private static CompletableFuture<AgentConfigView> readAsync(AgentConfigViewBroker broker) {
        return CompletableFuture.supplyAsync(() -> broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of("k"), 100));
    }

    private static String awaitSent(FakeLink link, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (link.sent.size() < count && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(link.sent.size() >= count);
        return link.sent.get(count - 1).getConfigViewRequest().getRequestId();
    }

    private static ConfigViewReply ok(String id) {
        return ConfigViewReply.newBuilder().setRequestId(id).setStatus(AgentGatewayOuterClass.ConfigViewStatus.CONFIG_VIEW_STATUS_OK).build();
    }

    @Test
    void aSendFailureIsUnavailableAndReleasesTheSlot() {
        FakeLink link = new FakeLink();
        link.onSend = m -> false;
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        assertEquals(AgentConfigView.unavailable(), broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of(), 10));
        assertEquals(0, broker.inFlight());
    }

    @Test
    void theDeadlineStartsOnlyOnceTheRequestIsOnTheWire() throws Exception {
        FakeLink link = new FakeLink();
        AtomicReference<AgentConfigViewBroker> self = new AtomicReference<>();
        // The send takes three deadlines (a session monitor held by a slow handler); the agent answers just after it.
        link.onSend = m -> {
            try { Thread.sleep(600); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                self.get().onReply(REF, ok(m.getConfigViewRequest().getRequestId()));
            });
            return true;
        };
        AgentConfigViewBroker broker = broker(link, Duration.ofMillis(200));
        self.set(broker);
        assertEquals(AgentConfigView.Status.OK, readAsync(broker).get(5, TimeUnit.SECONDS).status());
        assertEquals(0, broker.inFlight());
    }

    @Test
    void everyExitReleasesTheSlot() throws Exception {
        FakeLink link = new FakeLink();
        AgentConfigViewBroker broker = broker(link, Duration.ofMillis(300));

        var success = readAsync(broker);
        broker.onReply(REF, ok(awaitSent(link, 1)));
        assertEquals(AgentConfigView.Status.OK, success.get(5, TimeUnit.SECONDS).status());
        assertEquals(0, broker.inFlight(), "success");

        var refusal = readAsync(broker);
        broker.onReply(REF, ConfigViewReply.newBuilder().setRequestId(awaitSent(link, 2))
                .setStatus(AgentGatewayOuterClass.ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED)
                .setReason(AgentGatewayOuterClass.ConfigViewReason.CONFIG_VIEW_REASON_FILE_UNREADABLE).build());
        assertEquals(AgentConfigView.refused(Reason.FILE_UNREADABLE), refusal.get(5, TimeUnit.SECONDS));
        assertEquals(0, broker.inFlight(), "refusal");

        var timeout = readAsync(broker);
        awaitSent(link, 3);
        assertEquals(AgentConfigView.unavailable(), timeout.get(5, TimeUnit.SECONDS));
        assertEquals(0, broker.inFlight(), "timeout");

        var malformed = readAsync(broker);
        broker.onReply(REF, ConfigViewReply.newBuilder().setRequestId(awaitSent(link, 4)).build());
        assertEquals(AgentConfigView.malformed(), malformed.get(5, TimeUnit.SECONDS));
        assertEquals(0, broker.inFlight(), "malformed reply");

        broker = broker(link, Duration.ofSeconds(10));
        AgentConfigViewBroker longer = broker;
        var leaving = readAsync(longer);
        awaitSent(link, 5);
        longer.placementsLeft(REF, Set.of());
        assertEquals(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE), leaving.get(5, TimeUnit.SECONDS));
        assertEquals(0, longer.inFlight(), "placement left the lease");

        var closed = readAsync(longer);
        awaitSent(link, 6);
        longer.sessionClosed(REF);
        assertEquals(AgentConfigView.unavailable(), closed.get(5, TimeUnit.SECONDS));
        assertEquals(0, longer.inFlight(), "session ended");
    }

    @Test
    void aPlacementThatStaysInTheLeaseKeepsItsView() throws Exception {
        FakeLink link = new FakeLink();
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        var view = readAsync(broker);
        String id = awaitSent(link, 1);
        broker.placementsLeft(REF, Set.of(PLACEMENT));
        broker.placementsLeft(new AgentSessionRef(UUID.randomUUID(), 1), Set.of());      // another agent's lease
        Thread.sleep(50);
        assertEquals(1, broker.inFlight());
        broker.onReply(REF, ok(id));
        assertEquals(AgentConfigView.Status.OK, view.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void aReplyFromAnOlderSessionOfTheSameAgentNeverAnswers() throws Exception {
        FakeLink link = new FakeLink();
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        var view = readAsync(broker);
        String id = awaitSent(link, 1);
        broker.onReply(new AgentSessionRef(AGENT, 2), ok(id));
        Thread.sleep(50);
        assertEquals(1, broker.inFlight(), "a different fencing epoch is a different session");
        broker.onReply(REF, ok(id));
        assertEquals(AgentConfigView.Status.OK, view.get(5, TimeUnit.SECONDS).status());
    }

    @Test
    void anInterruptedCallerReleasesTheSlotAndKeepsItsInterrupt() throws Exception {
        FakeLink link = new FakeLink();
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        AtomicReference<AgentConfigView> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            result.set(broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of(), 10));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        awaitSent(link, 1);
        caller.interrupt();
        caller.join(5000);
        assertEquals(AgentConfigView.unavailable(), result.get());
        assertEquals(Boolean.TRUE, interrupted.get());
        assertEquals(0, broker.inFlight());
    }

    @Test
    void anAgentThatDidNotAdvertiseTheCapabilityIsUnavailableAndIsSentNothing() {
        FakeLink link = new FakeLink();
        link.configView = false;
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        assertEquals(AgentConfigView.unavailable(), broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of(), 10));
        assertTrue(link.sent.isEmpty());
        assertEquals(0, broker.inFlight());
    }

    @Test
    void aLeaseThatNarrowsBetweenTheCheckAndTheAdmitIsRefusedAndNothingIsSent() {
        FakeLink link = new FakeLink();
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        // The first check sees the placement; the lease is narrowed right after it, as a re-issue racing the read would.
        link.onLeaseCheck = () -> { if (checks.incrementAndGet() == 2) link.lease = Set.of(); };
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        assertEquals(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE),
                broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of(), 10));
        assertTrue(link.sent.isEmpty(), "the request is not sent for a placement that already left");
        assertEquals(0, broker.inFlight());
    }

    /** An unexpected failure of the send. */
    private static final class SendBroke extends Error {
        SendBroke() { super("send broke"); }
    }

    @Test
    void anUnexpectedErrorFromTheSendStillReleasesTheSlot() {
        FakeLink link = new FakeLink();
        link.onSend = m -> { throw new SendBroke(); };
        AgentConfigViewBroker broker = broker(link, Duration.ofSeconds(10));
        org.junit.jupiter.api.Assertions.assertThrows(SendBroke.class,
                () -> broker.read(TENANT, AGENT.toString(), PLACEMENT.toString(), List.of(), 10));
        assertEquals(0, broker.inFlight(), "an Error is rethrown, but the slot is not leaked");
    }
}
