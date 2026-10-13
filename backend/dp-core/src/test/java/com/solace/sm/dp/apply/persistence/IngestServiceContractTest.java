package com.solace.sm.dp.apply.persistence;

import static com.solace.sm.dp.apply.persistence.IngestFixture.batch;
import static com.solace.sm.dp.apply.persistence.IngestFixture.metric;
import static com.solace.sm.dp.apply.persistence.IngestFixture.sample;

import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import com.solace.sm.dp.metrics.ingest.AbstractTelemetryIngestPortContractTest;
import com.solace.sm.dp.metrics.ingest.IngestService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The in-process {@link IngestService} against the port contract kit. It lives beside {@link IngestFixture} because it
 * needs a real database; the kit it extends lives with the service and names none.
 */
@Testcontainers(disabledWithoutDocker = true)
class IngestServiceContractTest extends AbstractTelemetryIngestPortContractTest {

    private static IngestFixture fx;
    private static IngestService service;
    private static IngestFixture.Site site;
    private static IngestFixture.Site other;
    private static int next;

    @BeforeAll
    static void start() {
        fx = IngestFixture.create("ingest_contract");
        service = fx.service(IngestFixture.fast(20, 500));
        List<com.solace.sm.dp.api.metrics.declare.MetricDefinitionSpec> defs = List.of(
                metric("cache.size", "gauge", "entries", "cache", 30));
        site = fx.site("via-ssh", "2.4", defs, List.of());
        other = fx.site("via-ssh", "2.4", defs, List.of());
    }

    @AfterAll
    static void stop() {
        fx.close();
    }

    @Override
    protected TelemetryIngestPort port() {
        return service;
    }

    /** Each call is a NEW batch (so "the first send stores something") that the test may then repeat. */
    @Override
    protected IngestBatch validBatch() {
        Instant ts = IngestFixture.base().plusSeconds(60L * (++next));
        return batch(site, sample("cache.size", ts, 1, null));
    }

    @Override
    protected IngestBatch foreignTenantBatch() {
        return IngestBatch.polled(other.tenant(), site.placement(), List.of(sample("cache.size", IngestFixture.base(), 1, null)));
    }

    @Override
    protected void makeStorageUnavailable() {
        fx.outage(true);
    }

    @Override
    protected void makeStorageAvailable() {
        fx.outage(false);
        // The service rests for retry-delay after a failure and writes what it held; the next test starts after both.
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (service.heldBatches() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
