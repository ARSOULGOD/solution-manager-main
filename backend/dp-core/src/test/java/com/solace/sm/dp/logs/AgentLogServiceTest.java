package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Plan 016 task 8: the data plane serves an agent placement's logs through {@link LogService}, over the gateway's port. */
class AgentLogServiceTest extends LogServiceContractTest {

    private static final AgentSessionRef SESSION = new AgentSessionRef(UUID.randomUUID(), 1);
    private static final Instant T = Instant.parse("2026-10-06T06:25:01Z");

    /** The port the DP consumes: records what it was asked, answers what the test sets. */
    private static class FakePort implements AgentLogPort {
        final List<Object[]> requests = new ArrayList<>();
        final AtomicInteger sessionLookups = new AtomicInteger();
        Optional<AgentSessionRef> session = Optional.of(SESSION);
        AgentLogPage answer = new AgentLogPage(List.of(new LogLineView(T, Optional.empty(), "a line")), List.of(), Optional.empty());
        CompletableFuture<AgentLogPage> pending;

        @Override
        public Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId) {
            sessionLookups.incrementAndGet();
            return session;
        }

        @Override
        public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after,
                Instant before, Duration deadline) {
            requests.add(new Object[] {ref, placementId, limit, after, before, deadline});
            return pending != null ? pending : CompletableFuture.completedFuture(answer);
        }

        @Override
        public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
            throw new AssertionError();
        }

        @Override
        public void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes) {
            throw new AssertionError();
        }
    }

    /** Transport answers for exactly the known (organization, instance, placement) key: agent. */
    static final PlacementTransports KNOWN = new PlacementTransports() {
        @Override
        public Optional<String> transportOf(PlacementRef p) {
            boolean known = p.tenant().value().equals(ORGANIZATION) && p.instance().value().equals(INSTANCE)
                    && p.placement().value().equals(PLACEMENT);
            return known ? Optional.of(AGENT) : Optional.empty();
        }

        @Override
        public boolean isAgentTarget(UUID tenantId, UUID targetId) {
            return true;
        }
    };

    private FakePort port = new FakePort();

    private LogService over(FakePort p, int slots) {
        return wrap(new AgentLogService(KNOWN, p, Duration.ofSeconds(5), slots, Duration.ZERO));
    }

    /** The service under test; the router contract run wraps the agent service in the wired {@link RoutingLogService}. */
    LogService wrap(AgentLogService agent) {
        return agent;
    }

    @Override
    LogService service() {
        return over(port, 4);
    }

    @Override
    LogService blockingService(CountDownLatch entered, CountDownLatch release) {
        FakePort blocking = new FakePort() {
            @Override
            public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit,
                    Instant after, Instant before, Duration deadline) {
                entered.countDown();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return answer;
                });
            }
        };
        return over(blocking, 1);
    }

    @Override
    boolean untouched() {
        return port.requests.isEmpty() && port.sessionLookups.get() == 0;
    }

    @Test
    void readsPageThroughPortAndDerivesTheLevelFromTheText() {
        port.answer = new AgentLogPage(List.of(
                new LogLineView(T, Optional.empty(), "2026-10-06 06:25:01.123 [main] ERROR boom"),
                new LogLineView(T.plusSeconds(1), Optional.empty(), "the ERROR happened")), List.of(), Optional.empty());
        Instant after = T.minusSeconds(60);
        Instant before = T.plusSeconds(60);

        LogPageView page = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 25, after, before);

        assertEquals(1, port.requests.size());
        Object[] sent = port.requests.get(0);
        assertEquals(SESSION, sent[0]);
        assertEquals(PLACEMENT, sent[1]);
        assertEquals(25, sent[2]);
        assertEquals(after, sent[3]);
        assertEquals(before, sent[4]);
        assertEquals(Duration.ofSeconds(5), sent[5]);
        assertEquals(Optional.of("ERROR"), page.lines().get(0).level());
        assertEquals(Optional.empty(), page.lines().get(1).level(), "a line that merely mentions a level has none");
        assertEquals(T, page.lines().get(0).at());
        assertTrue(page.unavailableReason().isEmpty());
    }

    private static LogLineView at(int second, String message) {
        return new LogLineView(T.plusSeconds(second), Optional.empty(), message);
    }

    @Test
    void anAgentThatOverReturnsIsHeldToTheLimit() {
        port.answer = new AgentLogPage(List.of(at(1, "a"), at(2, "b"), at(3, "c"), at(4, "d")), List.of(), Optional.empty());

        LogPageView page = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 2, null, null);

        assertEquals(List.of("c", "d"), page.lines().stream().map(LogLineView::message).toList(), "the NEWEST limit lines");
    }

    @Test
    void anAgentThatReturnsLinesOutsideTheWindowIsHeldToTheExclusiveWindow() {
        port.answer = new AgentLogPage(List.of(at(1, "edge-after"), at(2, "in"), at(3, "edge-before"), at(9, "out")),
                List.of(), Optional.empty());

        LogPageView page = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, T.plusSeconds(1), T.plusSeconds(3));

        assertEquals(List.of("in"), page.lines().stream().map(LogLineView::message).toList());
    }

    @Test
    void anAgentThatReturnsLinesOutOfOrderIsHeldToOldestFirstKeepingEqualStampsInArrivalOrder() {
        port.answer = new AgentLogPage(List.of(at(3, "c"), at(1, "a"), at(2, "b1"), at(2, "b2")), List.of(), Optional.empty());

        LogPageView page = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

        assertEquals(List.of("a", "b1", "b2", "c"), page.lines().stream().map(LogLineView::message).toList());
    }

    @Test
    void gapsAreNotPartOfTheLogPageView() {
        port.answer = new AgentLogPage(List.of(new LogLineView(T, Optional.empty(), "x")),
                List.of(new AgentLogGap(T, T.plusSeconds(1), "DROPPED_OLDEST")), Optional.empty());
        assertEquals(1, over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null).lines().size());
    }

    @Test
    void noSessionOrMissingCapabilityReturnsFixedDegradedPage() {
        port.session = Optional.empty();
        LogPageView none = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null);
        assertEquals(Optional.of(AgentLogService.NO_SESSION), none.unavailableReason());
        assertTrue(port.requests.isEmpty());

        port.session = Optional.of(SESSION);
        for (String[] pair : new String[][] {
                {"NO_SESSION", AgentLogService.NO_SESSION},
                {"NO_LOGS_CAPABILITY", AgentLogService.NO_LOGS_CAPABILITY},
                {"NOT_COLLECTING", AgentLogService.NOT_COLLECTING},
                {"CONTAINER_MISSING", AgentLogService.CONTAINER_MISSING},
                {"BUSY", AgentLogService.AGENT_BUSY},
                {"PLACEMENT_NOT_IN_LEASE", AgentLogService.NOT_IN_LEASE},
                {"TIMEOUT", AgentLogService.TIMEOUT},
                {"SESSION_LOST", AgentLogService.SESSION_LOST},
                {"SOMETHING_NEW", AgentLogService.UNSERVED}}) {
            port.answer = AgentLogPage.unavailable(pair[0]);
            LogPageView page = over(port, 4).read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null);
            assertEquals(Optional.of(pair[1]), page.unavailableReason(), pair[0]);
            assertTrue(page.lines().isEmpty());
        }
    }

    @Test
    void timeoutReturnsFixedDegradedPageAndNamesNoHostOrContainer() {
        // the future never completes: the service bounds its own wait, and the text it gives is fixed
        port.pending = new CompletableFuture<>();
        LogService service = wrap(new AgentLogService(KNOWN, port, Duration.ofMillis(20), 4, Duration.ZERO));

        LogPageView page = service.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null);

        assertEquals(Optional.of(AgentLogService.TIMEOUT), page.unavailableReason());
        for (String text : new String[] {AgentLogService.NO_SESSION, AgentLogService.NO_LOGS_CAPABILITY,
                AgentLogService.NOT_COLLECTING, AgentLogService.CONTAINER_MISSING, AgentLogService.AGENT_BUSY,
                AgentLogService.NOT_IN_LEASE, AgentLogService.TIMEOUT, AgentLogService.SESSION_LOST,
                AgentLogService.UNSERVED, AgentLogService.UNRESOLVABLE, AgentLogService.AT_CAPACITY}) {
            assertFalse(text.contains("/") || text.matches(".*\\d+\\.\\d+.*"), "no path or address in: " + text);
            assertFalse(text.contains(PLACEMENT.toString()) || text.contains(ORGANIZATION.toString()), text);
        }
    }

    @Test
    void otherOrganizationsPlacementIsUnresolvable() {
        LogPageView page = over(port, 4).read(UUID.randomUUID(), INSTANCE, PLACEMENT, 5, null, null);

        assertEquals(Optional.of(AgentLogService.UNRESOLVABLE), page.unavailableReason());
        assertTrue(port.requests.isEmpty() && port.sessionLookups.get() == 0, "the gateway is never asked about it");
    }

    @Test
    void concurrentReadsBeyondBoundAreBusyPages() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LogService single = blockingService(entered, release);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> single.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(Optional.of(AgentLogService.AT_CAPACITY),
                    single.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null).unavailableReason());
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
