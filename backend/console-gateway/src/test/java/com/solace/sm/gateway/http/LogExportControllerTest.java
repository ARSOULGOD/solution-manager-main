package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.logs.LogExportService;
import com.solace.sm.dp.api.logs.LogExportStream;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeCatalog;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@code exportLogs} (plan 016 task 10) over the real filter, interceptor and controller with the dp-api ports FAKED. Pinned
 * here: who may call (the one authorization path, {@code logs.read} at the instance), that an SSH placement answers the 501
 * shape before the data plane is touched, the organization is the session's, the download headers and body, and that a
 * client that goes away closes the stream (which cancels the agent request in the data plane).
 */
class LogExportControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeInventory inventory;
    private FakeExport export;
    private UUID organization;
    private UUID instanceId;
    private UUID agentPlacement;

    /** Scripted pieces; records what it was asked and whether the stream was closed. */
    static final class FakeExport implements LogExportService {
        record Open(UUID organization, UUID instance, UUID placement) {
        }

        final List<Open> opens = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        List<String> pieces = List.of();

        @Override
        public LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId) {
            opens.add(new Open(organizationId, instanceId, placementId));
            List<String> script = new ArrayList<>(pieces);
            return new LogExportStream() {
                @Override
                public String next() {
                    return script.isEmpty() ? null : script.remove(0);
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }
    }

    @Configuration
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeExport logExport() {
            return new FakeExport();
        }

        @Bean @Primary LogsControllerTest.FakeComponents componentsCodec() {
            return new LogsControllerTest.FakeComponents();
        }

        @Bean LogExportController logExportController(DeclareOrchestrator orchestrator, SolutionCatalog catalog,
                DesignerCodec codec, LogExportService export) {
            return new LogExportController(orchestrator, catalog, codec, export);
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "sm.gateway.trusted-origins[0]=" + GatewayTestSupport.TRUSTED_ORIGIN,
                "sm.gateway.direct-client-connections=true",
                "sm.gateway.auth-rate.capacity=10", "sm.gateway.auth-rate.refill-per-minute=10",
                "sm.gateway.auth-rate.max-tracked-keys=100000");
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(context.getBean(CorrelationIdFilter.class), "/*").build();
        authorization = context.getBean(FakeAuthorizationService.class);
        inventory = context.getBean(FakeInventory.class);
        export = context.getBean(FakeExport.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        agentPlacement = UUID.randomUUID();
        putInstance(List.of(new PlacementView(agentPlacement, UUID.randomUUID(), "sm-orders", Optional.empty(),
                PlacementView.AGENT)));
    }

    private void putInstance(List<PlacementView> placements) {
        UUID solution = UUID.randomUUID();
        inventory.rows.put(instanceId, new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), solution, "1",
                InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH, placements, 41L));
        context.getBean(FakeCatalog.class).serve(solution, "1", "{}");
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    /** Performs the request and, when the body streams, completes the async dispatch. */
    private MvcResult download(String token, String component, String query) throws Exception {
        MvcResult first = mvc.perform(get("/api/dp/instances/" + instanceId + "/logs/" + component + "/export" + query)
                .cookie(new Cookie(COOKIE, token))).andReturn();
        if (first.getRequest().isAsyncStarted()) {
            first.getAsyncResult(5000);
            return mvc.perform(asyncDispatch(first)).andReturn();
        }
        return first;
    }

    @Test
    void exportStreamsChunksInOrderAsDownload() throws Exception {
        login("tok-dl");
        export.pieces = List.of("2026-10-06T06:25:01Z one\n", "", "2026-10-06T06:25:02Z two\n");

        MvcResult result = download("tok-dl", "cache-engine", "");

        assertEquals(200, result.getResponse().getStatus());
        assertEquals("2026-10-06T06:25:01Z one\n2026-10-06T06:25:02Z two\n", result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentType().startsWith("text/plain"), result.getResponse().getContentType());
        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertTrue(disposition.startsWith("attachment;") && disposition.contains("cache-engine"), disposition);
        assertEquals(1, export.closes.get(), "the stream is closed when the body ends");
        assertEquals(agentPlacement, export.opens.get(0).placement(), "the single live placement is the default");
    }

    @Test
    void exportRequiresLogsReadAtInstanceScope() throws Exception {
        Session session = login("tok-verb");

        download("tok-verb", "cache-engine", "");

        assertEquals(List.of(Permission.LOGS_READ), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
        assertEquals(session.organizationId(), export.opens.get(0).organization(), "the organization is the session's (I5)");
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNothingIsOpened() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, download("tok-deny", "cache-engine", "").getResponse().getStatus());

        assertTrue(export.opens.isEmpty());
    }

    @Test
    void exportOnSshPlacementIs501() throws Exception {
        login("tok-ssh");
        putInstance(List.of(new PlacementView(agentPlacement, UUID.randomUUID(), "sm-orders", Optional.empty())));

        MvcResult result = download("tok-ssh", "cache-engine", "");

        assertEquals(501, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("AGENT_OPERATION_UNSUPPORTED"));
        assertTrue(export.opens.isEmpty(), "no SSH export exists, and nothing is read");
    }

    @Test
    void anInstanceThatIsNotVisibleOrAForeignPlacementOrAnUndeclaredComponentIs404() throws Exception {
        login("tok-404");

        assertEquals(404, download("tok-404", "file-mesh", "").getResponse().getStatus());
        assertEquals(404, download("tok-404", "cache-engine", "?placementId=" + UUID.randomUUID()).getResponse().getStatus());
        UUID real = instanceId;
        instanceId = UUID.randomUUID();       // another organization's instance, or one that never existed: the same 404
        assertEquals(404, download("tok-404", "cache-engine", "").getResponse().getStatus());
        instanceId = real;
        putInstance(List.of());
        assertEquals(404, download("tok-404", "cache-engine", "").getResponse().getStatus(), "no live placement");

        assertTrue(export.opens.isEmpty());
    }

    @Test
    void aSecondLivePlacementRequiresPlacementId() throws Exception {
        login("tok-two");
        UUID second = UUID.randomUUID();
        putInstance(List.of(new PlacementView(agentPlacement, UUID.randomUUID(), "a", Optional.empty(), PlacementView.AGENT),
                new PlacementView(second, UUID.randomUUID(), "b", Optional.empty(), PlacementView.AGENT)));

        assertEquals(400, download("tok-two", "cache-engine", "").getResponse().getStatus());
        assertEquals(200, download("tok-two", "cache-engine", "?placementId=" + second).getResponse().getStatus());
        assertEquals(second, export.opens.get(0).placement());
    }

    @Test
    void exportCancelsAgentRequestWhenClientDisconnects() throws Exception {
        export.pieces = List.of("one\n", "two\n", "three\n");
        LogExportStream stream = export.open(organization, instanceId, agentPlacement);
        OutputStream gone = new OutputStream() {
            int writes;

            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                writes++;
                throw new IOException("Broken pipe");
            }
        };

        try {
            LogExportController.pump(stream, gone);
        } catch (IOException expected) {
            // the container's own signal that the client went away
        }

        assertEquals(1, export.closes.get(), "the stream (and with it the agent request and its slot) is released");
    }

    @Test
    void aKeepAliveIsFlushedButWritesNoBytes() throws Exception {
        export.pieces = List.of("", "a\n", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicInteger flushes = new AtomicInteger();
        OutputStream counting = new OutputStream() {
            @Override public void write(int b) { out.write(b); }
            @Override public void flush() { flushes.incrementAndGet(); }
        };

        LogExportController.pump(export.open(organization, instanceId, agentPlacement), counting);

        assertEquals("a\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(3, flushes.get(), "every piece, keep-alives included, is flushed so a gone client is noticed");
        assertEquals(1, export.closes.get());
    }
}
