package com.solace.sm.agw;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigView.Entry;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.ConfigViewReply;
import solace.sm.agentgateway.v1draft.AgentGatewayOuterClass.ConfigViewStatus;

/** Plan 017 task 4: what is specific to the gateway side (the wire shape, reply validation, logging); the port's behaviour is in the kit. */
class AgentGatewayConfigViewTest {
    private static final String TENANT = "8a0f3e7c-0000-4000-8000-000000000001";
    private static final String PLACEMENT = AgentGatewayServiceTest.Ports.PLACEMENT.toString();
    private static final Duration LONG = Duration.ofSeconds(10);

    private static CompletableFuture<AgentConfigView> read(ConfigViewHarness h, String label, List<String> allow, int maxBytes) {
        String agent = h.agentId(label);
        return CompletableFuture.supplyAsync(() -> h.fleet.service.configViewPort().read(TENANT, agent, PLACEMENT, allow, maxBytes));
    }

    private static AgentGatewayOuterClass.ConfigViewRequest awaitRequest(ConfigViewHarness h, String label, int count) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (h.requests(label).size() < count && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(h.requests(label).size() >= count, "the agent should have been sent request " + count);
        return h.requests(label).get(count - 1);
    }

    private static double counter(ConfigViewHarness h, String name) {
        return h.fleet.metrics.registry().counter(name).count();
    }

    @Test
    void theRequestCarriesThePlacementTheAllowListAndTheByteBoundUnderAFreshId() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            var first = read(h, "a", List.of("server.port", "cache.*"), 4096);
            var request = awaitRequest(h, "a", 1);
            assertEquals(PLACEMENT, request.getPlacementId());
            assertEquals(List.of("server.port", "cache.*"), request.getAllowList());
            assertEquals(4096, request.getMaxBytes());
            assertFalse(request.getRequestId().isBlank());
            h.replyOk("a", request.getRequestId(), List.of(), 0, false);
            first.get(5, TimeUnit.SECONDS);
            read(h, "a", List.of("x"), 1);
            assertNotEquals(request.getRequestId(), awaitRequest(h, "a", 2).getRequestId(), "every request has its own id");
        }
    }

    private static AgentConfigView answer(ConfigViewHarness h, ConfigViewReply.Builder reply) throws Exception {
        int before = h.requests("a").size();
        var view = read(h, "a", List.of("k"), 1024);
        String id = awaitRequest(h, "a", before + 1).getRequestId();
        h.reply("a", reply.setRequestId(id).build());
        return view.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aReplyOutsideTheContractIsUnavailableUnknownAndNothingOfItIsForwarded() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            var okWithReason = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_OK)
                    .setReason(AgentGatewayOuterClass.ConfigViewReason.CONFIG_VIEW_REASON_FILE_MISSING)
                    .addEntries(AgentGatewayOuterClass.ConfigEntry.newBuilder().setPath("k").setValue("v"));
            assertEquals(AgentConfigView.malformed(), answer(h, okWithReason));
            var refusedWithoutReason = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED);
            assertEquals(AgentConfigView.malformed(), answer(h, refusedWithoutReason));
            var refusedWithEntries = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED)
                    .setReason(AgentGatewayOuterClass.ConfigViewReason.CONFIG_VIEW_REASON_FILE_MISSING)
                    .addEntries(AgentGatewayOuterClass.ConfigEntry.newBuilder().setPath("k").setValue("v"));
            assertEquals(AgentConfigView.malformed(), answer(h, refusedWithEntries));
            var unspecified = ConfigViewReply.newBuilder();
            assertEquals(AgentConfigView.malformed(), answer(h, unspecified));
            var futureStatus = ConfigViewReply.newBuilder().setStatusValue(9);
            assertEquals(AgentConfigView.malformed(), answer(h, futureStatus));
            var hugeHidden = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_OK).setHiddenCount(-1);
            assertEquals(AgentConfigView.malformed(), answer(h, hugeHidden), "a uint32 above Integer.MAX_VALUE is not a count");
            var emptyPath = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_OK)
                    .addEntries(AgentGatewayOuterClass.ConfigEntry.newBuilder().setPath("").setValue("v"));
            assertEquals(AgentConfigView.malformed(), answer(h, emptyPath));
            assertEquals(7.0, counter(h, "agw_config_view_replies_malformed_total"));
        }
    }

    @Test
    void aRefusalWithAReasonThisGatewayDoesNotKnowIsRefusedUnknown() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            var future = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED).setReasonValue(42);
            assertEquals(AgentConfigView.refused(Reason.UNKNOWN), answer(h, future));
        }
    }

    @Test
    void everyWireReasonMapsToTheSameNameInTheClosedVocabulary() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            for (AgentGatewayOuterClass.ConfigViewReason wire : AgentGatewayOuterClass.ConfigViewReason.values()) {
                if (wire == AgentGatewayOuterClass.ConfigViewReason.UNRECOGNIZED
                        || wire == AgentGatewayOuterClass.ConfigViewReason.CONFIG_VIEW_REASON_UNSPECIFIED) continue;
                var reply = ConfigViewReply.newBuilder().setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_REFUSED).setReason(wire);
                String name = wire.name().substring("CONFIG_VIEW_REASON_".length());
                assertEquals(AgentConfigView.refused(Reason.valueOf(name)), answer(h, reply));
            }
        }
    }

    @Test
    void aReplacedSessionEndsItsPendingViewAtOnce() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            var view = read(h, "a", List.of("k"), 1024);
            var request = awaitRequest(h, "a", 1);
            h.connect("a");      // the agent reconnects; the newer session supersedes the older
            assertEquals(AgentConfigView.unavailable(), view.get(2, TimeUnit.SECONDS));
            h.replyOk("a", request.getRequestId(), List.of(new Entry("k", "v")), 0, false);      // over the new session: dropped
            assertEquals(1.0, counter(h, "agw_config_view_replies_ignored_total"));
        }
    }

    @Test
    void anOldAgentThatNeverAnswersTimesOutAndIsCounted() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(Duration.ofMillis(200))) {
            h.connect("a");
            assertEquals(AgentConfigView.unavailable(), read(h, "a", List.of("k"), 1024).get(5, TimeUnit.SECONDS));
            assertEquals(1.0, counter(h, "agw_config_view_timeouts_total"));
        }
    }

    @Test
    void badArgumentsThrowAndNothingIsSent() throws Exception {
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            var port = h.fleet.service.configViewPort();
            String agent = h.agentId("a");
            assertThrows(NullPointerException.class, () -> port.read(null, agent, PLACEMENT, List.of(), 10));
            assertThrows(IllegalArgumentException.class, () -> port.read(" ", agent, PLACEMENT, List.of(), 10));
            assertThrows(IllegalArgumentException.class, () -> port.read(TENANT, "not-a-uuid", PLACEMENT, List.of(), 10));
            assertThrows(IllegalArgumentException.class, () -> port.read(TENANT, agent, "not-a-uuid", List.of(), 10));
            assertThrows(NullPointerException.class, () -> port.read(TENANT, agent, PLACEMENT, null, 10));
            assertThrows(NullPointerException.class, () -> port.read(TENANT, agent, PLACEMENT, Arrays.asList("a", null), 10));
            assertThrows(IllegalArgumentException.class, () -> port.read(TENANT, agent, PLACEMENT, List.of(), 0));
            assertTrue(h.requests("a").isEmpty());
        }
    }

    @Test
    void noKeyNameOrValueIsEverLogged() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level level = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.TRACE);
        try (ConfigViewHarness h = new ConfigViewHarness(LONG)) {
            h.connect("a");
            h.connect("b");
            var view = read(h, "a", List.of("SECRETALLOW.*"), 1024);
            String id = awaitRequest(h, "a", 1).getRequestId();
            h.replyOk("b", id, List.of(new Entry("SECRETKEY-b", "SECRETVALUE-b")), 0, false);         // wrong session
            h.replyOk("a", "unknown", List.of(new Entry("SECRETKEY-u", "SECRETVALUE-u")), 0, false);  // unknown id
            h.reply("a", ConfigViewReply.newBuilder().setRequestId(id).setStatus(ConfigViewStatus.CONFIG_VIEW_STATUS_OK)
                    .setReason(AgentGatewayOuterClass.ConfigViewReason.CONFIG_VIEW_REASON_FILE_MISSING)
                    .addEntries(AgentGatewayOuterClass.ConfigEntry.newBuilder().setPath("SECRETKEY-m").setValue("SECRETVALUE-m")).build());
            assertEquals(AgentConfigView.malformed(), view.get(5, TimeUnit.SECONDS));
        } finally {
            root.detachAppender(appender);
            root.setLevel(level);
        }
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertFalse(text.contains("SECRET"), "a config key, value or allow pattern reached the log: " + text);
        }
    }
}
