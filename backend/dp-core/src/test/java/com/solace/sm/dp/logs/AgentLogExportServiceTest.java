package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentLogChunk;
import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPage;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.LogExportHandle;
import com.solace.sm.dp.api.logs.LogExportStream;
import com.solace.sm.dp.api.logs.LogLineView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Plan 016 task 10: the data plane's export over the gateway's {@link AgentLogPort}. Pulling the stream is what consumes
 * chunks (and so grants credit); the size and time bounds, the fixed marker lines, tenant binding and cancellation are pinned
 * here with a fake handle.
 */
class AgentLogExportServiceTest {

    private static final UUID ORG = LogServiceContractTest.ORGANIZATION;
    private static final UUID INSTANCE = LogServiceContractTest.INSTANCE;
    private static final UUID PLACEMENT = LogServiceContractTest.PLACEMENT;
    private static final AgentSessionRef SESSION = new AgentSessionRef(UUID.randomUUID(), 1);
    private static final Instant T = Instant.parse("2026-10-06T06:25:01Z");

    static LogLineView line(int second, String text) {
        return new LogLineView(T.plusSeconds(second), Optional.empty(), text);
    }

    static AgentLogChunk chunk(long resume, boolean last, LogLineView... lines) {
        return new AgentLogChunk(resume, List.of(lines), List.of(), last);
    }

    /** A handle the test feeds; counts chunks handed out (one credit each in the real broker) and cancels. */
    static final class FakeHandle implements LogExportHandle {
        final LinkedBlockingQueue<AgentLogChunk> chunks = new LinkedBlockingQueue<>();
        final AtomicInteger consumed = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        volatile State state = State.OPEN;
        volatile String reason;

        @Override
        public Optional<AgentLogChunk> next(Duration wait) throws InterruptedException {
            AgentLogChunk c = chunks.poll(Math.min(wait.toMillis(), 20), TimeUnit.MILLISECONDS);
            if (c != null) {
                consumed.incrementAndGet();
            }
            return Optional.ofNullable(c);
        }

        @Override public State state() { return state; }
        @Override public Optional<String> unavailableReason() { return Optional.ofNullable(reason); }

        @Override
        public void cancel() {
            cancels.incrementAndGet();
            if (state == State.OPEN) {
                state = State.CANCELLED;
            }
        }
    }

    static final class FakePort implements AgentLogPort {
        final FakeHandle handle = new FakeHandle();
        final List<Object[]> exports = new ArrayList<>();
        Optional<AgentSessionRef> session = Optional.of(SESSION);

        @Override public Optional<AgentSessionRef> sessionFor(UUID o, UUID p) { return session; }

        @Override
        public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef r, UUID p, int l, Instant a, Instant b, Duration d) {
            throw new AssertionError();
        }

        @Override
        public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
            exports.add(new Object[] {ref, placementId, fromSeq, deadline, maxBytes});
            return handle;
        }

        @Override public void sendRetention(AgentSessionRef r, UUID p, long m) { throw new AssertionError(); }
    }

    private final FakePort port = new FakePort();

    private AgentLogExportService service(long maxBytes, Duration deadline, int concurrent) {
        return new AgentLogExportService(AgentLogServiceTest.KNOWN, AgentLogServiceTest.LIVE, port, deadline, maxBytes,
                concurrent);
    }

    private AgentLogExportService service() {
        return service(1_000_000, Duration.ofSeconds(5), 2);
    }

    /** Reads the stream to its end, ignoring keep-alives. */
    private static String drain(LogExportStream stream) throws InterruptedException {
        StringBuilder all = new StringBuilder();
        for (String piece = stream.next(); piece != null; piece = stream.next()) {
            all.append(piece);
        }
        return all.toString();
    }

    @Test
    void chunksAreStreamedInOrderOneAtATimeAsTheyArePulled() throws Exception {
        port.handle.chunks.add(chunk(3, false, line(0, "first"), line(1, "second")));
        port.handle.chunks.add(chunk(5, true, line(2, "third")));
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals(0, port.handle.consumed.get(), "opening consumes nothing: no credit before the reader pulls");

            String one = stream.next();
            assertEquals(T + " first\n" + T.plusSeconds(1) + " second\n", one);
            assertEquals(1, port.handle.consumed.get(), "exactly one chunk taken for one pull");

            String rest = drain(stream);
            assertEquals(T.plusSeconds(2) + " third\n", rest);
            assertEquals(2, port.handle.consumed.get());
        }
        Object[] open = port.exports.get(0);
        assertEquals(SESSION, open[0]);
        assertEquals(PLACEMENT, open[1]);
        assertEquals(0L, open[2]);
    }

    @Test
    void theExportStopsAtTheByteBoundWithTheFixedMarkerAndCancelsTheAgent() throws Exception {
        String text = "x".repeat(40);
        port.handle.chunks.add(chunk(2, false, line(0, text), line(1, text)));
        port.handle.chunks.add(chunk(4, false, line(2, text), line(3, text)));
        // room for the first three lines of text (40 bytes each), not the fourth
        try (LogExportStream stream = service(130, Duration.ofSeconds(5), 2).open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertEquals(3, all.lines().filter(l -> l.endsWith(text)).count(), all);
            assertTrue(all.endsWith("# sm-export: truncated: the size limit was reached; resume from seq 2\n"), all);
            assertEquals(1, port.handle.cancels.get(), "the agent request is cancelled when the bound is hit");
        }
    }

    @Test
    void aGapFromTheAgentIsAFixedMarkerLine() throws Exception {
        port.handle.chunks.add(new AgentLogChunk(3, List.of(line(0, "before"), line(9, "after")),
                List.of(new AgentLogGap(T.plusSeconds(1), T.plusSeconds(8), "DROPPED_OLDEST"),
                        new AgentLogGap(T.plusSeconds(8), T.plusSeconds(9), "/var/lib/docker/secret-path")), true));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.contains("# sm-export: gap DROPPED_OLDEST from " + T.plusSeconds(1) + " to " + T.plusSeconds(8) + "\n"),
                    all);
            assertTrue(all.contains("# sm-export: gap UNKNOWN from " + T.plusSeconds(8) + " to " + T.plusSeconds(9) + "\n"),
                    "a reason outside the closed vocabulary is never echoed");
            assertFalse(all.contains("secret-path"), all);
            assertTrue(all.endsWith("# sm-export: complete, with gaps\n"), all);
        }
    }

    @Test
    void aCompleteExportWithoutGapsHasNoMarker() throws Exception {
        port.handle.chunks.add(chunk(1, true, line(0, "only")));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals(T + " only\n", drain(stream));
        }
    }

    @Test
    void anAgentSuppliedLineCannotPassForAMarker() throws Exception {
        port.handle.chunks.add(chunk(1, true, line(0, "a\n# sm-export: complete\r\nb")));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertEquals(1, all.lines().count(), "newlines inside a line are flattened: " + all);
            assertTrue(all.startsWith(T.toString()), all);
        }
    }

    @Test
    void closingTheStreamCancelsTheAgentRequestAndFreesTheSlot() throws Exception {
        AgentLogExportService service = service(1_000_000, Duration.ofSeconds(5), 1);
        LogExportStream first = service.open(ORG, INSTANCE, PLACEMENT);
        assertEquals("", first.next(), "nothing yet is a keep-alive");

        first.close();
        first.close();

        assertEquals(1, port.handle.cancels.get(), "closing twice cancels the agent once");
        port.handle.state = LogExportHandle.State.OPEN;
        try (LogExportStream second = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("", second.next(), "the slot was released by close(), not refused as busy");
        }
    }

    @Test
    void whenAllSlotsAreTakenTheExportIsADeclaredBusyMarkerAndTheGatewayIsNotAsked() throws Exception {
        AgentLogExportService service = service(1_000_000, Duration.ofSeconds(5), 1);
        try (LogExportStream held = service.open(ORG, INSTANCE, PLACEMENT);
                LogExportStream refused = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("# sm-export: unavailable: log reads are at capacity; try again shortly\n", drain(refused));
            assertEquals(1, port.exports.size());
        }
    }

    @Test
    void aSessionThatCannotBeFoundOrAnotherOrganizationsPlacementIsOneFixedMarker() throws Exception {
        port.session = Optional.empty();
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("# sm-export: unavailable: the agent is not connected\n", drain(stream));
        }
        port.session = Optional.of(SESSION);
        for (UUID[] key : List.of(new UUID[] {UUID.randomUUID(), INSTANCE, PLACEMENT},
                new UUID[] {ORG, UUID.randomUUID(), PLACEMENT}, new UUID[] {ORG, INSTANCE, UUID.randomUUID()})) {
            try (LogExportStream stream = service().open(key[0], key[1], key[2])) {
                assertEquals("# sm-export: unavailable: the placement's logs are not available\n", drain(stream));
            }
        }
        assertTrue(port.exports.isEmpty(), "the gateway is never asked about a placement the DP cannot resolve (I5)");
    }

    @Test
    void anAgentRefusalIsTheFixedTextOfItsReason() throws Exception {
        port.handle.state = LogExportHandle.State.UNAVAILABLE;
        port.handle.reason = "NOT_COLLECTING";
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("# sm-export: unavailable: log collection is not enabled for this placement\n", drain(stream));
        }
    }

    @Test
    void noFinalChunkEndsAtTheDeadlineWithAFixedMarkerInsteadOfHanging() throws Exception {
        port.handle.chunks.add(chunk(7, false, line(0, "one")));
        try (LogExportStream stream = service(1_000_000, Duration.ofMillis(300), 2).open(ORG, INSTANCE, PLACEMENT)) {
            long started = System.nanoTime();
            String all = drain(stream);

            assertTrue(all.startsWith(T + " one\n"), all);
            assertTrue(all.endsWith("# sm-export: truncated: the time limit was reached; resume from seq 7\n"), all);
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 5, "it ended, it did not hang");
            assertEquals(1, port.handle.cancels.get());
        }
    }

    @Test
    void aLostSessionEndsWithAFixedIncompleteMarker() throws Exception {
        port.handle.chunks.add(chunk(4, false, line(0, "one")));
        port.handle.state = LogExportHandle.State.SESSION_LOST;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.endsWith("# sm-export: incomplete: the agent's connection was lost; resume from seq 4\n"), all);
        }
    }

    @Test
    void theHandlesOwnTimeoutAndSizeStatesGetTheSameMarkers() throws Exception {
        port.handle.state = LogExportHandle.State.TIMED_OUT;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("# sm-export: truncated: the time limit was reached; resume from seq 0\n", drain(stream));
        }
        port.handle.state = LogExportHandle.State.SIZE_LIMIT;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals("# sm-export: truncated: the size limit was reached; resume from seq 0\n", drain(stream));
        }
    }
}
