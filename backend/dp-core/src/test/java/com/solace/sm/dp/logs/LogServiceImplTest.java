package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostLogs;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacementCatalog;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T9.1: the data plane's bounded log read. The transport and the catalog are faked; what is pinned is the
 * bound (limit and time window), the tenant-bound lookup (I5), that an unreachable host is DECLARED and never an
 * empty page, and that a line is returned as the container wrote it (the MI's own output; SM does not redact it).
 */
class LogServiceImplTest {

    private static final UUID ORGANIZATION = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();

    private static String line(String second, String message) {
        return "2026-10-06T06:25:" + second + "Z " + message;
    }

    private static SshDockerPlacement placement() {
        return new SshDockerPlacement(new TenantId(ORGANIZATION), "10.9.8.7", 22, "sm", UUID.randomUUID(),
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", "sm-orders", "/srv/sm", "/srv/sm/orders", "/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static class Recorder implements HostLogs, SshDockerPlacementCatalog {
        final List<PlacementRef> looked = new ArrayList<>();
        final List<Object[]> reads = new ArrayList<>();
        String output = "";
        RuntimeException failure;
        RuntimeException lookupFailure;

        @Override
        public SshDockerPlacement lookup(PlacementRef ref) {
            looked.add(ref);
            if (lookupFailure != null) {
                throw lookupFailure;
            }
            return placement();
        }

        @Override
        public String read(SshDockerPlacement p, int limit, Instant after, Instant before) {
            reads.add(new Object[] {limit, after, before});
            if (failure != null) {
                throw failure;
            }
            return output;
        }
    }

    private static LogServiceImpl service(Recorder recorder) {
        return new LogServiceImpl(recorder, recorder, 4, Duration.ofMillis(50));
    }

    private static RuntimeAdapterException adapter(String token) {
        return new RuntimeAdapterException(new ErrorClass(token));
    }

    @Test
    void boundedByLimitAndTimeWindow() {
        Recorder recorder = new Recorder();
        recorder.output = String.join("\n", line("01.0", "a"), line("02.0", "b"), line("03.0", "c"), line("04.0", "d"),
                line("05.0", "e"));
        Instant after = Instant.parse("2026-10-06T06:25:01Z");
        Instant before = Instant.parse("2026-10-06T06:25:05Z");

        LogPageView page = service(recorder).read(ORGANIZATION, INSTANCE, PLACEMENT, 2, after, before);

        // the transport is asked for the same bound the caller asked for
        assertEquals(1, recorder.reads.size());
        assertEquals(2, recorder.reads.get(0)[0]);
        assertEquals(after, recorder.reads.get(0)[1]);
        assertEquals(before, recorder.reads.get(0)[2]);
        // and the page is the NEWEST `limit` lines strictly inside the window, oldest first - whatever the host sent
        assertEquals(List.of("c", "d"), page.lines().stream().map(LogLineView::message).toList());
        assertTrue(page.unavailableReason().isEmpty());
    }

    // ---- OR-108 (5): a `before` with no `after` pages backwards in bounded windows --------------------------------

    private static final Instant T0 = Instant.parse("2026-10-06T12:00:00Z");

    /**
     * A host that behaves as Docker was measured to (M11): {@code --since}/{@code --until} are inclusive, there is no
     * tail with a window, and an answer over {@code maxLines} lines is the transport's "too large" refusal.
     */
    private static final class Host extends Recorder {
        final List<Instant> stamps = new ArrayList<>();
        int maxLines = Integer.MAX_VALUE;

        Host lineAt(Instant at) {
            stamps.add(at);
            return this;
        }

        @Override
        public String read(SshDockerPlacement p, int limit, Instant after, Instant before) {
            reads.add(new Object[] {limit, after, before});
            Predicate<Instant> inside = at -> (after == null || !at.isBefore(after)) && (before == null || !at.isAfter(before));
            List<Instant> window = stamps.stream().filter(inside).sorted().toList();
            if (window.size() > maxLines) {
                throw adapter("ssh.docker.output.too.large");
            }
            StringBuilder out = new StringBuilder();
            for (Instant at : window) {
                out.append(at).append(" at ").append(at).append('\n');
            }
            return out.toString();
        }
    }

    @Test
    void aBeforeWithNoAfterAsksTheHostForOneBoundedWindowNotTheWholeLog() {
        Host host = new Host();
        for (int i = 1; i <= 5; i++) {
            host.lineAt(T0.minusSeconds(i * 60L));
        }

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 3, null, T0);

        assertEquals(1, host.reads.size(), "three lines were in the first window, so no second read");
        assertEquals(T0.minus(Duration.ofMinutes(15)).minusNanos(1), host.reads.get(0)[1], "--since before minus W (inclusive)");
        assertEquals(T0, host.reads.get(0)[2]);
        assertEquals(List.of(T0.minusSeconds(180), T0.minusSeconds(120), T0.minusSeconds(60)),
                page.lines().stream().map(LogLineView::at).toList(), "the newest three, oldest first");
        assertTrue(page.unavailableReason().isEmpty());
    }

    @Test
    void aLargeLogIsPagedBackwardsInWindowsAndNeverDeclaredUnavailableForBeingLarge() {
        Host host = new Host();
        host.maxLines = 50;                                   // one 15-minute window of this log is under the bound
        for (int i = 0; i < 4000; i++) {                      // the whole log is far over it
            host.lineAt(T0.minusSeconds(1L + i));
        }

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 20, null, T0);

        assertTrue(page.unavailableReason().isEmpty(), "the whole log is large; a window of it is not");
        assertEquals(20, page.lines().size());
        assertEquals(T0.minusSeconds(1), page.lines().get(19).at());
        assertEquals(T0.minusSeconds(20), page.lines().get(0).at());
    }

    @Test
    void aQuietLogStepsBackThroughContiguousWindowsUntilItHasTheLimit() {
        Host host = new Host();
        host.lineAt(T0.minus(Duration.ofMinutes(10))).lineAt(T0.minus(Duration.ofMinutes(40)))
                .lineAt(T0.minus(Duration.ofMinutes(70)));

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 3, null, T0);

        assertEquals(List.of(T0.minus(Duration.ofMinutes(70)), T0.minus(Duration.ofMinutes(40)),
                T0.minus(Duration.ofMinutes(10))), page.lines().stream().map(LogLineView::at).toList());
        assertEquals(5, host.reads.size(), "windows 0..4 (75 minutes back); the fifth found the third line");
        for (int i = 1; i < host.reads.size(); i++) {
            assertEquals(((Instant) host.reads.get(i - 1)[1]).plusNanos(1), host.reads.get(i)[2],
                    "each window ends exactly where the previous began");
        }
    }

    @Test
    void aLineOnAWindowEdgeIsReadOnceAndNeverLost() {
        Host host = new Host();
        Instant edge = T0.minus(Duration.ofMinutes(15));       // exactly one window back
        host.lineAt(edge).lineAt(T0.minusSeconds(1));

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, T0);

        assertEquals(List.of(edge, T0.minusSeconds(1)), page.lines().stream().map(LogLineView::at).toList());
    }

    @Test
    void theLookBackIsBoundedAndAnEmptyLogIsAnEmptyLivePage() {
        Host host = new Host();

        LogPageView page = new LogServiceImpl(host, host, 4, Duration.ofMillis(50), Duration.ofMinutes(15), 3)
                .read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, T0);

        assertEquals(3, host.reads.size(), "at most maxWindows host reads");
        assertTrue(page.lines().isEmpty());
        assertTrue(page.unavailableReason().isEmpty(), "reachable and silent is not unreachable");
    }

    @Test
    void theWindowIsConfigurable() {
        Host host = new Host();

        new LogServiceImpl(host, host, 4, Duration.ofMillis(50), Duration.ofMinutes(2), 8)
                .read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, T0);

        assertEquals(T0.minus(Duration.ofMinutes(2)).minusNanos(1), host.reads.get(0)[1]);
    }

    @Test
    void aWindowThatIsItselfOverTheByteBoundIsNarrowedNotDeclaredUnavailable() {
        Host host = new Host();
        host.maxLines = 10;
        for (int i = 0; i < 300; i++) {                        // 300 lines in 15 minutes: over the bound; 1 in 3 seconds
            host.lineAt(T0.minusSeconds(1L + 3 * i));
        }

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, T0);

        assertTrue(page.unavailableReason().isEmpty(), page.unavailableReason().orElse(""));
        assertEquals(5, page.lines().size());
        assertEquals(T0.minusSeconds(1), page.lines().get(4).at());
    }

    @Test
    void aWindowThatCannotBeNarrowedEnoughIsStillDeclaredTooLargeAndTheHalvingIsBounded() {
        Host host = new Host();
        host.maxLines = 0;
        host.lineAt(T0.minusMillis(500)).lineAt(T0.minusMillis(400));      // two lines within one second: never fits

        LogPageView page = service(host).read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, T0);

        assertEquals(LogServiceImpl.TOO_LARGE, page.unavailableReason().orElseThrow());
        assertTrue(host.reads.size() <= 12, "the halving stops at the floor: " + host.reads.size());
    }

    @Test
    void anAfterAloneOrBothOrNeitherIsNotPagedBackwards() {
        Host host = new Host();
        Instant after = T0.minus(Duration.ofHours(3));
        LogServiceImpl service = service(host);

        service.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, null, null);
        service.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, after, null);
        service.read(ORGANIZATION, INSTANCE, PLACEMENT, 5, after, T0);

        assertEquals(3, host.reads.size(), "one host read each");
        assertEquals(after, host.reads.get(2)[1]);
        assertEquals(T0, host.reads.get(2)[2], "a caller's own window is the host's window");
    }

    @Test
    void theLookupIsBoundedByTenantInstanceAndPlacement() {
        Recorder recorder = new Recorder();

        service(recorder).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

        assertEquals(1, recorder.looked.size());
        PlacementRef ref = recorder.looked.get(0);
        assertEquals(ORGANIZATION, ref.tenant().value());
        assertEquals(INSTANCE, ref.instance().value());
        assertEquals(PLACEMENT, ref.placement().value());
    }

    @Test
    void anEmptyContainerLogIsAnEmptyLivePageNotADegradedOne() {
        Recorder recorder = new Recorder();

        LogPageView page = service(recorder).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

        assertTrue(page.lines().isEmpty());
        assertTrue(page.unavailableReason().isEmpty(), "reachable and silent is not unreachable");
    }

    @Test
    void unreachableIsDeclaredNotEmpty() {
        for (String token : List.of("ssh.connection.failed", "ssh.credential.unavailable", "ssh.credential.invalid",
                "ssh.docker.command.failed", "ssh.docker.output.too.large", "placement.unresolvable",
                "target.unavailable", "placement.invalid", "something.unforeseen")) {
            Recorder recorder = new Recorder();
            recorder.failure = adapter(token);
            Recorder lookup = new Recorder();
            lookup.lookupFailure = adapter(token);

            for (Recorder r : List.of(recorder, lookup)) {
                LogPageView page = service(r).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

                assertTrue(page.lines().isEmpty(), token);
                String reason = page.unavailableReason().orElseThrow(() -> new AssertionError(token + ": silent"));
                assertFalse(reason.isBlank(), token);
                assertFalse(reason.contains("10.9.8.7") || reason.contains("sm-orders") || reason.contains("/srv/sm")
                        || reason.contains(token), "I6: the reason is fixed text, naming no host, path or token: " + reason);
            }
        }
    }

    @Test
    void anOversizedWindowIsToldApartFromAnUnreachableHost() {
        Recorder tooLarge = new Recorder();
        tooLarge.failure = adapter("ssh.docker.output.too.large");
        Recorder down = new Recorder();
        down.failure = adapter("ssh.connection.failed");

        String large = service(tooLarge).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null)
                .unavailableReason().orElseThrow();
        String unreachable = service(down).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null)
                .unavailableReason().orElseThrow();

        assertFalse(large.equals(unreachable));
    }

    @Test
    void aLogLineIsNotRedactedBySm() {
        // the container's own output: SM neither decides what is sensitive in it nor drops it (the endpoint is logs.read)
        Recorder recorder = new Recorder();
        recorder.output = line("01.0", "connecting with password=hunter2 token=abc");

        LogPageView page = service(recorder).read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

        assertEquals("connecting with password=hunter2 token=abc", page.lines().get(0).message());
    }

    @Test
    void theRequestIsRefusedBeforeAnyHostIsTouchedWhenItIsOutOfBounds() {
        Recorder recorder = new Recorder();
        LogServiceImpl service = service(recorder);
        Instant a = Instant.parse("2026-10-06T06:25:01Z");

        for (int limit : new int[] {0, -5, 1001}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.read(ORGANIZATION, INSTANCE, PLACEMENT, limit, null, null), "limit " + limit);
        }
        assertThrows(IllegalArgumentException.class, () -> service.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, a, a));
        assertThrows(IllegalArgumentException.class,
                () -> service.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, a.plusSeconds(1), a));
        assertThrows(NullPointerException.class, () -> service.read(null, INSTANCE, PLACEMENT, 10, null, null));
        assertThrows(NullPointerException.class, () -> service.read(ORGANIZATION, INSTANCE, null, 10, null, null));
        assertTrue(recorder.looked.isEmpty() && recorder.reads.isEmpty());
    }

    @Test
    void readsAreBoundedInConcurrencyAndAFullPoolIsDeclaredNotQueuedForever() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Recorder recorder = new Recorder() {
            @Override
            public String read(SshDockerPlacement p, int limit, Instant after, Instant before) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return line("01.0", "a");
            }
        };
        LogServiceImpl single = new LogServiceImpl(recorder, recorder, 1, Duration.ofMillis(50));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LogPageView> first = pool.submit(() -> single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            LogPageView second = single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

            assertTrue(second.lines().isEmpty());
            assertTrue(second.unavailableReason().isPresent(), "a full pool is a declared degraded read, not a hang");
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).lines().size());
            // the permit came back: a third read is served
            assertEquals(1, single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null).lines().size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void theConstructorRefusesAnUnboundedPool() {
        Recorder recorder = new Recorder();
        assertThrows(IllegalArgumentException.class, () -> new LogServiceImpl(recorder, recorder, 0, Duration.ofMillis(1)));
        assertThrows(IllegalArgumentException.class, () -> new LogServiceImpl(recorder, recorder, 1, Duration.ofMillis(-1)));
    }
}
