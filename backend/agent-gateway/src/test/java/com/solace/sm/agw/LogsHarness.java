package com.solace.sm.agw;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import com.solace.sm.dp.api.logs.LogLineView;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;

/** A fleet of fake log-capable agents over the real {@link AgentGatewayService} (plan 016 task 7): the Connect and the Logs streams of each. */
final class LogsHarness implements AutoCloseable {
    final AgentGatewayServiceTest.Fleet fleet;
    final Map<String, LogsObserver> logs = new ConcurrentHashMap<>();
    final Map<String, StreamObserver<AgentGatewayOuterClass.LogUp>> logsIn = new ConcurrentHashMap<>();

    LogsHarness() {
        this(p -> { });
    }

    LogsHarness(java.util.function.Consumer<AgentGatewayProperties> configure) {
        fleet = new AgentGatewayServiceTest.Fleet(p -> {
            p.setClaimMinInterval(Duration.ZERO);
            configure.accept(p);
        });
    }

    static final class LogsObserver implements StreamObserver<AgentGatewayOuterClass.LogDown> {
        final List<AgentGatewayOuterClass.LogDown> messages = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile Throwable error;
        @Override public void onNext(AgentGatewayOuterClass.LogDown value) { messages.add(value); }
        @Override public void onError(Throwable t) { error = t; }
        @Override public void onCompleted() { completed = true; }
    }

    /** Connects an agent; with {@code withLogs} it advertises log capability and opens its Logs stream, as the real agent does. */
    void connect(String label, boolean withLogs) {
        fleet.connect(label, "test", withLogs ? List.of(AgentGatewayOuterClass.Capability.CAPABILITY_LOGS) : List.of(), "198.51.100.7");
        if (withLogs) openLogs(label);
    }

    LogsObserver openLogs(String label) {
        fleet.thumbprint.set(label);
        LogsObserver out = new LogsObserver();
        logsIn.put(label, fleet.service.logs(out));
        logs.put(label, out);
        return out;
    }

    List<AgentGatewayOuterClass.GatewayToAgent> sent(String label) {
        return fleet.outputs.get(label).messages;
    }

    List<AgentGatewayOuterClass.LogRequest> requests(String label) {
        return sent(label).stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasLogRequest)
                .map(AgentGatewayOuterClass.GatewayToAgent::getLogRequest).toList();
    }

    List<AgentGatewayOuterClass.LogRetention> retentions(String label) {
        return sent(label).stream().filter(AgentGatewayOuterClass.GatewayToAgent::hasLogRetention)
                .map(AgentGatewayOuterClass.GatewayToAgent::getLogRetention).toList();
    }

    int creditsTo(String label, String requestId) {
        return logs.get(label).messages.stream().filter(AgentGatewayOuterClass.LogDown::hasLogCredit)
                .map(AgentGatewayOuterClass.LogDown::getLogCredit).filter(c -> c.getRequestId().equals(requestId))
                .mapToInt(AgentGatewayOuterClass.LogCredit::getCreditsGranted).sum();
    }

    boolean cancelled(String label, String requestId) {
        return logs.get(label).messages.stream().anyMatch(m -> m.hasLogCancel() && m.getLogCancel().getRequestId().equals(requestId));
    }

    static AgentGatewayOuterClass.LogLine wireLine(LogLineView line) {
        return AgentGatewayOuterClass.LogLine.newBuilder().setTs(Timestamps.fromMillis(line.at().toEpochMilli()))
                .setStream("stdout").setText(ByteString.copyFromUtf8(line.message())).setTruncated(line.truncated()).build();
    }

    static AgentGatewayOuterClass.LogGap wireGap(com.solace.sm.dp.api.agw.AgentLogGap gap) {
        return AgentGatewayOuterClass.LogGap.newBuilder().setFrom(Timestamps.fromMillis(gap.from().toEpochMilli()))
                .setTo(Timestamps.fromMillis(gap.to().toEpochMilli())).setReason(gap.reason()).build();
    }

    void sendPage(String label, String requestId, List<LogLineView> lines, List<com.solace.sm.dp.api.agw.AgentLogGap> gaps,
            AgentGatewayOuterClass.UnavailableReason unavailable) {
        var page = AgentGatewayOuterClass.LogPage.newBuilder().setEnvelope(AgentGatewayServiceTest.envelope("page-" + UUID.randomUUID()))
                .setRequestId(requestId).setUnavailable(unavailable);
        lines.forEach(l -> page.addLines(wireLine(l)));
        gaps.forEach(g -> page.addGaps(wireGap(g)));
        logsIn.get(label).onNext(AgentGatewayOuterClass.LogUp.newBuilder().setLogPage(page).build());
    }

    void sendChunk(String label, String requestId, long seq, List<LogLineView> lines, boolean last) {
        var chunk = AgentGatewayOuterClass.LogChunk.newBuilder().setEnvelope(AgentGatewayServiceTest.envelope("chunk-" + UUID.randomUUID()))
                .setRequestId(requestId).setSeq(seq).setFinal(last);
        lines.forEach(l -> chunk.addLines(wireLine(l)));
        logsIn.get(label).onNext(AgentGatewayOuterClass.LogUp.newBuilder().setLogChunk(chunk).build());
    }

    static Instant at(String iso) { return Instant.parse(iso); }

    @Override public void close() throws Exception { fleet.close(); }
}
