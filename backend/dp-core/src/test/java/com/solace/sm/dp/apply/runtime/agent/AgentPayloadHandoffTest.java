package com.solace.sm.dp.apply.runtime.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentOperation;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The hand-off is bounded, never prints, and a snapshot taken for a pull survives the release that follows it. */
class AgentPayloadHandoffTest {

    private static final UUID TENANT = UUID.randomUUID();

    private static void stage(AgentPayloadHandoff h, String key) {
        h.stage(TENANT, key, AgentOperation.APPLY, key.getBytes(StandardCharsets.UTF_8), "sha256:" + key, null);
    }

    @Test
    void theOldestEntryIsDroppedAtTheBound() {
        AgentPayloadHandoff h = new AgentPayloadHandoff(2);
        stage(h, "a");
        stage(h, "b");
        stage(h, "c");
        assertEquals(2, h.size());
        assertTrue(h.find(TENANT, "a", AgentOperation.APPLY).isEmpty(), "a miss re-renders; the bound never blocks a command");
        assertTrue(h.find(TENANT, "c", AgentOperation.APPLY).isPresent());
    }

    @Test
    void anEntryIsBoundToItsTenantAndOperation() {
        AgentPayloadHandoff h = new AgentPayloadHandoff(4);
        stage(h, "a");
        assertTrue(h.find(UUID.randomUUID(), "a", AgentOperation.APPLY).isEmpty(), "another organization's lookup misses");
        assertTrue(h.find(TENANT, "a", AgentOperation.REMATERIALIZE).isEmpty(), "APPLY and REMATERIALIZE of one intent differ");
    }

    @Test
    void aSnapshotTakenBeforeTheReleaseIsNotZeroed() {
        AgentPayloadHandoff h = new AgentPayloadHandoff(4);
        stage(h, "payload");
        AgentPayloadHandoff.Held snapshot = h.find(TENANT, "payload", AgentOperation.APPLY).orElseThrow();
        h.release(TENANT, "payload", AgentOperation.APPLY);
        assertEquals("payload", new String(snapshot.bytes(), StandardCharsets.UTF_8),
                "a pull racing the adapter's release must never send zeros");
        assertEquals(0, h.size());
    }

    @Test
    void nothingPrintsAByteOrADigest() {
        AgentPayloadHandoff h = new AgentPayloadHandoff(4);
        stage(h, "very-secret-rendered-content");
        String printed = h.find(TENANT, "very-secret-rendered-content", AgentOperation.APPLY).orElseThrow() + " " + h;
        assertFalse(printed.contains("very-secret"), printed);
        assertFalse(printed.contains("sha256"), printed);
    }
}
