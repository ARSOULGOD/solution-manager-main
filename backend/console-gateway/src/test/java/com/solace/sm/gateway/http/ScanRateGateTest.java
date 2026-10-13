package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.api.session.SessionId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The per-session budget of {@code scanHostKeys} (plan 013 T1.4): 10 a minute, a token bucket per session. */
class ScanRateGateTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(5 * SECOND);
    private final ScanRateGate gate = new ScanRateGate(10, now::get);

    private static SessionId session() {
        return new SessionId(UUID.randomUUID());
    }

    @Test
    void tenAreAdmittedAndTheEleventhIsRefusedWithRetryAfter() {
        SessionId s = session();
        for (int i = 0; i < 10; i++) {
            gate.check(s);
        }
        GatewayRejectionException refused = assertThrows(GatewayRejectionException.class, () -> gate.check(s));
        assertEquals(ErrorCode.RATE_LIMITED, refused.code());
        // one token every 6 s: the wait is that long, never zero
        assertEquals("6", refused.headers().get("Retry-After"));
    }

    @Test
    void theBucketRefillsContinuously() {
        SessionId s = session();
        for (int i = 0; i < 10; i++) {
            gate.check(s);
        }
        now.addAndGet(5 * SECOND);
        assertThrows(GatewayRejectionException.class, () -> gate.check(s), "5 s is less than one token");
        now.addAndGet(2 * SECOND);      // 7 s in all: a little over one token (one every 6 s)
        assertDoesNotThrow(() -> gate.check(s));
        assertThrows(GatewayRejectionException.class, () -> gate.check(s));
        now.addAndGet(600 * SECOND);
        for (int i = 0; i < 10; i++) {
            gate.check(s);      // refilled to capacity, never beyond it
        }
        assertThrows(GatewayRejectionException.class, () -> gate.check(s));
    }

    @Test
    void eachSessionHasItsOwnBudget() {
        SessionId a = session();
        SessionId b = session();
        for (int i = 0; i < 10; i++) {
            gate.check(a);
        }
        assertThrows(GatewayRejectionException.class, () -> gate.check(a));
        assertDoesNotThrow(() -> gate.check(b));
    }

    @Test
    void aFullTableOfActiveSessionsFailsOpenRatherThanGrowing() {
        ScanRateGate small = new ScanRateGate(1, now::get, 2);
        small.check(session());
        small.check(session());
        assertDoesNotThrow(() -> small.check(session()), "a third active session is admitted, the table is not grown");
    }

    @Test
    void anIdleSessionIsSweptWhenRoomIsNeeded() {
        ScanRateGate small = new ScanRateGate(1, now::get, 2);
        SessionId a = session();
        small.check(a);
        small.check(session());
        now.addAndGet(120 * SECOND);       // both buckets are full again: indistinguishable from no entry
        SessionId c = session();
        small.check(c);
        assertThrows(GatewayRejectionException.class, () -> small.check(c), "c now holds a tracked, spent bucket");
    }
}
