package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.api.agw.kit.AbstractAgentConfigViewPortContractTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** Plan 017 task 4: the {@link AgentConfigViewPort} kit over the real {@link AgentGatewayService}, its real sessions and fake agents. */
class AgentConfigViewPortGatewayContractTest extends AbstractAgentConfigViewPortContractTest {
    private static final Duration DEADLINE = Duration.ofMillis(1500);
    private ConfigViewHarness harness;

    @BeforeEach
    void harness() {
        harness = new ConfigViewHarness(DEADLINE);
    }

    @AfterEach
    void close() throws Exception {
        harness.close();
    }

    @Override protected AgentConfigViewPort port() { return harness.fleet.service.configViewPort(); }
    @Override protected void connect(String label) { harness.connect(label); }
    @Override protected void disconnect(String label) { harness.disconnect(label); }
    @Override protected String agentId(String label) { return harness.agentId(label); }
    @Override protected String leasedPlacement() { return AgentGatewayServiceTest.Ports.PLACEMENT.toString(); }
    @Override protected String unleasedPlacement() { return UUID.nameUUIDFromBytes("not-leased".getBytes()).toString(); }
    @Override protected List<String> requestsReceivedBy(String label) {
        return harness.requests(label).stream().map(AgentGatewayOuterClass.ConfigViewRequest::getRequestId).toList();
    }
    @Override protected void agentRepliesOk(String label, String requestId, List<AgentConfigView.Entry> entries, int hiddenCount,
            boolean truncated) {
        harness.replyOk(label, requestId, entries, hiddenCount, truncated);
    }
    @Override protected void agentRefuses(String label, String requestId, AgentConfigView.Reason reason) {
        harness.refuse(label, requestId, reason);
    }
    @Override protected void placementLeavesLease(String label) { harness.emptyLeases(); }
    @Override protected Duration deadline() { return DEADLINE; }
}
