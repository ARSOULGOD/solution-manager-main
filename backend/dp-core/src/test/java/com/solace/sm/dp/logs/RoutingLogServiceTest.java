package com.solace.sm.dp.logs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 016 task 8: the transport of the placement's target, on the DP's own view, picks the implementation. */
class RoutingLogServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final UUID PLACEMENT = UUID.randomUUID();

    private static LogService answering(String marker) {
        return (o, i, p, limit, a, b) -> new LogPageView(
                List.of(new LogLineView(Instant.EPOCH, Optional.empty(), marker)), Optional.empty());
    }

    private static PlacementTransports transport(Optional<String> value) {
        return new PlacementTransports() {
            @Override
            public Optional<String> transportOf(PlacementRef placement) {
                assertEquals(ORG, placement.tenant().value());
                assertEquals(INSTANCE, placement.instance().value());
                assertEquals(PLACEMENT, placement.placement().value());
                return value;
            }

            @Override
            public boolean isAgentTarget(UUID tenantId, UUID targetId) {
                return false;
            }
        };
    }

    private static String read(Optional<String> transport) {
        LogService routing = new RoutingLogService(transport(transport), answering("ssh"), answering("agent"));
        return routing.read(ORG, INSTANCE, PLACEMENT, 5, null, null).lines().get(0).message();
    }

    @Test
    void sshPlacementUsesSshImplementation() {
        assertEquals("ssh", read(Optional.of(PlacementTransports.SSH_DOCKER)));
    }

    @Test
    void agentPlacementUsesAgentImplementation() {
        assertEquals("agent", read(Optional.of(PlacementTransports.AGENT)));
    }

    @Test
    void anUnknownPlacementGoesToTheSshPathWhichDeclaresItUnresolvable() {
        assertEquals("ssh", read(Optional.empty()));
    }

    @Test
    void aTransportItDoesNotKnowIsDeclaredNotGuessed() {
        LogService routing = new RoutingLogService(transport(Optional.of("carrier-pigeon")), answering("ssh"), answering("agent"));
        LogPageView page = routing.read(ORG, INSTANCE, PLACEMENT, 5, null, null);
        assertTrue(page.lines().isEmpty());
        assertTrue(page.unavailableReason().isPresent());
    }
}
