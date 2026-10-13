package com.solace.sm.dp.metrics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-3.1 (D-16): the bounded, per-placement, drop-the-oldest hold. */
class IngestBufferTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static IngestBuffer.Held held(UUID placement, int marker) {
        IngestBatch batch = IngestBatch.polled(TENANT, placement,
                List.of(new IngestSample("m." + marker, Instant.EPOCH.plusSeconds(marker), marker, Instant.EPOCH)));
        return new IngestBuffer.Held(batch, new CompletableFuture<IngestResult>());
    }

    @Test
    void theBufferNeverExceedsItsBoundAndDropsTheOldest() {
        IngestBuffer buffer = new IngestBuffer(3);
        UUID placement = UUID.randomUUID();
        IngestBuffer.Key key = new IngestBuffer.Key(TENANT, placement);
        List<IngestBuffer.Held> all = new java.util.ArrayList<>();
        List<IngestBuffer.Held> dropped = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            IngestBuffer.Held h = held(placement, i);
            all.add(h);
            dropped.addAll(buffer.offer(h));
            assertTrue(buffer.size(key) <= 3, "never above the bound, after offer " + i);
        }
        assertEquals(4, dropped.size());
        assertEquals(all.subList(0, 4), dropped, "the OLDEST four were dropped, oldest first");
        assertSame(all.get(4), buffer.peek(key), "the head is the oldest survivor");
        assertEquals(all.subList(4, 7), buffer.snapshot(key));
    }

    @Test
    void oneBacklogNeverEvictsAnotherPlacementsBatches() {
        IngestBuffer buffer = new IngestBuffer(2);
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        IngestBuffer.Held quietBatch = held(quiet, 0);
        buffer.offer(quietBatch);
        for (int i = 0; i < 10; i++) {
            buffer.offer(held(noisy, i));
        }
        assertSame(quietBatch, buffer.peek(new IngestBuffer.Key(TENANT, quiet)));
        assertEquals(2, buffer.size(new IngestBuffer.Key(TENANT, noisy)));
        assertEquals(3, buffer.total());
    }

    @Test
    void headsLeaveInArrivalOrder() {
        IngestBuffer buffer = new IngestBuffer(5);
        UUID placement = UUID.randomUUID();
        IngestBuffer.Key key = new IngestBuffer.Key(TENANT, placement);
        IngestBuffer.Held a = held(placement, 1);
        IngestBuffer.Held b = held(placement, 2);
        buffer.offer(a);
        buffer.offer(b);
        buffer.removeHead(key, b);
        assertEquals(2, buffer.size(key), "only the head may be removed: b is not the head");
        buffer.removeHead(key, a);
        assertSame(b, buffer.peek(key));
        buffer.removeHead(key, b);
        assertTrue(buffer.keys().isEmpty());
    }

    // ---- W2-13: the batch being committed is never evicted ----------------------------------------------------

    @Test
    void aBatchTheWriterIsCommittingIsNeverEvictedTheNextOldestGoesInstead() {
        IngestBuffer buffer = new IngestBuffer(3);
        UUID placement = UUID.randomUUID();
        IngestBuffer.Key key = new IngestBuffer.Key(TENANT, placement);
        IngestBuffer.Held a = held(placement, 0);
        IngestBuffer.Held b = held(placement, 1);
        IngestBuffer.Held c = held(placement, 2);
        buffer.offer(a);
        buffer.offer(b);
        buffer.offer(c);
        assertSame(a, buffer.claimHead(key), "the writer takes the head and starts the transaction");

        // twenty more arrive during the slow transaction
        List<IngestBuffer.Held> dropped = new java.util.ArrayList<>();
        List<IngestBuffer.Held> arrivals = new java.util.ArrayList<>();
        for (int i = 3; i < 23; i++) {
            IngestBuffer.Held h = held(placement, i);
            arrivals.add(h);
            dropped.addAll(buffer.offer(h));
        }

        assertTrue(dropped.stream().noneMatch(h -> h == a), "the committing head was not dropped");
        assertSame(a, buffer.peek(key));
        assertEquals(List.of(b, c), dropped.subList(0, 2), "the second-oldest went first, oldest first");
        assertEquals(1 + 3, buffer.size(key), "the committing batch is outside the bound");
        assertEquals(arrivals.subList(17, 20), buffer.snapshot(key).subList(1, 4), "the freshest three are kept");
    }

    @Test
    void onceReleasedTheHeadIsAnOrdinaryOldestBatchAgain() {
        IngestBuffer buffer = new IngestBuffer(2);
        UUID placement = UUID.randomUUID();
        IngestBuffer.Key key = new IngestBuffer.Key(TENANT, placement);
        IngestBuffer.Held a = held(placement, 0);
        buffer.offer(a);
        buffer.offer(held(placement, 1));
        buffer.claimHead(key);
        buffer.release(key, a); // held for retry, not committed

        List<IngestBuffer.Held> dropped = buffer.offer(held(placement, 2));

        assertEquals(List.of(a), dropped, "a released head is evictable");
    }

    @Test
    void aBoundOfOneKeepsTheCommittingBatchAndTheNewestArrival() {
        IngestBuffer buffer = new IngestBuffer(1);
        UUID placement = UUID.randomUUID();
        IngestBuffer.Key key = new IngestBuffer.Key(TENANT, placement);
        IngestBuffer.Held a = held(placement, 0);
        buffer.offer(a);
        buffer.claimHead(key);
        IngestBuffer.Held b = held(placement, 1);
        IngestBuffer.Held c = held(placement, 2);

        assertEquals(List.of(), buffer.offer(b));
        assertEquals(List.of(b), buffer.offer(c));
        assertEquals(List.of(a, c), buffer.snapshot(key));
    }

    @Test
    void aBoundBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new IngestBuffer(0));
    }
}
