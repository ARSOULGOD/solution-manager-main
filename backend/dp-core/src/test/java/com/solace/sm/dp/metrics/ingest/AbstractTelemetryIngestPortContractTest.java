package com.solace.sm.dp.metrics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.TelemetryIngestPort;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link TelemetryIngestPort} must pass (plan 011 P11-3.1): the in-process service today, and a
 * remote adapter (the agent gateway, AGW-D9) when one exists. "In-memory callbacks are never the sole contract":
 * this kit is what makes the seam a seam. It names no database, no buffer and no transaction.
 *
 * <p>A subclass supplies the port and the three situations the contract talks about. Its methods are public because
 * a remote adapter's tests live in another module and another package.
 */
public abstract class AbstractTelemetryIngestPortContractTest {

    protected abstract TelemetryIngestPort port();

    /** A batch for a live placement of the caller's tenant that the port will commit. */
    protected abstract IngestBatch validBatch();

    /** A batch whose tenant does not own the placement it names. */
    protected abstract IngestBatch foreignTenantBatch();

    /** Takes away whatever the port writes to, so that a valid batch cannot be committed right now. */
    protected abstract void makeStorageUnavailable();

    protected abstract void makeStorageAvailable();

    @Test
    public void committedIsIdempotentForTheSameBatch() {
        IngestBatch batch = validBatch();
        IngestResult first = port().ingest(batch);
        IngestResult.Committed one = assertInstanceOf(IngestResult.Committed.class, first, String.valueOf(first));
        IngestResult again = port().ingest(batch);
        IngestResult.Committed two = assertInstanceOf(IngestResult.Committed.class, again, String.valueOf(again));
        assertTrue(one.accepted() > 0, "the first send stored something");
        assertEquals(0, two.accepted(), "an exact replay stores nothing new");
        assertEquals(two.dropped(), java.util.Map.of(), "and counts nothing against the producer");
    }

    @Test
    public void retryAfterCarriesAPositiveDelay() {
        makeStorageUnavailable();
        try {
            IngestResult result = port().ingest(validBatch());
            IngestResult.RetryAfter retry = assertInstanceOf(IngestResult.RetryAfter.class, result, String.valueOf(result));
            assertTrue(retry.millis() > 0, "a retry delay of " + retry.millis());
        } finally {
            makeStorageAvailable();
        }
    }

    @Test
    public void aForeignTenantPlacementIsRejected() {
        IngestResult result = port().ingest(foreignTenantBatch());
        IngestResult.Rejected rejected = assertInstanceOf(IngestResult.Rejected.class, result, String.valueOf(result));
        assertNotNull(rejected.code());
        assertTrue(!rejected.code().isBlank());
    }

    @Test
    public void resultsAreOneOfTheFourPermittedKinds() {
        IngestResult result = port().ingest(validBatch());
        assertTrue(result instanceof IngestResult.Committed || result instanceof IngestResult.RetryAfter
                || result instanceof IngestResult.Rejected || result instanceof IngestResult.Pending, String.valueOf(result));
        assertTrue(!(result instanceof IngestResult.Pending), "Pending is reserved for quarantine and never returned in v1");
    }
}
