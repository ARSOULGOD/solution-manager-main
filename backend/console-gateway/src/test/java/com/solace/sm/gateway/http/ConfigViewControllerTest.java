package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.configview.ConfigViewResult;
import com.solace.sm.dp.api.configview.ConfigViewResult.Reason;
import com.solace.sm.dp.api.configview.ConfigViewService;
import com.solace.sm.dp.api.inventory.AcquisitionMode;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.api.inventory.InstanceView;
import com.solace.sm.dp.api.inventory.PlacementView;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeAuthorizationService.Outcome;
import com.solace.sm.gateway.http.GatewayTestSupport.FakeSessionAuthentication;
import com.solace.sm.gateway.http.InstanceControllerTest.FakeInventory;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * {@code viewConfig} (plan 017 task 6) over the real filter, interceptor, argument resolver, controller and advice pipeline,
 * with the data plane's {@link ConfigViewService} FAKED. Pinned here: who may call (the one authorization path,
 * {@code config.view} at the path's instance), the organization and instance handed to the data plane are the session's and
 * the path's, every result and refusal maps to its declared status, and no error body carries a path, host or container.
 */
class ConfigViewControllerTest {

    private static final String COOKIE = "__Host-SM_SESSION";
    /** Text a leak would carry: a host, a file path, a container name. None may appear in any body. */
    private static final List<String> LEAKS = List.of("/etc/", "application.yml", "orders-host", "sm-orders");

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FakeAuthorizationService authorization;
    private FakeInventory inventory;
    private FakeConfigView configView;
    private UUID organization;
    private UUID instanceId;
    private UUID placementId;

    /** Answers a scripted result; records what it was asked. */
    static final class FakeConfigView implements ConfigViewService {
        record Call(UUID organization, UUID instance, UUID placement) {
        }

        final List<Call> calls = new ArrayList<>();
        ConfigViewResult answer = new ConfigViewResult.Ok(List.of(), 0, false);

        @Override
        public ConfigViewResult view(UUID organizationId, UUID instanceId, UUID placementId) {
            calls.add(new Call(organizationId, instanceId, placementId));
            return answer;
        }
    }

    @Configuration
    @Import(DesignerControllerTest.Config.class)
    static class Config {
        @Bean FakeConfigView configView() {
            return new FakeConfigView();
        }

        @Bean ConfigViewErrorAdvice configViewErrorAdvice() {
            return new ConfigViewErrorAdvice();
        }

        @Bean com.solace.sm.gateway.http.metrics.MetricsErrorAdvice metricsErrorAdvice() {
            return new com.solace.sm.gateway.http.metrics.MetricsErrorAdvice();
        }

        @Bean ConfigViewController configViewController(DeclareOrchestrator orchestrator, ConfigViewService service) {
            return new ConfigViewController(orchestrator, service);
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
        configView = context.getBean(FakeConfigView.class);
        organization = UUID.randomUUID();
        instanceId = UUID.randomUUID();
        placementId = UUID.randomUUID();
        putInstance(List.of(new PlacementView(placementId, UUID.randomUUID(), "sm-orders", Optional.empty(),
                PlacementView.AGENT)));
    }

    private void putInstance(List<PlacementView> placements) {
        inventory.rows.put(instanceId, new InstanceView(instanceId, "orders-cache", UUID.randomUUID(), UUID.randomUUID(),
                "1", InstanceLifecycle.ACTIVE, AcquisitionMode.VIA_SSH, placements, 41L));
    }

    private Session login(String token) {
        Session session = GatewayTestSupport.newSession(UUID.randomUUID(), organization, false);
        context.getBean(FakeSessionAuthentication.class).register(token, session, "csrf-" + token);
        return session;
    }

    private MockHttpServletResponse view(String token, UUID instance, UUID placement) throws Exception {
        return mvc.perform(get("/api/dp/instances/" + instance + "/placements/" + placement + "/config")
                .cookie(new Cookie(COOKIE, token))).andReturn().getResponse();
    }

    private static void assertNoLeak(String body) {
        for (String leak : LEAKS) {
            assertFalse(body.contains(leak), "the body names no path, host or container: " + body);
        }
    }

    @Test
    void anOkResultIsTheEntriesTheHiddenCountAndTruncated() throws Exception {
        login("tok-ok");
        configView.answer = new ConfigViewResult.Ok(List.of(new ConfigViewResult.Entry("server.port", "8080"),
                new ConfigViewResult.Entry("management.endpoints.web.exposure.include", "health,info")), 7, true);

        MockHttpServletResponse response = view("tok-ok", instanceId, placementId);

        assertEquals(200, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals(new java.util.TreeSet<>(List.of("entries", "hiddenCount", "truncated")),
                fieldNames(body), "exactly the contract's three members");
        assertEquals("server.port", body.at("/entries/0/path").asText());
        assertEquals("8080", body.at("/entries/0/value").asText());
        assertEquals("health,info", body.at("/entries/1/value").asText());
        assertEquals(new java.util.TreeSet<>(List.of("path", "value")), fieldNames(body.at("/entries/0")));
        assertEquals(7, body.get("hiddenCount").asInt());
        assertTrue(body.get("truncated").asBoolean());
        assertNoLeak(response.getContentAsString());
    }

    @Test
    void anEmptyViewIsStill200WithEveryMember() throws Exception {
        login("tok-empty");

        MockHttpServletResponse response = view("tok-empty", instanceId, placementId);

        assertEquals(200, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertTrue(body.get("entries").isArray() && body.get("entries").isEmpty());
        assertEquals(0, body.get("hiddenCount").asInt());
        assertFalse(body.get("truncated").asBoolean());
    }

    @Test
    void theReadRequiresConfigViewAtTheInstanceAndTheOrganizationIsTheSessions() throws Exception {
        Session session = login("tok-verb");

        view("tok-verb", instanceId, placementId);

        assertEquals(List.of(Permission.CONFIG_VIEW), authorization.permissions);
        assertTrue(authorization.resources.stream().allMatch(r -> r.kind() == ResourceKind.INSTANCE
                && r.id().equals(instanceId) && r.tenant().value().equals(session.organizationId())),
                authorization.resources.toString());
        assertEquals(List.of(new FakeConfigView.Call(session.organizationId(), instanceId, placementId)), configView.calls,
                "the organization is the session's (I5); the instance and placement are the path's");
    }

    @Test
    void aCallerWithoutConfigViewIs403AndTheDataPlaneIsNotAsked() throws Exception {
        login("tok-deny");
        authorization.outcome = Outcome.DENY;

        MockHttpServletResponse response = view("tok-deny", instanceId, placementId);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("FORBIDDEN"));
        assertTrue(configView.calls.isEmpty());
    }

    @Test
    void anInvisibleInstanceOrAPlacementNotOfThisInstanceIs404AndTheDataPlaneIsNotAsked() throws Exception {
        login("tok-404");

        MockHttpServletResponse foreignInstance = view("tok-404", UUID.randomUUID(), placementId);
        MockHttpServletResponse foreignPlacement = view("tok-404", instanceId, UUID.randomUUID());

        assertEquals(404, foreignInstance.getStatus());
        assertEquals(404, foreignPlacement.getStatus());
        assertTrue(configView.calls.isEmpty());
        assertEquals(withoutCorrelation(foreignInstance.getContentAsString()),
                withoutCorrelation(foreignPlacement.getContentAsString()), "one 404 body: nothing tells them apart");
    }

    @Test
    void eachRefusalIsItsDeclaredStatusAndCodeAndNamesNoPathHostOrContainer() throws Exception {
        login("tok-refused");
        Map<Reason, Integer> status = new EnumMap<>(Reason.class);
        status.put(Reason.NOT_FOUND, 404);
        status.put(Reason.TOO_LARGE, 413);
        status.put(Reason.INVALID_YAML, 422);
        status.put(Reason.AGENT_UNAVAILABLE, 503);
        status.put(Reason.NOT_AN_AGENT_PLACEMENT, 501);
        status.put(Reason.BUSY, 429);
        status.put(Reason.BAD_GATEWAY, 502);
        Map<Reason, String> code = new EnumMap<>(Reason.class);
        code.put(Reason.NOT_FOUND, "NOT_FOUND");
        code.put(Reason.TOO_LARGE, "CONFIG_TOO_LARGE");
        code.put(Reason.INVALID_YAML, "CONFIG_INVALID_YAML");
        code.put(Reason.AGENT_UNAVAILABLE, "SERVICE_UNAVAILABLE");
        code.put(Reason.NOT_AN_AGENT_PLACEMENT, "AGENT_OPERATION_UNSUPPORTED");
        code.put(Reason.BUSY, "RATE_LIMITED");
        code.put(Reason.BAD_GATEWAY, "AGENT_REPLY_REJECTED");
        assertEquals(Reason.values().length, status.size(), "every reason is mapped");

        for (Reason reason : Reason.values()) {
            configView.answer = ConfigViewResult.refused(reason);
            MockHttpServletResponse response = view("tok-refused", instanceId, placementId);
            String body = response.getContentAsString();
            assertEquals((int) status.get(reason), response.getStatus(), reason.name());
            assertTrue(response.getContentType().startsWith("application/problem+json"), reason + " " + response.getContentType());
            assertEquals(code.get(reason), new ObjectMapper().readTree(body).get("code").asText(), reason.name());
            assertNoLeak(body);
        }
    }

    @Test
    void aMissingFileIsTheSame404AsAnUnknownPlacement() throws Exception {
        login("tok-same");
        MockHttpServletResponse unknown = view("tok-same", instanceId, UUID.randomUUID());
        configView.answer = ConfigViewResult.refused(Reason.NOT_FOUND);

        MockHttpServletResponse missing = view("tok-same", instanceId, placementId);

        assertEquals(404, missing.getStatus());
        assertEquals(withoutCorrelation(unknown.getContentAsString()), withoutCorrelation(missing.getContentAsString()),
                "a missing file does not tell a caller the placement exists");
    }

    @Test
    void busyIs429WithRetryAfterAndItsReason() throws Exception {
        login("tok-busy");
        configView.answer = ConfigViewResult.refused(Reason.BUSY);

        MockHttpServletResponse response = view("tok-busy", instanceId, placementId);

        assertEquals(429, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertEquals("config-view-concurrency",
                new ObjectMapper().readTree(response.getContentAsString()).get("reason").asText());
    }

    @Test
    void theRefusalsWithADetailCarryTheDataPlanesFixedTextOnly() throws Exception {
        login("tok-detail");
        for (Reason reason : List.of(Reason.TOO_LARGE, Reason.INVALID_YAML, Reason.AGENT_UNAVAILABLE, Reason.BAD_GATEWAY)) {
            configView.answer = ConfigViewResult.refused(reason);
            JsonNode body = new ObjectMapper().readTree(view("tok-detail", instanceId, placementId).getContentAsString());
            assertEquals(reason.message(), body.get("detail").asText(), reason.name());
        }
    }

    @Test
    void theNewErrorCodesCarryTheirStatuses() {
        assertEquals(413, ErrorCode.CONFIG_TOO_LARGE.status());
        assertEquals(422, ErrorCode.CONFIG_INVALID_YAML.status());
        assertEquals(502, ErrorCode.AGENT_REPLY_REJECTED.status());
    }

    private static java.util.TreeSet<String> fieldNames(JsonNode node) {
        java.util.TreeSet<String> names = new java.util.TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\":\"[^\"]*\"", "");
    }
}
