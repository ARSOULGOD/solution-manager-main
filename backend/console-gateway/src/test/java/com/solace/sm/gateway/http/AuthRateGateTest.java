package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code OR-24}: the pre-authentication token bucket itself, at the unit level — independent of
 * {@link AuthenticationInterceptor}'s wiring (proven separately by {@link GatewayHttpBoundaryTest}).
 */
class AuthRateGateTest {

    @Test
    void aBurstOfExactlyCapacitySucceedsThenTheNextRequestIsRateLimitedWithRetryAfter() {
        AuthRateGate gate = gate(2, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));
        MockHttpServletRequest request = requestFrom("203.0.113.1");

        gate.check(request);
        gate.check(request);

        GatewayRejectionException failure = assertThrows(GatewayRejectionException.class, () -> gate.check(request));
        assertEquals(ErrorCode.RATE_LIMITED, failure.code());
        assertEquals("1", failure.headers().get(AuthRateGate.RETRY_AFTER_HEADER));
    }

    @Test
    void refillOverFakeTimeAllowsAnotherRequestAfterTheRetryAfterWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-23T00:00:00Z"));
        AuthRateGate gate = gate(2, 60, 100_000, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.2");

        gate.check(request);
        gate.check(request);
        assertThrows(GatewayRejectionException.class, () -> gate.check(request));

        clock.advance(Duration.ofSeconds(1));

        gate.check(request); // must not throw: one token refilled
        assertThrows(GatewayRejectionException.class, () -> gate.check(request));
    }

    // ── M1: key derivation shared with the durable throttle (ClientAddresses.throttleKey) ──────

    @Test
    void twoIpv6AddressesInTheSameSlash64ShareABucket() {
        AuthRateGate gate = gate(1, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));

        gate.check(requestFrom("2001:db8::1"));

        assertThrows(GatewayRejectionException.class, () -> gate.check(requestFrom("2001:db8::2")));
    }

    @Test
    void twoIpv6AddressesInDifferentSlash64sDoNotShareABucket() {
        AuthRateGate gate = gate(1, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));

        gate.check(requestFrom("2001:db8::1")); // consumes the db8::/64 bucket's only token

        // A different /64 (db9 vs db8) must get an independent, still-full bucket — must not throw.
        gate.check(requestFrom("2001:db9::1"));
    }

    @Test
    void ipv4AddressesNeverShareABucketEvenWhenOnlyTheLastOctetDiffers() {
        AuthRateGate gate = gate(1, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));

        gate.check(requestFrom("203.0.113.1"));

        // If IPv4 were grouped by any prefix shorter than /32, this would also be rate-limited.
        gate.check(requestFrom("203.0.113.2"));
    }

    @Test
    void anIpv4MappedIpv6AddressSharesABucketWithThePlainIpv4Form() {
        // ClientAddresses.canonicalize resolves an IPv4-mapped IPv6 literal to its plain IPv4
        // form BEFORE AuthRateGate ever sees it (via ClientAddressResolver), so the two spellings
        // must key the identical throttle bucket.
        AuthRateGate gate = gate(1, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));

        gate.check(requestFrom("::ffff:203.0.113.5")); // consumes the shared bucket's only token

        assertThrows(GatewayRejectionException.class, () -> gate.check(requestFrom("203.0.113.5")));
    }

    @Test
    void aRawNonCanonicalResolvedAddressNeverCrashesTheGateAndStillKeysConsistently() {
        // ClientAddressResolver.resolve's own defence-in-depth fallback (canonicalizeOrRaw) can
        // hand back a raw, non-canonical string when the container reports something
        // ClientAddresses cannot parse. ClientAddresses.throttleKey refuses such a string with
        // IllegalArgumentException; AuthRateGate must catch that and key on the raw string itself
        // rather than ever throwing out of the gate.
        AuthRateGate gate = gate(1, 60, 100_000, Instant.parse("2026-09-23T00:00:00Z"));
        MockHttpServletRequest request = requestFrom("garbage-hostname-not-an-ip");

        gate.check(request); // must not throw IllegalArgumentException — consumes the only token
        assertThrows(GatewayRejectionException.class, () -> gate.check(request));

        // A DIFFERENT raw, non-canonical address must not collapse into the same fallback key.
        gate.check(requestFrom("another-garbage-hostname"));
    }

    // ── M2: bounded LRU eviction — never fail-closed for a brand-new key ───────────────────────

    @Test
    void atCapANewKeyIsAllowedAndTheLeastRecentlyUsedKeyIsEvictedWhileATouchedKeySurvives() {
        // capacity 2 lets us drive a bucket down to exactly zero remaining tokens, so a survived
        // key's exhausted state is observable (a fresh/evicted-and-recreated key would instead
        // start back at full capacity).
        AuthRateGate gate = gate(2, 60, 2, Instant.parse("2026-09-23T00:00:00Z"));

        gate.check(requestFrom("203.0.113.30")); // key A tracked: 2 -> 1 token left
        gate.check(requestFrom("203.0.113.31")); // key B tracked: 2 -> 1 token left; cap reached
        assertEquals(2, gate.trackedKeyCount());

        gate.check(requestFrom("203.0.113.30")); // touch A again: 1 -> 0 tokens left; A is now MRU, B is LRU

        // A brand-new key at the cap must be ALLOWED, never refused for being new — and must
        // evict the LRU key (B), not the just-touched A.
        gate.check(requestFrom("203.0.113.32"));
        assertEquals(2, gate.trackedKeyCount(), "eviction must keep the tracked-key count at the cap");

        // Proof B (not A) was evicted: A survived with its EXHAUSTED bucket (0 tokens, no time
        // elapsed) — if A had been evicted instead, it would come back full and this would pass.
        assertThrows(GatewayRejectionException.class, () -> gate.check(requestFrom("203.0.113.30")));

        // Corroboration: B, having been evicted, re-enters with a FULL bucket.
        gate.check(requestFrom("203.0.113.31"));
    }

    @Test
    void trackedKeyCountNeverExceedsTheConfiguredCapAcrossManyDistinctKeys() {
        AuthRateGate gate = gate(5, 60, 10, Instant.parse("2026-09-23T00:00:00Z"));

        for (int i = 0; i < 1000; i++) {
            // Every one of these is a brand-new key on first sight — none may ever be refused.
            gate.check(requestFrom("203.0." + (i / 256) + "." + (i % 256)));
            assertTrue(gate.trackedKeyCount() <= 10, "tracked=" + gate.trackedKeyCount());
        }
        assertEquals(10, gate.trackedKeyCount());
    }

    // ── m6: monotonic time source ────────────────────────────────────────────────────────────

    @Test
    void aBackwardsTimeSourceNeverRefundsATokenAndRefillIsStillDrivenBySupplier() {
        long[] currentNanos = {0L};
        AuthRateGate gate = gateWithNanos(1, 60, 100_000, () -> currentNanos[0]);
        MockHttpServletRequest request = requestFrom("203.0.113.40");

        gate.check(request); // consumes the bucket's only token at t=0

        currentNanos[0] = -5_000_000_000L; // the supplied source steps backwards by 5 seconds
        // Elapsed must be treated as non-negative rather than "refunding" a token for negative
        // elapsed time: still no token available.
        assertThrows(GatewayRejectionException.class, () -> gate.check(request));

        currentNanos[0] = 10_000_000_000L; // comfortably past a full refill relative to ANY prior reading
        gate.check(request); // must not throw: refill is driven by the supplier, not wall-clock time
    }

    private static AuthRateGate gate(int capacity, int refillPerMinute, int maxTrackedKeys, Instant now) {
        return gate(capacity, refillPerMinute, maxTrackedKeys, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** Translates a test {@link Clock} into nanoseconds exactly like production's test hook (see
     * {@link GatewayWebConfiguration#authRateGate}), so these unit tests exercise the same seam. */
    private static AuthRateGate gate(int capacity, int refillPerMinute, int maxTrackedKeys, Clock clock) {
        return gateWithNanos(capacity, refillPerMinute, maxTrackedKeys, () -> clock.millis() * 1_000_000L);
    }

    private static AuthRateGate gateWithNanos(int capacity, int refillPerMinute, int maxTrackedKeys,
            LongSupplier nanos) {
        GatewayProperties properties = new GatewayProperties();
        GatewayProperties.AuthRate authRate = new GatewayProperties.AuthRate();
        authRate.setCapacity(capacity);
        authRate.setRefillPerMinute(refillPerMinute);
        authRate.setMaxTrackedKeys(maxTrackedKeys);
        properties.setAuthRate(authRate);
        return new AuthRateGate(new ClientAddressResolver(properties), properties, nanos);
    }

    private static MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }
}
