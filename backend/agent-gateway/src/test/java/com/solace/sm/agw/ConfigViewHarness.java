package com.solace.sm.agw;

import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.IdentityReconciliation;
import com.solace.sm.dp.api.agw.LeaseGrant;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** Fake agents over the real {@link AgentGatewayService} for the config view (plan 017 task 4): the Connect stream of each. */
final class ConfigViewHarness implements AutoCloseable {
    final AgentGatewayServiceTest.Fleet fleet;

    ConfigViewHarness(Duration deadline) {
        fleet = new AgentGatewayServiceTest.Fleet(p -> {
            p.setClaimMinInterval(Duration.ZERO);
            p.setConfigViewDeadline(deadline);
        });
    }

    void connect(String label) {
        fleet.connect(label, "test", List.of(), "198.51.100.7");
    }

    void disconnect(String label) {
        fleet.inputs.get(label).onCompleted();
    }

    String agentId(String label) { return AgentGatewayServiceTest.Ports.id(label).toString(); }

    List<AgentGatewayOuterClass.ConfigViewRequest> requests(String label) {
        return fleet.outputs.get(label).messages.stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasConfigViewRequest)
                .map(AgentGatewayOuterClass.GatewayToAgent::getConfigViewRequest).toList();
    }

    void reply(String label, AgentGatewayOuterClass.ConfigViewReply reply) {
        fleet.inputs.get(label).onNext(AgentGatewayOuterClass.AgentToGateway.newBuilder().setConfigViewReply(reply).build());
    }

    void replyOk(String label, String requestId, List<AgentConfigView.Entry> entries, int hidden, boolean truncated) {
        var reply = AgentGatewayOuterClass.ConfigViewReply.newBuilder().setRequestId(requestId)
                .setStatus(AgentGatewayOuterClass.ConfigViewStatus.CONFIG_VIEW_STATUS_OK).setHiddenCount(hidden).setTruncated(truncated);
        entries.forEach(e -> reply.addEntries(AgentGatewayOuterClass.ConfigEntry.newBuilder().setPath(e.path()).setValue(e.value())));
        reply(label, reply.build());
    }

    void refuse(String label, String requestId, AgentConfigView.Reason reason) {
        reply(label, AgentGatewayOuterClass.ConfigViewReply.newBuilder().setRequestId(requestId)
                .setStatus(AgentGatewayOuterClass.ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED)
                .setReason(AgentGatewayOuterClass.ConfigViewReason.valueOf("CONFIG_VIEW_REASON_" + reason.name())).build());
    }

    /** A permission change re-issues every session's lease with no placements. */
    void emptyLeases() {
        LeaseGrant narrower = new LeaseGrant(AgentGatewayServiceTest.Ports.id("agent-1"), "any", AgentGatewayServiceTest.Ports.TARGET,
                List.of(), 1, 1, Instant.now(), Instant.now().plusSeconds(300), "dp", "nonce-2", List.of(AgentCapability.LIFECYCLE),
                Map.of(), Duration.ZERO, Instant.now());
        fleet.ports.reconciler = sessions -> sessions.stream().map(x -> new IdentityReconciliation(x,
                IdentityReconciliation.Status.REISSUED, narrower, 1, 1)).toList();
        fleet.service.reconcileSessions();
    }

    @Override public void close() throws Exception { fleet.close(); }
}
