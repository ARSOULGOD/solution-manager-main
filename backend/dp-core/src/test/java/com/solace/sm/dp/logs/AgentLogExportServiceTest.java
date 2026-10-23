package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        volatile long delayPerChunkMs;

        @Override
        public Optional<AgentLogChunk> next(Duration wait) throws InterruptedException {
            AgentLogChunk c = chunks.poll(Math.min(wait.toMillis(), 20), TimeUnit.MILLISECONDS);
            if (c != null) {
                if (delayPerChunkMs > 0) {
                    Thread.sleep(delayPerChunkMs);
                }
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
                concurrent, Duration.ofMillis(200));
    }

    private AgentLogExportService service() {
        return service(1_000_000, Duration.ofSeconds(5), 2);
    }

    private static void assertRefused(LogExportStream stream, LogExportStream.Refusal.Kind kind, String reason)
            throws InterruptedException {
        assertEquals(Optional.of(new LogExportStream.Refusal(kind, reason)), stream.refusal());
        assertNull(stream.next(), "a refused export has no body");
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
            assertEquals(1, port.handle.consumed.get(), "opening takes only the first chunk (held, to learn about refusals)");

            String one = stream.next();
            assertEquals(T + " first\n" + T.plusSeconds(1) + " second\n", one);
            assertEquals(1, port.handle.consumed.get(), "the held chunk is rendered; no second chunk is taken yet");

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
        String text = "x".repeat(400);
        port.handle.chunks.add(chunk(2, false, line(0, text), line(1, text)));
        port.handle.chunks.add(chunk(4, false, line(2, text), line(3, text)));
        try (LogExportStream stream = service(1100, Duration.ofSeconds(5), 2).open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.contains("# sm-export: truncated: the size limit was reached; resume from seq 2\n"), all);
            assertTrue(all.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1100, "bytes written, marker included");
            assertEquals(1, port.handle.cancels.get(), "the agent request is cancelled when the bound is hit");
        }
    }

    @Test
    void theBoundCountsEveryByteWrittenNotJustLineTextEvenForOneHugeMultiByteChunk() throws Exception {
        LogLineView[] lines = new LogLineView[500];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = line(i, "\u00e9\u4e2d\ud83d\ude00");     // 2 + 3 + 4 bytes of text, plus an instant and a newline
        }
        port.handle.chunks.add(new AgentLogChunk(501, List.of(lines),
                List.of(new AgentLogGap(T, T.plusSeconds(1), "DROPPED_OLDEST")), true));
        port.handle.state = LogExportHandle.State.COMPLETE;
        for (long bound : new long[] {1024, 1500, 4096}) {
            port.handle.chunks.clear();
            port.handle.chunks.add(new AgentLogChunk(501, List.of(lines),
                    List.of(new AgentLogGap(T, T.plusSeconds(1), "DROPPED_OLDEST")), true));
            try (LogExportStream stream = service(bound, Duration.ofSeconds(5), 2).open(ORG, INSTANCE, PLACEMENT)) {
                String all = drain(stream);

                int written = all.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                assertTrue(written <= bound, "bound " + bound + " but " + written + " bytes were written");
                assertTrue(all.endsWith("; resume from seq 0\n"), all.substring(Math.max(0, all.length() - 120)));
                assertTrue(all.contains("size limit"), "the cut says why");
            }
        }
    }

    @Test
    void aBoundTooSmallToHoldTheFixedMarkerIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentLogExportService(AgentLogServiceTest.KNOWN, AgentLogServiceTest.LIVE, port,
                        Duration.ofSeconds(5), 100, 2, Duration.ofSeconds(1)));
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
        port.handle.chunks.add(chunk(1, false, line(0, "a")));
        LogExportStream first = service.open(ORG, INSTANCE, PLACEMENT);
        assertEquals(T + " a\n", first.next());
        assertEquals("", first.next(), "nothing more yet is a keep-alive");

        first.close();
        first.close();

        assertEquals(1, port.handle.cancels.get(), "closing twice cancels the agent once");
        port.handle.state = LogExportHandle.State.OPEN;
        port.handle.chunks.add(chunk(1, false, line(0, "b")));
        try (LogExportStream second = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertTrue(second.refusal().isEmpty(), "the slot was released by close(), not refused as busy");
        }
    }

    @Test
    void whenAllSlotsAreTakenOpenIsARefusalAndTheGatewayIsNotAsked() throws Exception {
        AgentLogExportService service = service(1_000_000, Duration.ofSeconds(5), 1);
        port.handle.chunks.add(chunk(1, false, line(0, "a")));
        try (LogExportStream held = service.open(ORG, INSTANCE, PLACEMENT);
                LogExportStream refused = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertRefused(refused, LogExportStream.Refusal.Kind.CAPACITY, AgentLogService.AT_CAPACITY);
            assertEquals(1, port.exports.size());
        }
    }

    @Test
    void aSessionThatCannotBeFoundOrAnotherOrganizationsPlacementIsARefusalKnownBeforeAnyByte() throws Exception {
        port.session = Optional.empty();
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertRefused(stream, LogExportStream.Refusal.Kind.UNAVAILABLE, AgentLogService.NO_SESSION);
        }
        port.session = Optional.of(SESSION);
        for (UUID[] key : List.of(new UUID[] {UUID.randomUUID(), INSTANCE, PLACEMENT},
                new UUID[] {ORG, UUID.randomUUID(), PLACEMENT}, new UUID[] {ORG, INSTANCE, UUID.randomUUID()})) {
            try (LogExportStream stream = service().open(key[0], key[1], key[2])) {
                assertRefused(stream, LogExportStream.Refusal.Kind.UNRESOLVABLE, AgentLogService.UNRESOLVABLE);
            }
        }
        assertTrue(port.exports.isEmpty(), "the gateway is never asked about a placement the DP cannot resolve (I5)");
    }

    @Test
    void anAgentRefusalThatIsAlreadyKnownWhenTheExportOpensIsARefusalNotAFile() throws Exception {
        port.handle.state = LogExportHandle.State.UNAVAILABLE;
        port.handle.reason = "NOT_COLLECTING";
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertRefused(stream, LogExportStream.Refusal.Kind.UNAVAILABLE, AgentLogService.NOT_COLLECTING);
        }
    }

    @Test
    void anAgentThatSaysNothingWithinTheStartWaitIsARefusalAndTheRequestIsCancelled() throws Exception {
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertRefused(stream, LogExportStream.Refusal.Kind.UNAVAILABLE, AgentLogService.TIMEOUT);
            assertEquals(1, port.handle.cancels.get());
        }
    }

    @Test
    void aSessionLostOrTimedOutBeforeAnythingWasSentIsStillARefusalButAfterAChunkItIsAMarker() throws Exception {
        port.handle.state = LogExportHandle.State.SESSION_LOST;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertRefused(stream, LogExportStream.Refusal.Kind.UNAVAILABLE, AgentLogService.SESSION_LOST);
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
    void theHandlesOwnTimeoutAndSizeStatesAfterAChunkGetTheSameMarkers() throws Exception {
        for (LogExportHandle.State state : List.of(LogExportHandle.State.TIMED_OUT, LogExportHandle.State.SIZE_LIMIT)) {
            port.handle.chunks.add(chunk(7, false, line(0, "one")));
            port.handle.state = state;
            try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
                String all = drain(stream);
                String text = state == LogExportHandle.State.TIMED_OUT ? "time" : "size";
                assertTrue(all.endsWith("# sm-export: truncated: the " + text + " limit was reached; resume from seq 7\n"), all);
            }
        }
    }

    @Test
    void abortGivesAFixedIncompleteMarkerWithTheResumePointAndReleasesTheExport() throws Exception {
        port.handle.chunks.add(chunk(7, false, line(0, "one")));
        AgentLogExportService service = service(1_000_000, Duration.ofSeconds(5), 1);
        LogExportStream stream = service.open(ORG, INSTANCE, PLACEMENT);
        stream.next();

        assertEquals("# sm-export: incomplete: the export ended unexpectedly; resume from seq 7\n", stream.abort());

        assertEquals(1, port.handle.cancels.get());
        port.handle.state = LogExportHandle.State.OPEN;
        port.handle.chunks.add(chunk(1, true, line(0, "x")));
        try (LogExportStream next = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertTrue(next.refusal().isEmpty(), "the slot was released");
        }
    }

    @Test
    void theDeadlineIsCheckedOnEveryChunkNotOnlyWhileTheAgentIsIdle() throws Exception {
        port.handle.delayPerChunkMs = 60;
        for (int i = 0; i < 30; i++) {
            port.handle.chunks.add(chunk(i + 1, false, line(i, "l" + i)));
        }
        try (LogExportStream stream = service(1_000_000, Duration.ofMillis(300), 2).open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.contains("# sm-export: truncated: the time limit was reached; resume from seq "), all);
            assertTrue(port.handle.consumed.get() < 30, "chunks kept flowing but the DP bound still ended it");
        }
    }

    @Test
    void aSingleLineLongerThanTheWholeBudgetIsCutWithAFixedTailInsteadOfStallingTheResume() throws Exception {
        String huge = "\u00e9".repeat(5000);          // 10000 bytes of text against a 1024-byte bound
        port.handle.chunks.add(chunk(1, false, line(0, huge)));
        port.handle.chunks.add(chunk(2, true, line(1, "after")));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service(1024, Duration.ofSeconds(5), 2).open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1024, "within the bound");
            assertTrue(all.startsWith(T.toString() + " \u00e9"), "the line is there, cut");
            assertTrue(all.contains("[line truncated]\n"), all);
            assertTrue(all.contains(T.plusSeconds(1) + " after\n"), "and the export went on to the next chunk: " + all);
            assertFalse(all.contains("size limit"), "no stall at seq 0: " + all);
        }
    }

    // ---- final fix wave: resume (I-2), agent-truncated lines (I-3), a read error mid-export (M-7), lease leave (I-1) ----

    @Test
    void theStartSeqIsPassedToTheAgentAndANegativeOneIsRefusedAsABadArgument() throws Exception {
        port.handle.chunks.add(chunk(48212, true, line(0, "a")));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT, 48211L)) {
            assertEquals(48211L, port.exports.get(0)[2], "the resume point reaches the agent");
        }
        assertThrows(IllegalArgumentException.class, () -> service().open(ORG, INSTANCE, PLACEMENT, -1L));
        assertEquals(1, port.exports.size(), "a bad argument asks the gateway nothing");
        port.handle.chunks.add(chunk(1, true, line(0, "a")));
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals(0L, port.exports.get(1)[2], "the 3-argument open starts at the oldest line");
        }
    }

    /** A fake ring: chunks of two lines; an export from seq N serves every chunk that ends after N. */
    private static final class RingPort implements AgentLogPort {
        final List<AgentLogChunk> ring = new ArrayList<>();
        final List<Long> froms = new ArrayList<>();

        RingPort(int lines, int perChunk, String text) {
            for (int first = 0; first < lines; first += perChunk) {
                List<LogLineView> ls = new ArrayList<>();
                for (int i = first; i < Math.min(lines, first + perChunk); i++) ls.add(line(i, text + i));
                int end = Math.min(lines, first + perChunk);
                ring.add(new AgentLogChunk(end, ls, List.of(), end == lines));
            }
        }

        @Override public Optional<AgentSessionRef> sessionFor(UUID o, UUID p) { return Optional.of(SESSION); }
        @Override public CompletableFuture<AgentLogPage> requestPage(AgentSessionRef r, UUID p, int l, Instant a, Instant b, Duration d) { throw new AssertionError(); }
        @Override public void sendRetention(AgentSessionRef r, UUID p, long m) { throw new AssertionError(); }

        @Override
        public LogExportHandle openExport(AgentSessionRef ref, UUID placementId, long fromSeq, Duration deadline, long maxBytes) {
            froms.add(fromSeq);
            FakeHandle handle = new FakeHandle();
            for (AgentLogChunk c : ring) {
                if (c.resumeSeq() > fromSeq) handle.chunks.add(c);
            }
            handle.state = LogExportHandle.State.OPEN;
            return handle;
        }
    }

    @Test
    void aBoundedExportCanBeResumedFromTheSeqItsMarkerNamesWithNoLossAndOnlyTheDocumentedChunkRepeat() throws Exception {
        RingPort ring = new RingPort(12, 3, "x".repeat(60));
        AgentLogExportService small = new AgentLogExportService(AgentLogServiceTest.KNOWN, AgentLogServiceTest.LIVE, ring,
                Duration.ofSeconds(5), 1024, 2, Duration.ofMillis(200));
        String first;
        try (LogExportStream stream = small.open(ORG, INSTANCE, PLACEMENT)) {
            first = drain(stream);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("resume from seq (\\d+)").matcher(first);
        assertTrue(m.find(), first);
        long resume = Long.parseLong(m.group(1));
        assertTrue(resume > 0 && resume < 12, "it stopped part way: " + first);

        AgentLogExportService big = new AgentLogExportService(AgentLogServiceTest.KNOWN, AgentLogServiceTest.LIVE, ring,
                Duration.ofSeconds(5), 1_000_000, 2, Duration.ofMillis(200));
        String second;
        try (LogExportStream stream = big.open(ORG, INSTANCE, PLACEMENT, resume)) {
            second = drain(stream);
        }
        assertEquals(List.of(0L, resume), ring.froms);
        for (int i = 0; i < 12; i++) {
            String wanted = T.plusSeconds(i) + " " + "x".repeat(60) + i + "\n";
            assertTrue(first.contains(wanted) || second.contains(wanted), "line " + i + " is in one of the two downloads");
        }
        assertFalse(second.contains("# sm-export:"), "the resumed download ran to the end: " + second);
        // the cut fell inside a 3-line chunk, so at most that chunk's lines are in both
        long repeated = java.util.stream.IntStream.range(0, 12)
                .filter(i -> first.contains(T.plusSeconds(i) + " ") && second.contains(T.plusSeconds(i) + " ")).count();
        assertTrue(repeated <= 3, "repeats stay within one chunk: " + repeated);
    }

    @Test
    void aLineTheAgentTruncatedIsMarkedInTheExport() throws Exception {
        port.handle.chunks.add(new AgentLogChunk(2, List.of(new LogLineView(T, Optional.empty(), "cut here", true), line(1, "whole")),
                List.of(), true));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            assertEquals(T + " cut here [line truncated]\n" + T.plusSeconds(1) + " whole\n", drain(stream));
        }
    }

    @Test
    void aFinalChunkThatCarriesOnlyACollectionPausedGapIsAReadErrorNotACompleteDownload() throws Exception {
        port.handle.chunks.add(chunk(3, false, line(0, "one")));
        port.handle.chunks.add(new AgentLogChunk(3, List.of(), List.of(new AgentLogGap(T.plusSeconds(1), T.plusSeconds(9),
                "COLLECTION_PAUSED")), true));
        port.handle.state = LogExportHandle.State.COMPLETE;
        try (LogExportStream stream = service().open(ORG, INSTANCE, PLACEMENT)) {
            String all = drain(stream);

            assertTrue(all.contains("# sm-export: gap COLLECTION_PAUSED from "), all);
            assertTrue(all.endsWith("# sm-export: incomplete: the agent could not read the rest of the log; resume from seq 3\n"), all);
            assertFalse(all.contains("complete, with gaps"), all);
        }
    }

    @Test
    void anExportTheGatewayEndedBecauseThePlacementLeftTheLeaseFreesItsSlotAtOnce() throws Exception {
        AgentLogExportService service = service(1_000_000, Duration.ofSeconds(5), 1);
        port.handle.chunks.add(chunk(1, false, line(0, "a")));
        LogExportStream first = service.open(ORG, INSTANCE, PLACEMENT);
        assertEquals(T + " a\n", first.next());
        port.handle.state = LogExportHandle.State.UNAVAILABLE;
        port.handle.reason = "PLACEMENT_NOT_IN_LEASE";

        String rest = drain(first);

        assertEquals("# sm-export: incomplete: the export ended unexpectedly; resume from seq 1\n", rest);
        port.handle.state = LogExportHandle.State.OPEN;
        port.handle.reason = null;
        port.handle.chunks.add(chunk(1, false, line(0, "b")));
        try (LogExportStream second = service.open(ORG, INSTANCE, PLACEMENT)) {
            assertTrue(second.refusal().isEmpty(), "the slot was released when the export ended, not at the 5 minute deadline");
        }
    }
}
