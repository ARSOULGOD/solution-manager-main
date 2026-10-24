package com.solace.sm.dp.configview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.solace.sm.dp.api.agw.AgentConfigView;
import com.solace.sm.dp.api.agw.AgentConfigView.Entry;
import com.solace.sm.dp.api.agw.AgentConfigView.Reason;
import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.configview.ConfigViewResult;
import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.inventory.PlacementRecord;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Plan 017 task 5: the data plane's config view. The placement is resolved under the organization and instance from the DP's own
 * tables, the agent is the one the placement's target names, and every reply is re-checked against the allow list that was sent
 * before anything of it reaches the caller (Review Focus 4).
 */
class AgentConfigViewServiceTest {

    static final UUID ORGANIZATION = UUID.randomUUID();
    static final UUID INSTANCE = UUID.randomUUID();
    static final UUID PLACEMENT = UUID.randomUUID();
    static final UUID AGENT = UUID.randomUUID();

    static final List<String> ALLOW = List.of("server.port", "management.*", "logging.*", "info.*");
    static final int MAX_BYTES = 262_144;

    /** The port the DP consumes: records each request and answers what the test sets. */
    static class FakePort implements AgentConfigViewPort {
        final List<Object[]> requests = new CopyOnWriteArrayList<>();
        volatile Supplier<AgentConfigView> answer = () -> AgentConfigView.ok(List.of(new Entry("server.port", "8080")), 3, false);

        @Override
        public AgentConfigView read(String tenantId, String agentId, String placementId, List<String> allow, int maxBytes) {
            requests.add(new Object[] {tenantId, agentId, placementId, List.copyOf(allow), maxBytes});
            return answer.get();
        }
    }

    final AtomicBoolean live = new AtomicBoolean(true);
    volatile String transport = PlacementTransports.AGENT;
    volatile boolean agentResolved = true;
    final FakePort port = new FakePort();
    final List<AgentConfigViewService> opened = new ArrayList<>();

    final PlacementRows placements = new PlacementRows() {
        @Override public void insert(PlacementRecord row) { throw new AssertionError(); }
        @Override public Optional<PlacementRecord> findLive(UUID t, UUID i, UUID p) {
            return live.get() && known(t, i, p)
                    ? Optional.of(new PlacementRecord(t, p, i, UUID.randomUUID(), "c", "/d", "/c", "f", "ADDITIONAL_LOCATION", 1,
                            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Instant.EPOCH))
                    : Optional.empty();
        }
        @Override public Optional<PlacementRecord> find(UUID t, UUID i, UUID p) { throw new AssertionError(); }
        @Override public List<PlacementRecord> live(UUID t, UUID i) { throw new AssertionError(); }
        @Override public int retireAll(UUID t, UUID i, Instant at) { throw new AssertionError(); }
        @Override public int retire(UUID t, UUID i, UUID p, Instant at, UUID by) { throw new AssertionError(); }
        @Override public boolean lockApplyScope(UUID t, UUID i) { throw new AssertionError(); }
        @Override public boolean inFlightOn(UUID t, UUID i, UUID p) { throw new AssertionError(); }
        @Override public boolean instanceWideInFlight(UUID t, UUID i) { throw new AssertionError(); }
    };

    /** Answers for exactly the known (organization, instance, placement) key, as the table-backed catalog does (retired or not). */
    final PlacementTransports transports = new PlacementTransports() {
        @Override public Optional<String> transportOf(PlacementRef p) {
            return known(p.tenant().value(), p.instance().value(), p.placement().value()) ? Optional.of(transport) : Optional.empty();
        }
        @Override public boolean isAgentTarget(UUID tenantId, UUID targetId) { throw new AssertionError(); }
    };

    final AgentPlacementCatalog agents = p -> agentResolved && known(p.tenant().value(), p.instance().value(), p.placement().value())
            ? Optional.of(new AgentPlacementCatalog.AgentPlacement(AGENT,
                    new AgentPlacementRef(p.tenant().value(), p.instance().value(), p.placement().value()), "c",
                    new ConfigLocation("/config", new WriterAccess.RemoteSftp("/srv/cfg"), "application.yml",
                            ConfigLocation.Mechanism.ADDITIONAL_LOCATION), null))
            : Optional.empty();

    static boolean known(UUID t, UUID i, UUID p) {
        return t.equals(ORGANIZATION) && i.equals(INSTANCE) && p.equals(PLACEMENT);
    }

    AgentConfigViewService service(List<String> allow, Duration timeout, int maxBytes, int maxConcurrent) {
        AgentConfigViewService s = new AgentConfigViewService(placements, transports, agents, port, allow, timeout, maxBytes,
                maxConcurrent);
        opened.add(s);
        return s;
    }

    AgentConfigViewService service() {
        return service(ALLOW, Duration.ofSeconds(10), MAX_BYTES, 4);
    }

    ConfigViewResult viewWith(AgentConfigView reply) {
        port.answer = () -> reply;
        return service().view(ORGANIZATION, INSTANCE, PLACEMENT);
    }

    static ConfigViewResult refused(ConfigViewResult.Reason reason) {
        return new ConfigViewResult.Refused(reason);
    }

    @AfterEach
    void closeAll() {
        opened.forEach(AgentConfigViewService::close);
    }

    // ---- the re-check (Review Focus 4) --------------------------------------------------------------------------------------

    @Test
    void recheckRejectsReplyWithStrayKey() {
        ConfigViewResult result = viewWith(AgentConfigView.ok(List.of(new Entry("server.port", "8080"),
                new Entry("management.server.port", "9090"), new Entry("spring.datasource.password", "hunter2")), 0, false));

        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY), result,
                "one entry off the sent list discards the whole reply; nothing of it is forwarded");
    }

    @Test
    void recheckForwardsAReplyWhollyOnTheList() {
        ConfigViewResult result = viewWith(AgentConfigView.ok(List.of(new Entry("server.port", "8080"),
                new Entry("management.endpoints.web.exposure.include[0]", "health"), new Entry("logging.level.root", "INFO")),
                7, true));

        ConfigViewResult.Ok ok = assertInstanceOf(ConfigViewResult.Ok.class, result);
        assertEquals(List.of(new ConfigViewResult.Entry("server.port", "8080"),
                new ConfigViewResult.Entry("management.endpoints.web.exposure.include[0]", "health"),
                new ConfigViewResult.Entry("logging.level.root", "INFO")), ok.entries());
        assertEquals(7, ok.hiddenCount());
        assertTrue(ok.truncated());
    }

    @Test
    void recheckPrefixMatchesTheSubtreeOnly() {
        for (String stray : List.of("management", "managementx.a", "server.port.x", "server", "xserver.port", "info")) {
            assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY), viewWith(AgentConfigView.ok(List.of(new Entry(stray, "v")), 0,
                    false)), "'management.*' matches the subtree only; an exact entry matches itself only");
        }
    }

    @Test
    void recheckRejectsDuplicatePaths() {
        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY), viewWith(AgentConfigView.ok(List.of(
                new Entry("server.port", "8080"), new Entry("server.port", "9090")), 0, false)));
    }

    @Test
    void recheckRejectsAReplyOverTheByteLimitItWasSent() {
        port.answer = () -> AgentConfigView.ok(List.of(new Entry("info.a", "x".repeat(1020))), 0, false);   // 6 + 1020 > 1024

        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY),
                service(ALLOW, Duration.ofSeconds(10), 1024, 1).view(ORGANIZATION, INSTANCE, PLACEMENT));
        assertEquals(1024, port.requests.get(0)[4], "the limit checked is the one sent");
    }

    @Test
    void recheckCountsBytesAsUtf8() {
        // 6 path bytes + 510 two-byte characters = 1026 UTF-8 bytes, though only 516 chars
        port.answer = () -> AgentConfigView.ok(List.of(new Entry("info.a", "é".repeat(510))), 0, false);

        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY),
                service(ALLOW, Duration.ofSeconds(10), 1024, 1).view(ORGANIZATION, INSTANCE, PLACEMENT));
    }

    @Test
    void recheckRejectsMoreEntriesThanTheAgentMayEmit() {
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i <= AgentConfigViewService.MAX_ENTRIES; i++) {
            many.add(new Entry("info.k" + i, "v"));
        }
        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY), viewWith(AgentConfigView.ok(many, 0, false)));
    }

    @Test
    void anAnswerThatIsNotAViewAtAllIsBadGateway() {
        assertEquals(refused(ConfigViewResult.Reason.BAD_GATEWAY), viewWith(null));
    }

    // ---- resolution under the organization and instance (I5) ---------------------------------------------------------------

    @Test
    void tenantIsolation() {
        AgentConfigViewService s = service();
        assertEquals(refused(ConfigViewResult.Reason.NOT_FOUND), s.view(UUID.randomUUID(), INSTANCE, PLACEMENT),
                "another organization's placement is not found");
        assertEquals(refused(ConfigViewResult.Reason.NOT_FOUND), s.view(ORGANIZATION, UUID.randomUUID(), PLACEMENT),
                "another instance's placement is not found");
        assertEquals(refused(ConfigViewResult.Reason.NOT_FOUND), s.view(ORGANIZATION, INSTANCE, UUID.randomUUID()));
        assertTrue(port.requests.isEmpty(), "no agent is asked about a placement that did not resolve");
    }

    @Test
    void retiredPlacementRefused() {
        live.set(false);

        assertEquals(refused(ConfigViewResult.Reason.NOT_FOUND), service().view(ORGANIZATION, INSTANCE, PLACEMENT));
        assertTrue(port.requests.isEmpty());
    }

    @Test
    void nonAgentPlacementIs501() {
        for (String other : List.of(PlacementTransports.SSH_DOCKER, "carrier-pigeon")) {
            transport = other;
            assertEquals(refused(ConfigViewResult.Reason.NOT_AN_AGENT_PLACEMENT), service().view(ORGANIZATION, INSTANCE, PLACEMENT));
        }
        assertTrue(port.requests.isEmpty());
    }

    @Test
    void anAgentPlacementWithNoAgentOnItsTargetIsUnavailable() {
        agentResolved = false;

        assertEquals(refused(ConfigViewResult.Reason.AGENT_UNAVAILABLE), service().view(ORGANIZATION, INSTANCE, PLACEMENT));
        assertTrue(port.requests.isEmpty());
    }

    @Test
    void allowListIsSentFromProperty() {
        List<String> configured = List.of("server.port", "spring.application.name", "app.feature.*");

        ConfigViewResult result = service(configured, Duration.ofSeconds(10), 4096, 1).view(ORGANIZATION, INSTANCE, PLACEMENT);

        assertInstanceOf(ConfigViewResult.Ok.class, result);
        assertEquals(1, port.requests.size());
        Object[] sent = port.requests.get(0);
        assertEquals(ORGANIZATION.toString(), sent[0]);
        assertEquals(AGENT.toString(), sent[1], "the agent is the one the placement's target names in the DP's own tables");
        assertEquals(PLACEMENT.toString(), sent[2]);
        assertEquals(configured, sent[3]);
        assertEquals(4096, sent[4]);
    }

    // ---- the agent's answers, mapped -----------------------------------------------------------------------------------------

    @Test
    void refusalReasonsMapToResult() {
        Map<AgentConfigView, ConfigViewResult.Reason> table = Map.ofEntries(
                Map.entry(AgentConfigView.refused(Reason.FILE_MISSING), ConfigViewResult.Reason.NOT_FOUND),
                Map.entry(AgentConfigView.refused(Reason.FILE_TOO_LARGE), ConfigViewResult.Reason.TOO_LARGE),
                Map.entry(AgentConfigView.refused(Reason.TOO_MANY_KEYS), ConfigViewResult.Reason.TOO_LARGE),
                Map.entry(AgentConfigView.refused(Reason.INVALID_YAML), ConfigViewResult.Reason.INVALID_YAML),
                Map.entry(AgentConfigView.refused(Reason.PLACEMENT_NOT_IN_LEASE), ConfigViewResult.Reason.AGENT_UNAVAILABLE),
                Map.entry(AgentConfigView.refused(Reason.FILE_UNREADABLE), ConfigViewResult.Reason.BAD_GATEWAY),
                Map.entry(AgentConfigView.refused(Reason.NOT_PERMITTED), ConfigViewResult.Reason.BAD_GATEWAY),
                Map.entry(AgentConfigView.refused(Reason.UNKNOWN), ConfigViewResult.Reason.BAD_GATEWAY),
                Map.entry(AgentConfigView.busy(), ConfigViewResult.Reason.BUSY),
                Map.entry(AgentConfigView.unavailable(), ConfigViewResult.Reason.AGENT_UNAVAILABLE),
                Map.entry(AgentConfigView.malformed(), ConfigViewResult.Reason.BAD_GATEWAY));
        assertEquals(Reason.values().length + 3, table.size(), "every agent reason, plus busy, unavailable and malformed");
        table.forEach((reply, expected) -> assertEquals(refused(expected), viewWith(reply), reply.toString()));
    }

    @Test
    void aFullPoolIsBusyAtOnceAndNeverQueued() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        port.answer = () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentConfigView.ok(List.of(), 0, false);
        };
        AgentConfigViewService single = service(ALLOW, Duration.ofSeconds(10), MAX_BYTES, 1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ConfigViewResult> first = caller.submit(() -> single.view(ORGANIZATION, INSTANCE, PLACEMENT));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            assertEquals(refused(ConfigViewResult.Reason.BUSY), single.view(ORGANIZATION, INSTANCE, PLACEMENT));
            assertEquals(1, port.requests.size(), "the busy view was never sent");

            release.countDown();
            assertInstanceOf(ConfigViewResult.Ok.class, first.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ConfigViewResult.Ok.class, single.view(ORGANIZATION, INSTANCE, PLACEMENT), "the slot was freed");
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void aViewThatOutlivesTheTimeoutIsUnavailableAndTheCallIsInterrupted() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        port.answer = () -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return AgentConfigView.unavailable();
        };
        AgentConfigViewService s = service(ALLOW, Duration.ofMillis(100), MAX_BYTES, 1);

        long start = System.nanoTime();
        assertEquals(refused(ConfigViewResult.Reason.AGENT_UNAVAILABLE), s.view(ORGANIZATION, INSTANCE, PLACEMENT));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the abandoned call is interrupted, which frees the gateway's slot");
    }

    // ---- nothing of a key or value is logged or printed (I6) ---------------------------------------------------------------

    @Test
    void noValueInLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.solace.sm.dp.configview");
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        List<ConfigViewResult> results = new ArrayList<>();
        try {
            results.add(viewWith(AgentConfigView.ok(List.of(new Entry("server.port", "s3cr3t-value-1"),
                    new Entry("spring.datasource.password", "s3cr3t-value-2")), 0, false)));
            results.add(viewWith(AgentConfigView.ok(List.of(new Entry("server.port", "s3cr3t-value-3"),
                    new Entry("server.port", "s3cr3t-value-4")), 0, false)));
            results.add(viewWith(AgentConfigView.ok(List.of(new Entry("server.port", "s3cr3t-value-5")), 0, false)));
            results.add(viewWith(AgentConfigView.refused(Reason.INVALID_YAML)));
            results.add(viewWith(AgentConfigView.malformed()));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        assertFalse(appender.list.isEmpty(), "a discarded reply is logged (without its content)");
        List<String> printed = new ArrayList<>();
        appender.list.forEach(e -> printed.add(e.getFormattedMessage() + " " + e.getThrowableProxy()));
        results.forEach(r -> printed.add(r.toString()));
        for (String text : printed) {
            for (String secret : List.of("s3cr3t", "datasource", "password", "server.port")) {
                assertFalse(text.contains(secret), "no key or value is logged or printed: " + secret);
            }
        }
    }

    // ---- configuration bounds -------------------------------------------------------------------------------------------------

    @Test
    void ambiguousOrUnboundedAllowListsAreRejectedAtConstruction() {
        List<List<String>> bad = new ArrayList<>(List.of(List.of(), List.of(""), List.of(" "), List.of("server .port"),
                List.of("server.port "), List.of("*"), List.of(".*"), List.of("a.*.b"), List.of("a*"), List.of("a.b*"),
                List.of("a.**"), List.of("a..b"), List.of(".a"), List.of("a."), List.of("a[0]"), List.of("a\tb"),
                List.of("aé"), List.of("a.b", "a.b"), List.of("x".repeat(AgentConfigViewService.MAX_PATTERN_LENGTH + 1))));
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= AgentConfigViewService.MAX_ALLOW_ENTRIES; i++) {
            tooMany.add("k" + i);
        }
        bad.add(tooMany);
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        for (List<String> allow : bad) {
            assertThrows(IllegalArgumentException.class, () -> service(allow, Duration.ofSeconds(10), MAX_BYTES, 1),
                    "rejected: " + allow.size() + " entries");
        }
        assertThrows(NullPointerException.class, () -> service(withNull, Duration.ofSeconds(10), MAX_BYTES, 1));
        service(List.of("server.port", "spring-boot.app_name.x1", "a.*"), Duration.ofSeconds(10), MAX_BYTES, 1);
    }

    @Test
    void outOfRangeLimitsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> service(ALLOW, Duration.ofSeconds(10), 1023, 1));
        assertThrows(IllegalArgumentException.class,
                () -> service(ALLOW, Duration.ofSeconds(10), AgentConfigViewService.MAX_MAX_BYTES + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> service(ALLOW, Duration.ZERO, MAX_BYTES, 1));
        assertThrows(IllegalArgumentException.class, () -> service(ALLOW, Duration.ofSeconds(-1), MAX_BYTES, 1));
        assertThrows(IllegalArgumentException.class, () -> service(ALLOW, Duration.ofMinutes(2), MAX_BYTES, 1));
        assertThrows(IllegalArgumentException.class, () -> service(ALLOW, Duration.ofSeconds(10), MAX_BYTES, 0));
        assertThrows(NullPointerException.class, () -> service().view(ORGANIZATION, INSTANCE, null));
    }
}
