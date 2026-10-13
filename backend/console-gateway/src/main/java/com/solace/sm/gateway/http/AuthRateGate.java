package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * {@code OR-24}'s pre-authentication rate gate: a per-client-address token bucket that
 * {@link AuthenticationInterceptor} consults BEFORE any cp-api port call on {@code login} and
 * {@code rotateOwnCredential} — after origin validation, before session resolution.
 *
 * <p>⛔ <b>The key is {@link ClientAddresses#throttleKey} of the resolved, canonicalized client
 * address</b> — the SAME derivation {@code OR-20}'s durable throttle and the lockout-recovery
 * command use, so the two can never disagree about who "one client" is. An IPv6 address is
 * bucketed by its {@code /64} prefix — a single host commonly holds an entire {@code /64}, so
 * bucketing by the full address would let one attacker spread a flood across an effectively
 * unlimited number of buckets. An IPv4 address is bucketed by the full address. {@link
 * ClientAddressResolver#resolve} can, as its own defence-in-depth fallback, hand back a raw,
 * non-canonical string (a servlet container reporting a peer in a shape {@link ClientAddresses}
 * cannot parse); {@code throttleKey} refuses that with {@link IllegalArgumentException}, so this
 * class falls back to keying on the raw string itself rather than ever throwing out of the gate.
 *
 * <p>⛔ <b>Memory is bounded by a hard cap on distinct tracked keys, via least-recently-used
 * eviction — never by refusing a new key.</b> The bucket store is a bounded LRU (an
 * access-ordered {@link LinkedHashMap}): once {@code sm.gateway.auth-rate.max-tracked-keys}
 * distinct keys are tracked, tracking one more evicts whichever key has gone longest untouched.
 * <b>Accepted trade-off:</b> an evicted key re-enters with a FULL bucket — a fresh {@code
 * capacity} tokens, exactly like a brand-new key — so an evicted key's own consumption history is
 * lost. An attacker who wants one extra {@code capacity} worth of attempts against ONE particular
 * key must therefore first churn through {@code maxTrackedKeys} other distinct keys to evict it,
 * then attack it again — the cap makes that costly, not impossible. A brand-new key is NEVER
 * refused for being new: refusing new keys at the cap would itself be a sustainable, unbounded
 * denial of every fresh login attempt, which is strictly worse than the trade-off above.
 *
 * <p>⛔ <b>The time source is monotonic nanoseconds, never wall-clock time.</b> A backwards NTP
 * step in wall-clock time would make every bucket look like it refilled instantly, or — if
 * elapsed time were simply clamped at the top instead — freeze refill forever; {@code
 * System.nanoTime()} in production never steps backwards for exactly this reason. Elapsed time is
 * always computed as a SUBTRACTION of two {@code nanoTime}-shaped values (which stays correct
 * across a {@code long} wraparound); the absolute values themselves are never compared to one
 * another.
 */
public final class AuthRateGate {

    static final String RETRY_AFTER_HEADER = "Retry-After";

    private final ClientAddressResolver clientAddressResolver;
    private final LongSupplier nanos;
    private final int capacity;
    private final double refillPerMillis;
    private final int maxTrackedKeys;

    private final Object lock = new Object();
    private final LinkedHashMap<String, Bucket> buckets;

    public AuthRateGate(ClientAddressResolver clientAddressResolver, GatewayProperties properties, LongSupplier nanos) {
        this.clientAddressResolver = Objects.requireNonNull(clientAddressResolver, "clientAddressResolver");
        this.nanos = Objects.requireNonNull(nanos, "nanos");
        GatewayProperties.AuthRate authRate = Objects.requireNonNull(properties, "properties").getAuthRate();
        this.capacity = authRate.getCapacity();
        this.refillPerMillis = authRate.getRefillPerMinute() / 60_000.0;
        this.maxTrackedKeys = authRate.getMaxTrackedKeys();
        // accessOrder=true: get() AND put() on an existing key move it to the end (most recently
        // used); removeEldestEntry fires only when inserting a genuinely NEW key, evicting the
        // entry at the front (least recently used) — see the class javadoc's accepted trade-off.
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxTrackedKeys;
            }
        };
    }

    /**
     * @throws GatewayRejectionException {@code RATE_LIMITED}, carrying a whole-second {@code
     *         Retry-After} of at least 1, if this request's resolved client address has no token
     *         available right now.
     */
    public void check(HttpServletRequest request) {
        String key = keyFor(clientAddressResolver.resolve(request));
        long now = nanos.getAsLong();
        Outcome outcome = tryConsume(key, now);
        if (!outcome.allowed()) {
            throw new GatewayRejectionException(ErrorCode.RATE_LIMITED,
                    Map.of(RETRY_AFTER_HEADER, String.valueOf(outcome.retryAfterSeconds())));
        }
    }

    /**
     * Refills the bucket for {@code key} up to {@code now}, then consumes one token if available.
     * Single short lock at login rates — plain token math, no CAS/retry loop needed, since the
     * whole read-modify-write is already serialized by {@link #lock}.
     */
    private Outcome tryConsume(String key, long now) {
        synchronized (lock) {
            Bucket current = buckets.get(key);
            double currentTokens = current == null ? capacity : current.tokens();
            long lastAccessNanos = current == null ? now : current.lastAccessNanos();

            // A subtraction of two nanoTime-shaped values, never a comparison of their absolute
            // values — stays correct across a long wraparound. Clamped to zero so a time source
            // that steps backwards never "refunds" a token for negative elapsed time.
            long elapsedNanos = Math.max(0, now - lastAccessNanos);
            double elapsedMillis = elapsedNanos / 1_000_000.0;
            double refilled = Math.min(capacity, currentTokens + elapsedMillis * refillPerMillis);

            Outcome outcome;
            Bucket next;
            if (refilled >= 1.0) {
                next = new Bucket(refilled - 1.0, now);
                outcome = Outcome.allow();
            } else {
                next = new Bucket(refilled, now);
                double deficitTokens = 1.0 - refilled;
                long retryAfterSeconds = (long) Math.ceil(deficitTokens / refillPerMillis / 1000.0);
                outcome = Outcome.refused(retryAfterSeconds);
            }
            // Tracks the key if it is new (subject to LRU eviction of another key at the cap —
            // never refused), or just updates+re-freshens it if already tracked.
            buckets.put(key, next);
            return outcome;
        }
    }

    // ---- key derivation -----------------------------------------------------------------------

    /**
     * @return {@link ClientAddresses#throttleKey} of {@code resolvedAddress}, or {@code
     *         resolvedAddress} itself when it is not in canonical form — {@link
     *         ClientAddressResolver#resolve}'s own defence-in-depth fallback for a peer the
     *         servlet container reports in a shape {@link ClientAddresses} cannot parse.
     */
    private static String keyFor(String resolvedAddress) {
        try {
            return ClientAddresses.throttleKey(resolvedAddress);
        } catch (IllegalArgumentException notCanonical) {
            return resolvedAddress;
        }
    }

    // ---- test introspection ---------------------------------------------------------------------

    int trackedKeyCount() {
        synchronized (lock) {
            return buckets.size();
        }
    }

    private record Bucket(double tokens, long lastAccessNanos) {
    }

    private record Outcome(boolean allowed, long retryAfterSeconds) {
        static Outcome allow() {
            return new Outcome(true, 0);
        }

        static Outcome refused(long retryAfterSeconds) {
            return new Outcome(false, Math.max(1, retryAfterSeconds));
        }
    }
}
