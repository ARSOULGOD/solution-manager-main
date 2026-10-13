package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.gateway.http.metrics.MetricsRateGate;
import com.solace.sm.gateway.http.metrics.MetricsRejectionException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Plan 011 P11-7.2: the per-session token bucket (M-Q24 (5)), on a clock the test owns. */
class MetricsRateGateTest {

    private final AtomicLong nanos = new AtomicLong();

    @Test
    void ten_a_second_per_session_and_a_refill_that_follows_the_clock() {
        MetricsRateGate gate = new MetricsRateGate(10, nanos::get);
        SessionId session = new SessionId(UUID.randomUUID());
        for (int i = 0; i < 10; i++) {
            gate.check(session);
        }
        MetricsRejectionException refused = assertThrows(MetricsRejectionException.class, () -> gate.check(session));
        assertEquals("query-rate", refused.reason());
        assertEquals(1, refused.retryAfterSeconds());
        nanos.addAndGet(100_000_000L);   // 0.1 s: one token
        gate.check(session);
        assertThrows(MetricsRejectionException.class, () -> gate.check(session));
        nanos.addAndGet(5_000_000_000L); // a long idle refills to the capacity, never beyond it
        for (int i = 0; i < 10; i++) {
            gate.check(session);
        }
        assertThrows(MetricsRejectionException.class, () -> gate.check(session));
    }

    @Test
    void sessionsAreIndependentAndEveryAskIsCounted() {
        MetricsRateGate gate = new MetricsRateGate(2, nanos::get);
        SessionId a = new SessionId(UUID.randomUUID());
        SessionId b = new SessionId(UUID.randomUUID());
        gate.check(a);
        gate.check(a);
        assertThrows(MetricsRejectionException.class, () -> gate.check(a));
        gate.check(b);
        assertEquals(4, gate.checks(), "refused or not, one ask is one count");
    }

    @Test
    void theTableIsBoundedAndIdleSessionsAreSweptNotRefused() {
        MetricsRateGate gate = new MetricsRateGate(10, nanos::get, 3);
        for (int i = 0; i < 3; i++) {
            gate.check(new SessionId(UUID.randomUUID()));
        }
        nanos.addAndGet(2_000_000_000L);   // all three are idle, so full: indistinguishable from absent
        SessionId fourth = new SessionId(UUID.randomUUID());
        for (int i = 0; i < 10; i++) {
            gate.check(fourth);
        }
        assertThrows(MetricsRejectionException.class, () -> gate.check(fourth), "the swept table still limits the new session");
        // With every session active and the table full, a new session is admitted (fail open), never refused.
        MetricsRateGate full = new MetricsRateGate(10, nanos::get, 2);
        full.check(new SessionId(UUID.randomUUID()));
        full.check(new SessionId(UUID.randomUUID()));
        full.check(new SessionId(UUID.randomUUID()));
    }
}
