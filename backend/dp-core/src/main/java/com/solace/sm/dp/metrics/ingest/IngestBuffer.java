package com.solace.sm.dp.metrics.ingest;

import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * The bounded hold for batches that could not be written yet (discovery default D-16, B/D2; plan 011 P11-3.1).
 * One queue per placement, so one placement's backlog never evicts another's, each at most {@code bound} batches
 * (default 20 ticks). A full queue drops its OLDEST batch and hands it back so the caller can count it as
 * {@code BUFFER_DROPPED}: the freshest data is what an operator is looking at, and the gap shows as a gap.
 *
 * <p>Order is the arrival order within a placement, which the counter rules depend on (a series' previous sample must
 * be the one before it). Memory only: an SM restart loses it, and that shows as a gap, never as zeros (S-J).
 * Thread-safe; every method is short and holds the monitor for its whole body.
 */
final class IngestBuffer {

    /** A held batch and the future its original caller is waiting on; completing it twice is a no-op. */
    record Held(IngestBatch batch, CompletableFuture<IngestResult> done) {
    }

    record Key(UUID tenantId, UUID placementId) {
        static Key of(IngestBatch batch) {
            return new Key(batch.organizationId(), batch.placementId());
        }
    }

    private final int bound;
    private final Map<Key, Deque<Held>> queues = new HashMap<>();
    /** W2-13: the batch the writer is committing right now, per placement. It is never evicted while it commits. */
    private final Map<Key, Held> inFlight = new HashMap<>();

    IngestBuffer(int bound) {
        if (bound < 1) {
            throw new IllegalArgumentException("the buffer bound must be at least 1, was " + bound);
        }
        this.bound = bound;
    }

    /**
     * Appends, dropping the oldest while over the bound. Returns what was dropped, oldest first. A batch the writer has
     * {@link #claimHead claimed} is committing: it is not dropped (it would be counted lost while it lands) and does
     * not count against the bound, so the next-oldest goes instead.
     */
    synchronized List<Held> offer(Held held) {
        Key key = Key.of(held.batch());
        Deque<Held> queue = queues.computeIfAbsent(key, k -> new ArrayDeque<>());
        queue.addLast(held);
        List<Held> dropped = new ArrayList<>();
        Held committing = inFlight.get(key);
        while (queue.size() - (committing != null && queue.peekFirst() == committing ? 1 : 0) > bound) {
            if (committing != null && queue.peekFirst() == committing) {
                Held first = queue.removeFirst();
                dropped.add(queue.removeFirst());
                queue.addFirst(first);
            } else {
                dropped.add(queue.removeFirst());
            }
        }
        return dropped;
    }

    /** The head, marked as being committed so {@link #offer} leaves it alone until {@link #release} or {@link #removeHead}. */
    synchronized Held claimHead(Key key) {
        Held head = peek(key);
        if (head == null) {
            inFlight.remove(key);
        } else {
            inFlight.put(key, head);
        }
        return head;
    }

    /** The writer is done with {@code held} (committed, held for retry, or failed): it may be evicted again. */
    synchronized void release(Key key, Held held) {
        inFlight.remove(key, held);
    }

    synchronized Held peek(Key key) {
        Deque<Held> queue = queues.get(key);
        return queue == null ? null : queue.peekFirst();
    }

    /** Removes {@code held} if it is still the head; a batch evicted meanwhile is simply gone. */
    synchronized void removeHead(Key key, Held held) {
        Deque<Held> queue = queues.get(key);
        inFlight.remove(key, held);
        if (queue != null && queue.peekFirst() == held) {
            queue.removeFirst();
            if (queue.isEmpty()) {
                queues.remove(key);
            }
        }
    }

    /** A copy of the queue, oldest first, so a caller can answer each waiter without holding the monitor. */
    synchronized List<Held> snapshot(Key key) {
        Deque<Held> queue = queues.get(key);
        return queue == null ? List.of() : List.copyOf(queue);
    }

    synchronized Set<Key> keys() {
        return queues.entrySet().stream().filter(e -> !e.getValue().isEmpty()).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    synchronized int size(Key key) {
        Deque<Held> queue = queues.get(key);
        return queue == null ? 0 : queue.size();
    }

    synchronized int total() {
        return queues.values().stream().mapToInt(Deque::size).sum();
    }

    int bound() {
        return bound;
    }
}
