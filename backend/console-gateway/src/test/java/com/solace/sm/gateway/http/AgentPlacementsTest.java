package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 015 T3.3: the one place the gateway refuses what an agent placement cannot serve, decided on the placement's transport. */
class AgentPlacementsTest {

    private static final UUID SSH = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private static InstanceView view() {
        return new InstanceView(UUID.randomUUID(), "orders", UUID.randomUUID(), UUID.randomUUID(), "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH,
                List.of(new PlacementView(SSH, UUID.randomUUID(), "sm-a", Optional.empty()),
                        new PlacementView(AGENT, UUID.randomUUID(), "sm-b", Optional.empty(), PlacementView.AGENT)), 1L);
    }

    @Test
    void anAgentPlacementIsRefusedWith501AndAnSshOneIsNot() {
        GatewayRejectionException refused = assertThrows(GatewayRejectionException.class,
                () -> AgentPlacements.refuseIfAgent(view(), AGENT));
        assertEquals(ErrorCode.AGENT_OPERATION_UNSUPPORTED, refused.code());
        assertEquals(501, refused.code().status());
        assertDoesNotThrow(() -> AgentPlacements.refuseIfAgent(view(), SSH));
        assertDoesNotThrow(() -> AgentPlacements.refuseIfAgent(view(), UUID.randomUUID()), "an unknown placement is not this check's");
    }

    @Test
    void aPlacementViewBuiltWithoutATransportIsSshDocker() {
        PlacementView plain = new PlacementView(SSH, UUID.randomUUID(), "sm-a", Optional.empty());
        assertEquals(PlacementView.SSH_DOCKER, plain.transport());
        assertEquals(PlacementView.SSH_DOCKER, new PlacementView(SSH, UUID.randomUUID(), "sm-a", Optional.empty(), (String) null).transport());
    }
}
