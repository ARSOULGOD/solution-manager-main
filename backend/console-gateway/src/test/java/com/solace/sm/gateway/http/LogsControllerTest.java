package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.ViewModel;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.dp.api.inventory.ProbeStrength;
import com.solace.sm.dp.api.logs.LogLineView;
import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeCatalog;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * {@code readLogs} (plan 010 T9.1) over the real filter, interceptor, argument-resolver, controller and advice pipeline
 * with the cp-api and dp-api ports FAKED. The ssh read and the parsing are {@code dp-core}'s
 * ({@code LogServiceImplTest}); pinned here: who may call, that an invisible instance or an undeclared component is
 * the same 404, the bounds, the placement rule, that the organization is the session's, and the wire shape.
 */
class LogsControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeInventory inventory;
    private FakeLogs logs;
    private FakeComponents components;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

    @Configuration
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeLogs logs(Calls calls) {
            return new FakeLogs(calls);
        }

        /** Primary: the Designer config's own codec knows no components; this one answers with what the test sets. */
        @Bean @Primary FakeComponents componentsCodec() {
            return new FakeComponents();
        }

        @Bean LogsController logsController(DeclareOrchestrator orchestrator, SolutionCatalog catalog,
                DesignerCodec codec, LogService logs) {
            return new LogsController(orchestrator, catalog, codec, logs);
        }
    }

    static final class FakeLogs implements LogService {
        record Call(UUID organization, UUID instance, UUID placement, int limit, Instant after, Instant before) {
        }

        final Calls calls;
        final List<Call> seen = new ArrayList<>();
        LogPageView next = new LogPageView(List.of(), Optional.empty());

        FakeLogs(Calls calls) {
            this.calls = calls;
        }

        @Override
        public LogPageView read(UUID organizationId, UUID instanceId, UUID placementId, int limit, Instant after,
                Instant before) {
            calls.add("dp.logs");
            seen.add(new Call(organizationId, instanceId, placementId, limit, after, before));
            return next;
        }
    }

    static final class FakeComponents implements DesignerCodec {
        List<String> types = List.of("cache-engine");
        boolean unreadable;

        @Override
        public PackageModel.SolutionPackage readPackage(String packageDocumentJson) {
            if (unreadable) {
                throw new IllegalArgumentException("not a package");
            }
            return new PackageModel.SolutionPackage("cache", List.of(1), null, null, types.stream()
                    .map(t -> new PackageModel.PackageComponent(t, "exactly-one", null, "none", null, null, null,
                            List.of(), List.of(), List.of(), null, Map.of(), null))
                    .toList());
        }

        @Override
        public Map<String, Object> viewModelTree(ViewModel viewModel) {
            throw new AssertionError("logs never projects a view model");
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
        logs = context.getBean(FakeLogs.class);
        components = context.getBean(FakeComponents.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        putInstance(List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders",
                Optional.of(ProbeStrength.LIVENESS))));
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

    private MvcResult read(String token, String component, String query) throws Exception {
        return mvc.perform(get("/api/dp/instances/" + instanceId + "/logs/" + component + query)
                .cookie(new Cookie(COOKIE, token))).andReturn();
    }

    /** Parameters sent as parameters, so no URL-template encoding can change a {@code +} offset. */
    private MvcResult readWith(String token, String name, String value) throws Exception {
        return mvc.perform(get("/api/dp/instances/" + instanceId + "/logs/cache-engine").param(name, value)
                .cookie(new Cookie(COOKIE, token))).andReturn();
    }

    private MvcResult read(String token, String query) throws Exception {
        return read(token, "cache-engine", query);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private void assertNothingRead() {
        assertTrue(logs.seen.isEmpty(), "a host read was attempted: " + logs.seen);
        assertFalse(context.getBean(Calls.class).has("dp.logs"));
    }

    @Test
    void itRequiresLogsReadAtThePathsInstance() throws Exception {
        Session session = login("tok-verb");

        read("tok-verb", "");

        assertEquals(List.of(Permission.LOGS_READ), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
    }

    @Test
    void aCallerWithoutTheVerbIs403AndNoPortIsTouched() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        assertEquals(403, read("tok-deny", "").getResponse().getStatus());

        assertNothingRead();
    }

    @Test
    void anInstanceThatIsNotActiveIsTheSame404AndNothingIsRead() throws Exception {
        login("tok-missing");
        instanceId = UUID.randomUUID();

        MvcResult result = read("tok-missing", "");

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertNothingRead();
    }

    @Test
    void aComponentThePackageDoesNotDeclareIs404AndNothingIsRead() throws Exception {
        login("tok-component");

        for (String component : List.of("file-mesh", "Cache-Engine", "cache-engine2", "x")) {
            MvcResult result = read("tok-component", component, "");
            assertEquals(404, result.getResponse().getStatus(), component);
            assertEquals("NOT_FOUND", json(result).get("code").asText(), component);
        }
        assertNothingRead();
    }

    @Test
    void aPackageThatCannotBeReadIsTheSame404NotAServerError() throws Exception {
        login("tok-unreadable");
        components.unreadable = true;

        assertEquals(404, read("tok-unreadable", "").getResponse().getStatus());

        inventory.rows.clear();
        putInstance(List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders", Optional.empty())));
        context.getBean(FakeCatalog.class).documents.clear();
        components.unreadable = false;
        assertEquals(404, read("tok-unreadable", "").getResponse().getStatus(), "no document at all");
        assertNothingRead();
    }

    @Test
    void theOrganizationIsTheSessionsNeverARequestField() throws Exception {
        Session session = login("tok-i5");

        mvc.perform(get("/api/dp/instances/" + instanceId + "/logs/cache-engine?organizationId=" + UUID.randomUUID())
                .cookie(new Cookie(COOKIE, "tok-i5")).header("X-Organization", UUID.randomUUID().toString()))
                .andReturn();

        assertEquals(1, logs.seen.size());
        assertEquals(session.organizationId(), logs.seen.get(0).organization());
        assertEquals(instanceId, logs.seen.get(0).instance());
    }

    @Test
    void theLimitDefaultsToTwoHundredAndIsBoundedToOneThousand() throws Exception {
        login("tok-limit");

        assertEquals(200, read("tok-limit", "").getResponse().getStatus());
        assertEquals(200, logs.seen.get(0).limit());
        assertEquals(200, read("tok-limit", "?limit=1").getResponse().getStatus());
        assertEquals(200, read("tok-limit", "?limit=1000").getResponse().getStatus());
        assertEquals(1000, logs.seen.get(2).limit());
        int served = logs.seen.size();

        for (String bad : List.of("?limit=0", "?limit=1001", "?limit=-3", "?limit=abc")) {
            MvcResult result = read("tok-limit", bad);
            assertEquals(400, result.getResponse().getStatus(), bad);
            assertEquals("BAD_REQUEST", json(result).get("code").asText(), bad);
        }
        assertEquals(served, logs.seen.size(), "a refused request reads nothing");
    }

    @Test
    void theTimeWindowIsParsedAndAMalformedOrEmptyOneIs400() throws Exception {
        login("tok-window");

        read("tok-window", "?after=2026-10-06T06:25:01Z&before=2026-10-06T06:25:09.5Z");
        readWith("tok-window", "after", "2026-10-06T06:25:01+02:00");

        assertEquals(Instant.parse("2026-10-06T06:25:01Z"), logs.seen.get(0).after());
        assertEquals(Instant.parse("2026-10-06T06:25:09.5Z"), logs.seen.get(0).before());
        assertEquals(Instant.parse("2026-10-06T04:25:01Z"), logs.seen.get(1).after(), "an offset is honoured");
        assertEquals(null, logs.seen.get(1).before());
        int served = logs.seen.size();

        for (String bad : List.of("?after=yesterday", "?before=2026-10-06", "?after=1759731901",
                "?after=2026-10-06T06:25:09Z&before=2026-10-06T06:25:09Z",
                "?after=2026-10-06T06:25:09Z&before=2026-10-06T06:25:01Z")) {
            assertEquals(400, read("tok-window", bad).getResponse().getStatus(), bad);
        }
        assertEquals(served, logs.seen.size());
    }

    @Test
    void aSecondLivePlacementRequiresPlacementId() throws Exception {
        login("tok-placement");
        UUID second = UUID.randomUUID();
        putInstance(List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders", Optional.empty()),
                new PlacementView(second, UUID.randomUUID(), "sm-orders-2", Optional.empty())));

        MvcResult ambiguous = read("tok-placement", "");

        assertEquals(400, ambiguous.getResponse().getStatus());
        assertEquals("BAD_REQUEST", json(ambiguous).get("code").asText());
        assertNothingRead();

        assertEquals(200, read("tok-placement", "?placementId=" + second).getResponse().getStatus());
        assertEquals(second, logs.seen.get(0).placement());
    }

    @Test
    void aPlacementThatIsNotThisInstancesIs404AndNothingIsRead() throws Exception {
        login("tok-foreign");

        MvcResult result = read("tok-foreign", "?placementId=" + UUID.randomUUID());

        assertEquals(404, result.getResponse().getStatus());
        assertEquals("NOT_FOUND", json(result).get("code").asText());
        assertEquals(400, read("tok-foreign", "?placementId=not-a-uuid").getResponse().getStatus());
        assertNothingRead();
    }

    @Test
    void aSingleLivePlacementNeedsNoPlacementId() throws Exception {
        login("tok-single");

        assertEquals(200, read("tok-single", "").getResponse().getStatus());

        assertEquals(placementId, logs.seen.get(0).placement());
    }

    @Test
    void anInstanceWithNoLivePlacementIsDeclaredUnavailableNotAnEmptyPage() throws Exception {
        login("tok-none");
        putInstance(List.of());

        MvcResult result = read("tok-none", "");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertFalse(body.at("/availability/reason").asText().isBlank());
        assertTrue(body.get("lines").isEmpty());
        assertNothingRead();
    }

    @Test
    void theLinesCarryAtLevelAndMessageAndThereIsNoStateVersion() throws Exception {
        login("tok-wire");
        logs.next = new LogPageView(List.of(
                new LogLineView(Instant.parse("2026-10-06T06:25:17.036499796Z"), Optional.of("INFO"), "started"),
                new LogLineView(Instant.parse("2026-10-06T06:25:18Z"), Optional.empty(), "\tat com.Foo.bar")),
                Optional.empty());

        MvcResult result = read("tok-wire", "");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals(Set.of("lines"), fieldNames(body), "OR-38: no stateVersion, and a live page declares no availability");
        JsonNode first = body.get("lines").get(0);
        assertEquals(Set.of("at", "level", "message"), fieldNames(first));
        assertEquals("2026-10-06T06:25:17.036499796Z", first.get("at").asText());
        assertEquals("INFO", first.get("level").asText());
        assertEquals("started", first.get("message").asText());
        JsonNode second = body.get("lines").get(1);
        assertEquals(Set.of("at", "message"), fieldNames(second), "level only when parseable");
        assertEquals("\tat com.Foo.bar", second.get("message").asText());
    }

    @Test
    void anUnreachableHostIs200WithADeclaredUnavailableAvailabilityAndTheReason() throws Exception {
        login("tok-down");
        logs.next = new LogPageView(List.of(), Optional.of("the host could not be reached"));

        MvcResult result = read("tok-down", "");

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = json(result);
        assertEquals(Set.of("availability", "lines"), fieldNames(body));
        assertEquals(Set.of("reason", "state"), fieldNames(body.get("availability")));
        assertEquals("unavailable", body.at("/availability/state").asText());
        assertEquals("the host could not be reached", body.at("/availability/reason").asText());
        assertTrue(body.get("lines").isEmpty());
    }
}
