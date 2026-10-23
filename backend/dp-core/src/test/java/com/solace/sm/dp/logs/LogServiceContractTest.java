package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What every {@link LogService} owes its caller, whichever transport serves the placement (plan 016 task 8): out-of-bound
 * requests are refused before anything is touched, a placement that is not the caller's is a declared degraded page and
 * never a line, and reads are bounded in concurrency with a full pool declared, not queued. Run against the SSH
 * implementation and the agent one.
 */
abstract class LogServiceContractTest {

    protected static final UUID ORGANIZATION = UUID.randomUUID();
    protected static final UUID INSTANCE = UUID.randomUUID();
    protected static final UUID PLACEMENT = UUID.randomUUID();

    /** A service whose one known placement is (ORGANIZATION, INSTANCE, PLACEMENT) and has exactly one line to give. */
    abstract LogService service();

    /** The same, but a read of the known placement blocks until {@code release}, counting down {@code entered} first; a single slot. */
    abstract LogService blockingService(CountDownLatch entered, CountDownLatch release);

    /** True when nothing downstream of the service was touched by the calls so far. */
    abstract boolean untouched();

    @Test
    void outOfBoundRequestsAreRefusedBeforeAnythingIsTouched() {
        LogService service = service();
        Instant a = Instant.parse("2026-10-06T06:25:01Z");
        for (int limit : new int[] {0, -5, LogService.MAX_LIMIT + 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.read(ORGANIZATION, INSTANCE, PLACEMENT, limit, null, null), "limit " + limit);
        }
        assertThrows(IllegalArgumentException.class, () -> service.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, a, a));
        assertThrows(NullPointerException.class, () -> service.read(null, INSTANCE, PLACEMENT, 10, null, null));
        assertThrows(NullPointerException.class, () -> service.read(ORGANIZATION, INSTANCE, null, 10, null, null));
        assertTrue(untouched());
    }

    @Test
    void aKnownPlacementIsServed() {
        LogPageView page = service().read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);
        assertEquals(1, page.lines().size());
        assertTrue(page.unavailableReason().isEmpty());
    }

    @Test
    void anotherOrganizationsOrInstancesPlacementIsADeclaredEmptyPage() {
        LogService service = service();
        for (LogPageView page : new LogPageView[] {
                service.read(UUID.randomUUID(), INSTANCE, PLACEMENT, 10, null, null),
                service.read(ORGANIZATION, UUID.randomUUID(), PLACEMENT, 10, null, null),
                service.read(ORGANIZATION, INSTANCE, UUID.randomUUID(), 10, null, null)}) {
            assertTrue(page.lines().isEmpty());
            assertTrue(page.unavailableReason().isPresent(), "an unresolvable placement is declared, never an empty live page");
        }
    }

    @Test
    void readsAreBoundedInConcurrencyAndAFullPoolIsDeclaredNotQueuedForever() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LogService single = blockingService(entered, release);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<LogPageView> first = pool.submit(() -> single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            LogPageView second = single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null);

            assertTrue(second.lines().isEmpty());
            assertTrue(second.unavailableReason().isPresent(), "a full pool is a declared degraded read, not a hang");
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS).lines().size());
            assertEquals(1, single.read(ORGANIZATION, INSTANCE, PLACEMENT, 10, null, null).lines().size(),
                    "the permit came back");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
