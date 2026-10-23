package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.AgentLogGap;
import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.kit.AbstractAgentLogPortContractTest;
import com.solace.sm.dp.api.logs.LogLineView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** Plan 016 task 7: the {@link AgentLogPort} kit over the real {@link AgentGatewayService}, its real sessions and fake agents. */
class AgentLogPortGatewayContractTest extends AbstractAgentLogPortContractTest {
    private LogsHarness harness;

    @BeforeEach
    void harness() {
        harness = new LogsHarness();
    }

    @AfterEach
    void close() throws Exception {
        harness.close();
    }

    @Override protected AgentLogPort port() { return harness.fleet.service.logPort(); }
    @Override protected void connect(String label, boolean withLogs) { harness.connect(label, withLogs); }
    @Override protected AgentSessionRef session(String label) { return new AgentSessionRef(AgentGatewayServiceTest.Ports.id(label), 1); }
    @Override protected UUID leasedPlacement() { return AgentGatewayServiceTest.Ports.PLACEMENT; }
    @Override protected UUID unleasedPlacement() { return UUID.nameUUIDFromBytes("not-leased".getBytes()); }
    @Override protected List<String> requestsReceivedBy(String label) {
        return harness.requests(label).stream().map(AgentGatewayOuterClass.LogRequest::getRequestId).toList();
    }
    @Override protected void agentSendsPage(String label, String requestId, List<LogLineView> lines, List<AgentLogGap> gaps,
            Optional<String> unavailable) {
        harness.sendPage(label, requestId, lines, gaps, unavailable.map(r ->
                AgentGatewayOuterClass.UnavailableReason.valueOf("UNAVAILABLE_REASON_" + r))
                .orElse(AgentGatewayOuterClass.UnavailableReason.UNAVAILABLE_REASON_UNSPECIFIED));
    }
    @Override protected void agentSendsChunk(String label, String requestId, long resumeSeq, List<LogLineView> lines, boolean last) {
        harness.sendChunk(label, requestId, resumeSeq, lines, last);
    }
    @Override protected int creditsGrantedTo(String label, String requestId) { return harness.creditsTo(label, requestId); }
    @Override protected boolean cancelReceivedBy(String label, String requestId) { return harness.cancelled(label, requestId); }
    @Override protected List<Long> retentionsReceivedBy(String label, UUID placement) {
        return harness.retentions(label).stream().filter(r -> r.getPlacementRef().equals(placement.toString()))
                .map(AgentGatewayOuterClass.LogRetention::getMaxBytes).toList();
    }
    @Override protected int perAgentBound() { return harness.fleet.properties.getLogMaxInFlightPerAgent(); }
}
