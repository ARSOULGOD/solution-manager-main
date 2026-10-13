package com.solace.sm.gateway.http.metrics;

import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.gateway.http.ErrorCode;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The per-session query rate (plan 011 M-Q24 (5), M-Q22 (5): {@code RATE_LIMITED}, {@code reason: query-rate}, 10 per
 * second per session).
 *
 * <p>A token bucket per SESSION (never per address or per organization: one session is one browser, and a dashboard of
 * twelve widgets refreshing together is what the limit is sized for): capacity and refill are both
 * {@code permitsPerSecond}. Per process, like the login gate; it is a courtesy bound on the database budget, not a
 * security control.
 *
 * <p>⛔ <b>It is consulted from a HANDLER, once, never from the interceptor.</b> An export completes on an async
 * re-dispatch (MF18) and a handler method is not run again by it, so one request is one count whatever the dispatch
 * does. {@link #checks()} is what {@code ExportAsyncTimeoutIT} reads to prove it.
 *
 * <p>The table is bounded: a session idle for a second holds a full bucket, which is indistinguishable from no entry,
 * so those entries are swept whenever the table passes {@code maxTrackedSessions}. If the sweep cannot free room (every
 * session active), a new session shares nothing and is admitted rather than refused: the table fails open, because the
 * limit is a courtesy and an unbounded map is the alternative.
 */
public final class MetricsRateGate {

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final int permitsPerSecond;
    private final LongSupplier nanos;
    private final int maxTrackedSessions;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong checks = new AtomicLong();

    public MetricsRateGate(int permitsPerSecond, LongSupplier nanos) {
        this(permitsPerSecond, nanos, 100_000);
    }

    public MetricsRateGate(int permitsPerSecond, LongSupplier nanos, int maxTrackedSessions) {
        if (permitsPerSecond < 1 || maxTrackedSessions < 1) {
            throw new IllegalArgumentException("the rate and the table bound must be positive");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        this.maxTrackedSessions = maxTrackedSessions;
    }

    /**
     * Takes one token for this session or refuses.
     *
     * @throws MetricsRejectionException {@code 429 RATE_LIMITED}, {@code reason: query-rate}, {@code Retry-After: 1}
     */
    public void check(SessionId session) {
        Objects.requireNonNull(session, "session");
        checks.incrementAndGet();
        long now = nanos.getAsLong();
        if (buckets.size() >= maxTrackedSessions && !buckets.containsKey(session.value())) {
            sweep(now);
            if (buckets.size() >= maxTrackedSessions) {
                return;   // fail open: see the class comment
            }
        }
        Bucket bucket = buckets.computeIfAbsent(session.value(), id -> new Bucket(permitsPerSecond, now));
        if (!bucket.take(now, permitsPerSecond)) {
            throw new MetricsRejectionException(ErrorCode.RATE_LIMITED, MetricsRejectionException.QUERY_RATE, 1);
        }
    }

    /** How many times {@link #check} was asked, refused or not. A test hook for the one-request-one-count proof. */
    public long checks() {
        return checks.get();
    }

    private void sweep(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext();) {
            if (it.next().isIdle(now)) {
                it.remove();
            }
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updated;

        Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.updated = now;
        }

        synchronized boolean take(long now, int perSecond) {
            refill(now, perSecond);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        synchronized boolean isIdle(long now) {
            return now - updated >= SECOND_NANOS;
        }

        private void refill(long now, int perSecond) {
            long elapsed = Math.max(0, now - updated);
            tokens = Math.min(perSecond, tokens + elapsed * (perSecond / (double) SECOND_NANOS));
            updated = now;
        }
    }
}
