package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Plan 016 task 9: the per-placement cap is validated, stored first, then pushed; no REST write exists. */
class PlacementLogRetentionTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final AgentSessionRef SESSION = new AgentSessionRef(UUID.randomUUID(), 3);
    private static final long MAX = 1_000_000L;
    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    /** Everything either fake did, in order. */
    private static final List<String> EVENTS = new ArrayList<>();

    private static final class FakeRows implements PlacementLogRetentionRows {
        final Map<String, Long> stored = new HashMap<>();
        final List<Instant> writes = new ArrayList<>();
        RuntimeException upsertFailure;

        @Override
        public boolean isLiveAgentPlacement(UUID tenantId, UUID placementId) {
            return tenantId.equals(ORG) && placementId.equals(PLACEMENT);
        }

        @Override
        public void upsert(UUID tenantId, UUID placementId, long maxBytes, Instant at) {
            EVENTS.add("upsert");
            if (upsertFailure != null) throw upsertFailure;
            stored.put(tenantId + "/" + placementId, maxBytes);
            writes.add(at);
        }

        @Override
        public Optional<Long> find(UUID tenantId, UUID placementId) {
            return Optional.ofNullable(stored.get(tenantId + "/" + placementId));
        }
    }

    private static final class FakePort implements AgentLogPort {
        final List<Object[]> sent = new ArrayList<>();
        Optional<AgentSessionRef> session = Optional.of(SESSION);
        RuntimeException failure;

        @Override
        public Optional<AgentSessionRef> sessionFor(UUID organizationId, UUID placementId) {
            return session;
        }

        @Override
        public void sendRetention(AgentSessionRef ref, UUID placementId, long maxBytes) {
            EVENTS.add("push");
            if (failure != null) throw failure;
            sent.add(new Object[] {ref, placementId, maxBytes});
        }

        @Override
        public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef ref, UUID placementId, int limit, Instant after,
                Instant before, Duration deadline) {
            throw new AssertionError();
        }

        @Override
        public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
            throw new AssertionError();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void clear() {
        EVENTS.clear();
    }

    private final FakeRows rows = new FakeRows();
    private final FakePort port = new FakePort();
    private final PlacementLogRetention service =
            new PlacementLogRetention(rows, port, Clock.fixed(NOW, ZoneOffset.UTC), MAX);

    @Test
    void setCapPersistsAndPushes() {
        assertEquals(PlacementLogRetention.Outcome.STORED_AND_PUSHED, service.setCap(ORG, PLACEMENT, 4096));
        assertEquals(Optional.of(4096L), rows.find(ORG, PLACEMENT));
        assertEquals(List.of(NOW), rows.writes);
        assertEquals(1, port.sent.size());
        assertEquals(SESSION, port.sent.get(0)[0]);
        assertEquals(PLACEMENT, port.sent.get(0)[1]);
        assertEquals(4096L, port.sent.get(0)[2]);
        service.setCap(ORG, PLACEMENT, MAX);                    // the bound itself is allowed; a second set replaces
        assertEquals(Optional.of(MAX), rows.find(ORG, PLACEMENT));
    }

    @Test
    void zeroTurnsCollectionOff() {
        service.setCap(ORG, PLACEMENT, 4096);
        service.setCap(ORG, PLACEMENT, 0);
        assertEquals(Optional.of(0L), rows.find(ORG, PLACEMENT));
        assertEquals(0L, port.sent.get(1)[2]);
    }

    @Test
    void overMaximumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.setCap(ORG, PLACEMENT, MAX + 1));
        assertThrows(IllegalArgumentException.class, () -> service.setCap(ORG, PLACEMENT, -1));
        assertTrue(rows.stored.isEmpty(), "a rejected cap is neither stored");
        assertTrue(port.sent.isEmpty(), "nor sent");
    }

    @Test
    void otherOrganizationsPlacementIsUnresolvable() {
        assertThrows(PlacementLogRetention.UnresolvablePlacementException.class,
                () -> service.setCap(OTHER_ORG, PLACEMENT, 10));
        assertThrows(PlacementLogRetention.UnresolvablePlacementException.class,
                () -> service.setCap(ORG, UUID.randomUUID(), 10));       // unknown, retired and non-agent look the same
        assertTrue(rows.stored.isEmpty());
        assertTrue(port.sent.isEmpty());
    }

    @Test
    void noSessionStillStoresSoTheValueIsSentAtConnect() {
        port.session = Optional.empty();
        assertEquals(PlacementLogRetention.Outcome.STORED_AGENT_NOT_CONNECTED, service.setCap(ORG, PLACEMENT, 77));
        assertEquals(Optional.of(77L), rows.find(ORG, PLACEMENT));
        assertTrue(port.sent.isEmpty());
    }

    @Test
    void aFailedPushAfterTheStoreKeepsTheStoredValueAndSaysSo() {
        port.failure = new IllegalStateException("stream gone");
        assertEquals(PlacementLogRetention.Outcome.STORED_PUSH_FAILED, service.setCap(ORG, PLACEMENT, 88));
        assertEquals(Optional.of(88L), rows.find(ORG, PLACEMENT));
    }

    @Test
    void theCapIsStoredBeforeItIsPushed() {
        service.setCap(ORG, PLACEMENT, 10);
        assertEquals(List.of("upsert", "push"), EVENTS);
    }

    @Test
    void aFailingStoreSendsNothingAndPropagates() {
        rows.upsertFailure = new IllegalStateException("database down");
        assertThrows(IllegalStateException.class, () -> service.setCap(ORG, PLACEMENT, 10));
        assertEquals(List.of("upsert"), EVENTS, "the agent is never told a value that was not stored");
        assertTrue(port.sent.isEmpty());
    }
}
