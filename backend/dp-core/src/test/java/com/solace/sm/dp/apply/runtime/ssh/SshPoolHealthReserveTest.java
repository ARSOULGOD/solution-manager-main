package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe.ProbeResult;
import com.solace.sm.dp.metrics.collect.fetch.FetchProperties;
import com.solace.sm.dp.metrics.collect.fetch.FetchRequest;
import com.solace.sm.dp.metrics.collect.fetch.FetchResult;
import com.solace.sm.dp.metrics.collect.fetch.SshMetricFetcher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * W3-02: the collector and the health poller share ONE SshSessionPool, and its probe slots are global. The collector
 * bounds its own in-flight runs below {@code maxConcurrentProbes} (the health reserve), and the pool's load token is a
 * deferral, never a failed scrape. Driven through the real pool and the real fetcher over a fake connector.
 */
class SshPoolHealthReserveTest {

    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    /** Every probe blocks on {@code gate} so the slots stay held for as long as the test wants. */
    private static final class Connector implements HostConnector {
        final CountDownLatch gate = new CountDownLatch(1);
        final AtomicInteger inFlight = new AtomicInteger();

        @Override
        public HostConnection connect(SshDockerPlacement placement) {
            return new HostConnection() {
                @Override
                public boolean isAlive() {
                    return true;
                }

                @Override
                public ProbeResult probe(String fixedCommand, Duration deadline) {
                    inFlight.incrementAndGet();
                    try {
                        gate.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    return new ProbeResult(0, 200, "7".getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public HostMetricExec.InspectResult inspect(String fixedCommand, Duration deadline) {
                    return new HostMetricExec.InspectResult(0, "");
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static SshDockerPlacement placement(String host) {
        return new SshDockerPlacement(TENANT, host, 22, "sm", UUID.randomUUID(), "SHA256:AAAAAAAAAAAAAAAAAAAAAA",
                "cache-1", "/srv/sm", "/srv/sm/cache-1", "/app/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 1, AcquisitionMode.VIA_SSH, null);
    }

    @Test
    void twelveCollectorTargetsOnAnEightSlotPoolNeverCostHealthItsSlotOrBackOffAHealthyTarget() throws Exception {
        Connector connector = new Connector();
        SshSessionPoolProperties props = new SshSessionPoolProperties(32, Duration.ofMinutes(5), 8, 4,
                Duration.ofMillis(400), Duration.ofSeconds(15));
        SshSessionPool pool = new SshSessionPool(connector, props, Clock.systemUTC());
        // production's wiring: the pool's probe slots less one health slot
        SshMetricFetcher fetcher = new SshMetricFetcher(ref -> placement("host-" + ref.instance().value()), pool,
                FetchProperties.defaults(), Clock.systemUTC(), 7, props.acquireTimeout());
        ExecutorService executor = Executors.newFixedThreadPool(13);
        try {
            List<Future<FetchResult>> collector = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                PlacementRef ref = new PlacementRef(TENANT, new InstanceId(UUID.randomUUID()),
                        new PlacementId(UUID.randomUUID()));
                collector.add(executor.submit(
                        () -> fetcher.fetch(new FetchRequest(ref, UUID.randomUUID(), 8081, "/api/cache/size", Map.of()))));
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (connector.inFlight.get() < 7 && System.nanoTime() < until) {
                Thread.sleep(10);
            }
            assertEquals(7, connector.inFlight.get(), "the collector holds seven of the eight slots, not all of them");

            // a health probe in the same window, on yet another target
            Future<ProbeResult> health = executor
                    .submit(() -> pool.probe(placement("health-host"), 8081, "/actuator/health", Duration.ofSeconds(5)));
            Thread.sleep(700); // past the collector's own wait
            connector.gate.countDown();
            assertEquals(200, health.get(5, TimeUnit.SECONDS).httpStatus(),
                    "health got its slot within the acquire timeout");

            int ok = 0;
            int deferred = 0;
            for (Future<FetchResult> f : collector) {
                FetchResult r = f.get(10, TimeUnit.SECONDS);
                if (r instanceof FetchResult.Ok) {
                    ok++;
                } else {
                    assertInstanceOf(FetchResult.Deferred.class, r, "load is a deferral, never a transport failure: " + r);
                    deferred++;
                }
            }
            assertEquals(7, ok);
            assertEquals(5, deferred);
            assertTrue(deferred > 0);
        } finally {
            connector.gate.countDown();
            executor.shutdownNow();
            pool.close();
        }
    }
}
