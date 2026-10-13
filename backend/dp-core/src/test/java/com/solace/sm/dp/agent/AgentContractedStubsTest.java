package com.solace.sm.dp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandPayload;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Plan 015 T2.1b: the payload-source fake's contracted answer. The observed-state and telemetry-declarations stubs became real
 * classes in W3 (T3.3, T3.4) and the discovery stub in W4 (T4.1, {@code AgentDiscoveryService}); their cases were removed, the last
 * one's fenced-session case living on in the discovery kit and its registration cases in {@code AgentDiscoveryIT}.
 */
class AgentContractedStubsTest {

    @Test
    void thePayloadSourceFakeHandsOverWhatWasRegisteredAndRefusesTheRestWithTheRenderChangedCode() {
        FakeAgentCommandPayloadSource fake = new FakeAgentCommandPayloadSource();
        CommandId id = new CommandId(UUID.randomUUID());
        AgentCommandPayloadSource.Command command = new AgentCommandPayloadSource.Command(id, UUID.randomUUID(),
                new AgentPlacementRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), AgentOperation.APPLY,
                "key", "hmacsha256v1:" + "a".repeat(64), 1);
        GatewayPortException refused = assertThrows(GatewayPortException.class, () -> fake.payloadFor(command));
        assertEquals(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED, refused.code());
        assertEquals("agent.payload.render.changed", refused.code().wire());
        assertTrue(fake.plainDigestFor(command).isEmpty());
        fake.register(id, new byte[] {1, 2, 3}, "b".repeat(64));
        CommandPayload payload = fake.payloadFor(command);
        assertEquals(3, payload.bytes().length);
        assertEquals("b".repeat(64), fake.plainDigestFor(command).orElseThrow());
        assertFalse(payload.toString().contains("b".repeat(64)), "the payload never prints its digest");
    }
}
