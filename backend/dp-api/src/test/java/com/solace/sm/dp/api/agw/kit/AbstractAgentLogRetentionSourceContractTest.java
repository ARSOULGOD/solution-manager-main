package com.solace.sm.dp.api.agw.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentLogRetentionSource} must do (plan 016 task 9, final fix wave): the gateway reads it when a session opens
 * and at every lease event, so it must answer for a session it does not know, a session that is closed or stale, and a session of
 * another agent without throwing and without handing over anyone else's caps. A source is the data plane's view; the gateway
 * decides nothing about tenants (I5).
 */
public abstract class AbstractAgentLogRetentionSourceContractTest {

    /** The source under test. */
    protected abstract AgentLogRetentionSource source();

    /** An open session whose agent owns placements with stored caps, and exactly those caps. */
    protected abstract AgentSessionRef liveSession();

    protected abstract Map<UUID, Long> storedCaps();

    /** The same agent at an epoch that is not its open one (stale or already closed). */
    protected abstract AgentSessionRef staleSession();

    @Test
    void anOpenSessionIsHandedExactlyItsAgentsStoredCaps() {
        assertEquals(storedCaps(), source().capsFor(liveSession()));
    }

    @Test
    void anUnknownAgentOrAStaleOrClosedSessionIsHandedNothingAndItNeverThrows() {
        assertEquals(Map.of(), source().capsFor(new AgentSessionRef(UUID.randomUUID(), 1)), "an unknown agent");
        assertEquals(Map.of(), source().capsFor(staleSession()), "a session that is not the agent's open one");
    }

    @Test
    void theAnswerIsNeverNullAndCannotBeChangedByTheCaller() {
        Map<UUID, Long> caps = source().capsFor(liveSession());
        assertNotNull(caps);
        assertThrows(UnsupportedOperationException.class, () -> caps.put(UUID.randomUUID(), 1L));
    }
}
