package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.AgentDrainPort;
import com.solace.sm.dp.api.agw.kit.AbstractAgentDrainPortContractTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** Plan 015 T4.3: the {@link AgentDrainPort} kit over the real {@link AgentGatewayService} and its real sessions. */
class AgentDrainPortGatewayContractTest extends AbstractAgentDrainPortContractTest {

    private AgentGatewayServiceTest.Fleet fleet;

    @BeforeEach
    void fleet() {
        fleet = new AgentGatewayServiceTest.Fleet(p -> {
            p.setDrainWindow(Duration.ofMillis(300));
            p.setDrainReconnectMin(Duration.ofSeconds(9));
            p.setClaimMinInterval(Duration.ZERO);
        });
    }

    @AfterEach
    void close() throws Exception {
        fleet.close();
    }

    @Override protected AgentDrainPort port() { return fleet.service::drainAgent; }

    @Override protected UUID connect(String label) {
        fleet.connect(label);
        return AgentGatewayServiceTest.Ports.id(label);
    }

    @Override protected void reconnect(String label) { fleet.connect(label); }

    @Override protected UUID unknownAgent() { return AgentGatewayServiceTest.Ports.id("never-connected"); }

    @Override protected List<DrainNotice> noticesReceivedBy(String label) {
        List<DrainNotice> notices = new ArrayList<>();
        for (AgentGatewayServiceTest.CapturingObserver out : fleet.allOutputs(label)) {
            out.messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasDrain).map(m -> m.getDrain())
                    .forEach(d -> notices.add(new DrainNotice(Duration.ofSeconds(d.getReconnectAfter().getSeconds()), d.getReason())));
        }
        return notices;
    }

    @Override protected boolean connected(String label) {
        List<AgentGatewayServiceTest.CapturingObserver> all = fleet.allOutputs(label);
        if (all.isEmpty()) return false;
        AgentGatewayServiceTest.CapturingObserver latest = all.get(all.size() - 1);
        return !latest.completed && latest.messages.stream().noneMatch(AgentGatewayOuterClass.GatewayToAgent::hasError)
                && fleet.service.liveSessions() > 0;
    }

    @Override protected void drainWindowPasses() {
        try {
            TimeUnit.MILLISECONDS.sleep(900);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override protected Duration configuredReconnectAfter() { return Duration.ofSeconds(9); }
}
