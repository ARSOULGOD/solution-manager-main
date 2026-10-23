package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.GatewayToAgent;

/** Review I1: a timeout's LogCancel must never reach the agent before the LogRequest it cancels. */
class AgentLogBrokerOrderingTest {
    private static final UUID PLACEMENT = UUID.nameUUIDFromBytes("p".getBytes());

    private final List<String> wire = new CopyOnWriteArrayList<>();

    private AgentLogBroker.Link slowLink(AgentSessionRef ref, long sendMillis) {
        return new AgentLogBroker.Link() {
            @Override public AgentSessionRef ref() { return ref; }
            @Override public boolean logsSupported() { return true; }
            @Override public boolean inLease(UUID p) { return true; }
            @Override public boolean sendMessage(GatewayToAgent message) {
                try { Thread.sleep(sendMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                wire.add("request");      // the session monitor was finally obtained: the request goes out now
                return true;
            }
            @Override public void pushRetention(UUID p, long max) { }
        };
    }

    private void attachRecordingStream(AgentLogBroker broker, AgentSessionRef ref) {
        broker.attach(ref, new StreamObserver<>() {
            @Override public void onNext(AgentGatewayOuterClass.LogDown v) { if (v.hasLogCancel()) wire.add("cancel"); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        });
    }

    @Test
    void aSendSlowerThanTheDeadlineNeverLetsTheCancelOvertakeTheRequestForAPage() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            AgentSessionRef ref = new AgentSessionRef(UUID.randomUUID(), 1);
            AgentLogBroker broker = new AgentLogBroker(a -> slowLink(ref, 400), () -> List.of(ref.agentId()), scheduler,
                    new AgentGatewayProperties(), new AgentGatewayMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
            attachRecordingStream(broker, ref);
            var page = broker.requestPage(ref, PLACEMENT, 5, null, null, Duration.ofMillis(100));
            assertEquals("TIMEOUT", page.get(5, TimeUnit.SECONDS).unavailableReason().orElseThrow());
            assertEquals(List.of("request", "cancel"), wire, "the request is on the wire before any cancel");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void theSameHoldsForAnExport() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        try {
            AgentSessionRef ref = new AgentSessionRef(UUID.randomUUID(), 1);
            AgentLogBroker broker = new AgentLogBroker(a -> slowLink(ref, 400), () -> List.of(ref.agentId()), scheduler,
                    new AgentGatewayProperties(), new AgentGatewayMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
            attachRecordingStream(broker, ref);
            var export = broker.openExport(ref, PLACEMENT, 0, Duration.ofMillis(100), 1000);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (export.state() == com.solace.sm.dp.api.agw.LogExportHandle.State.OPEN && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(com.solace.sm.dp.api.agw.LogExportHandle.State.TIMED_OUT, export.state());
            assertTrue(wire.indexOf("request") >= 0 && wire.indexOf("request") < wire.indexOf("cancel"), "wire: " + wire);
        } finally {
            scheduler.shutdownNow();
        }
    }
}
