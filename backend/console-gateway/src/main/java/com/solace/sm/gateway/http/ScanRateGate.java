package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.session.SessionId;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The per-session budget of {@code scanHostKeys} (plan 013 T1.4; D-REG 7.1): a token bucket per SESSION, default 10 a
 * minute, refilled continuously. Over budget is {@code 429 RATE_LIMITED} with the seconds until the next token in
 * {@code Retry-After}.
 *
 * <p>A scan has the data plane connect to a host a caller named, so it is the one route here that can be used to probe;
 * the limit bounds how fast one session can do it. The shape is {@code MetricsRateGate}'s (per session, per process, a
 * bounded table whose idle entries are swept, failing OPEN when the table is full of active sessions: the limit is a
 * courtesy to the network, and the address policy is the control). It is consulted from the HANDLER, once, so one request
 * is one count.
 */
final class ScanRateGate {

    private static final long MINUTE_NANOS = 60L * 1_000_000_000L;

    private final int permitsPerMinute;
    private final LongSupplier nanos;
    private final int maxTrackedSessions;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    ScanRateGate(int permitsPerMinute, LongSupplier nanos) {
        this(permitsPerMinute, nanos, 100_000);
    }

    ScanRateGate(int permitsPerMinute, LongSupplier nanos, int maxTrackedSessions) {
        if (permitsPerMinute < 1 || maxTrackedSessions < 1) {
            throw new IllegalArgumentException("the rate and the table bound must be positive");
        }
        this.permitsPerMinute = permitsPerMinute;
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        this.maxTrackedSessions = maxTrackedSessions;
    }

    /**
     * Takes one token for this session or refuses.
     *
     * @throws GatewayRejectionException {@code 429 RATE_LIMITED}, {@code Retry-After} set
     */
    void check(SessionId session) {
        Objects.requireNonNull(session, "session");
        long now = nanos.getAsLong();
        if (buckets.size() >= maxTrackedSessions && !buckets.containsKey(session.value())) {
            sweep(now);
            if (buckets.size() >= maxTrackedSessions) {
                return;      // fail open: see the class comment
            }
        }
        Bucket bucket = buckets.computeIfAbsent(session.value(), id -> new Bucket(permitsPerMinute, now));
        long waitNanos = bucket.take(now, permitsPerMinute);
        if (waitNanos > 0) {
            long seconds = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
            throw new GatewayRejectionException(ErrorCode.RATE_LIMITED, Map.of("Retry-After", Long.toString(seconds)));
        }
    }

    private void sweep(long now) {
        for (Iterator<Bucket> it = buckets.values().iterator(); it.hasNext();) {
            if (it.next().isFull(now, permitsPerMinute)) {
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

        /** @return 0 when a token was taken, else the nanoseconds until one will be available */
        synchronized long take(long now, int perMinute) {
            refill(now, perMinute);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return 0;
            }
            double missing = 1.0 - tokens;
            return (long) Math.ceil(missing * (MINUTE_NANOS / (double) perMinute));
        }

        synchronized boolean isFull(long now, int perMinute) {
            refill(now, perMinute);
            return tokens >= perMinute;
        }

        private void refill(long now, int perMinute) {
            long elapsed = Math.max(0, now - updated);
            tokens = Math.min(perMinute, tokens + elapsed * (perMinute / (double) MINUTE_NANOS));
            updated = now;
        }
    }
}
