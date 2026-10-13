package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.DiscoveredContainer;
import com.solace.sm.dp.api.agw.DiscoveryResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentDiscoveryPort} must do (plan 015 T2.1b, kept by T4.1 for the real intake). One result per item, in the
 * report's order; the session is fenced before anything; an item that does not carry the instance label is not a registration
 * candidate and is refused with a code, never silently dropped (a dropped item would look registered) and with nothing
 * written. A container's labels, mounts and ports are agent-supplied facts and decide nothing here: the contract's
 * {@code sm.tenant} label names another organization and must change nothing. The registration cases themselves
 * (the placement table, the cap, the tombstones) are {@code AgentDiscoveryIT}'s.
 */
public abstract class AbstractAgentDiscoveryPortContractTest {

    protected abstract AgentKitWorld world();

    private static DiscoveredContainer container(String id) {
        return new DiscoveredContainer(id, "sm-" + id, "registry.example/cache:2.4",
                Map.of("sm.tenant", "another-organization"), List.of(), List.of());
    }

    @Test
    void everyItemWithoutTheInstanceLabelIsRefusedIncompleteInOrderAndNothingIsWritten() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        List<DiscoveryResult> results = world().discovery().report(session, "disc-1",
                List.of(container("aaaaaaaaaaaa"), container("bbbbbbbbbbbb"), container("cccccccccccc")), world().origin());
        assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc"), results.stream().map(DiscoveryResult::containerId).toList());
        for (DiscoveryResult r : results) {
            assertEquals(DiscoveryResult.State.REFUSED, r.state());
            assertEquals(GatewayCode.AGENT_DISCOVERY_INCOMPLETE, r.code());
            assertEquals("agent.discovery.incomplete", r.code().wire());
            assertNull(r.placementId());
        }
        assertEquals(0, world().rows("agent_discovery", agent));
    }

    @Test
    void anEmptyReportIsAnEmptyAnswer() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        assertTrue(world().discovery().report(session, "disc-2", List.of(), world().origin()).isEmpty());
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnything() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().ready(agent);
        world().open(agent);
        refused(GatewayCode.STALE_EPOCH, () -> world().discovery().report(stale, "disc-3", List.of(container("aaaaaaaaaaaa")), world().origin()));
        assertEquals(0, world().rows("agent_discovery", agent));
    }
}
