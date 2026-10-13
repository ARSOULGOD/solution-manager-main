package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.RuntimeAdapterException;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Plan 010 T7.1 (OR-39): one reused, bounded, kept-alive SSH session per TARGET. Driven through the
 * {@link HostConnector} seam: there is no SSH server in this module, so what is proven here is the pool's own
 * policy (reuse, cap, idle close, reconnect, rotation, bounds); the sshj connector is exercised against a live host
 * in plan 010 T6.6 / W9.
 */
class SshSessionPoolTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    // ---- doubles ------------------------------------------------------------------------------------------

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private static final class FakeConnection implements HostConnection {
        final SshDockerPlacement opened;
        volatile boolean alive = true;
        volatile boolean closed;
        volatile RuntimeAdapterException failNext;
        final List<String> commands = new ArrayList<>();
        volatile CountDownLatch gate;
        volatile CountDownLatch hangInspect;
        final AtomicInteger inFlight = new AtomicInteger();

        FakeConnection(SshDockerPlacement opened) {
            this.opened = opened;
        }

        @Override
        public boolean isAlive() {
            return alive && !closed;
        }

        @Override
        public ProbeResult probe(String fixedCommand, Duration deadline) {
            inFlight.incrementAndGet();
            try {
                synchronized (commands) {
                    commands.add(fixedCommand);
                }
                if (failNext != null) {
                    RuntimeAdapterException e = failNext;
                    failNext = null;
                    alive = false;
                    throw e;
                }
                CountDownLatch g = gate;
                if (g != null) {
                    try {
                        g.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ProbeResult(0, 200, "{\"status\":\"UP\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public HostMetricExec.InspectResult inspect(String fixedCommand, Duration deadline) {
            synchronized (commands) {
                commands.add(fixedCommand);
            }
            CountDownLatch hang = hangInspect;
            if (hang != null) {
                // W2-03: a wedged dockerd. Returns only when the connection is closed (sshj: the channel closes under
                // the blocked read) or the test is over.
                try {
                    hang.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeAdapterException(new ErrorClass("ssh.connection.lost"));
            }
            if (failNext != null) {
                RuntimeAdapterException e = failNext;
                failNext = null;
                alive = false;
                throw e;
            }
            return new HostMetricExec.InspectResult(0, "abc 2026-10-06T00:00:00Z\n");
        }

        @Override
        public void close() {
            closed = true;
            CountDownLatch hang = hangInspect;
            if (hang != null) {
                hang.countDown();
            }
        }
    }

    private static final class FakeConnector implements HostConnector {
        final List<FakeConnection> opened = new ArrayList<>();
        volatile RuntimeAdapterException failConnect;
        volatile RuntimeAdapterException failEveryNewConnection;

        @Override
        public synchronized HostConnection connect(SshDockerPlacement placement) {
            if (failConnect != null) {
                throw failConnect;
            }
            FakeConnection c = new FakeConnection(placement);
            c.failNext = failEveryNewConnection;
            opened.add(c);
            return c;
        }

        synchronized int connects() {
            return opened.size();
        }

        synchronized FakeConnection last() {
            return opened.get(opened.size() - 1);
        }
    }

    private static SshDockerPlacement placement(String host, UUID credential, String container) {
        return new SshDockerPlacement(TENANT, host, 22, "sm", credential,
                "SHA256:AAAAAAAAAAAAAAAAAAAAAA", container, "/srv/sm", "/srv/sm/" + container, "/app/config",
                "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
    }

    private static SshSessionPoolProperties props(int maxSessions, int probes, int perSession) {
        return new SshSessionPoolProperties(maxSessions, Duration.ofMinutes(5), probes, perSession,
                Duration.ofMillis(300), Duration.ofSeconds(15));
    }

    private static String tokenOf(Runnable r) {
        return assertThrows(RuntimeAdapterException.class, r::run).errorClass().token();
    }

    // ---- tests --------------------------------------------------------------------------------------------

    @Test
    void oneSessionServesEveryPlacementOnATarget() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();

        // three placements (three containers, three ports) on ONE target: one connection
        pool.probe(placement("vm-1.example", credential, "cache-a"), 8081, "/actuator/health", TIMEOUT);
        pool.probe(placement("vm-1.example", credential, "cache-b"), 8082, "/actuator/health", TIMEOUT);
        pool.probe(placement("vm-1.example", credential, "cache-c"), 8083, "/health", TIMEOUT);

        assertEquals(1, connector.connects(), "the pool is per TARGET, not per placement or per instance");
        assertEquals(3, connector.last().commands.size());
        assertEquals(1, pool.openSessions());
        // ...and a second target is a second session
        pool.probe(placement("vm-2.example", credential, "cache-a"), 8081, "/actuator/health", TIMEOUT);
        assertEquals(2, connector.connects());
        assertEquals(2, pool.openSessions());
    }

    @Test
    void theCapClosesTheLeastRecentlyUsedIdleSession() {
        FakeConnector connector = new FakeConnector();
        MutableClock clock = new MutableClock();
        SshSessionPool pool = new SshSessionPool(connector, props(2, 4, 4), clock);
        UUID credential = UUID.randomUUID();
        SshDockerPlacement a = placement("a.example", credential, "c");
        SshDockerPlacement b = placement("b.example", credential, "c");
        SshDockerPlacement c = placement("c.example", credential, "c");

        pool.probe(a, 8081, "/h", TIMEOUT);
        clock.advance(Duration.ofSeconds(1));
        pool.probe(b, 8081, "/h", TIMEOUT);
        clock.advance(Duration.ofSeconds(1));
        pool.probe(a, 8081, "/h", TIMEOUT);          // a is now more recent than b
        clock.advance(Duration.ofSeconds(1));
        pool.probe(c, 8081, "/h", TIMEOUT);          // over the cap: the LRU idle one (b) goes

        FakeConnection forA = connector.opened.get(0);
        FakeConnection forB = connector.opened.get(1);
        FakeConnection forC = connector.opened.get(2);
        assertTrue(forB.closed, "the least recently used idle session is closed");
        assertFalse(forA.closed);
        assertFalse(forC.closed);
        assertEquals(2, pool.openSessions());
    }

    @Test
    void whenEverySessionIsBusyTheCapRefusesRatherThanClosingALiveProbe() throws Exception {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(1, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // hold the only session busy
            pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);
            connector.last().gate = gate;
            Future<ProbeResult> inFlight = executor
                    .submit(() -> pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT));
            awaitInFlight(connector.last());

            assertEquals("ssh.pool.busy", tokenOf(
                    () -> pool.probe(placement("b.example", credential, "c"), 8081, "/h", TIMEOUT)));
            assertFalse(connector.opened.get(0).closed, "a session with a probe in flight is never the victim");
            gate.countDown();
            assertEquals(200, inFlight.get(5, TimeUnit.SECONDS).httpStatus());
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void anIdleSessionIsClosedAfterTheIdleTimeoutAndOnlyThen() {
        FakeConnector connector = new FakeConnector();
        MutableClock clock = new MutableClock();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), clock);
        UUID credential = UUID.randomUUID();
        pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);

        clock.advance(Duration.ofMinutes(4));
        pool.evictIdle();
        assertFalse(connector.last().closed, "not idle long enough");
        assertEquals(1, pool.openSessions());

        clock.advance(Duration.ofMinutes(2));
        pool.evictIdle();
        assertTrue(connector.opened.get(0).closed);
        assertEquals(0, pool.openSessions());

        // the next probe opens a new one
        pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);
        assertEquals(2, connector.connects());
    }

    @Test
    void aDroppedSessionIsDetectedByKeepAliveAndReconnectedBeforeTheNextProbe() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        SshDockerPlacement p = placement("a.example", credential, "c");
        pool.probe(p, 8081, "/h", TIMEOUT);
        FakeConnection first = connector.last();

        first.alive = false;                      // keep-alive found the transport dead between polls

        ProbeResult result = pool.probe(p, 8081, "/h", TIMEOUT);

        assertEquals(200, result.httpStatus(), "the probe is answered from the new session");
        assertEquals(2, connector.connects(), "reconnected immediately");
        assertEquals(1, first.commands.size(), "no probe was ever sent down the dead session");
        assertTrue(first.closed, "the dead session is released");
        assertEquals(1, pool.openSessions());
    }

    @Test
    void aReusedSessionThatDiesUnderTheProbeIsReconnectedAndReprobedOnce() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        SshDockerPlacement p = placement("a.example", credential, "c");
        pool.probe(p, 8081, "/h", TIMEOUT);
        connector.last().failNext = new RuntimeAdapterException(new ErrorClass(SshSessionPool.CONNECTION_LOST));

        ProbeResult result = pool.probe(p, 8081, "/h", TIMEOUT);

        assertEquals(200, result.httpStatus());
        assertEquals(2, connector.connects());
        assertTrue(connector.opened.get(0).closed);
    }

    @Test
    void aFreshSessionThatFailsUnderItsFirstProbeIsNotRetried() {
        FakeConnector connector = new FakeConnector();
        connector.failEveryNewConnection = new RuntimeAdapterException(new ErrorClass(SshSessionPool.CONNECTION_LOST));
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        SshDockerPlacement p = placement("a.example", UUID.randomUUID(), "c");

        // nothing was reused, so there is no stale connection to blame: the failure is the answer, once
        assertEquals(SshSessionPool.CONNECTION_LOST, tokenOf(() -> pool.probe(p, 8081, "/h", TIMEOUT)));
        assertEquals(1, connector.connects(), "no reconnect loop");
        assertEquals(0, pool.openSessions(), "a dead session does not stay in the pool");
        assertTrue(connector.last().closed);
    }

    @Test
    void aFailedConnectLeavesNothingInThePool() {
        FakeConnector connector = new FakeConnector();
        connector.failConnect = new RuntimeAdapterException(new ErrorClass("ssh.connection.failed"));
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        SshDockerPlacement p = placement("a.example", UUID.randomUUID(), "c");

        assertEquals("ssh.connection.failed", tokenOf(() -> pool.probe(p, 8081, "/h", TIMEOUT)));
        assertEquals(0, pool.openSessions());
        // and the next probe tries again rather than waiting on a placeholder that never completes
        connector.failConnect = null;
        assertEquals(200, pool.probe(p, 8081, "/h", TIMEOUT).httpStatus());
    }

    @Test
    void aRotatedCredentialIsUsedByTheNextSession() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID oldCredential = UUID.randomUUID();
        UUID newCredential = UUID.randomUUID();
        pool.probe(placement("a.example", oldCredential, "c"), 8081, "/h", TIMEOUT);
        FakeConnection old = connector.last();

        // the target's record was re-read and now names a rotated credential
        pool.probe(placement("a.example", newCredential, "c"), 8081, "/h", TIMEOUT);

        assertEquals(2, connector.connects(), "a session opened with the old key is never reused past a rotation");
        assertEquals(newCredential, connector.last().opened.credentialRef());
        assertTrue(old.closed);
        assertEquals(1, old.commands.size());
        assertEquals(1, pool.openSessions());
    }

    @Test
    void aChangedPinnedHostKeyIsAlsoANewSession() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        SshDockerPlacement before = placement("a.example", credential, "c");
        SshDockerPlacement after = new SshDockerPlacement(TENANT, "a.example", 22, "sm", credential,
                "SHA256:BBBBBBBBBBBBBBBBBBBBBB", "c", "/srv/sm", "/srv/sm/c", "/app/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1);
        pool.probe(before, 8081, "/h", TIMEOUT);
        pool.probe(after, 8081, "/h", TIMEOUT);
        assertEquals(2, connector.connects(), "a session verified against a different pin is not reusable");
    }

    @Test
    void theProbePoolIsBoundedAcrossTargets() throws Exception {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(8, 1, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);
            connector.last().gate = gate;
            Future<ProbeResult> held = executor
                    .submit(() -> pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT));
            awaitInFlight(connector.last());

            assertEquals("ssh.pool.busy", tokenOf(
                    () -> pool.probe(placement("b.example", credential, "c"), 8081, "/h", TIMEOUT)),
                    "one probe slot in the pool: a second target's probe waits acquireTimeout, then is refused");
            gate.countDown();
            held.get(5, TimeUnit.SECONDS);
            // the slot is released: the same probe now goes through
            assertEquals(200, pool.probe(placement("b.example", credential, "c"), 8081, "/h", TIMEOUT).httpStatus());
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void probesOnOneSessionAreBoundedPerSession() throws Exception {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(8, 8, 1), new MutableClock());
        UUID credential = UUID.randomUUID();
        CountDownLatch gate = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);
            connector.last().gate = gate;
            Future<ProbeResult> held = executor
                    .submit(() -> pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT));
            awaitInFlight(connector.last());

            assertEquals("ssh.pool.busy", tokenOf(
                    () -> pool.probe(placement("a.example", credential, "d"), 8082, "/h", TIMEOUT)));
            // a DIFFERENT target is not held up by the first target's busy session
            assertEquals(200, pool.probe(placement("b.example", credential, "c"), 8081, "/h", TIMEOUT).httpStatus());
            gate.countDown();
            held.get(5, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void aRefusedProbeOpensNoSession() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        SshDockerPlacement p = placement("a.example", UUID.randomUUID(), "c");

        assertEquals("ssh.probe.refused", tokenOf(() -> pool.probe(p, 8081, "/a;b", TIMEOUT)));
        assertEquals("ssh.probe.refused", tokenOf(() -> pool.probe(p, 0, "/h", TIMEOUT)));
        assertEquals("ssh.probe.refused", tokenOf(() -> pool.probe(p, 8081, "/h", Duration.ofSeconds(60))));
        assertEquals(0, connector.connects());
        assertEquals(0, pool.openSessions());
    }

    @Test
    void closingThePoolClosesEverySession() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT);
        pool.probe(placement("b.example", credential, "c"), 8081, "/h", TIMEOUT);

        pool.close();

        assertTrue(connector.opened.stream().allMatch(c -> c.closed));
        assertEquals(0, pool.openSessions());
        assertEquals("ssh.pool.closed", tokenOf(
                () -> pool.probe(placement("a.example", credential, "c"), 8081, "/h", TIMEOUT)));
    }

    /**
     * M8 / the plan's "the apply path keeps its per-operation connection (T6.1) so an apply never queues behind a
     * probe". Structural: no class that serves the apply path names the pool. A class file names every class it
     * uses in its constant pool, so a plain byte search is a sound detector for a direct reference.
     */
    @Test
    void theApplyPathNeverBorrowsFromThePool() throws IOException {
        for (Class<?> applyPath : new Class<?>[] {SshDockerRuntimeAdapter.class, SshjDockerTransport.class,
                SshDockerConfigEditor.class, SshDockerDiagnostics.class,
                SshDockerPlacementCatalog.class}) {
            String resource = applyPath.getName().replace('.', '/') + ".class";
            try (InputStream in = applyPath.getClassLoader().getResourceAsStream(resource)) {
                String constantPool = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(constantPool.contains("SshSessionPool"),
                        applyPath.getSimpleName() + " must not reference the poll pool");
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static void awaitInFlight(FakeConnection connection) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (connection.inFlight.get() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the held probe never started");
            }
            Thread.sleep(5);
        }
    }

    // ---- plan 011 P11-4.2b: the metrics curl and docker inspect share the poller's session ----------------

    @Test
    void theMetricsCurlAndInspectRideTheTargetsOneSessionAndBothAreFixedCommands() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        UUID credential = UUID.randomUUID();
        SshDockerPlacement p = placement("vm-1.example", credential, "cache-a");

        pool.probe(p, 8081, "/actuator/health", TIMEOUT);
        ProbeResult curl = pool.curl(p, HostMetricProbe.curl(8081, "/api/cache/stats", java.util.Map.of(), TIMEOUT));
        HostMetricExec.InspectResult inspect = pool.inspect(p, HostMetricProbe.inspect("cache-a", TIMEOUT));

        assertEquals(1, connector.connects(), "health, metrics and inspect share ONE connection per target");
        assertEquals(200, curl.httpStatus());
        assertEquals(0, inspect.exitCode());
        List<String> sent = connector.last().commands;
        assertEquals(3, sent.size());
        assertTrue(sent.get(1).endsWith("'http://127.0.0.1:8081/api/cache/stats'"), sent.get(1));
        assertEquals("timeout --kill-after=2 3 docker inspect --format '{{.Id}} {{.State.StartedAt}}' -- cache-a",
                sent.get(2));
    }

    @Test
    void aReusedSessionThatDiesUnderAnInspectIsReplacedAndTheInspectRepeatedOnce() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 4, 4), new MutableClock());
        SshDockerPlacement p = placement("vm-1.example", UUID.randomUUID(), "cache-a");
        pool.probe(p, 8081, "/h", TIMEOUT);
        connector.last().failNext = new RuntimeAdapterException(new ErrorClass(SshSessionPool.CONNECTION_LOST));

        HostMetricExec.InspectResult result = pool.inspect(p, HostMetricProbe.inspect("cache-a", TIMEOUT));

        assertEquals(0, result.exitCode());
        assertEquals(2, connector.connects(), "one reconnect, no loop");
    }

    @Test
    void theMetricsCommandsAreBoundedByTheSameProbeSlotsAsHealth() {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = new SshSessionPool(connector, props(4, 1, 4), new MutableClock());
        SshDockerPlacement p = placement("vm-1.example", UUID.randomUUID(), "cache-a");
        pool.probe(p, 8081, "/h", TIMEOUT);
        CountDownLatch gate = new CountDownLatch(1);
        connector.last().gate = gate;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProbeResult> holding = executor.submit(() -> pool.probe(p, 8081, "/h", TIMEOUT));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connector.last().inFlight.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertEquals("ssh.pool.busy", tokenOf(() -> pool.inspect(p, HostMetricProbe.inspect("cache-a", TIMEOUT))));
            assertEquals("ssh.pool.busy", tokenOf(() -> pool.curl(p,
                    HostMetricProbe.curl(8081, "/x", java.util.Map.of(), TIMEOUT))));
            gate.countDown();
            holding.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    // ---- W2-03: a command that never returns must not hold a shared probe slot forever ---------------------

    private static SshSessionPool boundedPool(FakeConnector connector, int probes) {
        // the connection's own deadline is timeout + 100 ms, the pool's backstop another 100 ms
        return new SshSessionPool(connector, props(4, probes, 4), new MutableClock(), Duration.ofMillis(100),
                Duration.ofMillis(100));
    }

    @Test
    void anInspectThatNeverReturnsFreesItsSlotWithinTheDeadlineAndNothingElseIsStarved() throws Exception {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = boundedPool(connector, 1); // ONE shared probe slot: the fleet-wide resource
        SshDockerPlacement stuck = placement("wedged.example", UUID.randomUUID(), "cache-a");
        SshDockerPlacement healthy = placement("healthy.example", UUID.randomUUID(), "cache-b");
        pool.probe(stuck, 8081, "/h", TIMEOUT);
        connector.last().hangInspect = new CountDownLatch(1);
        FakeConnection wedged = connector.last();

        long start = System.nanoTime();
        String token = tokenOf(() -> pool.inspect(stuck, HostMetricProbe.inspect("cache-a", Duration.ofSeconds(1))));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals("timeout", token);
        assertTrue(elapsedMillis < 3_000, "gave up at the deadline, not after 30 s: " + elapsedMillis + " ms");
        assertTrue(wedged.closed, "the stuck connection is closed so the blocked read ends");
        // the one slot is free again: another target's probe answers instead of ssh.pool.busy
        assertEquals(200, pool.probe(healthy, 8081, "/h", TIMEOUT).httpStatus());
        assertEquals(1, connector.opened.stream().filter(c -> !c.closed).count(), "only the healthy session remains");
        assertEquals(2, connector.connects(), "a timeout is never reconnect-and-repeat");
    }

    @Test
    void aProbeThatNeverReturnsIsAbandonedTheSameWay() throws Exception {
        FakeConnector connector = new FakeConnector();
        SshSessionPool pool = boundedPool(connector, 1);
        SshDockerPlacement p = placement("wedged.example", UUID.randomUUID(), "cache-a");
        pool.probe(p, 8081, "/h", TIMEOUT);
        connector.last().gate = new CountDownLatch(1); // the next probe blocks for up to 10 s

        long start = System.nanoTime();
        assertEquals("timeout", tokenOf(() -> pool.probe(p, 8081, "/h", Duration.ofSeconds(1))));

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000);
        assertEquals(200, pool.probe(p, 8081, "/h", TIMEOUT).httpStatus(), "the slot and the target both recover");
    }
}
